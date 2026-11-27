package gardening.app

import cats.syntax.either.*
import gardening.adapters.http.*
import gardening.adapters.sqlite.{SqliteLocation, SqlitePlantStore}
import gardening.adapters.prometheus.{PrometheusAttentionFeedMetrics, PrometheusStorageMetrics}
import gardening.adapters.system.SystemClock
import gardening.capabilities.Logger
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import ox.{EitherMode, supervisedError}
import sttp.shared.Identity
import sttp.tapir.server.metrics.prometheus.PrometheusMetrics
import sttp.tapir.server.netty.sync.{NettySyncServer, NettySyncServerOptions}

import java.nio.file.Paths
import scala.util.Using
import scala.util.chaining.scalaUtilChainingOps

object Main:

  def main(args: Array[String]): Unit =
    val config = AppConfig.load()
    if args.toList.contains("--migrate-only") then
      Using.resource(AppResources.acquire(SqliteLocation.File(config.storage.dbPath), config.storage.lockTimeout)): resources =>
        val _ = Flyway.configure().dataSource(resources.dataSource).load().migrate()
    else serve(config)

  private def serve(config: AppConfig): Unit =
    given log: Logger = new Logger:
      private val underlying           = LoggerFactory.getLogger("gardening")
      def info(message: String): Unit  = underlying.info(message)
      def error(message: String): Unit = underlying.error(message)

    val registry          = (new PrometheusRegistry).tap(JvmMetrics.builder().register(_))
    val prometheusMetrics = PrometheusMetrics[Identity](namespace = "gardening", registry = registry).addRequestsTotal().addRequestsDuration()
    val feedHeartbeats    = ConnectionHeartbeats.make(staleness = config.attention.feedStalenessThreshold)(using SystemClock)
    PrometheusAttentionFeedMetrics.register(registry, feedHeartbeats)
    PrometheusStorageMetrics.register(registry, Paths.get(config.storage.dbPath), config.storage.photosDir)

    val serverResult = Using.resource(AppResources.acquire(SqliteLocation.File(config.storage.dbPath), config.storage.lockTimeout)): resources =>
      supervisedError(EitherMode[Throwable]()):
        val _ = Flyway.configure().dataSource(resources.dataSource).load().migrate()
        SqlitePlantStore.checkDormancyBackfill(resources.transactor)
        val programs  = Programs.make(resources, config, registry)
        val endpoints = List(HealthApi.serverEndpoint, prometheusMetrics.metricsEndpoint) ++
          PlantApi.serverEndpoints(using programs.plants, programs.plantAttentionMonitor) ++
          AttentionApi.serverEndpoints(using programs.plantAttentionMonitor, feedHeartbeats) ++
          OperationApi.serverEndpoints(using programs.operations) ++
          SubstrateApi.serverEndpoints(using programs.substrateCatalog) ++
          PhotoApi.serverEndpoints(config.photo.maxUploadSize)(using programs.photos) ++
          PesticideApi.serverEndpoints(using programs.pesticideCatalog) :+
          StaticSite.endpoint(config.server.staticDir)

        val metricsInterceptor = prometheusMetrics.metricsInterceptor(Seq(AttentionApi.attentionFeedEndpoint))
        val serverOptions      = NettySyncServerOptions.customiseInterceptors.metricsInterceptor(metricsInterceptor).options
        log.info(s"gardening backend starting on host=${config.server.host} port=${config.server.port}")
        NettySyncServer(serverOptions).host(config.server.host).port(config.server.port).addEndpoints(endpoints).startAndWait().asRight

    serverResult.left.foreach(log.error("startup", _))
    if serverResult.isLeft then sys.exit(1)

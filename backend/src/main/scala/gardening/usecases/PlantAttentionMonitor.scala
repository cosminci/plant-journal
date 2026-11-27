package gardening.usecases

import cats.syntax.either.*
import cats.syntax.eq.*
import cats.syntax.option.*
import cats.syntax.traverse.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.ports.{DormancyStore, PlantAttentionStore, PlantAttentionMonitorMetricsApi}
import gardening.domain.plants.DormancyPeriod
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive
import gardening.capabilities.{Clock, Logger}
import gardening.domain.attention.WateringHistory.*

import language.experimental.captureChecking

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.util.chaining.scalaUtilChainingOps

trait PlantAttentionMonitor:
  def current: AttentionProjection
  def refreshAll: RefreshAttentionResult

object PlantAttentionMonitor:

  final case class Settings(minSampleCount: WateringSampleCount, historySize: WateringSampleSize, overdueGracePeriod: FiniteDuration)

  def make(settings: Settings)(using
      store: PlantAttentionStore^,
      dormancy: DormancyStore^,
      clock: Clock^
  )(using log: Logger^, metrics: PlantAttentionMonitorMetricsApi^): PlantAttentionMonitor^{store, dormancy, clock, log, metrics} =
    LivePlantAttentionMonitor(settings)

  private class LivePlantAttentionMonitor(settings: Settings)(using store: PlantAttentionStore^, dormancy: DormancyStore^, clock: Clock^)(using
      log: Logger^,
      metrics: PlantAttentionMonitorMetricsApi^
  ) extends PlantAttentionMonitor:
    // A failed initial projection means the store is unreachable at startup; crash rather than serve stale data.
    @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
    private val currentProjection = computeProjection.toTry.get.tap(recordWateringMetrics).pipe(AtomicReference(_))

    override def current: AttentionProjection = currentProjection.get()

    override def refreshAll: RefreshAttentionResult = synchronized:
      computeProjection match
        case Left(reason)      => RefreshAttentionResult.RefreshFailed(reason).tap(_ => log.error("refresh attention", reason))
        case Right(projection) =>
          val previousLevels = currentProjection.get().plants.map(p => p.plantId -> p.watering.level).toMap
          currentProjection.set(projection)
          logLevelTransitions(projection, previousLevels)
          recordWateringMetrics(projection)
          RefreshAttentionResult.Refreshed(projection)

    private def computeProjection: Either[Throwable, AttentionProjection] =
      val measuredAt = clock.now()
      for
        plants      <- store.listActivePlants()
        assessments <- plants.traverse(assessPlant(_, measuredAt))
      yield AttentionProjection(measuredAt, assessments.flatten)

    private def assessPlant(plant: AttentionPlant, measuredAt: Instant): Either[Throwable, Option[PlantAttention]] =
      dormancy.listPeriods(plant.id).flatMap: periods =>
        if periods.exists(_.maybeEndedAt.isEmpty) then none[PlantAttention].asRight
        else
          val closed = periods.flatMap(period => period.maybeEndedAt.map(period.startedAt -> _))
          sampleWaterings(plant.id, windows(periods), Vector.empty).map: dates =>
            attentionFor(PlantAttentionSample(plant.id, dates), measuredAt, closed).some

    private def windows(periods: Vector[DormancyPeriod]): List[WateringWindow] =
      val starts = None +: periods.map(_.maybeEndedAt)
      val ends   = periods.map(period => Some(period.startedAt)) :+ None
      starts.zip(ends).reverseIterator.collect:
        case (maybeStart, maybeEnd) if !maybeStart.exists(start => maybeEnd.exists(end => !start.isBefore(end))) =>
          WateringWindow(maybeStart, maybeEnd)
      .toList

    private def sampleWaterings(plant: PlantId, remaining: List[WateringWindow], dates: WateringHistory): ReadWateringsResult =
      val size = settings.historySize - dates.size
      remaining match
        case window :: rest if size > 0 =>
          store.readWaterings(plant, window, size.assume[Positive]) match
            case Right(found) => sampleWaterings(plant, rest, dates ++ found)
            case Left(reason) => reason.asLeft
        case _ => dates.asRight

    private def attentionFor(sample: PlantAttentionSample, measuredAt: Instant, closed: Vector[(Instant, Instant)]): PlantAttention =
      val dates       = sample.wateringDates
      val sampleCount = dates.sampleCount
      val watering    =
        dates.headOption match
          case None                 => WateringAttention.Unavailable(sampleCount, none)
          case Some(latestWatering) =>
            val timeSinceWatering = elapsed(latestWatering, measuredAt, closed)
            if sampleCount < settings.minSampleCount then WateringAttention.Unavailable(sampleCount, timeSinceWatering.some)
            else assessWatering(dates, sampleCount, timeSinceWatering, closed)
      PlantAttention(sample.plantId, watering)

    private def assessWatering(
        dates: Vector[Instant],
        sampleCount: WateringSampleCount,
        timeSinceWatering: FiniteDuration,
        closed: Vector[(Instant, Instant)]
    ): WateringAttention =
      val intervals = dates.reverse.sliding(2).flatMap: window =>
        window.headOption.zip(window.lastOption).map((previous, current) => elapsed(previous, current, closed))
      val average = intervals.foldLeft(Duration.Zero)(_ + _) / (dates.size - 1L)
      WateringAttention.Current(sampleCount, average, timeSinceWatering).assess(settings.overdueGracePeriod)

    private def logLevelTransitions(projection: AttentionProjection, previousLevels: Map[PlantId, AttentionLevel]): Unit =
      val transitions = projection.plants.flatMap: plant =>
        val nextLevel = plant.watering.level
        previousLevels.get(plant.plantId).filter(_ =!= nextLevel).map(previousLevel => s"${plant.plantId.value}:$previousLevel->$nextLevel")
      if transitions.nonEmpty then log.info(s"attention changed ${transitions.mkString(",")}")

    private def recordWateringMetrics(projection: AttentionProjection): Unit =
      projection.plants.foreach:
        case PlantAttention(plantId, available: WateringAttention.Available) =>
          metrics.setWateringUrgencyRatio(plantId, available.elapsed.toNanos.toDouble / available.averageInterval.toNanos.toDouble)
          metrics.setWateringCadence(plantId, available.averageInterval)
        case _: PlantAttention => ()

    private def elapsed(previous: Instant, current: Instant, closed: Vector[(Instant, Instant)]): FiniteDuration =
      val inactive = closed.iterator.map: (start, end) =>
        val from  = if start.isAfter(previous) then start else previous
        val until = if end.isBefore(current) then end else current
        math.max(0L, ChronoUnit.MILLIS.between(from, until))
      (ChronoUnit.MILLIS.between(previous, current) - inactive.sum).millis

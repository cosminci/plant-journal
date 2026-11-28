package gardening.adapters.sqlite

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.plants.*
import gardening.ports.{PlantStore, PlantAttentionStore}
import io.circe.parser.decode
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import java.time.Instant
import scala.util.Try

import Codecs.given

object SqlitePlantStore:

  def make(transactor: Transactor): PlantStore & PlantAttentionStore = LiveSqlitePlantStore(transactor)

  def checkDormancyBackfill(transactor: Transactor): Unit =
    val missing = connect(transactor):
      sql"""select 1 from plant where registered_at is null
            union all select 1 from operation where is_watering is null
            limit 1""".query[Int].run()
    def incomplete = IllegalStateException("Complete the one-time dormancy backfill before starting the journal.")
    trust(Either.cond(test = missing.isEmpty, right = (), left = incomplete))

  private class LiveSqlitePlantStore(transactor: Transactor) extends PlantStore, PlantAttentionStore:

    override def addPlant(plant: Plant): AddPlantResult =
      try
        val details = plant.details
        transact(transactor):
          sql"""insert into plant (id, species, nickname, location, substrate, status, registered_at)
                values (${plant.id.value}, ${details.species.value}, ${details.maybeNickname.map(_.value)},
                        ${details.location.value}, ${details.substrate.asJson.noSpaces}, ${details.status.toString},
                        ${sqliteInstant.format(plant.registeredAt)})""".update.run()
        AddPlantResult.Added
      catch case error: SqlException => AddPlantResult.AddFailed(error)

    override def getPlants(status: PlantStatus): GetPlantsResult =
      try
        val rows = connect(transactor):
          val query =
            sql"select id, species, nickname, location, substrate, status, registered_at from plant where status = ${status.toString} order by rowid"
          query.query[PlantRow].run()
        GetPlantsResult.Read(trust(rows.traverse(toPlant)))
      catch
        case error: SqlException       => GetPlantsResult.ReadFailed(error)
        case error: DatabaseCorruption => GetPlantsResult.ReadFailed(error)

    override def getArchivedCount: ArchivedCountResult =
      try
        connect(transactor)(sql"select count(*) from plant where status = 'Archived'".query[Long].run().headOption) match
          case Some(count) =>
            count.refineEither[GreaterEqual[0L]] match
              case Right(checked) => ArchivedCountResult.Counted(checked)
              // SQLite COUNT(*) cannot return a negative number.
              // $COVERAGE-OFF$
              case Left(message) => ArchivedCountResult.ReadFailed(DatabaseCorruption(IllegalStateException(message)))
              // $COVERAGE-ON$
          // COUNT(*) without GROUP BY always returns exactly one row.
          // $COVERAGE-OFF$
          case None => ArchivedCountResult.ReadFailed(DatabaseCorruption(IllegalStateException("archived plant count query returned no row")))
          // $COVERAGE-ON$
      catch case error: SqlException => ArchivedCountResult.ReadFailed(error)

    override def getPlant(plant: PlantId): GetPlantResult =
      try
        connect(transactor)(selectPlant(plant.value).query[PlantRow].run().headOption) match
          case None      => GetPlantResult.RecordMissing
          case Some(row) => GetPlantResult.Read(trust(toPlant(row)))
      catch
        case error: SqlException       => GetPlantResult.ReadFailed(error)
        case error: DatabaseCorruption => GetPlantResult.ReadFailed(error)

    override def updatePlant(plant: Plant): UpdatePlantResult =
      try
        transact(transactor)(updatePlantRow(plant).update.run()) match
          case 1 => UpdatePlantResult.Updated
          case _ => UpdatePlantResult.UpdateFailed(RuntimeException(s"plant not found while updating: ${plant.id.value}"))
      catch case error: SqlException => UpdatePlantResult.UpdateFailed(error)

    private def selectPlant(id: String) =
      sql"select id, species, nickname, location, substrate, status, registered_at from plant where id = $id"

    private def toPlant(row: PlantRow) =
      for
        registeredAt <- registration(row.maybeRegisteredAt)
        substrate    <- decode[Substrate](row.substrate).leftMap(r => RuntimeException(s"invalid stored substrate: ${r.getMessage}", r))
        // The schema check constrains every stored status to a PlantStatus name.
        // $COVERAGE-OFF$
        status <- PlantStatus.values.find(_.toString.equals(row.status)).toRight(RuntimeException(s"invalid state: ${row.status}"))
      // $COVERAGE-ON$
      yield
        val details = PlantDetails(Species(row.species), row.maybeNickname.map(Nickname.apply), Location(row.location), substrate, status)
        Plant(PlantId(row.id), details, registeredAt)

    override def listActivePlants(): ListAttentionPlantsResult =
      try
        val rows = connect(transactor):
          sql"select id, registered_at from plant where status = 'Active' order by rowid".query[AttentionPlantRow].run()
        rows.traverse(row => registration(row.maybeRegisteredAt).map(AttentionPlant(PlantId(row.id), _)))
      catch case error: SqlException => error.asLeft

    override def readWaterings(plant: PlantId, window: WateringWindow, limit: WateringSampleSize): ReadWateringsResult =
      try
        val maybeFrom  = window.maybeFromInclusive.map(sqliteInstant.format)
        val maybeUntil = window.maybeUntilExclusive.map(sqliteInstant.format)
        val size: Int  = limit
        val dates      = connect(transactor):
          sql"""select date from operation where plant_id = ${plant.value} and is_watering = 1
                and date >= coalesce($maybeFrom, '') and date < coalesce($maybeUntil, '~')
                order by date desc, id desc limit $size""".query[String].run()
        dates.traverse(parseTimestamp)
      catch case error: SqlException => error.asLeft

    private def registration(maybeValue: Option[String]) =
      maybeValue.toRight(IllegalStateException("missing plant registration timestamp")).flatMap(parseTimestamp)

    private def parseTimestamp(value: String) =
      Try(Instant.parse(value)).toEither.left.map(_ => RuntimeException(s"invalid stored operation date: $value"))

    private def updatePlantRow(plant: Plant) =
      val details = plant.details
      sql"""update plant
            set species = ${details.species.value},
                nickname = ${details.maybeNickname.map(_.value)},
                location = ${details.location.value},
                substrate = ${details.substrate.asJson.noSpaces},
                status = ${details.status.toString}
            where id = ${plant.id.value} and (status = ${details.status.toString} or (status = 'Active' and ${details.status.toString} = 'Archived'))"""

  private case class PlantRow(
      id: String,
      species: String,
      maybeNickname: Option[String],
      location: String,
      substrate: String,
      status: String,
      maybeRegisteredAt: Option[String]
  ) derives DbCodec

  private case class AttentionPlantRow(id: String, maybeRegisteredAt: Option[String]) derives DbCodec

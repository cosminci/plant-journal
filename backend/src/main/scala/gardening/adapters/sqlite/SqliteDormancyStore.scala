package gardening.adapters.sqlite

import cats.data.NonEmptyList
import cats.syntax.either.*
import cats.syntax.eq.*
import com.augustnagro.magnum.*
import gardening.domain.PlantId
import gardening.domain.plants.*
import gardening.ports.DormancyStore
import java.time.Instant
import scala.util.Try

object SqliteDormancyStore:
  def make(transactor: Transactor): DormancyStore = LiveSqliteDormancyStore(transactor)

  private case class PeriodRow(id: String, startedAt: String, maybeEndedAt: Option[String]) derives DbCodec

  private class LiveSqliteDormancyStore(transactor: Transactor) extends DormancyStore:
    def listPeriods(plant: PlantId): ListDormancyPeriodsResult =
      try
        val rows = connect(transactor):
          sql"""select id, started_at, ended_at from dormancy_period
                where plant_id = ${plant.value}
                order by started_at, ended_at is null, ended_at, id""".query[PeriodRow].run()
        val parsed = Try:
          rows.toVector.map: row =>
            DormancyPeriod(DormancyPeriodId(row.id), Instant.parse(row.startedAt), row.maybeEndedAt.map(Instant.parse))
        parsed.toEither
      catch case error: SqlException => error.asLeft

    def insertPeriod(plant: PlantId, period: DormancyPeriod): InsertDormancyPeriodResult =
      try
        val maybeEndedAt = period.maybeEndedAt.map(sqliteInstant.format)
        val _            = transact(transactor):
          sql"""insert into dormancy_period (id, plant_id, started_at, ended_at)
                values (${period.id.value}, ${plant.value}, ${sqliteInstant.format(period.startedAt)}, $maybeEndedAt)""".update.run()
        ().asRight
      catch case error: SqlException => error.asLeft

    def updatePeriods(plant: PlantId, periods: NonEmptyList[DormancyPeriod]): UpdateDormancyPeriodsResult =
      try
        transact(transactor):
          periods.toList.foreach: period =>
            val maybeEndedAt = period.maybeEndedAt.map(sqliteInstant.format)
            val count        = sql"""update dormancy_period set started_at = ${sqliteInstant.format(period.startedAt)}, ended_at = $maybeEndedAt
                         where id = ${period.id.value} and plant_id = ${plant.value}""".update.run()
            trust(Either.cond(test = count === 1, right = (), left = IllegalStateException(s"missing dormancy period ${period.id.value}")))
        ().asRight
      catch
        case error: SqlException       => error.asLeft
        case error: DatabaseCorruption => error.asLeft

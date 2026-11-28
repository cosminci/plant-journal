package gardening.domain.plants

import cats.data.NonEmptyList
import java.time.Instant

opaque type DormancyPeriodId = String
object DormancyPeriodId:
  def apply(value: String): DormancyPeriodId         = value
  extension (id: DormancyPeriodId) def value: String = id

final case class DormancyPeriod(id: DormancyPeriodId, startedAt: Instant, maybeEndedAt: Option[Instant])

enum DormancyEdit:
  case Start(since: Instant)
  case End(since: Instant)
  case CorrectDates(periods: NonEmptyList[DormancyPeriod])

type ListDormancyPeriodsResult   = Either[Throwable, Vector[DormancyPeriod]]
type InsertDormancyPeriodResult  = Either[Throwable, Unit]
type UpdateDormancyPeriodsResult = Either[Throwable, Unit]

enum GetDormancyHistoryResult:
  case Read(periods: Vector[DormancyPeriod])
  case PlantMissing
  case ReadFailed(reason: Throwable)

enum EditDormancyResult:
  case Edited(periods: Vector[DormancyPeriod])
  case PlantMissing, InvalidDates
  case EditFailed(reason: Throwable)

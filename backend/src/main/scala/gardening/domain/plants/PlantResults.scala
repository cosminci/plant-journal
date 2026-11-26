package gardening.domain.plants

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual

enum GetPlantResult:
  case Read(plant: Plant)
  case RecordMissing
  case ReadFailed(reason: Throwable)

enum GetPlantsResult:
  case Read(plants: Vector[Plant])
  case ReadFailed(reason: Throwable)

enum CreatePlantResult:
  case Created(plant: Plant)
  case UnknownComponent
  case CatalogReadFailed(reason: Throwable)
  case CreateFailed(reason: Throwable)

enum AddPlantResult:
  case Added
  case AddFailed(reason: Throwable)

sealed trait ArchivedCountResult

object ArchivedCountResult:
  sealed abstract case class Counted(count: Long :| GreaterEqual[0L]) extends ArchivedCountResult

  object Counted:
    def apply(count: Long :| GreaterEqual[0L]): Counted = new Counted(count) {}
  final case class ReadFailed(reason: Throwable) extends ArchivedCountResult

enum EditPlantResult:
  case Edited(plant: Plant)
  case PlantMissing
  case PlantArchived
  case UnknownComponent
  case CatalogReadFailed(reason: Throwable)
  case EditFailed(reason: Throwable)

enum UpdatePlantResult:
  case Updated
  case UpdateFailed(reason: Throwable)

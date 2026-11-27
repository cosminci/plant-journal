package gardening.ports

import cats.data.NonEmptyList
import gardening.domain.PlantId
import gardening.domain.plants.*

trait DormancyStore:
  def listPeriods(plant: PlantId): ListDormancyPeriodsResult
  def insertPeriod(plant: PlantId, period: DormancyPeriod): InsertDormancyPeriodResult
  def updatePeriods(plant: PlantId, periods: NonEmptyList[DormancyPeriod]): UpdateDormancyPeriodsResult

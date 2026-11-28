package gardening.ports

import gardening.domain.attention.*
import gardening.domain.PlantId

trait PlantAttentionStore:
  def listActivePlants(): ListAttentionPlantsResult
  def readWaterings(plant: PlantId, window: WateringWindow, limit: WateringSampleSize): ReadWateringsResult

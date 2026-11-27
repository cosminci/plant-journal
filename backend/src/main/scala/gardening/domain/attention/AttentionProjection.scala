package gardening.domain.attention

import gardening.domain.PlantId

import java.time.Instant

final case class PlantAttentionSample(plantId: PlantId, wateringDates: WateringHistory)

final case class AttentionPlant(id: PlantId, registeredAt: Instant)
final case class WateringWindow(maybeFromInclusive: Option[Instant], maybeUntilExclusive: Option[Instant])
type ListAttentionPlantsResult = Either[Throwable, Vector[AttentionPlant]]
type ReadWateringsResult       = Either[Throwable, WateringHistory]

final case class PlantAttention(plantId: PlantId, watering: WateringAttention)
final case class AttentionProjection(measuredAt: Instant, plants: Vector[PlantAttention])

enum RefreshAttentionResult:
  case Refreshed(projection: AttentionProjection)
  case RefreshFailed(reason: Throwable)

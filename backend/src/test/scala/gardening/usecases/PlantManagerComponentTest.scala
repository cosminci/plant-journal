package gardening.usecases

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.plants.*
import gardening.domain.substrate.{AddSubstrateComponentResult, DeleteSubstrateMixResult, GetSubstrateComponentResult, GetSubstrateComponentsResult, GetSubstrateMixesResult, SaveSubstrateMixResult, UpdateSubstrateComponentResult}
import gardening.ports.{SubstrateStore, PlantStore, PlantManagerMetricsApi}
import gardening.capabilities.{IdGenerator, PlantUpdateLock, TestImplicits}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine

import language.experimental.captureChecking

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class PlantManagerComponentTest extends munit.FunSuite with TestImplicits:

  private val perliteId  = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val pineBarkId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000004"))
  private val sand3to5Id = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000005"))
  private val lecaId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000007"))

  private val substrate = Substrate
    .of(List(SubstratePart(perliteId, share = 100)))
    .getOrElse(fail("invalid test substrate"))

  private val plant = Plant(
    id = PlantId("p1"),
    details = PlantDetails(
      species = Species("Ficus lyrata"),
      maybeNickname = Nickname("Fern").some,
      location = Location("Balcony"),
      substrate = substrate,
      status = PlantStatus.Active
    ),
    registeredAt = java.time.Instant.EPOCH
  )

  private val seededComponents = Vector(perliteId, pineBarkId, sand3to5Id, lecaId)
    .map(id => SubstrateComponent(id, SubstrateComponentData(SubstrateComponentName(id.value.toString), none), SubstrateComponentStatus.Active))

  test("should create an active plant with initial substrate independently of operations"):
    val refs    = Refs()
    val manager = buildManager(refs)

    val result = manager.createPlant(plant.details.species, plant.details.maybeNickname, plant.details.location, substrate)

    val expectedPlant = plant.copy(id = PlantId("id-1"))
    assertEquals(result, CreatePlantResult.Created(expectedPlant))
    assertEquals(refs.createdPlants.get(), Vector(expectedPlant))

  test("should distinguish unknown substrate components, catalog reads, and failed writes"):
    val failure        = RuntimeException("unavailable")
    val missingRefs    = Refs()
    val readFailedRefs = Refs()

    val missing = buildManager(
      missingRefs,
      componentReadResult = GetSubstrateComponentsResult.Read(Vector.empty)
    ).createPlant(plant.details.species, none, plant.details.location, substrate)
    val readFailed = buildManager(
      readFailedRefs,
      componentReadResult = GetSubstrateComponentsResult.ReadFailed(failure)
    ).createPlant(plant.details.species, none, plant.details.location, substrate)
    val writeFailed =
      buildManager(addPlantResult = AddPlantResult.AddFailed(failure)).createPlant(plant.details.species, none, plant.details.location, substrate)

    assertEquals(missing, CreatePlantResult.UnknownComponent)
    assertEquals(readFailed, CreatePlantResult.CatalogReadFailed(failure))
    assertEquals(writeFailed, CreatePlantResult.CreateFailed(failure))
    assertEquals(missingRefs.createdPlants.get(), Vector.empty)
    assertEquals(readFailedRefs.createdPlants.get(), Vector.empty)

  test("should return current plants by status and surface read failures"):
    val failure = RuntimeException("plant read failed")
    val unnamed = plant.copy(id = PlantId("p2"), details = plant.details.copy(maybeNickname = None))
    val refs    = Refs()

    val active    = buildManager(refs, getPlantsResult = GetPlantsResult.Read(Vector(plant, unnamed)))
    val failedOne = buildManager(getPlantsResult = GetPlantsResult.ReadFailed(failure))

    val activeResult   = active.getPlants(PlantStatus.Active)
    val archivedResult = failedOne.getPlants(PlantStatus.Archived)

    assertEquals(activeResult, GetPlantsResult.Read(Vector(plant, unnamed)))
    assertEquals(refs.requestedStatuses.get(), Vector(PlantStatus.Active))
    assertEquals(archivedResult, GetPlantsResult.ReadFailed(failure))

  test("should hydrate display names for active and archived plants at startup"):
    val archived     = plant.copy(id = PlantId("p9"), details = plant.details.copy(status = PlantStatus.Archived, maybeNickname = None))
    val refs         = Refs()
    val archivedRead = Some(GetPlantsResult.Read(Vector(archived)))
    val manager      = buildManager(refs, getPlantsResult = GetPlantsResult.Read(Vector(plant)), archivedPlantsResult = archivedRead)

    manager.warmDisplayNames()

    val expectedDisplayNames = Vector(PlantStatus.Active -> Vector(plant.id -> "Fern"), PlantStatus.Archived -> Vector(archived.id -> "Ficus lyrata"))

    assertEquals(refs.requestedStatuses.get(), Vector(PlantStatus.Active, PlantStatus.Archived))
    assertEquals(refs.displayNameWrites.get(), expectedDisplayNames)

  test("should still hydrate active display names when the archived read fails at startup"):
    val refs         = Refs()
    val archivedRead = Some(GetPlantsResult.ReadFailed(RuntimeException("archived read failed")))
    val manager      = buildManager(refs, getPlantsResult = GetPlantsResult.Read(Vector(plant)), archivedPlantsResult = archivedRead)

    manager.warmDisplayNames()

    assertEquals(refs.displayNameWrites.get(), Vector(PlantStatus.Active -> Vector(plant.id -> "Fern")))

  test("should read a non-negative archived count independently of plant lists"):
    val failure = RuntimeException("count unavailable")
    val refs    = Refs()

    val countResult  = buildManager(refs, archivedCountResult = ArchivedCountResult.Counted(4L)).getArchivedCount
    val failedResult = buildManager(archivedCountResult = ArchivedCountResult.ReadFailed(failure)).getArchivedCount

    assertEquals(countResult, ArchivedCountResult.Counted(4L))
    assertEquals(failedResult, ArchivedCountResult.ReadFailed(failure))
    assertEquals(refs.requestedStatuses.get(), Vector.empty)

  test("should archive an active plant by editing its status alone"):
    val archivedPlant = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))
    val refs          = Refs()

    val result = buildManager(refs).editPlant(plant.id, _.copy(status = PlantStatus.Archived))

    assertEquals(result, EditPlantResult.Edited(archivedPlant))
    assertEquals(refs.updatedPlants.get(), Vector(archivedPlant))

  test("should edit an active plant's details independently of operations"):
    val newSubstrate    = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val revisedSpecies  = Species("Monstera deliciosa")
    val revisedLocation = Location("Living room")
    val refs            = Refs()

    val result        = buildManager(refs).editPlant(plant.id, _.copy(revisedSpecies, none, revisedLocation, newSubstrate))
    val expectedPlant = plant.copy(details = plant.details.copy(revisedSpecies, none, revisedLocation, newSubstrate))

    assertEquals(result, EditPlantResult.Edited(expectedPlant))
    assertEquals(refs.updatedPlants.get(), Vector(expectedPlant))

  test("should reject editing an archived plant regardless of the requested change, and preserve distinct failure results"):
    val failure       = RuntimeException("write unavailable")
    val archivedPlant = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))
    val readFailure   = RuntimeException("read unavailable")
    val refs          = Refs()

    val archivedAgainResult = buildManager(refs, getPlantResult = GetPlantResult.Read(archivedPlant))
      .editPlant(plant.id, _.copy(status = PlantStatus.Archived))
    val archivedWithUnknownComponentResult = buildManager(
      refs,
      getPlantResult = GetPlantResult.Read(archivedPlant),
      componentReadResult = GetSubstrateComponentsResult.Read(Vector.empty)
    ).editPlant(plant.id, _.copy(location = Location("Kitchen")))
    val missingResult = buildManager(refs, getPlantResult = GetPlantResult.RecordMissing)
      .editPlant(plant.id, _.copy(location = Location("Kitchen")))
    val readResult = buildManager(refs, getPlantResult = GetPlantResult.ReadFailed(readFailure))
      .editPlant(plant.id, _.copy(location = Location("Kitchen")))
    val writeFailedResult = buildManager(refs, updatePlantResult = UpdatePlantResult.UpdateFailed(failure))
      .editPlant(plant.id, _.copy(location = Location("Kitchen")))

    assertEquals(archivedAgainResult, EditPlantResult.PlantArchived)
    assertEquals(archivedWithUnknownComponentResult, EditPlantResult.PlantArchived)
    assertEquals(missingResult, EditPlantResult.PlantMissing)
    assertEquals(readResult, EditPlantResult.EditFailed(readFailure))
    assertEquals(writeFailedResult, EditPlantResult.EditFailed(failure))
    val attemptedWrite = plant.copy(details = plant.details.copy(location = Location("Kitchen")))
    assertEquals(refs.updatedPlants.get(), Vector(attemptedWrite))

  test("should distinguish unknown substrate components and catalog read failures when changing substrate"):
    val newSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val failure      = RuntimeException("unavailable")
    val refs         = Refs()

    val unknownResult = buildManager(refs, componentReadResult = GetSubstrateComponentsResult.Read(Vector.empty))
      .editPlant(plant.id, _.copy(substrate = newSubstrate))
    val readFailedResult = buildManager(refs, componentReadResult = GetSubstrateComponentsResult.ReadFailed(failure))
      .editPlant(plant.id, _.copy(substrate = newSubstrate))

    assertEquals(unknownResult, EditPlantResult.UnknownComponent)
    assertEquals(readFailedResult, EditPlantResult.CatalogReadFailed(failure))
    assertEquals(refs.updatedPlants.get(), Vector.empty)

  test("dormancy edits preserve period identity and reject moving a boundary before registration"):
    val manager = buildManager(now = plant.registeredAt.plusSeconds(10))
    val start   = plant.registeredAt.plusSeconds(1)
    val end     = start.plusSeconds(1)
    val id      = DormancyPeriodId("id-1")
    val opened  = DormancyPeriod(id, start, maybeEndedAt = None)
    assertEquals(manager.editDormancy(plant.id, DormancyEdit.Start(start)), EditDormancyResult.Edited(Vector(opened)))
    assertEquals(manager.editDormancy(plant.id, DormancyEdit.End(end)), EditDormancyResult.Edited(Vector(DormancyPeriod(id, start, Some(end)))))
    val invalid = cats.data.NonEmptyList.one(DormancyPeriod(id, plant.registeredAt.minusSeconds(1), Some(end)))
    assertEquals(manager.editDormancy(plant.id, DormancyEdit.CorrectDates(invalid)), EditDormancyResult.InvalidDates)
    val corrected = DormancyPeriod(id, start, Some(start))
    val result    = manager.editDormancy(plant.id, DormancyEdit.CorrectDates(cats.data.NonEmptyList.one(corrected)))
    assertEquals(result, EditDormancyResult.Edited(Vector(corrected)))

  test("rejects invalid transitions and reports unavailable histories"):
    val now     = plant.registeredAt.plusSeconds(10)
    val start   = now.minusSeconds(5)
    val manager = buildManager(now = now)
    assertEquals(manager.getDormancyHistory(plant.id), GetDormancyHistoryResult.Read(Vector.empty))
    assertEquals(manager.editDormancy(plant.id, DormancyEdit.End(start)), EditDormancyResult.InvalidDates)
    assertEquals(manager.editDormancy(plant.id, DormancyEdit.Start(now.plusSeconds(1))), EditDormancyResult.InvalidDates)
    val opened = DormancyPeriod(DormancyPeriodId("id-1"), start, maybeEndedAt = None)
    assertEquals(manager.editDormancy(plant.id, DormancyEdit.Start(start)), EditDormancyResult.Edited(Vector(opened)))
    assertEquals(manager.editDormancy(plant.id, DormancyEdit.Start(now)), EditDormancyResult.InvalidDates)
    val wrong = DormancyPeriod(DormancyPeriodId("wrong"), start, maybeEndedAt = None)
    assertEquals(manager.editDormancy(plant.id, DormancyEdit.CorrectDates(cats.data.NonEmptyList.one(wrong))), EditDormancyResult.InvalidDates)
    val missing = buildManager(getPlantResult = GetPlantResult.RecordMissing)
    assertEquals(missing.getDormancyHistory(plant.id), GetDormancyHistoryResult.PlantMissing)
    assertEquals(missing.editDormancy(plant.id, DormancyEdit.Start(start)), EditDormancyResult.PlantMissing)
    val offline = RuntimeException("offline")
    val failed  = buildManager(getPlantResult = GetPlantResult.ReadFailed(offline))
    assertEquals(failed.getDormancyHistory(plant.id), GetDormancyHistoryResult.ReadFailed(offline))
    assertEquals(failed.editDormancy(plant.id, DormancyEdit.Start(start)), EditDormancyResult.EditFailed(offline))
    val archived = buildManager(getPlantResult = GetPlantResult.Read(plant.copy(details = plant.details.copy(status = PlantStatus.Archived))))
    assertEquals(archived.editDormancy(plant.id, DormancyEdit.Start(start)), EditDormancyResult.InvalidDates)

  test("accepts touching periods and reports dormancy store failures"):
    val start   = plant.registeredAt
    val end     = start.plusSeconds(5)
    val id      = DormancyPeriodId("previous")
    val first   = DormancyPeriod(id, start, Some(end))
    val manager = buildManager(now = end, initialPeriods = Vector(first))
    val second  = DormancyPeriod(DormancyPeriodId("id-1"), end, maybeEndedAt = None)
    val started = manager.editDormancy(plant.id, DormancyEdit.Start(end))
    val ended   = manager.editDormancy(plant.id, DormancyEdit.End(end))
    assertEquals(started, EditDormancyResult.Edited(Vector(first, second)))
    assertEquals(ended, EditDormancyResult.Edited(Vector(first, second.copy(maybeEndedAt = Some(end)))))
    val offline    = RuntimeException("dormancy store offline")
    val brokenRead = buildManager(periodReadError = Some(offline))
    assertEquals(brokenRead.getDormancyHistory(plant.id), GetDormancyHistoryResult.ReadFailed(offline))
    assertEquals(brokenRead.editDormancy(plant.id, DormancyEdit.Start(start)), EditDormancyResult.EditFailed(offline))
    val brokenWrite = buildManager(periodWriteError = Some(offline))
    assertEquals(brokenWrite.editDormancy(plant.id, DormancyEdit.Start(start)), EditDormancyResult.EditFailed(offline))

  final private case class Refs():
    val createdPlants: AtomicReference[Vector[Plant]]                                        = AtomicReference(Vector.empty)
    val requestedStatuses: AtomicReference[Vector[PlantStatus]]                              = AtomicReference(Vector.empty)
    val updatedPlants: AtomicReference[Vector[Plant]]                                        = AtomicReference(Vector.empty)
    val displayNameWrites: AtomicReference[Vector[(PlantStatus, Vector[(PlantId, String)])]] = AtomicReference(Vector.empty)

  private def recordingMetrics(refs: Refs): PlantManagerMetricsApi = new PlantManagerMetricsApi:
    def setPlantsCount(status: PlantStatus, count: Long): Unit                              = ()
    def incrementSubstrateComponent(component: SubstrateComponentId): Unit                  = ()
    def setPlantsDisplayNames(status: PlantStatus, plants: Vector[(PlantId, String)]): Unit =
      refs.displayNameWrites.updateAndGet(_ :+ (status -> plants)): Unit

  private def buildManager(
      refs: Refs = Refs(),
      addPlantResult: AddPlantResult = AddPlantResult.Added,
      getPlantsResult: GetPlantsResult = GetPlantsResult.Read(Vector.empty),
      archivedPlantsResult: Option[GetPlantsResult] = None,
      archivedCountResult: ArchivedCountResult = ArchivedCountResult.Counted(0L),
      getPlantResult: GetPlantResult = GetPlantResult.Read(plant),
      updatePlantResult: UpdatePlantResult = UpdatePlantResult.Updated,
      componentReadResult: GetSubstrateComponentsResult = GetSubstrateComponentsResult.Read(seededComponents),
      now: java.time.Instant = plant.registeredAt,
      initialPeriods: Vector[DormancyPeriod] = Vector.empty,
      periodReadError: Option[Throwable] = None,
      periodWriteError: Option[Throwable] = None
  ) =
    val store = new PlantStore:
      override def addPlant(plant: Plant): AddPlantResult =
        refs.createdPlants.updateAndGet(_ :+ plant).pipe(_ => addPlantResult)
      override def getPlants(status: PlantStatus): GetPlantsResult =
        refs.requestedStatuses.updateAndGet(_ :+ status)
        status match
          case PlantStatus.Archived => archivedPlantsResult.getOrElse(getPlantsResult)
          case _                    => getPlantsResult
      override def getArchivedCount: ArchivedCountResult        = archivedCountResult
      override def getPlant(plant: PlantId): GetPlantResult     = getPlantResult
      override def updatePlant(plant: Plant): UpdatePlantResult =
        refs.updatedPlants.updateAndGet(_ :+ plant).pipe(_ => updatePlantResult)
    val substrateStore = new SubstrateStore:
      override def getSubstrateComponents: GetSubstrateComponentsResult                         = componentReadResult
      override def getSubstrateComponent(id: SubstrateComponentId): GetSubstrateComponentResult =
        fail("plants must not read a single substrate component")
      override def addSubstrateComponent(component: SubstrateComponent): AddSubstrateComponentResult =
        fail("plants must not write substrate components")
      override def updateSubstrateComponent(component: SubstrateComponent): UpdateSubstrateComponentResult =
        fail("plants must not edit substrate components")
      override def getSubstrateMixes: GetSubstrateMixesResult =
        fail("plants must not read substrate mixes")
      override def saveSubstrateMix(mix: SubstrateMix): SaveSubstrateMixResult =
        fail("plants must not write substrate mixes")
      override def deleteSubstrateMix(id: java.util.UUID): DeleteSubstrateMixResult =
        fail("plants must not delete substrate mixes")
    val dormancy = new gardening.ports.DormancyStore:
      private val periods                                                                  = AtomicReference(initialPeriods)
      def listPeriods(plant: PlantId): ListDormancyPeriodsResult                           = periodReadError.toLeft(periods.get())
      def insertPeriod(plant: PlantId, period: DormancyPeriod): InsertDormancyPeriodResult =
        periodWriteError.toLeft {
          val _ = periods.updateAndGet(_ :+ period)
        }
      def updatePeriods(plant: PlantId, updates: cats.data.NonEmptyList[DormancyPeriod]): UpdateDormancyPeriodsResult =
        val byId = updates.toList.map(period => period.id -> period).toMap
        val _    = periods.updateAndGet(_.map(period => byId.getOrElse(period.id, period)))
        Right(())
    PlantManager.make(using store, dormancy, substrateStore, () => "id-1", () => now, PlantUpdateLock.make)(
      using
      log,
      recordingMetrics(refs)
    )

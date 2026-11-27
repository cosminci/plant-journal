package gardening.adapters.sqlite

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.operations.*
import gardening.domain.plants.*
import gardening.ports.{PlantStore, PlantAttentionStore, OperationStore}
import gardening.adapters.sqlite.SqliteHelpers.{execute, perliteId, seedPlant}
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import org.flywaydb.core.Flyway

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqlitePlantAttentionStoreSeamIntegrationTest extends FunSuite:

  private val date      = Instant.parse("2026-01-01T00:00:00Z")
  private val unbounded = WateringWindow(maybeFromInclusive = None, maybeUntilExclusive = None)

  private val perliteSubstrate    = Substrate.of(List(SubstratePart(perliteId, share = 100))).getOrElse(fail("invalid perlite substrate"))
  private val defaultPlantDetails = PlantDetails(Species("Ficus lyrata"), none, Location("Balcony"), perliteSubstrate, PlantStatus.Active)
  private val care = OperationDetails.Care(Set(ActionType.Watered, ActionType.Fertilized), Set.empty, MoistureLevel.Wet, Note("a little dry").some)

  test("should return no attention samples when none have been seeded"):
    Using.resource(storeResource): resource =>
      assertEquals(resource.plantStore.listActivePlants(), Right(Vector.empty))

  test("should read bounded watering dates for every active plant"):
    Using.resource(storeResource): resource =>
      val dataSource      = resource.dataSource
      val plantStore      = resource.plantStore
      val operationStore  = resource.operationStore
      val firstPlantId    = PlantId("p1")
      val secondPlantId   = PlantId("p2")
      val archivedPlantId = PlantId("archived")
      seedPlant(dataSource, id = firstPlantId.value)
      seedPlant(dataSource, id = secondPlantId.value)
      seedPlant(dataSource, id = archivedPlantId.value)
      seedPlant(dataSource, id = "no-waterings")

      val firstWaterings = Vector.tabulate(22): index =>
        Operation(OperationId(f"first-$index%02d"), firstPlantId, date.plusSeconds(index.toLong), care)
      val secondWaterings = Vector.tabulate(3): index =>
        Operation(OperationId(f"second-$index%02d"), secondPlantId, date, care)
      val archivedWatering = Operation(OperationId("archived-watering"), archivedPlantId, date, care)
      val nonWatering      =
        Operation(OperationId("care-only"), firstPlantId, date.plusSeconds(30), care.copy(actions = Set(ActionType.Pruned, ActionType.Showered)))
      val operations = firstWaterings ++ secondWaterings :+ archivedWatering :+ nonWatering
      val logged     = operations.map(operationStore.addOperation)
      val archived = plantStore.updatePlant(Plant(archivedPlantId, defaultPlantDetails.copy(status = PlantStatus.Archived), java.time.Instant.EPOCH))
      val firstWateringHistory  = firstWaterings.reverse.take(20).map(_.date)
      val secondWateringHistory = secondWaterings.reverse.map(_.date)
      assertEquals(logged, operations.map(operation => AddOperationResult.Logged(operation.id)))
      assertEquals(archived, UpdatePlantResult.Updated)
      val active = Vector(firstPlantId, secondPlantId, PlantId("no-waterings")).map(AttentionPlant(_, SqliteHelpers.registeredAt))
      assertEquals(plantStore.listActivePlants(), Right(active))
      assertEquals(plantStore.readWaterings(firstPlantId, unbounded, 20), Right(firstWateringHistory))
      assertEquals(plantStore.readWaterings(secondPlantId, unbounded, 20), Right(secondWateringHistory))
      val bounded = plantStore.readWaterings(firstPlantId, WateringWindow(Some(date.plusSeconds(3)), Some(date.plusSeconds(5))), 20)
      assertEquals(bounded, Right(Vector(date.plusSeconds(4), date.plusSeconds(3))))

  test("should report malformed watering dates through the attention read"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val plantStore     = resource.plantStore
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val watering = Operation(OperationId("watering"), PlantId("p1"), date, care)
      val logged   = operationStore.addOperation(watering)
      execute(dataSource, "update operation set date = 'today' where id = ?", watering.id.value)
      val actual = plantStore.readWaterings(watering.plantId, unbounded, 20)

      assertEquals(logged, AddOperationResult.Logged(watering.id))
      actual match
        case Left(reason) =>
          assertEquals(reason.getMessage, "invalid stored operation date: today")
        case other => fail(s"expected ReadFailed, got $other")

  test("should exclude archived plants from the attention read"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val plantStore = resource.plantStore
      seedPlant(dataSource, id = "archived", status = PlantStatus.Archived)
      execute(dataSource, "update plant set substrate = '[]' where id = ?", "archived")

      assertEquals(plantStore.listActivePlants(), Right(Vector.empty))

  test("stores periods by plant and rolls back a correction batch when an ID is not found"):
    Using.resource(storeResource): resource =>
      seedPlant(resource.dataSource, id = "p1")
      seedPlant(resource.dataSource, id = "p2")
      val dormancy = SqliteDormancyStore.make(resource.connection.transactor)
      val plant    = PlantId("p1")
      val period   = DormancyPeriod(DormancyPeriodId("winter"), date, maybeEndedAt = None)
      val closed   = period.copy(maybeEndedAt = Some(date.plusSeconds(5)))
      assertEquals(dormancy.insertPeriod(plant, period), Right(()))
      assert(dormancy.insertPeriod(plant, period).isLeft)
      assertEquals(dormancy.listPeriods(plant), Right(Vector(period)))
      assertEquals(dormancy.listPeriods(PlantId("p2")), Right(Vector.empty))
      val wrongId = closed.copy(id = DormancyPeriodId("wrong"))
      assert(dormancy.updatePeriods(plant, cats.data.NonEmptyList.of(closed, wrongId)).isLeft)
      assertEquals(dormancy.listPeriods(plant), Right(Vector(period)))
      assertEquals(dormancy.updatePeriods(plant, cats.data.NonEmptyList.one(closed)), Right(()))
      assertEquals(dormancy.listPeriods(plant), Right(Vector(closed)))
      val otherPlant = PlantId("p2")
      val historical = closed.copy(id = DormancyPeriodId("previous-winter"))
      assertEquals(dormancy.insertPeriod(otherPlant, historical), Right(()))
      assertEquals(dormancy.listPeriods(otherPlant), Right(Vector(historical)))

  test("reports an incomplete backfill and unavailable tables"):
    Using.resource(storeResource): resource =>
      val source   = resource.dataSource
      val plant    = PlantId("p1")
      val dormancy = SqliteDormancyStore.make(resource.connection.transactor)
      seedPlant(source, id = plant.value)
      execute(source, "update plant set registered_at = null where id = ?", plant.value)
      assertEquals(resource.plantStore.listActivePlants().left.map(_.getMessage), Left("missing plant registration timestamp"))
      execute(source, "drop table dormancy_period")
      assert(dormancy.listPeriods(plant).isLeft)
      val period = DormancyPeriod(DormancyPeriodId("winter"), date, maybeEndedAt = None)
      assert(dormancy.updatePeriods(plant, cats.data.NonEmptyList.one(period)).isLeft)
      execute(source, "drop table operation")
      assert(resource.plantStore.readWaterings(plant, unbounded, 20).isLeft)
      execute(source, "drop table plant_photo")
      execute(source, "drop table plant")
      assert(resource.plantStore.listActivePlants().isLeft)

  test("startup requires both registration dates and watering flags to be backfilled"):
    Using.resource(storeResource): resource =>
      val source = resource.dataSource
      val plant  = PlantId("p1")
      seedPlant(source, id = plant.value)
      val operation = Operation(OperationId("watering"), plant, date, care)
      assertEquals(resource.operationStore.addOperation(operation), AddOperationResult.Logged(operation.id))
      SqlitePlantStore.checkDormancyBackfill(resource.connection.transactor)
      execute(source, "update operation set is_watering = null where id = ?", operation.id.value)
      val reason = intercept[DatabaseCorruption](SqlitePlantStore.checkDormancyBackfill(resource.connection.transactor))
      assert(reason.getMessage.contains("Complete the one-time dormancy backfill"))

  final private case class StoreResource(
      connection: SqliteConnection,
      dataSource: DataSource,
      plantStore: PlantStore & PlantAttentionStore,
      operationStore: OperationStore
  ) extends AutoCloseable:
    override def close(): Unit = connection.close()

  private def storeResource =
    val connection = Sqlite.make(SqliteHelpers.lockTimeout).connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    val _          = Flyway.configure().dataSource(connection.dataSource).load().migrate()
    StoreResource(connection, connection.dataSource, SqlitePlantStore.make(connection.transactor), SqliteOperationStore.make(connection.transactor))

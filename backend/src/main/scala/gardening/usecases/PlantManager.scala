package gardening.usecases

import cats.syntax.either.*
import cats.syntax.eq.*
import cats.syntax.option.*
import cats.data.NonEmptyList
import gardening.domain.*
import gardening.domain.plants.*
import gardening.domain.substrate.GetSubstrateComponentsResult
import gardening.ports.{DormancyStore, PlantStore, PlantManagerMetricsApi, SubstrateStore}
import gardening.capabilities.{Clock, IdGenerator, PlantUpdateLock, Logger}
import monocle.syntax.all.*

import language.experimental.captureChecking

import scala.util.chaining.scalaUtilChainingOps
import java.time.Instant

trait PlantManager:
  def createPlant(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate): CreatePlantResult
  def getPlants(status: PlantStatus): GetPlantsResult
  def getArchivedCount: ArchivedCountResult
  def editPlant(plant: PlantId, revise: PlantDetails => PlantDetails): EditPlantResult
  def warmDisplayNames(): Unit
  def getDormancyHistory(plant: PlantId): GetDormancyHistoryResult
  def editDormancy(plant: PlantId, edit: DormancyEdit): EditDormancyResult

object PlantManager:

  def make(using
      store: PlantStore^,
      dormancy: DormancyStore^,
      substrateStore: SubstrateStore^,
      idGen: IdGenerator^,
      clock: Clock^,
      lock: PlantUpdateLock^
  )(using
      log: Logger^,
      metrics: PlantManagerMetricsApi^
  ): PlantManager^{store, dormancy, substrateStore, idGen, clock, lock, log, metrics} =
    new LivePlantManager

  private class LivePlantManager(using
      store: PlantStore^,
      dormancy: DormancyStore^,
      substrateStore: SubstrateStore^,
      idGen: IdGenerator^,
      clock: Clock^,
      lock: PlantUpdateLock^
  )(using log: Logger^, metrics: PlantManagerMetricsApi^) extends PlantManager:

    override def createPlant(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate): CreatePlantResult =
      substrateStore.getSubstrateComponents match
        case GetSubstrateComponentsResult.ReadFailed(reason) =>
          CreatePlantResult.CatalogReadFailed(reason).tap(_ => log.error("create plant", reason))
        case GetSubstrateComponentsResult.Read(components) =>
          val known = components.filter(_.status === SubstrateComponentStatus.Active).map(_.id).toSet
          if !substrate.parts.forall(part => known.contains(part.componentId)) then CreatePlantResult.UnknownComponent
          else
            val plant = Plant(PlantId(idGen.nextId()), PlantDetails(species, maybeNickname, location, substrate, PlantStatus.Active), clock.now())
            store.addPlant(plant) match
              case AddPlantResult.Added =>
                log.info(s"plant created $plant")
                substrate.parts.foreach(part => metrics.incrementSubstrateComponent(part.componentId))
                CreatePlantResult.Created(plant)
              case AddPlantResult.AddFailed(reason) => CreatePlantResult.CreateFailed(reason).tap(_ => log.error("create plant", reason))

    override def getPlants(status: PlantStatus): GetPlantsResult =
      store.getPlants(status).tap:
        case GetPlantsResult.ReadFailed(reason) => log.error("get plants", reason)
        case GetPlantsResult.Read(found)        =>
          metrics.setPlantsCount(status, found.size.toLong)
          metrics.setPlantsDisplayNames(status, found.map(displayName))

    override def getArchivedCount: ArchivedCountResult =
      store.getArchivedCount.tap:
        case ArchivedCountResult.ReadFailed(reason) => log.error("get archived count", reason)
        case ArchivedCountResult.Counted(count)     => metrics.setPlantsCount(PlantStatus.Archived, count)

    override def warmDisplayNames(): Unit =
      val _ = getPlants(PlantStatus.Active)
      val _ = getPlants(PlantStatus.Archived)

    override def getDormancyHistory(plant: PlantId): GetDormancyHistoryResult =
      store.getPlant(plant) match
        case GetPlantResult.RecordMissing      => GetDormancyHistoryResult.PlantMissing
        case GetPlantResult.ReadFailed(reason) => failedDormancyRead(reason)
        case _: GetPlantResult.Read            =>
          dormancy.listPeriods(plant).fold(failedDormancyRead, GetDormancyHistoryResult.Read.apply)

    private def failedDormancyRead(reason: Throwable): GetDormancyHistoryResult =
      log.error("read dormancy", reason)
      GetDormancyHistoryResult.ReadFailed(reason)

    override def editDormancy(plant: PlantId, edit: DormancyEdit): EditDormancyResult = lock.exclusively:
      store.getPlant(plant) match
        case GetPlantResult.RecordMissing                                                => EditDormancyResult.PlantMissing
        case GetPlantResult.ReadFailed(reason)                                           => failedDormancyEdit(reason)
        case GetPlantResult.Read(found) if found.details.status === PlantStatus.Archived => EditDormancyResult.InvalidDates
        case GetPlantResult.Read(found)                                                  =>
          dormancy.listPeriods(plant).fold(failedDormancyEdit, periods => changeDormancy(found, periods, edit))

    private def failedDormancyEdit(reason: Throwable): EditDormancyResult =
      log.error("edit dormancy", reason)
      EditDormancyResult.EditFailed(reason)

    private def changeDormancy(plant: Plant, periods: Vector[DormancyPeriod], edit: DormancyEdit): EditDormancyResult =
      edit match
        case DormancyEdit.Start(since) =>
          val maybeEarliest = periods.lastOption.fold(Option(plant.registeredAt))(_.maybeEndedAt)
          if maybeEarliest.exists(from => !since.isBefore(from)) && !since.isAfter(clock.now()) then
            val period = DormancyPeriod(DormancyPeriodId(idGen.nextId()), since, maybeEndedAt = None)
            savedDormancy(plant.id, periods :+ period, dormancy.insertPeriod(plant.id, period))
          else EditDormancyResult.InvalidDates
        case DormancyEdit.End(since) =>
          periods.lastOption.filter(_.maybeEndedAt.isEmpty) match
            case Some(period) => reviseDormancy(plant, periods, NonEmptyList.one(period.copy(maybeEndedAt = since.some)))
            case None         => EditDormancyResult.InvalidDates
        case DormancyEdit.CorrectDates(updates) =>
          val existing = periods.map(period => period.id -> period).toMap
          if updates.forall(period => existing.get(period.id).exists(_.maybeEndedAt.isDefined === period.maybeEndedAt.isDefined)) then
            reviseDormancy(plant, periods, updates)
          else EditDormancyResult.InvalidDates

    private def reviseDormancy(plant: Plant, periods: Vector[DormancyPeriod], updates: NonEmptyList[DormancyPeriod]): EditDormancyResult =
      val changes = updates.toList.map(period => period.id -> period).toMap
      val revised = periods.map(period => changes.getOrElse(period.id, period))
      if changes.size === updates.length && validDormancy(revised, plant.registeredAt, clock.now()) then
        savedDormancy(plant.id, revised, dormancy.updatePeriods(plant.id, updates))
      else EditDormancyResult.InvalidDates

    private def validDormancy(periods: Vector[DormancyPeriod], registeredAt: Instant, now: Instant): Boolean =
      periods.zipWithIndex.forall: (period, index) =>
        val maybeEarliest = if index === 0 then registeredAt.some else periods.lift(index - 1).flatMap(_.maybeEndedAt)
        maybeEarliest.exists(from => !period.startedAt.isBefore(from)) &&
        !period.startedAt.isAfter(now) &&
        period.maybeEndedAt.forall(end => !end.isBefore(period.startedAt) && !end.isAfter(now))

    private def savedDormancy(plant: PlantId, periods: Vector[DormancyPeriod], savedResult: Either[Throwable, Unit]): EditDormancyResult =
      savedResult match
        case Left(error) => failedDormancyEdit(error)
        case Right(_)    =>
          log.info(s"dormancy edited ${plant.value}")
          EditDormancyResult.Edited(periods)

    private def displayName(plant: Plant) =
      plant.id -> plant.details.maybeNickname.map(_.value).getOrElse(plant.details.species.value)

    override def editPlant(plant: PlantId, revise: PlantDetails => PlantDetails): EditPlantResult = lock.exclusively:
      val editResult =
        for
          edited <- readAndRevise(plant, revise)
          _      <- rejectUnknownSubstrate(edited.details.substrate)
          saved  <- persistEdit(edited)
        yield saved
      editResult.fold(identity, EditPlantResult.Edited.apply).tap:
        case EditPlantResult.Edited(edited) => log.info(s"plant edited $edited")
        case _                              => ()

    private def readAndRevise(plant: PlantId, revise: PlantDetails => PlantDetails): Either[EditPlantResult, Plant] =
      store.getPlant(plant) match
        case GetPlantResult.RecordMissing      => EditPlantResult.PlantMissing.asLeft
        case GetPlantResult.ReadFailed(reason) => EditPlantResult.EditFailed(reason).asLeft.tap(_ => log.error("edit plant", reason))
        case GetPlantResult.Read(found) if found.details.status === PlantStatus.Archived => EditPlantResult.PlantArchived.asLeft
        case GetPlantResult.Read(found)                                                  => found.focus(_.details).modify(revise).asRight

    private def rejectUnknownSubstrate(substrate: Substrate) =
      substrateStore.getSubstrateComponents match
        case GetSubstrateComponentsResult.ReadFailed(reason) =>
          EditPlantResult.CatalogReadFailed(reason).asLeft.tap(_ => log.error("edit plant", reason))
        case GetSubstrateComponentsResult.Read(components) =>
          val known = components.filter(_.status === SubstrateComponentStatus.Active).map(_.id).toSet
          Either.cond(substrate.parts.forall(part => known.contains(part.componentId)), right = (), EditPlantResult.UnknownComponent)

    private def persistEdit(plant: Plant): Either[EditPlantResult, Plant] =
      store.updatePlant(plant) match
        case UpdatePlantResult.Updated              => plant.asRight
        case UpdatePlantResult.UpdateFailed(reason) => EditPlantResult.EditFailed(reason).asLeft.tap(_ => log.error("edit plant", reason))

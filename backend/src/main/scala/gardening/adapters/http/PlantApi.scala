package gardening.adapters.http

import cats.syntax.either.*
import cats.data.NonEmptyList
import gardening.domain.*
import gardening.usecases.PlantAttentionMonitor
import gardening.domain.plants.*
import gardening.usecases.PlantManager
import io.circe.derivation.{ConfiguredCodec, ConfiguredEnumCodec}
import io.circe.{Codec, Decoder, Encoder, Json}
import sttp.model.{MediaType, StatusCode}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.Codec as TapirCodec
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import scala.util.chaining.*
import java.time.Instant

import Codecs.given

final private case class ArchivedPlantCount(count: Long) derives Codec.AsObject
final private case class PlantCreation(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate)
final private case class PlantPatchOperation(op: String, path: String, value: Json) derives Codec.AsObject
final private case class DormancyHistoryBody(periods: Vector[DormancyPeriod])
private enum DormancyPatchBody:
  case Start(since: Instant)
  case End(since: Instant)
  case CorrectDates(periods: List[DormancyPeriod])

object PlantApi:

  private val unknownComponent    = ApiError("unknown substrate component")
  private val catalogReadFailed   = ApiError("substrate catalog could not be read")
  private val plantCreationFailed = ApiError("plant could not be created")
  private val plantCreationErrors = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.UnprocessableEntity, jsonBody[ApiError])(unknownComponent),
    oneOfVariantExactMatcher(StatusCode.ServiceUnavailable, jsonBody[ApiError])(catalogReadFailed),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )
  private val plantMissing              = ApiError("plant not found")
  private val plantArchived             = ApiError("plant already archived")
  private val unsupportedPlantPatch     = "unsupported plant patch"
  private val unsupportedPatchMediaType = ApiError("unsupported patch media type")
  private val plantEditFailed           = ApiError("plant could not be edited")
  private val plantPatchErrors          = oneOf[ApiError | String](
    oneOfVariantExactMatcher(StatusCode.BadRequest, stringBody)(unsupportedPlantPatch),
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(plantMissing),
    oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(plantArchived),
    oneOfVariantExactMatcher(StatusCode.UnprocessableEntity, jsonBody[ApiError])(unknownComponent),
    oneOfVariantExactMatcher(StatusCode.ServiceUnavailable, jsonBody[ApiError])(catalogReadFailed),
    oneOfVariantExactMatcher(StatusCode.UnsupportedMediaType, jsonBody[ApiError])(unsupportedPatchMediaType),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )

  final private case class JsonPatchFormat() extends CodecFormat:
    override val mediaType: MediaType = MediaType.unsafeApply("application", "json-patch+json")

  private val plantPatchBody =
    stringBodyUtf8AnyFormat(summon[TapirCodec.JsonCodec[Vector[PlantPatchOperation]]].schema(_.copy(isOptional = false)).format(JsonPatchFormat()))

  private val plantEndpoint       = endpoint.errorOut(ApiError.generic)
  private val createPlantEndpoint =
    endpoint.post.in("plants").in(jsonBody[PlantCreation]).errorOut(plantCreationErrors)
      .out(statusCode(StatusCode.Created)).out(jsonBody[Plant]).summary("Create an active plant")
  private val getPlantsEndpoint =
    plantEndpoint.get.in("plants").in(query[PlantStatus]("status").default(PlantStatus.Active))
      .out(jsonBody[Vector[Plant]]).summary("List plants by status")
  private val getArchivedCountEndpoint =
    plantEndpoint.get.in("plants" / "archived" / "count")
      .out(jsonBody[ArchivedPlantCount]).summary("Count archived plants")
  private val patchPlantEndpoint =
    endpoint.patch.in("plants" / path[String]("plantId")).in(extractFromRequest(_.contentTypeParsed)).in(plantPatchBody)
      .errorOut(plantPatchErrors).out(statusCode(StatusCode.NoContent)).summary("Patch a plant")

  private val dormancyEndpoint     = plantEndpoint.in("plants" / path[String]("plantId") / "dormancy")
  private val getDormancyEndpoint  = dormancyEndpoint.get.out(jsonBody[DormancyHistoryBody]).summary("Read dormancy periods")
  private val invalidDormancyDates = ApiError("invalid dormancy dates")
  private val dormancyEditFailed   = ApiError("dormancy could not be edited")
  private val dormancyPatchErrors  = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(plantMissing),
    oneOfVariantExactMatcher(StatusCode.UnprocessableEntity, jsonBody[ApiError])(invalidDormancyDates),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )
  private val patchDormancyEndpoint = endpoint.patch.in("plants" / path[String]("plantId") / "dormancy")
    .in(jsonBody[DormancyPatchBody]).errorOut(dormancyPatchErrors)
    .out(jsonBody[DormancyHistoryBody]).summary("Edit dormancy periods")

  private[http] val publicEndpoints: List[AnyEndpoint] =
    List(createPlantEndpoint, getPlantsEndpoint, getArchivedCountEndpoint, patchPlantEndpoint, getDormancyEndpoint, patchDormancyEndpoint)

  def serverEndpoints(using plants: PlantManager, attention: PlantAttentionMonitor): List[ServerEndpoint[Any, Identity]] =
    List(
      getDormancyEndpoint.handle: id =>
        plants.getDormancyHistory(PlantId(id)) match
          case GetDormancyHistoryResult.Read(periods) => DormancyHistoryBody(periods).asRight
          case GetDormancyHistoryResult.PlantMissing  => (StatusCode.NotFound, plantMissing).asLeft
          case _: GetDormancyHistoryResult.ReadFailed =>
            (StatusCode.InternalServerError, ApiError("dormancy could not be read")).asLeft,
      patchDormancyEndpoint.handle: (id, body) =>
        val maybeEdit = body match
          case DormancyPatchBody.Start(since)          => Some(DormancyEdit.Start(since))
          case DormancyPatchBody.End(since)            => Some(DormancyEdit.End(since))
          case DormancyPatchBody.CorrectDates(periods) => NonEmptyList.fromList(periods).map(DormancyEdit.CorrectDates.apply)
        maybeEdit.fold[EditDormancyResult](EditDormancyResult.InvalidDates)(edit => plants.editDormancy(PlantId(id), edit)) match
          case EditDormancyResult.Edited(periods) => attention.refreshAll.pipe(_ => DormancyHistoryBody(periods).asRight)
          case EditDormancyResult.PlantMissing    => plantMissing.asLeft
          case EditDormancyResult.InvalidDates    => invalidDormancyDates.asLeft
          case _: EditDormancyResult.EditFailed   => dormancyEditFailed.asLeft,
      createPlantEndpoint.handle: input =>
        plants.createPlant(input.species, input.maybeNickname, input.location, input.substrate) match
          case CreatePlantResult.Created(plant)       => plant.asRight
          case CreatePlantResult.UnknownComponent     => unknownComponent.asLeft
          case _: CreatePlantResult.CatalogReadFailed => catalogReadFailed.asLeft
          case _: CreatePlantResult.CreateFailed      => plantCreationFailed.asLeft,
      getPlantsEndpoint.handle: status =>
        plants.getPlants(status) match
          case GetPlantsResult.Read(found)   => found.asRight
          case _: GetPlantsResult.ReadFailed => (StatusCode.InternalServerError, ApiError("plants could not be read")).asLeft,
      getArchivedCountEndpoint.handle: _ =>
        plants.getArchivedCount match
          case ArchivedCountResult.Counted(count) => ArchivedPlantCount(count).asRight
          case _: ArchivedCountResult.ReadFailed  => (StatusCode.InternalServerError, ApiError("archived count could not be read")).asLeft,
      patchPlantEndpoint.handle: (plantId, maybeContentType, patch) =>
        val isJsonPatch =
          maybeContentType.exists(mediaType => mediaType.mainType.equals("application") && mediaType.subType.equals("json-patch+json"))
        if !isJsonPatch then unsupportedPatchMediaType.asLeft
        else
          patch match
            case Vector(PlantPatchOperation("replace", "/details/status", value)) if value.equals(Json.fromString("archived")) =>
              plants.editPlant(PlantId(plantId), _.copy(status = PlantStatus.Archived)) match
                case _: EditPlantResult.Edited     => attention.refreshAll.pipe(_ => ().asRight)
                case EditPlantResult.PlantMissing  => plantMissing.asLeft
                case EditPlantResult.PlantArchived => plantArchived.asLeft
                case _                             => ApiError("plant could not be archived").asLeft
            case Vector(PlantPatchOperation("replace", "/details", value)) =>
              value.as[PlantCreation] match
                case Left(_)     => unsupportedPlantPatch.asLeft
                case Right(edit) =>
                  plants.editPlant(PlantId(plantId), _.copy(edit.species, edit.maybeNickname, edit.location, edit.substrate)) match
                    case _: EditPlantResult.Edited            => ().asRight
                    case EditPlantResult.PlantMissing         => plantMissing.asLeft
                    case EditPlantResult.PlantArchived        => plantArchived.asLeft
                    case EditPlantResult.UnknownComponent     => unknownComponent.asLeft
                    case _: EditPlantResult.CatalogReadFailed => catalogReadFailed.asLeft
                    case _: EditPlantResult.EditFailed        => plantEditFailed.asLeft
            case _ => unsupportedPlantPatch.asLeft
    )

  private given Schema[Json] = Schema.any

  private given TapirCodec.PlainCodec[PlantStatus] = TapirCodec.string
    .mapDecode { value =>
      PlantStatus.values.find(status => Codecs.lowerCamel(status.toString).equals(value)) match
        case Some(status) => DecodeResult.Value(status)
        case None         => DecodeResult.Error(value, IllegalArgumentException(s"invalid plant status: $value"))
    }(status => Codecs.lowerCamel(status.toString))
    .schema(Codecs.enumSchema[PlantStatus])

  private given Schema[PlantStatus] = Codecs.enumSchema[PlantStatus]
  private given Codec[PlantStatus]  = ConfiguredEnumCodec.derived

  private given Codec[Species] = Codec.from(
    Decoder.decodeString.emap(value => Either.cond(value.trim.nonEmpty, Species(value), "species must not be blank")),
    Encoder.encodeString.contramap(_.value)
  )
  private given Codec[Nickname] = Codec.from(
    Decoder.decodeString.emap(value => Either.cond(value.trim.nonEmpty, Nickname(value), "nickname must not be blank")),
    Encoder.encodeString.contramap(_.value)
  )
  private given Codec[Location] = Codec.from(
    Decoder.decodeString.emap(value => Either.cond(value.trim.nonEmpty, Location(value), "location must not be blank")),
    Encoder.encodeString.contramap(_.value)
  )
  private given Codec.AsObject[PlantCreation]  = ConfiguredCodec.derived
  private given Codec.AsObject[PlantDetails]   = ConfiguredCodec.derived
  private given Codec.AsObject[Plant]          = ConfiguredCodec.derived
  private given Codec[DormancyPeriodId]        = Codec.from(Decoder.decodeString.map(DormancyPeriodId.apply), Encoder.encodeString.contramap(_.value))
  private given Schema[DormancyPeriodId]       = Schema.string
  private given Schema[DormancyPeriod]         = Schema.derived[DormancyPeriod].modify(_.maybeEndedAt)(_.copy(isOptional = false).nullable)
  private given Codec.AsObject[DormancyPeriod] = ConfiguredCodec.derived
  private given Codec.AsObject[DormancyPatchBody]      = ConfiguredCodec.derived
  private given Schema[DormancyPatchBody.CorrectDates] =
    Schema.derived[DormancyPatchBody.CorrectDates]
      .modify(_.periods)(_.copy(isOptional = false).description("At least one period is required."))
  private given Schema[DormancyHistoryBody] = Schema.derived[DormancyHistoryBody].modify(_.periods)(_.copy(isOptional = false))
  private given Schema[PlantCreation]       = Schema
    .derived[PlantCreation]
    .modify(_.maybeNickname)(_.copy(isOptional = false).nullable)
    .modify(_.substrate)(_.copy(isOptional = false))

  private type PlantText = Species | Nickname | Location
  private given [A <: PlantText]: Schema[A] = Schema.string
  private given Schema[PlantDetails]        = Schema
    .derived[PlantDetails]
    .modify(_.maybeNickname)(_.copy(isOptional = false).nullable)
    .modify(_.substrate)(_.copy(isOptional = false))

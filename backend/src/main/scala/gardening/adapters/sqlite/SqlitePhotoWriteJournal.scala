package gardening.adapters.sqlite

import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.plants.*
import gardening.ports.PhotoWriteJournal

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import scala.util.Try

object SqlitePhotoWriteJournal:

  private val timestampFormatter = DateTimeFormatterBuilder().appendInstant(9).toFormatter

  def make(transactor: Transactor): PhotoWriteJournal = LiveSqlitePhotoWriteJournal(transactor)

  private class LiveSqlitePhotoWriteJournal(transactor: Transactor) extends PhotoWriteJournal:

    override def recordAdd(idempotencyKey: String, plant: PlantId, capturedAt: Instant, photo: PhotoId): PhotoJournalWriteResult =
      try
        transact(transactor):
          sql"""insert into photo_write_intent (key, operation, status, plant_id, captured_at, photo_id)
                values ($idempotencyKey, 'Add', 'Pending', ${plant.value}, ${timestampFormatter.format(capturedAt)}, ${photo.value.toString})"""
            .update.run()
        PhotoJournalWriteResult.Recorded
      catch case error: SqlException => PhotoJournalWriteResult.RecordFailed(error)

    override def recordRemove(photo: PhotoId): PhotoJournalWriteResult =
      try
        transact(transactor):
          val key = photo.value.toString
          sql"""insert into photo_write_intent (key, operation, status, photo_id)
                values ($key, 'Remove', 'Pending', $key)""".update.run()
        PhotoJournalWriteResult.Recorded
      catch case error: SqlException => PhotoJournalWriteResult.RecordFailed(error)

    override def markDone(key: String): PhotoJournalWriteResult =
      try
        transact(transactor):
          sql"update photo_write_intent set status = 'Done' where key = $key".update.run()
        PhotoJournalWriteResult.Recorded
      catch case error: SqlException => PhotoJournalWriteResult.RecordFailed(error)

    override def discard(key: String): PhotoJournalWriteResult =
      try
        transact(transactor):
          sql"delete from photo_write_intent where key = $key".update.run()
        PhotoJournalWriteResult.Recorded
      catch case error: SqlException => PhotoJournalWriteResult.RecordFailed(error)

    override def findByKey(key: String): PhotoJournalFindResult =
      try
        val maybeIntentRow = connect(transactor):
          sql"select key, operation, status, photo_id, plant_id, captured_at from photo_write_intent where key = $key"
            .query[PhotoWriteIntentRow].run().headOption
        maybeIntentRow match
          case None      => PhotoJournalFindResult.NotFound
          case Some(row) => PhotoJournalFindResult.Found(trust(toIntent(row)))
      catch
        case error: SqlException       => PhotoJournalFindResult.FindFailed(error)
        case error: DatabaseCorruption => PhotoJournalFindResult.FindFailed(error)

    override def list(): PhotoJournalListResult =
      try
        val rows = connect(transactor):
          sql"select key, operation, status, photo_id, plant_id, captured_at from photo_write_intent".query[PhotoWriteIntentRow].run()
        PhotoJournalListResult.Listed(trust(rows.traverse(toIntent)))
      catch
        case error: SqlException       => PhotoJournalListResult.ListFailed(error)
        case error: DatabaseCorruption => PhotoJournalListResult.ListFailed(error)

    private def toIntent(row: PhotoWriteIntentRow): Either[Throwable, PhotoWriteIntent] =
      parseWriteStatus(row.status).flatMap:
        status =>
          row.operation match
            case "Add"    => toAddIntent(row, status)
            case "Remove" => toRemoveIntent(row, status)
            // A CHECK constraint on this column makes every other stored value unreachable.
            // $COVERAGE-OFF$
            case other => Left(RuntimeException(s"invalid stored photo write intent operation: $other"))
            // $COVERAGE-ON$

    private def toAddIntent(row: PhotoWriteIntentRow, status: PhotoWriteIntentStatus): Either[Throwable, PhotoWriteIntent] =
      for
        plantId    <- requireColumn(row.maybePlantId, s"add photo write intent ${row.key} is missing plant_id").map(PlantId.apply)
        capturedAt <- requireColumn(row.maybeCapturedAt, s"add photo write intent ${row.key} is missing captured_at").flatMap(parseCapturedAt)
        photoId    <- requireColumn(row.maybePhotoId, s"add photo write intent ${row.key} is missing photo_id").flatMap(parsePhotoId)
      yield PhotoWriteIntent.Add(row.key, status, plantId, capturedAt, photoId)

    private def toRemoveIntent(row: PhotoWriteIntentRow, status: PhotoWriteIntentStatus): Either[Throwable, PhotoWriteIntent] =
      requireColumn(row.maybePhotoId, s"remove photo write intent ${row.key} is missing photo_id")
        .flatMap(parsePhotoId)
        .map(PhotoWriteIntent.Remove(row.key, status, _))

    private def requireColumn(maybeColumnValue: Option[String], message: String): Either[Throwable, String] =
      maybeColumnValue.toRight(RuntimeException(message))

    private def parseWriteStatus(value: String): Either[Throwable, PhotoWriteIntentStatus] = value match
      case "Pending" => Right(PhotoWriteIntentStatus.Pending)
      case "Done"    => Right(PhotoWriteIntentStatus.Done)
      // A CHECK constraint on this column makes every other stored value unreachable.
      // $COVERAGE-OFF$
      case other => Left(RuntimeException(s"invalid stored photo write intent status: $other"))
      // $COVERAGE-ON$

    private def parsePhotoId(value: String): Either[Throwable, PhotoId] =
      Try(PhotoId(UUID.fromString(value))).toEither.left.map(_ => RuntimeException(s"invalid stored photo write intent photo id: $value"))

    private def parseCapturedAt(value: String): Either[Throwable, Instant] =
      Try(Instant.parse(value)).toEither.left.map(_ => RuntimeException(s"invalid stored photo write intent capturedAt: $value"))

  private case class PhotoWriteIntentRow(
      key: String,
      operation: String,
      status: String,
      maybePhotoId: Option[String],
      maybePlantId: Option[String],
      maybeCapturedAt: Option[String]
  ) derives DbCodec

package com.yaprak.reader.domain.statistics

import com.yaprak.reader.domain.library.Book
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.Flow

const val MAX_READING_DURATION_RECEIPTS = 100_000
const val MAX_READING_DURATION_MILLIS = 172_800_000L
const val MAX_READING_DURATION_TIMESTAMP = 253_402_300_799_999L
const val MAX_READING_DURATION_TOTAL_MILLIS = MAX_READING_DURATION_MILLIS * MAX_READING_DURATION_RECEIPTS
private val READING_DURATION_HASH = Regex("[0-9a-f]{64}")

/** A cumulative checkpoint of one interval in one civil day, measured by a monotonic clock. */
data class ReadingDurationReceipt(
    val id: String,
    val bookId: String,
    val contentSha256: String,
    val localDate: String,
    val zoneId: String,
    val startedAt: Long,
    val endedAt: Long,
    val elapsedMillis: Long,
)

data class ReadingDurationDay(val localDate: String, val elapsedMillis: Long)
data class BookReadingDuration(val book: Book, val totalMillis: Long)

/** Only durable receipts for the current library and current book hashes contribute. */
class ReadingDurationSnapshot(
    days: List<ReadingDurationDay>,
    books: List<BookReadingDuration>,
    val totalMillis: Long,
) {
    val days: List<ReadingDurationDay> = Collections.unmodifiableList(ArrayList(days))
    /** Includes current books without a recorded duration, with a zero total. */
    val books: List<BookReadingDuration> = Collections.unmodifiableList(ArrayList(books))
}

enum class ReadingDurationFailure { INVALID_RECORD, SOURCE, RECOVERY, LIMIT, STALE_BOOK }
class ReadingDurationException(
    val reason: ReadingDurationFailure,
    cause: Throwable? = null,
) : Exception(reason.name, cause)

interface ReadingDurationRepository {
    fun observe(): Flow<ReadingDurationSnapshot>
    /** Same-id checkpoints advance by MAX; retries never add the duration twice. */
    suspend fun record(receipt: ReadingDurationReceipt)
}

/** Shared storage/backup validation. Wall-clock differences are never treated as elapsed time. */
fun validateReadingDurationReceipt(receipt: ReadingDurationReceipt) {
    val valid = runCatching {
        require(receipt.id.length == 36 && UUID.fromString(receipt.id).toString() == receipt.id)
        require(receipt.bookId.isNotBlank() && receipt.bookId.length <= 200 &&
            receipt.bookId.none { it.isISOControl() || it.isSurrogate() })
        require(receipt.contentSha256.matches(READING_DURATION_HASH))
        require(receipt.localDate.length == 10)
        val date = LocalDate.parse(receipt.localDate)
        require(date.toString() == receipt.localDate)
        require(receipt.zoneId.isNotEmpty() && receipt.zoneId.length <= 100 &&
            receipt.zoneId.none { it.isISOControl() || it.isSurrogate() })
        val zone = ZoneId.of(receipt.zoneId)
        require(zone.id == receipt.zoneId)
        require(receipt.startedAt in 0..MAX_READING_DURATION_TIMESTAMP &&
            receipt.endedAt in receipt.startedAt..MAX_READING_DURATION_TIMESTAMP)
        require(receipt.elapsedMillis in 1..MAX_READING_DURATION_MILLIS)
        require(Instant.ofEpochMilli(receipt.startedAt).atZone(zone).toLocalDate() == date)
        val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        require(receipt.endedAt <= nextMidnight)
    }.isSuccess
    if (!valid) throw ReadingDurationException(ReadingDurationFailure.INVALID_RECORD)
}

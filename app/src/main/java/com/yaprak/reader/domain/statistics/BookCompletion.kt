package com.yaprak.reader.domain.statistics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.Flow

const val MAX_BOOK_COMPLETION_EVENTS = 100_000
const val BOOK_COMPLETION_VISIBLE_LIMIT = 200

/** Explicit status transition time, never inferred from an import, locator, or current status. */
data class BookCompletionEvent(val id: String, val bookId: String, val contentSha256: String,
    val completedAt: Long, val localDate: String, val zoneId: String)
data class BookCompletionEntry(val event: BookCompletionEvent, val title: String, val authors: String?)
class BookCompletionSnapshot(val total: Int, entries: List<BookCompletionEntry>) {
    val entries: List<BookCompletionEntry> = Collections.unmodifiableList(ArrayList(entries))
}
enum class BookCompletionFailure { SOURCE, RECOVERY, INVALID_RECORD, LIMIT }
class BookCompletionException(val reason: BookCompletionFailure, cause: Throwable? = null) : Exception(reason.name, cause)
interface BookCompletionRepository { fun observe(): Flow<BookCompletionSnapshot> }

fun validateBookCompletionEvent(event: BookCompletionEvent) {
    val valid = runCatching {
        require(event.id.length == 36 && UUID.fromString(event.id).toString() == event.id)
        require(event.bookId.isNotBlank() && event.bookId.length <= 200 &&
            event.bookId.none { it.isISOControl() || it.isSurrogate() })
        require(event.contentSha256.matches(Regex("[0-9a-f]{64}")))
        require(event.completedAt in 0..MAX_READING_DURATION_TIMESTAMP)
        require(event.localDate.length == 10 && LocalDate.parse(event.localDate).toString() == event.localDate)
        require(event.zoneId.isNotEmpty() && event.zoneId.length <= 100 &&
            event.zoneId.none { it.isISOControl() || it.isSurrogate() })
        val zone = ZoneId.of(event.zoneId)
        require(zone.id == event.zoneId)
        require(Instant.ofEpochMilli(event.completedAt).atZone(zone).toLocalDate().toString() == event.localDate)
    }.isSuccess
    if (!valid) throw BookCompletionException(BookCompletionFailure.INVALID_RECORD)
}
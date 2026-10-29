package com.yaprak.reader.domain.vocabulary

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

const val MAX_WORD_REVIEW_CARDS = 200
const val MAX_WORD_REVIEW_HISTORY = 100
const val MAX_WORD_REVIEW_EVENTS = 100_000
const val WORD_REVIEW_ALGORITHM = 1
enum class WordRating { AGAIN, HARD, GOOD, EASY }
enum class WordReviewFailure { SOURCE, STALE, CLOCK, LIMIT, INVALID }
class WordReviewException(val reason: WordReviewFailure) : Exception(reason.name)

data class WordSchedule(
    val wordId: String, val bookId: String, val contentSha256: String,
    val wordRevision: Long, val fingerprint: String, val version: Long,
    val step: Int, val reviewedAt: Long, val dueAt: Long, val zoneId: String,
    val lastEventId: String, val algorithm: Int = WORD_REVIEW_ALGORITHM,
)
data class WordReviewCard(val entry: LibraryWord, val schedule: WordSchedule?) {
    val currentSchedule get() = schedule?.takeIf { it.wordRevision == entry.word.revision &&
        it.fingerprint == wordReviewFingerprint(entry.word) }
    val expectedVersion get() = schedule?.version ?: 0L
}
data class WordReviewCommand(val id: String, val card: WordReviewCard, val rating: WordRating)
data class WordReviewEvent(
    val id: String, val wordId: String, val bookId: String, val contentSha256: String,
    val wordRevision: Long, val fingerprint: String, val english: String, val turkish: String,
    val wordCreatedAt: Long, val previousVersion: Long, val version: Long, val step: Int,
    val reviewedAt: Long, val dueAt: Long, val zoneId: String, val rating: WordRating,
    val algorithm: Int = WORD_REVIEW_ALGORITHM,
) {
    fun schedule() = WordSchedule(wordId, bookId, contentSha256, wordRevision, fingerprint,
        version, step, reviewedAt, dueAt, zoneId, id, algorithm)
    fun matches(command: WordReviewCommand) = id == command.id && wordId == command.card.entry.word.id &&
        bookId == command.card.entry.bookId && contentSha256 == command.card.entry.contentSha256 &&
        wordRevision == command.card.entry.word.revision && fingerprint == wordReviewFingerprint(command.card.entry.word) &&
        wordCreatedAt == command.card.entry.word.createdAt && previousVersion == command.card.expectedVersion && rating == command.rating
}
data class WordReviewHistoryEntry(val event: WordReviewEvent, val bookTitle: String, val previousContent: Boolean)
data class WordReviewSnapshot(
    val cards: List<WordReviewCard>, val dueCount: Int, val newCount: Int, val totalWords: Int,
    val history: List<WordReviewHistoryEntry>, val historyCount: Int,
)
interface WordReviewRepository {
    suspend fun load(): WordReviewSnapshot
    suspend fun rate(command: WordReviewCommand): WordReviewEvent
}

// Digest exact UTF-16 code units, including legacy text; UTF-8 replacement cannot alias malformed strings.
fun wordReviewFingerprint(word: SavedWord): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(word.id, word.createdAt.toString(), word.revision.toString(), word.english, word.turkish).forEach { value ->
        digest.update(value.length.toString().toByteArray(Charsets.US_ASCII)); digest.update(0.toByte())
        value.forEach { digest.update((it.code ushr 8).toByte()); digest.update(it.code.toByte()) }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

fun validateReviewWord(entry: LibraryWord) {
    fun text(value: String, limit: Int) = value.length in 1..limit && value.isNotBlank()
    val word = entry.word
    reviewRequire(text(word.id, 1000) && text(entry.bookId, 1000) && text(entry.bookTitle, 1000) &&
        (entry.bookAuthors == null || text(entry.bookAuthors, 2000)) &&
        entry.contentSha256.matches(Regex("[0-9a-f]{64}")) && word.createdAt >= 0 && word.revision >= 0 &&
        text(word.english, MAX_ENGLISH_WORD_LENGTH) && text(word.turkish, MAX_TURKISH_MEANING_LENGTH) &&
        word.english.none { it == '\r' || it == '\n' } && word.turkish.none { it == '\r' || it == '\n' })
}
fun validateWordSchedule(value: WordSchedule) {
    reviewRequire(value.wordId.length in 1..1000 && value.bookId.length in 1..1000 &&
        value.contentSha256.matches(Regex("[0-9a-f]{64}")) && value.fingerprint.matches(Regex("[0-9a-f]{64}")) &&
        value.wordRevision >= 0 && value.version in 1 until Long.MAX_VALUE && value.step in -1..6 &&
        value.algorithm == WORD_REVIEW_ALGORITHM && validReviewTime(value.reviewedAt) && validReviewTime(value.dueAt) &&
        value.dueAt > value.reviewedAt && canonicalReviewId(value.lastEventId))
    try { reviewRequire(value.zoneId.length in 1..100); ZoneId.of(value.zoneId) }
    catch (_: Exception) { throw WordReviewException(WordReviewFailure.INVALID) }
}
fun validateWordReviewEvent(value: WordReviewEvent) {
    validateWordSchedule(value.schedule())
    reviewRequire(value.previousVersion >= 0 && value.previousVersion < Long.MAX_VALUE - 1 &&
        value.version == value.previousVersion + 1 && value.wordCreatedAt >= 0 && value.wordCreatedAt <= value.reviewedAt)
    val word = SavedWord(value.wordId, value.english, value.turkish, value.wordCreatedAt, value.wordRevision)
    validateReviewWord(LibraryWord(word, value.bookId, value.contentSha256, "history", null))
    reviewRequire(value.fingerprint == wordReviewFingerprint(word))
    val expectedDue = calculateReviewDue(value.step, value.reviewedAt, ZoneId.of(value.zoneId))
    reviewRequire(value.dueAt == expectedDue)
    reviewRequire((value.rating == WordRating.AGAIN) == (value.step == -1))
}

/** Simple calendar-day ladder, not FSRS or Anki scheduling. Only an explicit rating creates an event. */
fun planWordReview(command: WordReviewCommand, now: Long, zone: ZoneId): WordReviewEvent {
    val card = command.card
    validateReviewWord(card.entry)
    reviewRequire(canonicalReviewId(command.id))
    card.schedule?.let { validateWordSchedule(it); reviewRequire(it.wordId == card.entry.word.id &&
        it.bookId == card.entry.bookId && it.contentSha256 == card.entry.contentSha256) }
    if (!validReviewTime(now) || now < card.entry.word.createdAt ||
        card.schedule?.let { now < it.reviewedAt } == true) throw WordReviewException(WordReviewFailure.CLOCK)
    if (card.currentSchedule?.let { now < it.dueAt } == true) throw WordReviewException(WordReviewFailure.STALE)
    val previous = card.currentSchedule?.step ?: -1
    val step = when (command.rating) {
        WordRating.AGAIN -> -1
        WordRating.HARD -> (previous - 1).coerceAtLeast(0)
        WordRating.GOOD -> (previous + 1).coerceAtMost(6)
        WordRating.EASY -> (previous + 2).coerceAtMost(6)
    }
    if (card.expectedVersion >= Long.MAX_VALUE - 1) throw WordReviewException(WordReviewFailure.LIMIT)
    val word = card.entry.word
    return WordReviewEvent(command.id, word.id, card.entry.bookId, card.entry.contentSha256, word.revision,
        wordReviewFingerprint(word), word.english, word.turkish, word.createdAt, card.expectedVersion,
        card.expectedVersion + 1, step, now, calculateReviewDue(step, now, zone), zone.id, command.rating).also(::validateWordReviewEvent)
}

private val reviewDays = listOf(1L, 3L, 7L, 14L, 30L, 60L, 120L)
fun calculateReviewDue(step: Int, now: Long, zone: ZoneId): Long = try {
    reviewRequire(step in -1..6 && validReviewTime(now))
    val due = if (step == -1) Math.addExact(now, 10 * 60 * 1000L)
        else Instant.ofEpochMilli(now).atZone(zone).plusDays(reviewDays[step]).toInstant().toEpochMilli()
    if (!validReviewTime(due) || due <= now) throw WordReviewException(WordReviewFailure.CLOCK)
    due
} catch (failure: WordReviewException) { throw failure }
catch (_: Exception) { throw WordReviewException(WordReviewFailure.CLOCK) }
fun validReviewTime(value: Long) = value in 946684800000L..4102444799999L // 2000..2099 UTC
fun canonicalReviewId(value: String) = try { value.length == 36 && UUID.fromString(value).toString() == value }
    catch (_: Exception) { false }
private fun reviewRequire(condition: Boolean) { if (!condition) throw WordReviewException(WordReviewFailure.INVALID) }

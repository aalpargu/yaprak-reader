package com.yaprak.reader.data.persistence

import android.database.Cursor
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.domain.vocabulary.*
import java.time.Clock
import java.time.ZoneId
import java.util.PriorityQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable explicit ratings. Shared restore gate and a writer transaction protect the word and receipt together. */
class RoomWordReviewRepository(
    private val database: LibraryDatabase, private val fileMutex: Mutex = Mutex(),
    private val validateAccess: () -> Unit = {}, private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(), private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : WordReviewRepository {
    override suspend fun load(): WordReviewSnapshot = transaction(false) { sql, active ->
        val now = clock.millis()
        if (!validReviewTime(now)) fail(WordReviewFailure.CLOCK)
        val cards = PriorityQueue(MAX_WORD_REVIEW_CARDS, cardOrder.reversed())
        var total = 0; var due = 0; var fresh = 0
        sql.query(REVIEW_WORDS).use { cursor ->
            while (cursor.moveToNext()) {
                active()
                val entry = cursor.readLibraryWord()
                validateReviewWord(entry)
                val schedule = if (cursor.isNull(cursor.getColumnIndexOrThrow("scheduleWordId"))) null else cursor.schedule("scheduleWordId")
                if (schedule != null) {
                    requireSource(schedule.bookId == entry.bookId && schedule.contentSha256 == entry.contentSha256 &&
                        schedule.wordRevision <= entry.word.revision)
                    if (schedule.wordRevision == entry.word.revision) requireSource(schedule.fingerprint == wordReviewFingerprint(entry.word))
                    requireSource(cursor.text("proofId") == schedule.lastEventId && cursor.number("proofMatches") == 1L)
                    if (now < schedule.reviewedAt) fail(WordReviewFailure.CLOCK)
                }
                val card = WordReviewCard(entry, schedule)
                requireSource(total < Int.MAX_VALUE); total++
                if (card.currentSchedule == null) fresh++
                if (card.currentSchedule?.let { it.dueAt > now } == true) continue
                due++
                if (cards.size < MAX_WORD_REVIEW_CARDS) cards.add(card)
                else if (cardOrder.compare(card, cards.peek()) < 0) { cards.poll(); cards.add(card) }
            }
        }
        val history = PriorityQueue(MAX_WORD_REVIEW_HISTORY, historyOrder.reversed())
        var count = 0
        var prior: WordSchedule? = null
        var expectedLatestId: String? = null
        sql.query("""SELECT e.*, b.title AS bookTitle, w.revision AS currentRevision,
            w.english AS currentEnglish, w.turkish AS currentTurkish, w.createdAt AS currentCreatedAt,
            s.lastEventId AS latestEventId
            FROM word_review_events e INNER JOIN saved_words w ON w.id=e.wordId AND w.bookId=e.bookId
              AND w.contentSha256=e.contentSha256
            INNER JOIN books b ON b.id=e.bookId AND b.sha256=e.contentSha256
            LEFT JOIN word_schedules s ON s.wordId=e.wordId ORDER BY e.wordId,e.version""").use { cursor ->
            while (cursor.moveToNext()) {
                active()
                if (++count > MAX_WORD_REVIEW_EVENTS) fail(WordReviewFailure.LIMIT)
                val event = cursor.event()
                if (prior?.wordId != event.wordId) {
                    if (prior != null) requireSource(prior!!.lastEventId == expectedLatestId)
                    prior = null
                }
                expectedLatestId = cursor.text("latestEventId")
                requireSource(prior?.let { event.wordRevision >= it.wordRevision } != false)
                val historicalWord = SavedWord(event.wordId, event.english, event.turkish, event.wordCreatedAt, event.wordRevision)
                val historicalEntry = LibraryWord(historicalWord, event.bookId, event.contentSha256, "history", null)
                val planned = try { planWordReview(WordReviewCommand(event.id, WordReviewCard(historicalEntry, prior), event.rating),
                    event.reviewedAt, ZoneId.of(event.zoneId)) } catch (_: WordReviewException) { fail(WordReviewFailure.SOURCE) }
                requireSource(planned == event)
                prior = event.schedule()
                val currentRevision = cursor.number("currentRevision")
                requireSource(event.wordRevision <= currentRevision && event.wordCreatedAt == cursor.number("currentCreatedAt"))
                val previous = event.wordRevision != currentRevision
                if (!previous) requireSource(event.english == cursor.text("currentEnglish") && event.turkish == cursor.text("currentTurkish"))
                val title = cursor.text("bookTitle").also { requireSource(it.length in 1..1000 && it.isNotBlank()) }
                val item = WordReviewHistoryEntry(event, title, previous)
                if (history.size < MAX_WORD_REVIEW_HISTORY) history.add(item)
                else if (historyOrder.compare(item, history.peek()) < 0) { history.poll(); history.add(item) }
            }
        }
        if (prior != null) requireSource(prior!!.lastEventId == expectedLatestId)
        WordReviewSnapshot(cards.sortedWith(cardOrder), due, fresh, total, history.sortedWith(historyOrder), count)
    }

    override suspend fun rate(command: WordReviewCommand): WordReviewEvent = transaction(true) { sql, active ->
        validateReviewWord(command.card.entry)
        if (!canonicalReviewId(command.id)) fail(WordReviewFailure.INVALID)
        event(sql, command.id, active)?.let { existing ->
            if (!existing.matches(command)) fail(WordReviewFailure.STALE)
            return@transaction existing // A committed retry succeeds even when the next due date is in the future.
        }
        val expected = command.card.entry
        val actual = sql.query(SimpleSQLiteQuery("$LIBRARY_WORDS_JOIN WHERE saved.id=?", arrayOf(expected.word.id))).use { cursor ->
            active(); if (cursor.moveToFirst()) cursor.readLibraryWord() else null
        }
        // Book metadata is display context; text, revision, creation time and publication are the review identity.
        if (actual == null || actual.word != expected.word || actual.bookId != expected.bookId ||
            actual.contentSha256 != expected.contentSha256) fail(WordReviewFailure.STALE)
        val current = sql.query(SimpleSQLiteQuery("SELECT * FROM word_schedules WHERE wordId=?", arrayOf(expected.word.id))).use {
            active(); if (it.moveToFirst()) it.schedule() else null
        }
        if (current != command.card.schedule) fail(WordReviewFailure.STALE)
        if (current != null) {
            requireSource(current.wordRevision <= actual.word.revision && event(sql, current.lastEventId, active)?.schedule() == current)
            if (current.wordRevision == actual.word.revision) requireSource(current.fingerprint == wordReviewFingerprint(actual.word))
        }
        val count = sql.query("SELECT COUNT(*) AS count FROM word_review_events").use {
            active(); requireSource(it.moveToFirst()); it.number("count")
        }
        if (count >= MAX_WORD_REVIEW_EVENTS) fail(WordReviewFailure.LIMIT)
        validateCurrentHistory(sql, actual, current, active)
        val result = planWordReview(command, clock.millis(), zone())
        active()
        sql.execSQL("""INSERT INTO word_review_events
            (id,wordId,bookId,contentSha256,wordRevision,fingerprint,english,turkish,wordCreatedAt,
             previousVersion,version,step,reviewedAt,dueAt,zoneId,rating,algorithm)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", arrayOf<Any>(result.id, result.wordId, result.bookId,
            result.contentSha256, result.wordRevision, result.fingerprint, result.english, result.turkish,
            result.wordCreatedAt, result.previousVersion, result.version, result.step, result.reviewedAt,
            result.dueAt, result.zoneId, result.rating.name, result.algorithm))
        // API26-compatible UPSERT: the schedule is inside the same writer transaction as the unique event.
        sql.execSQL("""INSERT OR REPLACE INTO word_schedules
            (wordId,bookId,contentSha256,wordRevision,fingerprint,version,step,reviewedAt,dueAt,zoneId,lastEventId,algorithm)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", arrayOf<Any>(result.wordId, result.bookId, result.contentSha256,
            result.wordRevision, result.fingerprint, result.version, result.step, result.reviewedAt,
            result.dueAt, result.zoneId, result.id, result.algorithm))
        active()
        result
    }

    private fun event(sql: SupportSQLiteDatabase, id: String, active: () -> Unit): WordReviewEvent? =
        sql.query(SimpleSQLiteQuery("SELECT * FROM word_review_events WHERE id=?", arrayOf(id))).use {
            active(); if (it.moveToFirst()) it.event() else null
        }
    private fun validateCurrentHistory(sql: SupportSQLiteDatabase, current: LibraryWord, schedule: WordSchedule?, active: () -> Unit) {
        var previous: WordSchedule? = null
        sql.query(SimpleSQLiteQuery("SELECT * FROM word_review_events WHERE wordId=? ORDER BY version", arrayOf(current.word.id))).use { cursor ->
            while (cursor.moveToNext()) {
                active(); val event = cursor.event()
                requireSource(event.bookId == current.bookId && event.contentSha256 == current.contentSha256 &&
                    event.wordCreatedAt == current.word.createdAt && event.wordRevision <= current.word.revision &&
                    previous?.let { event.wordRevision >= it.wordRevision } != false)
                if (event.wordRevision == current.word.revision) requireSource(event.english == current.word.english && event.turkish == current.word.turkish)
                val word = SavedWord(event.wordId, event.english, event.turkish, event.wordCreatedAt, event.wordRevision)
                val card = WordReviewCard(LibraryWord(word, current.bookId, current.contentSha256, "history", null), previous)
                val planned = try { planWordReview(WordReviewCommand(event.id, card, event.rating), event.reviewedAt, ZoneId.of(event.zoneId)) }
                    catch (_: WordReviewException) { fail(WordReviewFailure.SOURCE) }
                requireSource(planned == event); previous = event.schedule()
            }
        }
        requireSource(previous == schedule)
    }
    private suspend fun <T> transaction(write: Boolean, block: (SupportSQLiteDatabase, () -> Unit) -> T): T {
        val caller = currentCoroutineContext()
        return withContext(io) { try {
            fileMutex.withLock {
                caller.ensureActive(); validateAccess()
                suspend fun work(): T {
                    val lease = currentCoroutineContext()
                    val active = { lease.ensureActive(); caller.ensureActive() }
                    active()
                    return block(database.openHelper.writableDatabase, active).also { active() }
                }
                if (write) database.useWriterConnection { it.withTransaction(SQLiteTransactionType.IMMEDIATE) { work() } }
                else database.useReaderConnection { it.withTransaction(SQLiteTransactionType.DEFERRED) { work() } }
            }
        } catch (cancelled: CancellationException) { caller.ensureActive(); fail(WordReviewFailure.SOURCE) }
        catch (failure: WordReviewException) { throw failure }
        catch (_: Exception) { fail(WordReviewFailure.SOURCE) } }
    }
}

private val cardOrder = compareBy<WordReviewCard> { it.currentSchedule?.dueAt ?: 0L }
    .thenBy { it.entry.word.createdAt }.thenBy { it.entry.word.id }
private val historyOrder = compareByDescending<WordReviewHistoryEntry> { it.event.reviewedAt }.thenByDescending { it.event.id }
private const val REVIEW_WORDS = """SELECT saved.id,saved.english,saved.turkish,saved.createdAt,
    saved.bookId,saved.contentSha256,book.title,book.authors,saved.revision,
    s.wordId AS scheduleWordId,s.bookId AS scheduleBookId,s.contentSha256 AS scheduleHash,
    s.wordRevision,s.fingerprint,s.version,s.step,s.reviewedAt,s.dueAt,s.zoneId,s.lastEventId,s.algorithm,
    e.id AS proofId,(e.wordId=s.wordId AND e.bookId=s.bookId AND e.contentSha256=s.contentSha256
      AND e.wordRevision=s.wordRevision AND e.fingerprint=s.fingerprint AND e.version=s.version
      AND e.step=s.step AND e.reviewedAt=s.reviewedAt AND e.dueAt=s.dueAt AND e.zoneId=s.zoneId
      AND e.algorithm=s.algorithm) AS proofMatches
    FROM saved_words saved INNER JOIN books book ON book.id=saved.bookId AND book.sha256=saved.contentSha256
    LEFT JOIN word_schedules s ON s.wordId=saved.id LEFT JOIN word_review_events e ON e.id=s.lastEventId"""
private fun Cursor.schedule(idColumn: String = "wordId"): WordSchedule {
    val joined = idColumn == "scheduleWordId"
    return WordSchedule(text(idColumn), text(if (joined) "scheduleBookId" else "bookId"),
        text(if (joined) "scheduleHash" else "contentSha256"), number("wordRevision"), text("fingerprint"),
        number("version"), integer("step"), number("reviewedAt"), number("dueAt"), text("zoneId"),
        text("lastEventId"), integer("algorithm")).also(::validateWordSchedule)
}
private fun Cursor.event() = WordReviewEvent(text("id"), text("wordId"), text("bookId"), text("contentSha256"),
    number("wordRevision"), text("fingerprint"), text("english"), text("turkish"), number("wordCreatedAt"),
    number("previousVersion"), number("version"), integer("step"), number("reviewedAt"), number("dueAt"),
    text("zoneId"), try { WordRating.valueOf(text("rating")) } catch (_: Exception) { fail(WordReviewFailure.SOURCE) },
    integer("algorithm")).also(::validateWordReviewEvent)
private fun Cursor.text(name: String): String {
    val index = getColumnIndexOrThrow(name); requireSource(getType(index) == Cursor.FIELD_TYPE_STRING); return getString(index)
}
private fun Cursor.number(name: String): Long {
    val index = getColumnIndexOrThrow(name); requireSource(getType(index) == Cursor.FIELD_TYPE_INTEGER); return getLong(index)
}
private fun Cursor.integer(name: String): Int = number(name).also { requireSource(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
private fun requireSource(value: Boolean) { if (!value) fail(WordReviewFailure.SOURCE) }
private fun fail(reason: WordReviewFailure): Nothing = throw WordReviewException(reason)

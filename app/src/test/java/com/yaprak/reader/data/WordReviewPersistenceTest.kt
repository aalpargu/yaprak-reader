package com.yaprak.reader.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.vocabulary.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WordReviewPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val book = Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", "book.epub", 10, "a".repeat(64), 1)
    private var now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant() = Instant.ofEpochMilli(now)
    }
    private fun repo(db: LibraryDatabase, mutex: Mutex = Mutex(), gate: () -> Unit = {}) =
        RoomWordReviewRepository(db, mutex, gate, clock = clock, zone = { ZoneId.of("Europe/Istanbul") })
    private suspend fun seed(db: LibraryDatabase, size: Int = 1) {
        RoomLibraryRepository(db.books()).add(book)
        repeat(size) { db.savedWords().add(SavedWordEntity("word-$it", book.id, book.sha256, "leaf-$it", "yaprak-$it", it.toLong())) }
    }
    private suspend fun fixture(test: suspend (LibraryDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try { seed(db); test(db) } finally { db.close() }
    }
    private fun command(card: WordReviewCard, rating: WordRating = WordRating.GOOD) = WordReviewCommand(UUID.randomUUID().toString(), card, rating)

    @Test fun explicitRatingPersistsAndRetryIsIdempotentWhileConflictingReceiptAndEarlySecondRatingFail() = runBlocking {
        fixture { db ->
            val repository = repo(db); val snapshot = repository.load()
            assertEquals(0, snapshot.historyCount); assertEquals(1, snapshot.newCount)
            val c = command(snapshot.cards.single()); val event = repository.rate(c)
            assertEquals(event, repository.rate(c)); assertEquals(1, repository.load().historyCount)
            assertTrue(repository.load().cards.isEmpty())
            fails(WordReviewFailure.STALE) { repository.rate(c.copy(rating = WordRating.EASY)) }
            fails(WordReviewFailure.STALE) { repository.rate(command(c.card)) }
            now = event.dueAt
            val second = repository.rate(command(repository.load().cards.single(), WordRating.EASY))
            assertEquals(2L, second.version); assertEquals(2, second.step)
            assertEquals(0L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM reading_progress").use { it.moveToFirst(); it.getLong(0) })
        }
    }
    @Test fun editAbaRejectsOldCardAndPreservesMarkedPreviousContentHistoryAndDeleteCascades() = runBlocking {
        fixture { db ->
            val repository = repo(db); val oldCard = repository.load().cards.single()
            repository.rate(command(oldCard))
            val words = RoomSavedWordRepository(db)
            words.update(book.id, book.sha256, oldCard.entry.word.id, "leaf-0", "sayfa")
            words.update(book.id, book.sha256, oldCard.entry.word.id, "leaf-0", "yaprak-0")
            fails(WordReviewFailure.STALE) { repository.rate(command(oldCard)) }
            val edited = repository.load()
            assertEquals(2L, edited.cards.single().entry.word.revision); assertTrue(edited.history.single().previousContent)
            val event = repository.rate(command(edited.cards.single()))
            assertEquals(0, event.step); assertEquals(2L, event.version)
            words.remove(book.id, book.sha256, oldCard.entry.word.id)
            assertEquals(0, repository.load().historyCount)
            assertEquals(0L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM word_schedules").use { it.moveToFirst(); it.getLong(0) })
        }
    }
    @Test fun dueQueueCountsAllRowsBeforeCapAndHistoryOrdersBoundedly() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            seed(db, 230); val repository = repo(db); val first = repository.load()
            assertEquals(230, first.dueCount); assertEquals(230, first.newCount); assertEquals(200, first.cards.size)
            assertEquals("word-0", first.cards.first().entry.word.id)
            repeat(105) { repository.rate(command(repository.load().cards.first())); now++ }
            val result = repository.load()
            assertEquals(125, result.dueCount); assertEquals(100, result.history.size); assertEquals(105, result.historyCount)
            assertTrue(result.history.zipWithNext().all { (a, b) -> a.event.reviewedAt >= b.event.reviewedAt })
            assertTrue(result.cards.any { it.entry.word.id == "word-229" })
        } finally { db.close() }
    }
    @Test fun racingAnswersHaveOneWinnerAndSameCommandRetryHasOneReceipt() = runBlocking {
        fixture { db ->
            val repository = repo(db); val card = repository.load().cards.single()
            val first = command(card); val second = command(card)
            val results = listOf(first, second).map { async(Dispatchers.IO) { runCatching { repository.rate(it) } } }.awaitAll()
            assertEquals(1, results.count { it.isSuccess }); assertEquals(1, repository.load().historyCount)
            val successful = results.indexOfFirst { it.isSuccess }; assertEquals(results[successful].getOrThrow(), repository.rate(listOf(first, second)[successful]))
        }
    }
    @Test fun currentHashClockGateAndRawCorruptionFailWithoutCreditingNewData() = runBlocking {
        fixture { db ->
            val repository = repo(db); val card = repository.load().cards.single()
            fails(WordReviewFailure.SOURCE) { repo(db, gate = { error("Recovery pending") }).load() }
            val event = repository.rate(command(card)); now--
            fails(WordReviewFailure.CLOCK) { repository.load() }; now = event.dueAt
            db.openHelper.writableDatabase.execSQL("UPDATE word_schedules SET step=1.5")
            fails(WordReviewFailure.SOURCE) { repository.load() }
            db.openHelper.writableDatabase.execSQL("UPDATE word_schedules SET step=0")
            RoomSavedWordRepository(db).update(book.id, book.sha256, card.entry.word.id, "edited", "anlam")
            val current = repository.load().cards.single()
            db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256=?", arrayOf("b".repeat(64)))
            fails(WordReviewFailure.STALE) { repository.rate(command(current)) }
            assertEquals(0, repository.load().totalWords)
        }
    }
    @Test fun cancelledCallerWaitingForRestoreLockNeverWrites() = runBlocking {
        fixture { db ->
            val mutex = Mutex(locked = true); val card = repo(db).load().cards.single()
            val pending = launch { repo(db, mutex).rate(command(card)) }
            yield(); pending.cancelAndJoin(); mutex.unlock()
            assertEquals(0, repo(db).load().historyCount)
        }
    }
    @Test fun individuallyValidButWrongHistoricalStepAndSchedulePointingToOldReceiptAreRejected() = runBlocking {
        fixture { db ->
            val repository = repo(db); val first = repository.rate(command(repository.load().cards.single()))
            now = first.dueAt; val second = repository.rate(command(repository.load().cards.single()))
            now = second.dueAt; val nextCard = repository.load().cards.single()
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE word_review_events SET step=1,dueAt=? WHERE id=?",
                arrayOf<Any>(calculateReviewDue(1, first.reviewedAt, ZoneId.of(first.zoneId)), first.id))
            fails(WordReviewFailure.SOURCE) { repository.load() }
            fails(WordReviewFailure.SOURCE) { repository.rate(command(nextCard)) }
            sql.execSQL("UPDATE word_review_events SET step=0,dueAt=? WHERE id=?", arrayOf<Any>(first.dueAt, first.id))
            sql.execSQL("UPDATE word_schedules SET version=?,step=?,reviewedAt=?,dueAt=?,lastEventId=?",
                arrayOf<Any>(first.version, first.step, first.reviewedAt, first.dueAt, first.id))
            fails(WordReviewFailure.SOURCE) { repository.load() }
            sql.execSQL("UPDATE word_schedules SET version=?,step=?,reviewedAt=?,dueAt=?,lastEventId=?",
                arrayOf<Any>(second.version, second.step, second.reviewedAt, second.dueAt, second.id))
            assertEquals(2, repository.load().historyCount)
        }
    }
    @Test fun receiptAndScheduleSurviveCloseAndReopenAndBookRemovalCascades() = runBlocking {
        val name = "srs-${UUID.randomUUID().toString().take(8)}.db"
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            var db = open(); seed(db)
            val c = command(repo(db).load().cards.single()); val receipt = repo(db).rate(c); db.close()
            db = open()
            try {
                assertEquals(receipt, repo(db).load().history.single().event); assertEquals(receipt, repo(db).rate(c))
                db.books().deleteExact(book.id, book.sha256)
                assertEquals(0, repo(db).load().historyCount)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
    private suspend fun fails(reason: WordReviewFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") } catch (failure: WordReviewException) { assertEquals(reason, failure.reason) }
    }
}

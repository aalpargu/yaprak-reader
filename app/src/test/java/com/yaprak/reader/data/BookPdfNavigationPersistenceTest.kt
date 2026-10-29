package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.settings.*
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookPdfNavigationPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val pdf = Book("pdf", "PDF", "Yazar", BookFormat.PDF, "source.pdf", "pdf.pdf", 100,
        "a".repeat(64), 1, 5)
    private val epub = pdf.copy(id = "epub", format = BookFormat.EPUB, sha256 = "b".repeat(64),
        originalName = "source.epub", storageName = "epub.epub", pageCount = null)
    private suspend fun fixture(callback: RoomDatabase.QueryCallback? = null, block: suspend (LibraryDatabase) -> Unit) {
        val builder = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
        if (callback != null) builder.setQueryCallback(callback, Executor { it.run() })
        val database = builder.build()
        try { database.books().insert(pdf.toEntity()); database.books().insert(epub.toEntity()); block(database) }
        finally { database.close() }
    }
    private fun navigationCount(db: LibraryDatabase) = db.openHelper.writableDatabase
        .compileStatement("SELECT COUNT(*) FROM book_pdf_navigation_preferences").simpleQueryForLong()
    private suspend fun failure(reason: BookPdfViewPreferencesFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (failure: BookPdfViewPreferencesException) { assertEquals(reason, failure.reason) }
    }
    private fun insert(db: LibraryDatabase, hash: Any = pdf.sha256, mode: Any = "VERTICAL_SCROLL") {
        db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO book_pdf_navigation_preferences (bookId, contentSha256, mode) VALUES (?, ?, ?)",
            arrayOf(pdf.id, hash, mode))
    }
    @Test fun verifiedAbsenceAndRepeatedPagedChoiceLeaveAllReadingAndPreferenceRecordsUntouched() = runBlocking {
        fixture { db ->
            val repository = RoomBookPdfViewPreferencesRepository(db)
            assertEquals(BookPdfViewPreferences(), repository.observe(pdf.id, pdf.sha256).first())
            repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.PAGED)
            assertEquals(0L, navigationCount(db)); assertTrue(db.bookPdfViewPreferences().all().isEmpty())
            assertEquals(pdf.toEntity(), db.books().findById(pdf.id))
            assertNull(db.reading().resume(pdf.id, pdf.sha256)); assertTrue(db.reading().observed(pdf.id, pdf.sha256).isEmpty())
            for ((id, hash) in listOf("missing" to pdf.sha256, epub.id to epub.sha256, pdf.id to "d".repeat(64))) {
                failure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.observe(id, hash).first() }
                failure(BookPdfViewPreferencesFailure.STALE_BOOK) { repository.saveNavigation(id, hash, PdfNavigationMode.VERTICAL_SCROLL) }
            }
            assertEquals(0L, navigationCount(db))
        }
    }
    @Test fun concurrentFieldSavesMergeAndModeWritesNeverRewriteCropFitOrSpread() = runBlocking {
        fixture { db ->
            val mutex = Mutex(); val first = RoomBookPdfViewPreferencesRepository(db, mutex)
            val second = RoomBookPdfViewPreferencesRepository(db, mutex)
            coroutineScope {
                listOf(async(Dispatchers.Default) { first.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL) },
                    async(Dispatchers.Default) { second.saveCrop(pdf.id, pdf.sha256, 12, 7) },
                    async(Dispatchers.Default) { second.saveFit(pdf.id, pdf.sha256, PdfFitPreference.WIDTH) },
                    async(Dispatchers.Default) { first.saveSpread(pdf.id, pdf.sha256, true) }).awaitAll()
            }
            val expected = BookPdfViewPreferences(12, 7, PdfFitPreference.WIDTH, true, PdfNavigationMode.VERTICAL_SCROLL)
            assertEquals(expected, first.observe(pdf.id, pdf.sha256).first())
            val before = db.bookPdfViewPreferences().all()
            first.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.PAGED)
            assertEquals(before, db.bookPdfViewPreferences().all())
            assertEquals(expected.copy(navigationMode = PdfNavigationMode.PAGED), second.observe(pdf.id, pdf.sha256).first())
            second.saveCrop(pdf.id, pdf.sha256, 0, 0)
            assertEquals(PdfNavigationMode.PAGED, first.observe(pdf.id, pdf.sha256).first().navigationMode)
            assertEquals(1L, navigationCount(db))
        }
    }
    @Test fun separateBooksKeepTheirModesAfterReopenAndBookDeletionCascadesOnlyItsOwnMode() = runBlocking {
        val name = "pnp-${UUID.randomUUID().toString().take(8)}.db"
        var db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        val other = pdf.copy(id = "other", sha256 = "c".repeat(64), storageName = "other.pdf")
        try {
            db.books().insert(pdf.toEntity()); db.books().insert(other.toEntity())
            val repository = RoomBookPdfViewPreferencesRepository(db)
            repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL)
            repository.saveNavigation(other.id, other.sha256, PdfNavigationMode.VERTICAL_SCROLL)
            repository.saveNavigation(other.id, other.sha256, PdfNavigationMode.PAGED)
            db.close(); db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            val reopened = RoomBookPdfViewPreferencesRepository(db)
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, reopened.observe(pdf.id, pdf.sha256).first().navigationMode)
            assertEquals(PdfNavigationMode.PAGED, reopened.observe(other.id, other.sha256).first().navigationMode)
            assertEquals(2L, navigationCount(db)); db.books().deleteExact(pdf.id, pdf.sha256)
            assertEquals(1L, navigationCount(db))
            assertEquals(PdfNavigationMode.PAGED, reopened.observe(other.id, other.sha256).first().navigationMode)
            failure(BookPdfViewPreferencesFailure.STALE_BOOK) { reopened.observe(pdf.id, pdf.sha256).first() }
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun malformedOrStaleModeCannotHideBehindAnAbsentOldPdfPreferencesRow() = runBlocking {
        fixture { db ->
            val repository = RoomBookPdfViewPreferencesRepository(db)
            for ((hash, mode, reason) in listOf(
                Triple(pdf.sha256, "SCROLL" as Any, BookPdfViewPreferencesFailure.SOURCE),
                Triple(pdf.sha256, "vertical_scroll" as Any, BookPdfViewPreferencesFailure.SOURCE),
                Triple(pdf.sha256, "" as Any, BookPdfViewPreferencesFailure.SOURCE),
                Triple(pdf.sha256, byteArrayOf(65) as Any, BookPdfViewPreferencesFailure.SOURCE),
                Triple("c".repeat(64), "VERTICAL_SCROLL" as Any, BookPdfViewPreferencesFailure.STALE_BOOK),
                Triple("bad hash", "PAGED" as Any, BookPdfViewPreferencesFailure.SOURCE))) {
                insert(db, hash, mode)
                failure(reason) { repository.observe(pdf.id, pdf.sha256).first() }
                failure(reason) { repository.saveCrop(pdf.id, pdf.sha256, 1, 2) }
                failure(reason) { repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.PAGED) }
                assertEquals(1L, navigationCount(db)); assertTrue(db.bookPdfViewPreferences().all().isEmpty())
            }
            insert(db, byteArrayOf(65), "PAGED")
            failure(BookPdfViewPreferencesFailure.SOURCE) { repository.observe(pdf.id, pdf.sha256).first() }
        }
    }
    @Test fun malformedExistingCropRowPreventsANavigationWriteWithoutSilentlyRepairingOtherFields() = runBlocking {
        fixture { db ->
            val repository = RoomBookPdfViewPreferencesRepository(db)
            repository.saveCrop(pdf.id, pdf.sha256, 4, 5)
            db.openHelper.writableDatabase.execSQL("UPDATE book_pdf_view_preferences SET doublePagePreferred = 2 WHERE bookId = ?", arrayOf(pdf.id))
            val before = db.bookPdfViewPreferences().all()
            failure(BookPdfViewPreferencesFailure.SOURCE) { repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL) }
            assertEquals(0L, navigationCount(db)); assertEquals(before, db.bookPdfViewPreferences().all())
        }
    }
    @Test fun actualModeTableInvalidationPublishesAnotherRepositoriesLatestChoice() = runBlocking {
        fixture { db -> coroutineScope {
            val repository = RoomBookPdfViewPreferencesRepository(db)
            val second = RoomBookPdfViewPreferencesRepository(db)
            val changes = Channel<BookPdfViewPreferences>(Channel.UNLIMITED)
            val observer = launch { repository.observe(pdf.id, pdf.sha256).collect { changes.send(it) } }
            try {
                assertEquals(PdfNavigationMode.PAGED, withTimeout(5000) { changes.receive() }.navigationMode)
                second.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL)
                assertEquals(PdfNavigationMode.VERTICAL_SCROLL, withTimeout(5000) { changes.receive() }.navigationMode)
                second.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.PAGED)
                assertEquals(PdfNavigationMode.PAGED, withTimeout(5000) { changes.receive() }.navigationMode)
            } finally { observer.cancelAndJoin(); changes.close() }
        } }
    }
    @Test fun queuedCancellationAndStartupGuardRefuseWithoutAnyModeCommit() = runBlocking {
        fixture { db ->
            val mutex = Mutex(locked = true); val calls = AtomicInteger(); val ready = AtomicBoolean(false)
            val repository = RoomBookPdfViewPreferencesRepository(db, mutex, validateAccess = { calls.incrementAndGet(); check(ready.get()) })
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL) }
            cancelled.cancelAndJoin(); assertTrue(cancelled.isCancelled); assertEquals(0, calls.get()); mutex.unlock()
            failure(BookPdfViewPreferencesFailure.SOURCE) { repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL) }
            assertEquals(0L, navigationCount(db)); ready.set(true)
            repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL)
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, repository.observe(pdf.id, pdf.sha256).first().navigationMode)
        }
    }
    @Test fun nativeModeInsertFailureRetainsTheWholeOldViewAndAnExplicitRetryCanCommit() = runBlocking {
        fixture { db ->
            val repository = RoomBookPdfViewPreferencesRepository(db); repository.saveCrop(pdf.id, pdf.sha256, 4, 5)
            val before = db.bookPdfViewPreferences().all()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_pdf_mode BEFORE INSERT ON book_pdf_navigation_preferences BEGIN SELECT RAISE(ABORT, 'controlled failure'); END")
            failure(BookPdfViewPreferencesFailure.SOURCE) { repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL) }
            assertEquals(before, db.bookPdfViewPreferences().all()); assertEquals(0L, navigationCount(db))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_pdf_mode")
            repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL)
            assertEquals(BookPdfViewPreferences(4, 5, navigationMode = PdfNavigationMode.VERTICAL_SCROLL), repository.observe(pdf.id, pdf.sha256).first())
        }
    }
    @Test fun originalCallerCancellationAtModeInsertRollsBackWithoutBecomingASourceFailure() = runBlocking {
        val running = AtomicReference<Job?>(); val cancel = AtomicBoolean(false)
        val callback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                if (cancel.get() && sqlQuery.trimStart().startsWith("INSERT") && sqlQuery.contains("book_pdf_navigation_preferences")) running.get()?.cancel()
            }
        }
        fixture(callback) { db ->
            val repository = RoomBookPdfViewPreferencesRepository(db); repository.saveCrop(pdf.id, pdf.sha256, 4, 5)
            val before = db.bookPdfViewPreferences().all()
            val writing = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL) }
            running.set(writing); cancel.set(true); writing.start(); withTimeout(5000) { writing.join() }
            assertTrue(writing.isCancelled); cancel.set(false)
            assertEquals(0L, navigationCount(db)); assertEquals(before, db.bookPdfViewPreferences().all())
            repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL)
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, repository.observe(pdf.id, pdf.sha256).first().navigationMode)
        }
    }
    @Test fun aClosedRoomSourceCannotBeReopenedForReadingOrSavingModes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        db.books().insert(pdf.toEntity()); val repository = RoomBookPdfViewPreferencesRepository(db)
        db.close()
        failure(BookPdfViewPreferencesFailure.SOURCE) { repository.observe(pdf.id, pdf.sha256).first() }
        failure(BookPdfViewPreferencesFailure.SOURCE) { repository.saveNavigation(pdf.id, pdf.sha256, PdfNavigationMode.VERTICAL_SCROLL) }
        assertFalse(db.isOpen)
    }
}

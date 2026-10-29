package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryLabelsPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book0", "Kitap", "Yazar", BookFormat.EPUB, "book.epub", "book.epub", 100, "a".repeat(64), 1)
    private val second = book.copy(id = "book1", sha256 = "b".repeat(64), storageName = "other.epub")
    private suspend fun seed(db: LibraryDatabase) {
        val library = RoomLibraryRepository(db.books()); library.add(book); library.add(second)
        library.updateOrganization(book.id, book.sha256, BookOrganization(BookReadingStatus.COMPLETED, true,
            "Seri", "Tür", listOf("İş", "Hedef", "Korunan"), listOf("İş", "Raf")))
        library.updateOrganization(second.id, second.sha256, BookOrganization(BookReadingStatus.READING, false,
            tags = listOf("ış"), collections = listOf("ış")))
        db.reading().save(ReadingProgressEntity(book.id, book.sha256, 1, "saved", "chapter", 10))
        db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, "Korunan not", 10))
    }
    private suspend fun <T> using(block: suspend (LibraryDatabase, RoomLibraryLabelsRepository) -> T): T {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        return try { seed(db); block(db, RoomLibraryLabelsRepository(db)) } finally { db.close() }
    }
    @Test fun renameMergeAndRemoveChangeOnlyOwnedFieldsAndPreserveCompletionNotesAndProgress() = runBlocking { using { db, repo ->
        val library = RoomLibraryRepository(db.books())
        var snapshot = repo.observe().first()
        assertEquals(2, snapshot.entries(LibraryLabelKind.TAG).single { it.name == "İş" }.bookCount)
        assertEquals(2, repo.change(LibraryLabelKind.TAG, "İş", "Hedef", snapshot.matching(LibraryLabelKind.TAG, "İş")))
        val actual = library.findById(book.id)!!.organization
        assertEquals(listOf("Hedef", "Korunan"), actual.tags)
        assertEquals(listOf("İş", "Raf"), actual.collections)
        assertEquals(BookReadingStatus.COMPLETED, actual.readingStatus); assertTrue(actual.favorite)
        assertEquals("Seri", actual.series); assertEquals("Tür", actual.genre)
        assertEquals(listOf("Hedef"), library.findById(second.id)!!.organization.tags)
        snapshot = repo.observe().first()
        assertEquals(2, repo.change(LibraryLabelKind.COLLECTION, "İş", null, snapshot.matching(LibraryLabelKind.COLLECTION, "İş")))
        assertEquals(listOf("Raf"), library.findById(book.id)!!.organization.collections)
        assertTrue(library.findById(second.id)!!.organization.collections.isEmpty())
        assertEquals(1, db.books().completionEventCount())
        assertEquals("saved", db.reading().resume(book.id, book.sha256)!!.locatorJson)
        assertEquals("Korunan not", db.openHelper.writableDatabase.compileStatement("SELECT text FROM book_notes").simpleQueryForString())
    } }
    @Test fun changedOrganizationRefusesWholeBatchWithoutLosingOtherMetadata() = runBlocking { using { db, repo ->
        val captured = repo.observe().first().matching(LibraryLabelKind.TAG, "İş")
        val library = RoomLibraryRepository(db.books())
        library.updateOrganization(second.id, second.sha256, library.findById(second.id)!!.organization.copy(favorite = true))
        val failure = runCatching { repo.change(LibraryLabelKind.TAG, "İş", "Yeni", captured) }.exceptionOrNull()
        assertEquals(LibraryLabelFailure.CONFLICT, (failure as LibraryLabelException).reason)
        assertEquals(listOf("İş", "Hedef", "Korunan"), library.findById(book.id)!!.organization.tags)
        assertTrue(library.findById(second.id)!!.organization.favorite)
        assertEquals(1, db.books().completionEventCount())
    } }
    @Test fun addedMatchingBookAndDeletedOrReplacedIdentitiesCannotProducePartialSuccess() = runBlocking { using { db, repo ->
        val captured = repo.observe().first().matching(LibraryLabelKind.TAG, "İş")
        val library = RoomLibraryRepository(db.books())
        val third = book.copy(id = "book2", sha256 = "c".repeat(64))
        library.addImported(third, BookOrganization(tags = listOf("iş")))
        fun reason(failure: Throwable?) = (failure as LibraryLabelException).reason
        assertEquals(LibraryLabelFailure.CONFLICT, reason(runCatching { repo.change(LibraryLabelKind.TAG, "İş", null, captured) }.exceptionOrNull()))
        db.books().deleteExact(third.id, third.sha256)
        db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ? WHERE id = ?", arrayOf("d".repeat(64), second.id))
        assertEquals(LibraryLabelFailure.CONFLICT, reason(runCatching { repo.change(LibraryLabelKind.TAG, "İş", null, captured) }.exceptionOrNull()))
        assertEquals(listOf("İş", "Hedef", "Korunan"), library.findById(book.id)!!.organization.tags)
    } }
    @Test fun failureOnSecondBookRollsBackFirstBookAndCanRetry() = runBlocking { using { db, repo ->
        val captured = repo.observe().first().matching(LibraryLabelKind.TAG, "İş")
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER refuse_label BEFORE UPDATE OF tagsJson ON book_organizations WHEN NEW.bookId = 'book1' BEGIN SELECT RAISE(ABORT, 'controlled'); END")
        assertNotNull(runCatching { repo.change(LibraryLabelKind.TAG, "İş", "Yeni", captured) }.exceptionOrNull())
        assertEquals(captured, repo.observe().first().matching(LibraryLabelKind.TAG, "İş"))
        sql.execSQL("DROP TRIGGER refuse_label")
        assertEquals(2, repo.change(LibraryLabelKind.TAG, "İş", "Yeni", captured))
        assertEquals(1, db.books().completionEventCount())
    } }
    @Test fun concurrentCommandsHaveOneWinnerAndActualObserverSeesCommittedLabels() = runBlocking { using { _, repo ->
        val updates = Channel<LibraryLabelSnapshot>(Channel.UNLIMITED)
        val collector = launch { repo.observe().collect { updates.send(it) } }
        try {
            val initial = withTimeout(5_000) { updates.receive() }
            val captured = initial.matching(LibraryLabelKind.TAG, "İş")
            val results = coroutineScope { listOf("Yeni A", "Yeni B").map { name -> async(Dispatchers.IO) {
                runCatching { repo.change(LibraryLabelKind.TAG, "İş", name, captured) }
            } }.awaitAll() }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(LibraryLabelFailure.CONFLICT, (results.single { it.isFailure }.exceptionOrNull() as LibraryLabelException).reason)
            withTimeout(5_000) { do { val snapshot = updates.receive()
                if (snapshot.entries(LibraryLabelKind.TAG).any { it.name.startsWith("Yeni ") }) break
            } while (true) }
        } finally { collector.cancelAndJoin() }
    } }
    @Test fun malformedRawFieldsFailWithoutRepairAndStaleRowsAreExcluded() = runBlocking { using { db, repo ->
        val sql = db.openHelper.writableDatabase
        for (mutation in listOf("favorite = 1.5", "favorite = 'bad'", "readingStatus = 'UNKNOWN'", "tagsJson = '[1]'", "collectionsJson = 'bad'")) {
            sql.execSQL("UPDATE book_organizations SET " + mutation + " WHERE bookId = 'book0'")
            assertNotNull(mutation, runCatching { repo.observe().first() }.exceptionOrNull())
            sql.execSQL("UPDATE book_organizations SET favorite = 1, readingStatus = 'COMPLETED', tagsJson = '[\"İş\",\"Hedef\",\"Korunan\"]', collectionsJson = '[\"İş\",\"Raf\"]' WHERE bookId = 'book0'")
        }
        sql.execSQL("UPDATE book_organizations SET contentSha256 = ? WHERE bookId = ?", arrayOf("f".repeat(64), second.id))
        assertEquals(1, repo.observe().first().members.size)
    } }
    @Test fun realDatabaseReopenRetainsMergedLabelsAndOtherRecords() = runBlocking {
        val name = "lbl-" + java.util.UUID.randomUUID().toString().take(8) + ".db"
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            val first = open()
            try {
                seed(first)
                val repo = RoomLibraryLabelsRepository(first)
                repo.change(LibraryLabelKind.TAG, "İş", "Hedef", repo.observe().first().matching(LibraryLabelKind.TAG, "İş"))
            } finally { first.close() }
            val reopened = open()
            try {
                assertEquals(listOf("Hedef", "Korunan"), RoomLibraryRepository(reopened.books()).findById(book.id)!!.organization.tags)
                assertEquals(2, RoomLibraryLabelsRepository(reopened).observe().first().entries(LibraryLabelKind.TAG).single { it.name == "Hedef" }.bookCount)
                assertEquals(1, reopened.books().completionEventCount())
                assertEquals("saved", reopened.reading().resume(book.id, book.sha256)?.locatorJson)
                assertEquals("Korunan not", reopened.openHelper.writableDatabase.compileStatement("SELECT text FROM book_notes").simpleQueryForString())
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun cancellationRecoveryAndClosedDatabaseNeverPretendTheBatchSucceeded() = runBlocking { using { db, repo ->
        val captured = repo.observe().first().matching(LibraryLabelKind.TAG, "İş")
        val lock = Mutex(locked = true)
        val blocked = RoomLibraryLabelsRepository(db, fileMutex = lock)
        val pending = async { blocked.change(LibraryLabelKind.TAG, "İş", null, captured) }
        yield(); pending.cancelAndJoin(); lock.unlock()
        assertTrue(pending.isCancelled); assertEquals(captured, repo.observe().first().matching(LibraryLabelKind.TAG, "İş"))
        val recovery = RoomLibraryLabelsRepository(db, validateAccess = { throw LibraryLabelException(LibraryLabelFailure.RECOVERY) })
        assertEquals(LibraryLabelFailure.RECOVERY, (runCatching { recovery.change(LibraryLabelKind.TAG, "İş", null, captured) }
            .exceptionOrNull() as LibraryLabelException).reason)
        db.close(); assertTrue(runCatching { repo.observe().first() }.isFailure); assertFalse(db.isOpen)
    } }
}

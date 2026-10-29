package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.passages.EpubHighlightColor
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RoomStudyRecordsSourceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = BookEntity("book", "İstanbul 🌿", "Çağrı Şen", "EPUB", "original.epub", "book.epub",
        100, "a".repeat(64), 1, null)
    private fun passage(id: String, text: String = "  İğne 🌿  ") = EpubPassageEntity(id, book.id, book.sha256,
        1, "OPS/$id.xhtml", "b".repeat(64), 2, text.length + 2, text, 9)

    @Test fun `actual records preserve scope order colors exact source text and detached counts`() = runBlocking {
        database { db ->
            db.books().insert(book.copy(id = "other", sha256 = "c".repeat(64)))
            db.bookNotes().add(BookNoteEntity("note-a", book.id, book.sha256, "Old", 7))
            db.bookNotes().add(BookNoteEntity("note-b", book.id, book.sha256, "  Not\n🌿  ", 7))
            db.bookNotes().add(BookNoteEntity("note-stale", book.id, "old-hash", "STALE", 99))
            db.bookNotes().add(BookNoteEntity("note-other", "other", "c".repeat(64), "OTHER", 99))
            db.savedWords().add(SavedWordEntity("word-a", book.id, book.sha256, "leaf", "yaprak", 8))
            db.savedWords().add(SavedWordEntity("word-b", book.id, book.sha256, "snow\t🌿", "kar 漢字", 8))
            db.savedWords().add(SavedWordEntity("word-stale", book.id, "old-hash", "STALE", "eski", 99))
            db.epubPassages().add(passage("plain"))
            db.epubPassages().add(passage("color").copy(highlightColor = "BLUE", revision = 1))
            db.epubPassages().add(passage("note").copy(attachedNote = "  Bağlı\n漢字  ", revision = 7))
            db.epubPassages().add(passage("both").copy(highlightColor = "GREEN", attachedNote = "Diğer bağlı not", revision = 9))
            db.epubPassages().add(passage("stale").copy(contentSha256 = "old-hash", anchorVersion = 99))
            val before = counts(db)
            val snapshot = RoomBookStudySource(db).snapshotRecords(book.id, book.sha256)
            assertEquals(StudyExportBook(book.title, book.authors, BookFormat.EPUB), snapshot.book)
            assertEquals(listOf("  Not\n🌿  ", "Old"), snapshot.notes.map { it.text })
            assertEquals(listOf("snow\t🌿", "leaf"), snapshot.words.map { it.english })
            assertEquals(listOf("OPS/plain.xhtml", "OPS/note.xhtml", "OPS/color.xhtml", "OPS/both.xhtml"), snapshot.passages.map { it.anchor.resourceHref })
            val linked = snapshot.passages.single { it.anchor.resourceHref == "OPS/note.xhtml" }
            assertEquals("  Bağlı\n漢字  ", linked.attachedNote)
            assertEquals("  İğne 🌿  ", linked.text)
            assertEquals(7L, linked.revision)
            assertNull(linked.highlightColor)
            assertEquals(EpubHighlightColor.BLUE, snapshot.passages.single { it.anchor.resourceHref == "OPS/color.xhtml" }.highlightColor)
            assertEquals(4, snapshot.noteCount); assertEquals(2, snapshot.wordCount)
            assertEquals(4, snapshot.passageCount); assertEquals(8, snapshot.recordCount)
            assertEquals(before, counts(db))
            validateStudyRecordsSnapshot(snapshot)
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE books SET title = 'New title' WHERE id = 'book'")
                db.openHelper.writableDatabase.execSQL("UPDATE book_notes SET text = 'Edited' WHERE id = 'note-b'")
                db.openHelper.writableDatabase.execSQL("UPDATE epub_passages SET attachedNote = 'Edited', revision = revision + 1 WHERE id = 'note'")
            }
            assertEquals(book.title, snapshot.book.title)
            assertEquals("  Not\n🌿  ", snapshot.notes.first().text)
            assertEquals("  Bağlı\n漢字  ", linked.attachedNote)
            assertEquals(7L, linked.revision)
        }
    }

    @Test fun `persisted typed records survive database close and real reopen`() = runBlocking {
        val name = "ps-${UUID.randomUUID().toString().take(8)}.db"
        try {
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                db.books().insert(book)
                db.epubPassages().add(passage("persisted").copy(highlightColor = "PINK", attachedNote = "Exact\n🌿", revision = 11))
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                val snapshot = RoomBookStudySource(db).snapshotRecords(book.id, book.sha256)
                assertEquals(1, snapshot.recordCount)
                val saved = snapshot.passages.single()
                assertEquals("Exact\n🌿", saved.attachedNote)
                assertEquals(EpubHighlightColor.PINK, saved.highlightColor)
                assertEquals(11L, saved.revision)
                assertEquals("OPS/persisted.xhtml", saved.anchor.resourceHref)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `missing changed and empty books retain typed failure distinctions`() = runBlocking {
        database { db ->
            val source = RoomBookStudySource(db)
            failure(StudyExportFailure.MISSING_BOOK) { source.snapshotRecords("missing", book.sha256) }
            failure(StudyExportFailure.CHANGED_BOOK) { source.snapshotRecords(book.id, "changed") }
            failure(StudyExportFailure.NO_RECORDS) { source.snapshotRecords(book.id, book.sha256) }
            failure(StudyExportFailure.UNSUPPORTED_FORMAT) { source.snapshot(book.id, book.sha256, StudyExportFormat.PDF) }
            assertEquals(listOf(1L, 0L, 0L, 0L), counts(db))
        }
    }

    @Test fun `actual PDF notes retain true page indices and reject out of range pages`() = runBlocking {
        database { db ->
            db.openHelper.writableDatabase.execSQL("UPDATE books SET format = 'PDF', pageCount = 8 WHERE id = 'book'")
            db.bookNotes().add(BookNoteEntity("general", book.id, book.sha256, "General", 1))
            db.bookNotes().add(BookNoteEntity("page", book.id, book.sha256, "Last page", 2, 7))
            val source = RoomBookStudySource(db)
            val snapshot = source.snapshotRecords(book.id, book.sha256)
            assertEquals(BookFormat.PDF, snapshot.book.format)
            assertEquals(8, snapshot.book.pageCount)
            assertEquals(listOf(StudyNoteRecord("Last page", 7), StudyNoteRecord("General")), snapshot.notes)
            db.openHelper.writableDatabase.execSQL("UPDATE book_notes SET pdfPageIndex = 8 WHERE id = 'page'")
            failure(StudyExportFailure.IO) { source.snapshotRecords(book.id, book.sha256) }
        }
    }

    @Test fun `typed PDF snapshot checks unknown current anchors without changing legacy format filtering`() = runBlocking {
        database { db ->
            db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, "Ordinary", 1))
            db.epubPassages().add(passage("unknown").copy(anchorVersion = 2))
            val source = RoomBookStudySource(db)
            failure(StudyExportFailure.IO) { source.snapshotRecords(book.id, book.sha256) }
            assertEquals(0, source.snapshot(book.id, book.sha256).passageCount)
            assertEquals(0, source.snapshot(book.id, book.sha256, StudyExportFormat.MARKDOWN).passageCount)
        }
    }

    @Test fun `typed PDF snapshot rejects passage rows attached to PDF books`() = runBlocking {
        database { db ->
            db.epubPassages().add(passage("wrong-format"))
            db.openHelper.writableDatabase.execSQL("UPDATE books SET format = 'PDF', pageCount = 2 WHERE id = 'book'")
            failure(StudyExportFailure.IO) { RoomBookStudySource(db).snapshotRecords(book.id, book.sha256) }
        }
    }

    @Test fun `typed authoritative book hash must be lower hex while previous formats retain matching semantics`() = runBlocking {
        for (hash in listOf("A".repeat(64), "g".repeat(64), "legacy-hash")) database { db ->
            db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ? WHERE id = 'book'", arrayOf(hash))
            db.bookNotes().add(BookNoteEntity("note", book.id, hash, "Exact legacy note", 1))
            db.savedWords().add(SavedWordEntity("word", book.id, hash, "leaf", "yaprak", 1))
            val source = RoomBookStudySource(db)
            failure(StudyExportFailure.IO) { source.snapshotRecords(book.id, hash) }
            for (format in listOf(StudyExportFormat.TEXT, StudyExportFormat.MARKDOWN, StudyExportFormat.ANKI_TSV))
                assertEquals(1, source.snapshot(book.id, hash, format).wordCount)
            assertEquals(hash, db.books().findById(book.id)!!.sha256)
            assertEquals(listOf(1L, 1L, 1L, 0L), counts(db))
        }
    }

    @Test fun `strict SQL types and invalid current source fields fail before detached partial results`() = runBlocking {
        val edits = listOf(
            "UPDATE books SET title = CAST('Title' AS BLOB)",
            "UPDATE books SET pageCount = 0.5",
            "UPDATE book_notes SET text = CAST('Note' AS BLOB)",
            "UPDATE book_notes SET pdfPageIndex = 0.5",
            "UPDATE saved_words SET english = CAST('leaf' AS BLOB)",
            "UPDATE saved_words SET turkish = char(10) || 'line'",
            "UPDATE epub_passages SET endOffset = 0.5",
            "UPDATE epub_passages SET attachedNote = CAST('note' AS BLOB)",
            "UPDATE epub_passages SET attachedNote = ''",
            "UPDATE epub_passages SET highlightColor = 'UNKNOWN'",
            "UPDATE epub_passages SET revision = 0.5",
            "UPDATE epub_passages SET revision = -1",
            "UPDATE epub_passages SET nodeTextSha256 = 'bad'",
            "UPDATE epub_passages SET resourceHref = '../outside.xhtml'",
        )
        for (edit in edits) database { db ->
            db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, "Valid", 1))
            db.savedWords().add(SavedWordEntity("word", book.id, book.sha256, "leaf", "yaprak", 1))
            db.epubPassages().add(passage("passage"))
            db.openHelper.writableDatabase.execSQL(edit)
            failure(StudyExportFailure.IO) { RoomBookStudySource(db).snapshotRecords(book.id, book.sha256) }
            assertEquals(listOf(1L, 1L, 1L, 1L), counts(db))
        }
    }

    @Test fun `PDF record limit is exact and does not reduce legacy text capacity or write source rows`() = runBlocking {
        database { db ->
            db.withTransaction {
                repeat(999) { db.bookNotes().add(BookNoteEntity("note-$it", book.id, book.sha256, "Note $it", it.toLong())) }
                db.savedWords().add(SavedWordEntity("word", book.id, book.sha256, "leaf", "yaprak", 1))
            }
            val source = RoomBookStudySource(db)
            assertEquals(1000, source.snapshotRecords(book.id, book.sha256).recordCount)
            db.savedWords().add(SavedWordEntity("extra", book.id, book.sha256, "snow", "kar", 2))
            val before = counts(db)
            failure(StudyExportFailure.TOO_LARGE) { source.snapshotRecords(book.id, book.sha256) }
            assertEquals(999, source.snapshot(book.id, book.sha256).noteCount)
            assertEquals(2, source.snapshot(book.id, book.sha256).wordCount)
            assertEquals(before, counts(db))
        }
    }

    @Test fun `PDF source bytes are measured in UTF8 while legacy text retains its eight MiB budget`() = runBlocking {
        database { db ->
            db.withTransaction {
                repeat(175) { db.bookNotes().add(BookNoteEntity("note-$it", book.id, book.sha256, "雪".repeat(2000), it.toLong())) }
            }
            val source = RoomBookStudySource(db)
            failure(StudyExportFailure.TOO_LARGE) { source.snapshotRecords(book.id, book.sha256) }
            assertEquals(175, source.snapshot(book.id, book.sha256).noteCount)
            assertEquals(listOf(1L, 175L, 0L, 0L), counts(db))
        }
    }

    @Test fun `closed database cannot reopen through typed or legacy snapshots`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        db.books().insert(book)
        db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, "Note", 1))
        val source = RoomBookStudySource(db)
        assertEquals(1, source.snapshotRecords(book.id, book.sha256).noteCount)
        db.close()
        failure(StudyExportFailure.IO) { source.snapshotRecords(book.id, book.sha256) }
        for (format in listOf(StudyExportFormat.TEXT, StudyExportFormat.MARKDOWN, StudyExportFormat.ANKI_TSV))
            failure(StudyExportFailure.IO) { source.snapshot(book.id, book.sha256, format) }
        assertFalse(db.isOpen)
    }

    @Test fun `source guard failure and explicit cancellation do not become an empty or successful export`() = runBlocking {
        database { db ->
            db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, "Note", 1))
            val guarded = RoomBookStudySource(db, validateRead = { error("Recovery pending") })
            failure(StudyExportFailure.IO) { guarded.snapshotRecords(book.id, book.sha256) }
            val cancelled = RoomBookStudySource(db, validateRead = { throw CancellationException("Guard cancelled") })
            assertTrue(runCatching { cancelled.snapshotRecords(book.id, book.sha256) }.exceptionOrNull() is CancellationException)
            lateinit var job: Job
            var delivered: StudyRecordsSnapshot? = null
            val callerCancelled = RoomBookStudySource(db, validateRead = { job.cancel() })
            job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { delivered = callerCancelled.snapshotRecords(book.id, book.sha256) }
            job.start(); job.join()
            assertTrue(job.isCancelled); assertNull(delivered)
            assertEquals(listOf(1L, 1L, 0L, 0L), counts(db))
        }
    }

    @Test @Config(sdk = [28]) fun `typed and legacy snapshots work with older framework SQLite leases`() = runBlocking {
        database { db ->
            db.epubPassages().add(passage("older").copy(attachedNote = "Source note", revision = 3))
            val source = RoomBookStudySource(db)
            val typed = source.snapshotRecords(book.id, book.sha256)
            assertEquals(1, typed.noteCount); assertEquals(1, typed.passageCount)
            assertEquals("Source note", typed.passages.single().attachedNote)
            assertEquals(1, source.snapshot(book.id, book.sha256).noteCount)
        }
    }

    @Test fun `shared cursor scanner honors original caller cancellation while Room context stays active`() = runBlocking {
        val caller = Job()
        val cursor = TrackingCursor(caller, cancelAt = 3)
        repeat(10) { cursor.addRow(arrayOf("row-$it")) }
        var retained = 0
        val failure = runCatching { scanStudyExportCursor(cursor, caller) { retained++ } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(2, retained); assertEquals(3, cursor.advances)
        assertTrue(cursor.isClosed); assertEquals(1, cursor.closes)
    }

    @Test fun `shared cursor scanner closes on source failure without accepting later rows`() = runBlocking {
        val cursor = TrackingCursor(Job(), cancelAt = -1)
        repeat(10) { cursor.addRow(arrayOf("row-$it")) }
        val failure = runCatching { scanStudyExportCursor(cursor, currentCoroutineContext()) {
            if (it.position == 2) throw StudyExportException(StudyExportFailure.IO)
        } }.exceptionOrNull()
        assertTrue(failure is StudyExportException)
        assertEquals(3, cursor.advances); assertEquals(1, cursor.closes)
        assertTrue(cursor.isClosed)
    }

    private class TrackingCursor(private val caller: Job, private val cancelAt: Int) : MatrixCursor(arrayOf("text")) {
        var advances = 0
        var closes = 0
        override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
            advances++
            if (advances == cancelAt) caller.cancel()
            return super.onMove(oldPosition, newPosition)
        }
        override fun close() { closes++; super.close() }
    }

    private suspend fun database(work: suspend (LibraryDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try { db.books().insert(book); work(db) } finally { db.close() }
    }

    private fun counts(db: LibraryDatabase): List<Long> = listOf("books", "book_notes", "saved_words", "epub_passages").map {
        db.openHelper.writableDatabase.compileStatement("SELECT count(*) FROM $it").simpleQueryForLong()
    }

    private suspend fun failure(reason: StudyExportFailure, work: suspend () -> Unit) {
        val problem = runCatching { work() }.exceptionOrNull()
        assertTrue("Expected $reason, got $problem", problem is StudyExportException)
        assertEquals(reason, (problem as StudyExportException).reason)
    }
}

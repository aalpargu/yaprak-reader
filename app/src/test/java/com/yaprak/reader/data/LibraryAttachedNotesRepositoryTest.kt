package com.yaprak.reader.data

import android.content.Context
import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.notes.LibraryNoteSource
import com.yaprak.reader.domain.notes.LibraryNotesResult
import com.yaprak.reader.domain.notes.LibraryNotesSourceException
import com.yaprak.reader.domain.notes.MAX_LIBRARY_NOTE_RESULTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryAttachedNotesRepositoryTest {
    private val book = BookEntity("book", "İstanbul", "Çağrı Şen", "EPUB", "book.epub", "book.epub", 1, "a".repeat(64), 1, null)
    private fun passage(id: String, note: String?, time: Long = 2) = EpubPassageEntity(
        id, book.id, book.sha256, 1, "OPS/$id.xhtml", "b".repeat(64), 1, 13,
        "Source quote", time, "PINK", note, 7,
    )
    private suspend fun database(block: suspend (LibraryDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        try { db.books().insert(book); block(db) } finally { db.close() }
    }

    @Test fun `colliding ids retain typed identities exact attached text and current source revision`() = runBlocking {
        database { db ->
            db.bookNotes().add(BookNoteEntity("same", book.id, book.sha256, "Ordinary note", 1))
            db.epubPassages().add(passage("same", "  IŞIK 🌿\nİkinci satır  "))
            db.epubPassages().add(passage("color-only", null))
            val repo = RoomLibraryNotesRepository(db)
            val rows = repo.observe("").first()
            assertEquals(2, rows.totalCount)
            assertEquals(2, rows.entries.map { it.identity }.distinct().size)
            val attached = rows.entries.single { it.source == LibraryNoteSource.EPUB_PASSAGE }
            assertEquals("  IŞIK 🌿\nİkinci satır  ", attached.text)
            assertEquals("Source quote", attached.sourceText)
            assertEquals(7L, attached.sourceRevision)
            assertEquals("OPS/same.xhtml", attached.sourceAnchor!!.resourceHref)
            assertNull(attached.pdfPageIndex)
            assertEquals(attached, repo.find(book.id, book.sha256, "same", LibraryNoteSource.EPUB_PASSAGE))
            assertEquals("Ordinary note", repo.find(book.id, book.sha256, "same")!!.text)
            assertEquals(listOf(attached), repo.observe("ışık istanbul çağrı").first().entries)
            assertEquals(listOf(attached), repo.observe("Source quote").first().entries)
            assertEquals(listOf(attached), repo.observe("quote ışık çağrı").first().entries)
        }
    }

    @Test fun `combined source keeps full count bounded newest rows and old attached search matches`() = runBlocking {
        database { db ->
            db.withTransaction {
                repeat(1100) { db.bookNotes().add(BookNoteEntity("note-$it", book.id, book.sha256, "Ortak", it.toLong())) }
                repeat(250) { db.epubPassages().add(passage("passage-$it", if (it == 3) "Eşsiz eski bağlı not" else "Ortak bağlı", 1200L + it)) }
            }
            val repo = RoomLibraryNotesRepository(db)
            val result = repo.observe("").first()
            assertEquals(1350, result.totalCount)
            assertEquals(MAX_LIBRARY_NOTE_RESULTS, result.entries.size)
            assertTrue(result.entries.all { it.source == LibraryNoteSource.EPUB_PASSAGE })
            assertEquals("passage-249", result.entries.first().id)
            assertEquals("passage-50", result.entries.last().id)
            assertEquals("passage-3", repo.observe("eşsiz eski").first().entries.single().id)
        }
    }

    @Test fun `live invalidation follows attached edit clear hash replacement and book removal`() = runBlocking {
        database { db ->
            val repo = RoomLibraryNotesRepository(db)
            val updates = Channel<LibraryNotesResult>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) { repo.observe("").collect { updates.send(it) } }
            suspend fun next(predicate: (LibraryNotesResult) -> Boolean): LibraryNotesResult = withTimeout(10_000) {
                var row = updates.receive()
                while (!predicate(row)) row = updates.receive()
                row
            }
            try {
                next { it.totalCount == 0 }
                db.epubPassages().add(passage("live", "İlk bağlı not"))
                val original = next { it.totalCount == 1 }.entries.single()
                db.withTransaction { db.openHelper.writableDatabase.execSQL(
                    "UPDATE epub_passages SET attachedNote = ?, revision = revision + 1 WHERE id = ?", arrayOf("Yeni 🌿", "live")) }
                val updated = next { it.entries.singleOrNull()?.text == "Yeni 🌿" }.entries.single()
                assertEquals(original.copy(text = "Yeni 🌿", sourceRevision = 8), updated)
                assertEquals(updated, repo.find(book.id, book.sha256, "live", LibraryNoteSource.EPUB_PASSAGE))
                db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE epub_passages SET attachedNote = NULL WHERE id = 'live'") }
                next { it.totalCount == 0 }
                db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE epub_passages SET attachedNote = 'Geri gelen not' WHERE id = 'live'") }
                next { it.totalCount == 1 }
                db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ? WHERE id = ?", arrayOf("c".repeat(64), book.id)) }
                next { it.totalCount == 0 }
                assertNull(repo.find(book.id, book.sha256, "live", LibraryNoteSource.EPUB_PASSAGE))
                assertEquals(1, db.books().deleteExact(book.id, "c".repeat(64)))
                assertEquals(0, repo.observe("").first().totalCount)
                assertEquals(0L, db.openHelper.writableDatabase.compileStatement("SELECT count(*) FROM epub_passages").simpleQueryForLong())
            } finally { job.cancelAndJoin(); updates.close() }
        }
    }

    @Test fun `malformed attached fields fail whole source before returning partial notes`() = runBlocking {
        for ((column, expression) in listOf(
            "highlightColor" to "'BROWN'", "attachedNote" to "CAST('bad' AS BLOB)",
            "attachedNote" to "''", "revision" to "0.5", "revision" to "-1",
            "resourceHref" to "'https://example.invalid/remote'", "anchorVersion" to "2",
            "nodeTextSha256" to "'invalid'", "endOffset" to "4097",
        )) database { db ->
            db.bookNotes().add(BookNoteEntity("ordinary", book.id, book.sha256, "Valid", 1))
            db.epubPassages().add(passage("bad", "Valid attached note"))
            db.openHelper.writableDatabase.execSQL("UPDATE epub_passages SET $column = $expression WHERE id = 'bad'")
            assertTrue(column, runCatching { repo(db).observe("").first() }.exceptionOrNull() is LibraryNotesSourceException)
            assertTrue(column, runCatching { repo(db).find(book.id, book.sha256, "bad", LibraryNoteSource.EPUB_PASSAGE) }.exceptionOrNull() is LibraryNotesSourceException)
        }
    }

    @Test fun `note-only passage remains searchable with absent highlight color`() = runBlocking {
        database { db ->
            db.epubPassages().add(passage("note-only", "Kaynaklı not").copy(highlightColor = null))
            val row = repo(db).observe("").first().entries.single()
            assertEquals(LibraryNoteSource.EPUB_PASSAGE, row.source)
            assertNull(row.highlightColor)
            assertEquals("Kaynaklı not", row.text)
        }
    }

    private fun repo(db: LibraryDatabase) = RoomLibraryNotesRepository(db)
}

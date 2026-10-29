package com.yaprak.reader.data

import android.app.Application
import android.database.MatrixCursor
import com.yaprak.reader.data.persistence.readLibraryNotesCursor
import com.yaprak.reader.domain.notes.LibraryNotesSourceException
import com.yaprak.reader.domain.notes.limitLibraryNoteQuery
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryNotesCursorTest {
    @Test fun `row scan cancellation closes cursor and stops before another row`() = runBlocking {
        val cursor = TrackedCursor()
        repeat(20) { cursor.addRow(row(it)) }
        val job = launch(start = CoroutineStart.LAZY) { readLibraryNotesCursor(cursor, "") }
        cursor.cancelJob = job
        cursor.cancelOnAdvance = 3
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(3, cursor.advances)
        assertEquals(1, cursor.closes)
        assertTrue(cursor.isClosed)
    }

    @Test fun `invalid row closes cursor with a typed failure and no successful partial result`() = runBlocking {
        val cursor = TrackedCursor()
        cursor.addRow(row(0))
        cursor.addRow(row(1).also { it[8] = 0 }) // EPUB note must not claim a PDF page.
        cursor.addRow(row(2))
        val failure = runCatching { readLibraryNotesCursor(cursor, "") }.exceptionOrNull()
        assertTrue(failure is LibraryNotesSourceException)
        assertEquals(2, cursor.advances)
        assertEquals(1, cursor.closes)
    }

    @Test fun `captured caller cancellation stops scan while Room context stays active`() = runBlocking {
        val cursor = TrackedCursor()
        repeat(20) { cursor.addRow(row(it)) }
        val caller = Job()
        cursor.cancelJob = caller
        cursor.cancelOnAdvance = 3
        val failure = runCatching { readLibraryNotesCursor(cursor, "", caller) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue("The independent read context must remain active", currentCoroutineContext().isActive)
        assertTrue(caller.isCancelled)
        assertEquals(3, cursor.advances)
        assertEquals(1, cursor.closes)
    }

    @Test fun `successful scan closes cursor and keeps exact saved Unicode text`() = runBlocking {
        val cursor = TrackedCursor()
        cursor.addRow(row(0).also { it[6] = "  Çok satır\n🌿  " })
        val result = readLibraryNotesCursor(cursor, "çok 🌿")
        assertEquals(1, result.totalCount)
        assertEquals("  Çok satır\n🌿  ", result.entries.single().text)
        assertEquals(1, cursor.closes)
    }

    @Test fun `public query bound retains whole supplementary characters`() {
        assertEquals("x".repeat(200), limitLibraryNoteQuery("x".repeat(201)))
        assertEquals("x".repeat(198) + "😀", limitLibraryNoteQuery("x".repeat(198) + "😀y"))
        assertEquals("x".repeat(199), limitLibraryNoteQuery("x".repeat(199) + "😀y"))
        val original = "i\u0307🌿\nçok satır"
        assertEquals(original, limitLibraryNoteQuery(original))
    }

    @Test fun `corrupt oversized identities and malformed content hashes fail before retaining a note`() = runBlocking {
        for ((column, corrupt) in listOf(0 to "x".repeat(1001), 1 to "x".repeat(1001),
            2 to "x".repeat(65), 2 to "A".repeat(64), 2 to "invalid-hash")) {
            val cursor = TrackedCursor()
            cursor.addRow(row(0).also { it[column] = corrupt })
            assertTrue(runCatching { readLibraryNotesCursor(cursor, "") }.exceptionOrNull() is LibraryNotesSourceException)
            assertEquals(1, cursor.closes)
        }
    }

    private fun row(index: Int): Array<Any?> = arrayOf("note-$index", "book", "a".repeat(64), "Başlık", null,
        "EPUB", "Metin $index", index.toLong(), null, null,
        "BOOK_NOTE", null, null, null, null, null, 0L, null, null, null, null, null)

    private class TrackedCursor : MatrixCursor(arrayOf("id", "bookId", "contentSha256", "title", "authors",
        "format", "text", "createdAt", "pdfPageIndex", "pageCount", "source", "sourceText",
        "resourceHref", "nodeTextSha256", "startOffset", "endOffset", "revision", "highlightColor", "anchorVersion",
        "anchorJson", "anchorKey", "selectionKey")) {
        var advances = 0
        var closes = 0
        var cancelJob: Job? = null
        var cancelOnAdvance: Int? = null
        override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
            advances++
            if (advances == cancelOnAdvance) cancelJob!!.cancel()
            return super.onMove(oldPosition, newPosition)
        }
        override fun close() {
            closes++
            super.close()
        }
    }
}

package com.yaprak.reader.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.formats.pdf.PdfPosition
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadingSnapshotIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val hash = "a".repeat(64)
    private fun book(id: String, format: String, contentHash: String = hash) = BookEntity(id, "Book", null, format, "book", "book-$id", 1, contentHash, 1, if (format == "PDF") 20 else null)
    private fun span(href: String) = ObservedTextSpan(href, "0/1", 0, 4, "b".repeat(64))

    @Test fun pdfTxtAndCbzCannotStoreOriginalEpubCoverageAndRefusalPreservesTheirPreviousPosition() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repository = RoomReadingStateRepository(db)
            for ((index, format) in listOf("PDF", "TXT", "CBZ").withIndex()) {
                val sourceHash = ('a'.code + index).toChar().toString().repeat(64)
                db.books().insert(book(format, format, sourceHash))
                val original = ReadingLocation("old-$format", format.lowercase())
                repository.record(format, sourceHash, ReadingSnapshot(original))
                val forged = ReadingLocation("new-$format", "one.xhtml")
                assertTrue(runCatching { repository.record(format, sourceHash, ReadingSnapshot(forged, listOf(span(forged.resourceHref)))) }.exceptionOrNull() is IllegalStateException)
                assertEquals(original, repository.resume(format, sourceHash))
                assertTrue(db.reading().observed(format, sourceHash).isEmpty())
            }
            val epubHash = "d".repeat(64)
            db.books().insert(book("epub", "EPUB", epubHash))
            val epub = ReadingLocation("epub-location", "one.xhtml")
            repository.record("epub", epubHash, ReadingSnapshot(epub, listOf(span(epub.resourceHref))))
            assertEquals(epub, repository.resume("epub", epubHash))
            assertEquals(1, db.reading().observed("epub", epubHash).size)
            assertEquals("0/1", db.reading().observed("epub", epubHash).single().nodePath)
        } finally { db.close() }
    }

    @Test fun actualPdfPositionReopensWithoutFabricatingTextAndStaleHashesCannotOverwriteIt() = runBlocking {
        val path = File(temporary.root, "read.db").absolutePath
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, path).build()
        var db = open()
        try {
            db.books().insert(book("pdf", "PDF"))
            val repository = RoomReadingStateRepository(db)
            for (page in listOf(0, 19, 3)) repository.record("pdf", hash, ReadingSnapshot(PdfPosition.encode(page)))
            assertTrue(runCatching { repository.record("pdf", "c".repeat(64), ReadingSnapshot(PdfPosition.encode(4))) }.isFailure)
            db.close(); db = open()
            assertEquals(PdfPosition.encode(3), RoomReadingStateRepository(db).resume("pdf", hash))
            assertNull(RoomReadingStateRepository(db).resume("pdf", "c".repeat(64)))
            assertTrue(db.reading().observed("pdf", hash).isEmpty())
        } finally { db.close() }
    }
}

package com.yaprak.reader.formats.pdf

import android.graphics.Paint
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.data.search.LocalBookTextExtractor
import com.yaprak.reader.data.search.LocalLibraryTextIndex
import com.yaprak.reader.data.search.TextIndexDatabase
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android15 native decoder plus SQLite FTS; no user library database, OCR or upload. */
@RunWith(AndroidJUnit4::class)
class PdfLibraryTextIndexDeviceTest {
    @Test fun nativeMultiPagePdfJoinsLibraryFtsWithRealPageTargetsAndUnchangedPrivateBytes() = runBlocking {
        fixture(listOf("First dependency injection", null, "Third dependency injection")) { book, file, service ->
            val original = file.readBytes()
            service.rebuild {}
            val result = service.observe("dependency injection").first()
            assertEquals(2, result.totalMatches)
            assertEquals(listOf(0, 2), result.hits.map { it.target.pdfPageIndex })
            assertTrue(result.hits.all { it.target.sourceFormat == BookFormat.PDF && it.target.resourceHref.isEmpty() && it.chapterTitle == null })
            result.hits.forEach { assertEquals(it, service.resolve(it)) }
            val scope = BookTextScope(book.id, book.sha256)
            val prepared = service.prepare(scope, "dependency")
            assertEquals(scope, prepared.scope); assertEquals(2, prepared.totalMatches)
            assertEquals(listOf(0, 2), prepared.references.map { it.hit.target.pdfPageIndex })
            assertTrue(prepared.references.all { it.hit.target.sourceFormat == BookFormat.PDF && it.text.contains("dependency") })
            assertNull(prepared.section); assertNull(prepared.chapter); assertNull(prepared.prefix)
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun imageOnlyPdfGetsNoTextStateWithoutAutomaticOcr() = runBlocking {
        fixture(listOf(null, null)) { _, file, service ->
            val original = file.readBytes(); service.rebuild {}
            val result = service.observe("absent").first()
            assertEquals(LibraryTextBookState.NO_TEXT, result.books.single().state)
            assertEquals(0, result.totalMatches); assertTrue(result.hits.isEmpty())
            assertArrayEquals(original, file.readBytes())
        }
    }

    private suspend fun fixture(texts: List<String?>, block: suspend (Book, File, LocalLibraryTextIndex) -> Unit) {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "index-device-${UUID.randomUUID()}.pdf"
        val file = File(File(context.filesDir, "books").apply { mkdirs() }, name)
        withContext(Dispatchers.IO) {
            val writer = android.graphics.pdf.PdfDocument()
            try {
                texts.forEachIndexed { index, text ->
                    val page = writer.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 300, index + 1).create())
                    try {
                        if (text != null) page.canvas.drawText(text, 12f, 100f, Paint().apply { textSize = 16f })
                        else page.canvas.drawRect(20f, 20f, 100f, 100f, Paint())
                    } finally { writer.finishPage(page) }
                }
                file.outputStream().use(writer::writeTo)
            } finally { writer.close() }
        }
        val main = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val cache = Room.inMemoryDatabaseBuilder(context, TextIndexDatabase::class.java).build()
        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val book = Book("native-pdf", "Device fixture", null, BookFormat.PDF, name, name, file.length(), hash, 1, pageCount = texts.size)
            val library = RoomLibraryRepository(main.books()); library.add(book)
            val service = LocalLibraryTextIndex(cache, library, LocalBookTextExtractor(context))
            block(book, file, service)
            assertNull(main.reading().resume(book.id, hash))
        } finally { cache.close(); main.close(); assertTrue(file.delete()) }
    }
}

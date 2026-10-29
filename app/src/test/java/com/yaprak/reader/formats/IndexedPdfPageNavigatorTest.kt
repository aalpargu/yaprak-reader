package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.formats.pdf.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IndexedPdfPageNavigatorTest {
    private class Native : PdfNativeRenderer {
        override val pageCount = 3
        override var textAvailable = true
        var onText: () -> Unit = {}
        var text = "Native page text"
        override fun openPage(pageIndex: Int) = object : PdfNativePage {
            override val width = 600; override val height = 800
            override fun render(bitmap: Bitmap) = error("No rendering during proof")
            override fun textContents(): List<String> { onText(); return listOf(text) }
            override fun close() = Unit
        }
        override fun close() = Unit
    }
    private class Fixture(val file: File, val native: Native, val document: PdfDocument) {
        var active = true
        var validation: (Book) -> Unit = {}
        val jumps = mutableListOf<Int>()
        val book = Book("pdf", "Current", null, BookFormat.PDF, "book.pdf", "book.pdf", file.length(),
            indexedPdfTextHash(file.readText()), 1, pageCount = 3)
        val navigator = DocumentIndexedPdfPageNavigator(book, document, VerifiedBookFile(file.parentFile!!), Mutex(),
            { validation(it) }, { active }, { jumps += it; true })
        fun target() = LibraryTextTarget(book.id, book.sha256, "", indexedPdfTextHash(native.text),
            sourceFormat = BookFormat.PDF, pdfPageIndex = 2)
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val dir = Files.createTempDirectory("indexed-pdf-").toFile()
        val file = File(dir, "book.pdf").apply { writeText("Private fixture bytes") }
        val native = Native(); val document = PdfDocument(native)
        try { Fixture(file, native, document).block() }
        finally { document.closeAndAwait(); assertTrue(file.delete()); assertTrue(dir.delete()) }
    }

    @Test fun `verified real page has one use and wrong page consumes rather than retargets proof`() = runBlocking {
        fixture {
            val target = target()
            assertEquals(2, navigator.verifiedPageIndex(target))
            assertFalse(navigator.goToPage(1)); assertFalse(navigator.goToPage(2)); assertTrue(jumps.isEmpty())
            assertEquals(2, navigator.verifiedPageIndex(target)); assertTrue(navigator.goToPage(2))
            assertFalse(navigator.goToPage(2)); assertEquals(listOf(2), jumps)
        }
    }

    @Test fun `source type file text page capability and current book checks cannot forge page proof`() = runBlocking {
        fixture {
            val target = target()
            for (invalid in listOf(target.copy(contentSha256 = "a".repeat(64)), target.copy(bookId = "other"),
                target.copy(resourceSha256 = "b".repeat(64)), target.copy(pdfPageIndex = 3),
                target.copy(resourceHref = "chapter.xhtml"), target.copy(sourceFormat = BookFormat.EPUB))) {
                assertNull(navigator.verifiedPageIndex(invalid)); assertFalse(navigator.goToPage(2))
            }
            native.textAvailable = false; assertNull(navigator.verifiedPageIndex(target)); native.textAvailable = true
            validation = { error("Book changed or recovery pending") }; assertNull(navigator.verifiedPageIndex(target))
            validation = {}; file.writeText("Different bytes")
            assertNull(navigator.verifiedPageIndex(target)); assertTrue(jumps.isEmpty())
        }
    }

    @Test fun `post native file change and changed foreground reject proof before navigation`() = runBlocking {
        for (changeFile in listOf(false, true)) fixture {
            val target = target()
            native.onText = { if (changeFile) file.writeText("Changed during proof") else active = false }
            assertNull(navigator.verifiedPageIndex(target)); assertFalse(navigator.goToPage(2)); assertTrue(jumps.isEmpty())
        }
    }

    @Test fun `cancelled verification and completed owner cannot leave reusable native receipts`() = runBlocking {
        fixture {
            val target = target()
            lateinit var cancelled: Deferred<Int?>
            cancelled = async(start = CoroutineStart.LAZY) { navigator.verifiedPageIndex(target) }
            native.onText = { cancelled.cancel() }; cancelled.start()
            assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
            assertFalse(navigator.goToPage(2))
            native.onText = {}
            val completed = async { navigator.verifiedPageIndex(target) }
            assertEquals(2, completed.await()); assertFalse(navigator.goToPage(2)); assertTrue(jumps.isEmpty())
        }
    }

    @Test fun `overlapping later request invalidates earlier response even when its native text matches`() = runBlocking {
        fixture {
            val target = target(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            native.onText = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val first = async { navigator.verifiedPageIndex(target) }; yield()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = async { navigator.verifiedPageIndex(target.copy(resourceSha256 = "b".repeat(64))) }; yield()
            release.countDown()
            assertNull(first.await()); assertNull(second.await()); assertFalse(navigator.goToPage(2))
        }
    }
}

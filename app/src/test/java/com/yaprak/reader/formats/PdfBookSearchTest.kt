package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.*
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PdfBookSearchTest {
    private class Native(private val pages: List<List<String>>, override val textAvailable: Boolean = true) : PdfNativeRenderer {
        override val pageCount get() = pages.size
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var onText: (Int) -> Unit = {}
        var onClose: () -> Unit = {}
        override fun openPage(pageIndex: Int): PdfNativePage {
            events += "open:$pageIndex"
            return object : PdfNativePage {
                override val width = 100
                override val height = 100
                override fun render(bitmap: Bitmap) { events += "render:$pageIndex" }
                override fun textContents(): List<String> { events += "text:$pageIndex"; onText(pageIndex); return pages[pageIndex] }
                override fun close() { events += "page-close:$pageIndex" }
            }
        }
        override fun close() { events += "close"; onClose() }
    }
    private fun doc(native: Native, scope: TestScope) = PdfDocument(native, StandardTestDispatcher(scope.testScheduler))
    private fun search(document: PdfDocument, scope: TestScope, navigate: (Int) -> Boolean = { true }) =
        PdfBookSearch(document, navigate, StandardTestDispatcher(scope.testScheduler))
    @Test fun searchesAllActualPagesAndNavigationOnlyOccursWhenSelected() = runTest {
        val native = Native(listOf(listOf("one alpha and alpha"), emptyList(), listOf("three ALPHA")))
        val document = doc(native, this); val navigation = mutableListOf<Int>()
        try {
            val source = search(document, this) { navigation += it; true }
            val result = source.find("alpha")
            assertEquals(listOf(0, 0, 2), result.hits.map { PdfPosition.decodeOrNull(it.location, 3) })
            assertEquals(listOf("Sayfa 1", "Sayfa 1", "Sayfa 3"), result.hits.map { it.title })
            assertFalse(result.truncated); assertTrue(navigation.isEmpty())
            assertTrue(result.hits[2].snippet!!.contains("ALPHA"))
            assertTrue(source.goTo(result.hits[2].location)); assertEquals(listOf(2), navigation)
            assertEquals(listOf("open:0", "text:0", "page-close:0", "open:1", "text:1", "page-close:1", "open:2", "text:2", "page-close:2"), native.events)
        } finally { document.close(); advanceUntilIdle() }
    }
    @Test fun literalNotRegexTurkishAndWhitespaceAcrossNativeBlocks() = runTest {
        val native = Native(listOf(listOf("İSTANBUL dependency", "\n\tinjection 🌿 [a.b]")))
        val document = doc(native, this)
        try {
            val source = search(document, this)
            for (query in listOf("istanbul", "dependency injection", "🌿", "[a.b]")) assertEquals(query, 1, source.find(query).hits.size)
            assertEquals(0, source.find(".*").hits.size)
        } finally { document.close(); advanceUntilIdle() }
    }
    @Test fun emptyScannedOrNonMatchingPagesAreRealEmptyResults() = runTest {
        val native = Native(listOf(emptyList(), listOf("another")))
        val document = doc(native, this)
        try { assertEquals(BookSearchResults(emptyList(), false), search(document, this).find("absent")) }
        finally { document.close(); advanceUntilIdle() }
    }
    @Test fun actual201stMatchTruncatesButExactly200DoesNot() = runTest {
        for (count in listOf(200, 201)) {
            val native = Native(listOf(listOf(List(count) { "alpha" }.joinToString(" "))))
            val document = doc(native, this)
            try {
                val result = search(document, this).find("alpha")
                assertEquals(200, result.hits.size); assertEquals(count == 201, result.truncated)
                assertEquals("page-close:0", native.events.last())
            } finally { document.close(); advanceUntilIdle() }
        }
    }
    @Test fun unsupportedAndInvalidQueriesNeverOpenNativePage() = runTest {
        val unsupported = Native(listOf(emptyList()), false); val unsupportedDoc = doc(unsupported, this)
        try {
            assertFalse(search(unsupportedDoc, this).supported)
            assertEquals(BookSearchFailure.UNSUPPORTED, failure { search(unsupportedDoc, this).find("word") })
            assertTrue(unsupported.events.isEmpty())
        } finally { unsupportedDoc.close(); advanceUntilIdle() }
        val native = Native(listOf(listOf("word"))); val document = doc(native, this)
        try {
            for (query in listOf("", "  ", "x".repeat(201), "\uD83C", "\u0000"))
                assertEquals(BookSearchFailure.INVALID_QUERY, failure { search(document, this).find(query) })
            assertTrue(native.events.isEmpty())
        } finally { document.close(); advanceUntilIdle() }
    }
    @Test fun tooLargeMalformedNativePageAndLaterErrorNeverReturnPartialSuccess() = runTest {
        for (blocks in listOf(listOf("x".repeat(MAX_PDF_PAGE_TEXT)), List(MAX_PDF_TEXT_BLOCKS + 1) { "" })) {
            val native = Native(listOf(listOf("alpha"), blocks)); val document = doc(native, this)
            try { assertEquals(BookSearchFailure.LIMIT, failure { search(document, this).find("alpha") }) }
            finally { document.close(); advanceUntilIdle() }
            assertTrue(native.events.contains("page-close:1"))
        }
        val malformed = Native(listOf(listOf("alpha"), listOf("\uDC00"))); val document = doc(malformed, this)
        try { assertEquals(BookSearchFailure.SOURCE, failure { search(document, this).find("alpha") }) }
        finally { document.close(); advanceUntilIdle() }
        val native = Native(listOf(listOf("alpha"), emptyList())).apply { onText = { if (it == 1) throw IOException("Native failed") } }
        val broken = doc(native, this)
        try { assertTrue(runCatching { search(broken, this).find("alpha") }.exceptionOrNull() is IOException) }
        finally { broken.close(); advanceUntilIdle() }
        assertTrue(native.events.contains("page-close:1"))
    }
    @Test fun cancellationInsideNativeCallClosesPageAndNeverScansNextPage() = runTest {
        val native = Native(listOf(listOf("alpha"), listOf("alpha"))); val document = doc(native, this)
        val request = async { search(document, this@runTest).find("alpha") }
        native.onText = { request.cancel() }; runCurrent()
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("open:0", "text:0", "page-close:0"), native.events)
        document.close(); advanceUntilIdle()
    }
    @Test fun closedSourceNeverOpensOrNavigatesAndInvalidPageAnchorIsRejected() = runTest {
        val native = Native(listOf(listOf("alpha"))); val document = doc(native, this); var navigation = 0
        val source = search(document, this) { navigation++; true }
        assertFalse(source.goTo(ReadingLocation("{}", "EPUB/chapter.xhtml")))
        assertFalse(source.goTo(PdfPosition.encode(99)))
        document.close(); advanceUntilIdle()
        assertFalse(source.goTo(PdfPosition.encode(0))); assertEquals(0, navigation)
        assertEquals(BookSearchFailure.SOURCE, failure { source.find("alpha") })
        assertEquals(listOf("close"), native.events)
    }
    @Test fun snippetContextKeepsEmojiWholeAtBothBoundaries() {
        val text = "a".repeat(20) + "🌿" + "b".repeat(79) + "word" + "c".repeat(79) + "🌿" + "d".repeat(20)
        val start = text.indexOf("word")
        val snippet = pdfSearchSnippet(text, start, start + 4)
        assertTrue(snippet.startsWith("…🌿")); assertTrue(snippet.endsWith("🌿…"))
        assertTrue(validPdfText(snippet)); assertTrue(snippet.contains("word"))
    }
    @Test fun totalScanBudgetRejectsAWholeBookWithoutRetainingEarlierMatches() = runTest {
        val page = "x".repeat(MAX_PDF_PAGE_TEXT - 1)
        val native = Native(List(65) { listOf(page) }); val document = doc(native, this)
        try { assertEquals(BookSearchFailure.LIMIT, failure { search(document, this).find("absent") }) }
        finally { document.close(); advanceUntilIdle() }
        assertEquals(65, native.events.count { it.startsWith("text:") })
        assertEquals(65, native.events.count { it.startsWith("page-close:") })
    }
    @Test fun extractionAndRenderingCannotHoldTwoNativePagesAtOnce() = runTest {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val disposed = CompletableDeferred<Unit>()
        val native = Native(listOf(listOf("alpha"), listOf("beta"))).apply {
            onText = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            onClose = { disposed.complete(Unit) }
        }
        val document = PdfDocument(native, Dispatchers.IO)
        val text = async { document.extractText(0) }; runCurrent()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val image = async { document.renderThumbnail(1) }; runCurrent()
            assertEquals(listOf("open:0", "text:0"), native.events.toList())
            release.countDown()
            assertEquals("alpha", text.await().text)
            val bitmap = image.await(); bitmap.recycle()
            assertEquals(listOf("open:0", "text:0", "page-close:0", "open:1", "render:1", "page-close:1"), native.events.toList())
        } finally { release.countDown(); document.close(); withContext(Dispatchers.IO) { withTimeout(5000) { disposed.await() } } }
    }
    @Test fun closeDuringNativeExtractionRejectsResultAndWaitsForPageCleanup() = runTest {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val disposed = CompletableDeferred<Unit>()
        val native = Native(listOf(listOf("alpha"))).apply {
            onText = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            onClose = { disposed.complete(Unit) }
        }
        val document = PdfDocument(native, Dispatchers.IO)
        val text = async { runCatching { document.extractText(0) } }; runCurrent()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); document.close()
            assertFalse(document.isOpen); assertFalse(document.textAvailable)
            assertEquals(listOf("open:0", "text:0"), native.events.toList())
            release.countDown(); assertTrue(text.await().exceptionOrNull() is IllegalStateException)
            withContext(Dispatchers.IO) { withTimeout(5000) { disposed.await() } }
            assertEquals(listOf("open:0", "text:0", "page-close:0", "close"), native.events.toList())
        } finally { release.countDown(); document.close() }
    }
    private suspend fun failure(block: suspend () -> Unit): BookSearchFailure? =
        (runCatching { block() }.exceptionOrNull() as? BookSearchException)?.reason
}

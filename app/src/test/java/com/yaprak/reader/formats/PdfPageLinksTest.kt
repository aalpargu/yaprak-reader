package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.*
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
class PdfPageLinksTest {
    private class Native : PdfNativeRenderer {
        override val pageCount = 4
        override var linksAvailable = true
        var links = listOf(PdfNativeGotoLink(2, 20.0, 375.0, 1.5))
        var onLinks: () -> Unit = {}
        var onOpen: (Int) -> Unit = {}
        val events = mutableListOf<String>()
        override fun openPage(pageIndex: Int): PdfNativePage {
            events += "open:$pageIndex"; onOpen(pageIndex)
            return object : PdfNativePage {
                override val width = 400
                override val height = 1000
                override fun gotoLinks(): List<PdfNativeGotoLink> { events += "links:$pageIndex"; onLinks(); return links }
                override fun render(bitmap: Bitmap) { error("Link reading must not allocate/render a bitmap") }
                override fun close() { events += "close:$pageIndex" }
            }
        }
        override fun close() { events += "document-close" }
    }
    private class Fixture(scope: TestScope) {
        val native = Native()
        val document = PdfDocument(native, StandardTestDispatcher(scope.testScheduler))
        var active: Int? = 0
        val navigated = mutableListOf<PdfResumePosition>()
        val source = DocumentPdfPageLinksSource(document, { active }) { navigated += it; true }
    }

    @Test fun `actual ordered native destinations resolve to original Y and navigate only on explicit action`() = runTest {
        val f = Fixture(this)
        try {
            f.native.links += PdfNativeGotoLink(0, 0.0, 0.0, 0.0)
            val rows = f.source.load(0)
            assertEquals(listOf(1, 2), rows.map { it.ordinal })
            assertEquals(listOf(2, 0), rows.map { it.targetPageIndex })
            assertEquals(2, rows.map { it.id }.toSet().size)
            assertTrue(f.navigated.isEmpty())
            val target = f.source.resolve(0, rows.first().id)
            assertEquals(PdfResumePosition(2, 375000), PdfPosition.decodeResumeOrNull(target, 4))
            assertFalse(f.source.goTo(target.copy()))
            assertTrue(f.source.goTo(target)); assertFalse(f.source.goTo(target))
            assertEquals(listOf(PdfResumePosition(2, 375000)), f.navigated)
            assertEquals(listOf("open:0", "links:0", "close:0", "open:2", "close:2"), f.native.events)
            assertTrue(runCatching { (rows as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        } finally { f.document.close(); advanceUntilIdle() }
    }

    @Test fun `empty pages are real empty results and full budget is accepted without partial overflow`() = runTest {
        val f = Fixture(this)
        try {
            f.native.links = emptyList(); assertTrue(f.source.load(0).isEmpty())
            f.native.links = List(MAX_PDF_PAGE_LINKS) { PdfNativeGotoLink(1, 0.0, 0.0, 0.0) }
            assertEquals(MAX_PDF_PAGE_LINKS, f.source.load(0).size)
            f.native.links += PdfNativeGotoLink(1, 0.0, 0.0, 0.0)
            assertEquals(PdfPageLinksFailure.LIMIT, failure { f.source.load(0) })
            assertTrue(f.navigated.isEmpty()); assertEquals("close:0", f.native.events.last())
        } finally { f.document.close(); advanceUntilIdle() }
    }

    @Test fun `invalid native page and nonfinite or negative coordinates reject the entire result`() = runTest {
        val f = Fixture(this)
        try {
            val valid = f.native.links.single()
            for (bad in listOf(valid.copy(pageIndex = -1), valid.copy(pageIndex = 4), valid.copy(x = Double.NaN),
                valid.copy(x = -1.0), valid.copy(y = Double.POSITIVE_INFINITY), valid.copy(y = -0.1),
                valid.copy(zoom = Double.NaN), valid.copy(zoom = -1.0))) {
                f.native.links = listOf(valid, bad)
                assertEquals(bad.toString(), PdfPageLinksFailure.SOURCE, failure { f.source.load(0) })
                assertEquals("close:0", f.native.events.last())
            }
        } finally { f.document.close(); advanceUntilIdle() }
    }

    @Test fun `target coordinates must fit real target dimensions including a valid bottom edge`() = runTest {
        val f = Fixture(this)
        try {
            for (bad in listOf(PdfNativeGotoLink(2, 400.1, 0.0, 0.0), PdfNativeGotoLink(2, 0.0, 1000.1, 0.0))) {
                f.native.links = listOf(bad)
                val row = f.source.load(0).single()
                assertEquals(PdfPageLinksFailure.SOURCE, failure { f.source.resolve(0, row.id) })
            }
            f.native.links = listOf(PdfNativeGotoLink(2, 400.0, 1000.0, 0.0))
            val row = f.source.load(0).single()
            assertEquals(PdfResumePosition(2, 1000000), PdfPosition.decodeResumeOrNull(f.source.resolve(0, row.id), 4))
            assertTrue(f.navigated.isEmpty())
        } finally { f.document.close(); advanceUntilIdle() }
    }

    @Test fun `unsupported closed and undrawn sources never open a page`() = runTest {
        val f = Fixture(this)
        f.native.linksAvailable = false
        assertFalse(f.source.supported)
        assertEquals(PdfPageLinksFailure.UNSUPPORTED, failure { f.source.load(0) })
        f.native.linksAvailable = true; f.active = null
        assertEquals(PdfPageLinksFailure.INTERRUPTED, failure { f.source.load(0) })
        assertTrue(f.native.events.isEmpty())
        f.document.close(); advanceUntilIdle()
        assertFalse(f.source.supported)
        assertEquals(PdfPageLinksFailure.SOURCE, failure { f.source.load(0) })
        assertEquals(listOf("document-close"), f.native.events)
    }

    @Test fun `reload expires old IDs and resolved objects and arbitrary IDs never open a target page`() = runTest {
        val f = Fixture(this)
        try {
            val first = f.source.load(0).single()
            val target = f.source.resolve(0, first.id)
            val second = f.source.load(0).single()
            assertNotEquals(first.id, second.id); assertFalse(f.source.goTo(target))
            val opens = f.native.events.size
            for (id in listOf(first.id, "foreign", ""))
                assertEquals(PdfPageLinksFailure.SOURCE, failure { f.source.resolve(0, id) })
            assertEquals(opens, f.native.events.size)
            assertTrue(f.navigated.isEmpty())
        } finally { f.document.close(); advanceUntilIdle() }
    }

    @Test fun `page changes during native extraction reject delivery and close the page`() = runTest {
        val f = Fixture(this)
        try {
            f.native.onLinks = { f.active = 1 }
            assertEquals(PdfPageLinksFailure.INTERRUPTED, failure { f.source.load(0) })
            assertEquals(listOf("open:0", "links:0", "close:0"), f.native.events)
        } finally { f.document.close(); advanceUntilIdle() }
    }

    @Test fun `pause invalidation rejects late loads even when the same page is active again`() = runTest {
        val f = Fixture(this)
        try {
            f.native.onLinks = { f.source.invalidate() }
            assertEquals(PdfPageLinksFailure.INTERRUPTED, failure { f.source.load(0) })
            f.native.onLinks = {}
            assertEquals(1, f.source.load(0).size)
        } finally { f.document.close(); advanceUntilIdle() }
    }

    @Test fun `pause during target measurement expires destination and never navigates`() = runTest {
        val f = Fixture(this)
        try {
            val row = f.source.load(0).single()
            f.native.onOpen = { if (it == 2) f.source.invalidate() }
            assertEquals(PdfPageLinksFailure.INTERRUPTED, failure { f.source.resolve(0, row.id) })
            assertTrue(f.navigated.isEmpty()); assertEquals("close:2", f.native.events.last())
        } finally { f.document.close(); advanceUntilIdle() }
    }

    @Test fun `cancellation inside native extraction propagates and releases its page`() = runTest {
        val f = Fixture(this)
        try {
            val request = async { f.source.load(0) }
            f.native.onLinks = { request.cancel() }; runCurrent()
            assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
            assertEquals(listOf("open:0", "links:0", "close:0"), f.native.events)
            assertTrue(f.navigated.isEmpty())
        } finally { f.document.close(); advanceUntilIdle() }
    }

    @Test fun `native failure releases page and a new successful load can retry`() = runTest {
        val f = Fixture(this)
        try {
            f.native.onLinks = { throw java.io.IOException("Unavailable") }
            assertTrue(runCatching { f.source.load(0) }.exceptionOrNull() is java.io.IOException)
            assertEquals("close:0", f.native.events.last())
            f.native.onLinks = {}
            assertEquals(1, f.source.load(0).size)
        } finally { f.document.close(); advanceUntilIdle() }
    }

    @Test fun `closing during native extraction denies result and disposes after page cleanup`() = runTest {
        val f = Fixture(this)
        f.native.onLinks = { f.document.close() }
        assertTrue(runCatching { f.source.load(0) }.exceptionOrNull() is IllegalStateException)
        advanceUntilIdle()
        assertEquals(listOf("open:0", "links:0", "close:0", "document-close"), f.native.events)
        assertTrue(f.navigated.isEmpty())
    }

    @Test fun `links and dimensions cannot hold two native page leases at once`() = runTest {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val native = Native().apply { onLinks = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) } }
        val document = PdfDocument(native, Dispatchers.IO)
        val links = async { document.gotoLinks(0) }; runCurrent()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val dimensions = async { document.pageDimensions(2) }; runCurrent()
            assertEquals(listOf("open:0", "links:0"), native.events.toList())
            release.countDown()
            assertEquals(1, links.await().size); assertEquals(PdfPageDimensions(400, 1000), dimensions.await())
            assertEquals(listOf("open:0", "links:0", "close:0", "open:2", "close:2"), native.events)
        } finally { release.countDown(); document.closeAndAwait() }
    }

    private suspend fun failure(block: suspend () -> Any?): PdfPageLinksFailure? =
        (runCatching { block() }.exceptionOrNull() as? PdfPageLinksException)?.reason
}

package com.yaprak.reader.formats.fb2

import android.app.Application
import androidx.fragment.app.Fragment
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.reader.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Fb2ReaderBoundaryTest {
    private val book = Book("id", "FB2", null, BookFormat.FB2, "text.fb2", "id.fb2", 1, "a".repeat(64), 1)
    private val location = ReadingLocation("native locator", "Text/part0001.xhtml")
    private fun session(surface: ReaderSurface, onClose: () -> Unit = {}) = object : ReaderSession {
        override val book = this@Fb2ReaderBoundaryTest.book
        override val resume = location
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit) = surface
        override fun close() = onClose()
    }
    @Test fun nativeLocatorIsRetainedButSynthesizedMarkupCanNeverPersistEpubCoverage() = runBlocking {
        var requested = true
        val surface = object : ReaderSurface {
            override val fragment = Fragment()
            override fun forward() = true
            override fun backward() = true
            override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
                requested = coverageAllowed()
                onSnapshot(ReadingSnapshot(location, listOf(ObservedTextSpan(location.resourceHref, "0", 0, 3, "b".repeat(64)))))
            }
        }
        val wrapped = Fb2ReaderSession(session(surface)) {}
        val snapshots = mutableListOf<ReadingSnapshot>()
        val wrappedSurface = wrapped.createSurface(null, false, {}, {})
        wrappedSurface.observe({ true }, snapshots::add)
        assertFalse(requested); assertEquals(listOf(ReadingSnapshot(location)), snapshots)
        wrapped.close()
        wrappedSurface.observe({ true }, snapshots::add)
        assertEquals(1, snapshots.size)
    }
    @Test fun plainSelectionKeepsNativeOwnershipAndDropsEveryFormatSpecificProvenance() = runBlocking {
        var clears = 0
        val native = object : TextSelectionSource {
            override val active = true
            override suspend fun capture() = SelectedText("literal text", SelectionRangeCandidate(location.resourceHref, "0", 0, 3, "b".repeat(64)),
                SourceRangeAnchor(location.resourceHref, listOf(SourceRangeSegment("0", 0, 3, "b".repeat(64), "lit"))),
                pdfPageIndex = 2, sourceSelectionId = "native", pdfOcr = true)
            override fun clear() { clears++ }
            override fun isCurrent(requestId: String?) = requestId == "native"
        }
        val surface = object : ReaderSurface {
            override val fragment = Fragment()
            override val textSelection = native
            override fun forward() = true; override fun backward() = true
            override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) = Unit
        }
        val wrapped = Fb2ReaderSession(session(surface)) {}
        val selection = wrapped.createSurface(null, false, {}, {}).textSelection!!
        assertTrue(selection.active); assertTrue(selection.isCurrent("native")); assertFalse(selection.isCurrent("stale"))
        assertEquals(SelectedText("literal text", sourceSelectionId = "native"), selection.capture())
        selection.clear(); assertEquals(1, clears); wrapped.close()
    }
    @Test fun closeOwnsOnlyItsArtifactAndRunsOnceEvenIfNativeCloseFails() {
        var closes = 0; var releases = 0
        val surface = object : ReaderSurface {
            override val fragment = Fragment(); override fun forward() = false; override fun backward() = false
            override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) = Unit
        }
        val wrapped = Fb2ReaderSession(session(surface) { closes++; error("controlled native close") }) { releases++ }
        assertNotNull(runCatching { wrapped.close() }.exceptionOrNull()); wrapped.close()
        assertEquals(1, closes); assertEquals(1, releases)
        assertNotNull(runCatching { wrapped.createSurface(null, false, {}, {}) }.exceptionOrNull())
    }
    @Test fun replacedSurfaceCannotCaptureSelectionSearchOrPublishProgress() = runBlocking {
        var searches = 0
        val native = object : ReaderSurface {
            override val fragment = Fragment()
            override val textSelection = object : TextSelectionSource {
                override val active = true
                override suspend fun capture() = SelectedText("text", sourceSelectionId = "native")
            }
            override val bookSearch = object : BookTextSearch {
                override suspend fun find(query: String): BookSearchResults { searches++; error("obsolete request reached native") }
                override fun goTo(location: ReadingLocation): Boolean { searches++; return true }
            }
            override fun forward() = true; override fun backward() = true
            override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) { onSnapshot(ReadingSnapshot(location)) }
        }
        val wrapped = Fb2ReaderSession(session(native)) {}
        val old = wrapped.createSurface(null, false, {}, {})
        val latest = wrapped.createSurface(null, false, {}, {})
        assertFalse(old.textSelection!!.active); assertNull(old.textSelection!!.capture())
        assertFalse(old.bookSearch!!.goTo(location))
        assertTrue(runCatching { old.bookSearch!!.find("x") }.exceptionOrNull() is BookSearchException)
        old.observe({ true }) { error("obsolete snapshot") }
        assertEquals(0, searches); assertEquals(SelectedText("text", sourceSelectionId = "native"), latest.textSelection!!.capture())
        wrapped.close(); assertNull(latest.textSelection!!.capture())
    }
}

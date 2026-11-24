package com.yaprak.reader.feature.reader

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.reader.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSelectionCoordinatorTest {
    private class Surface : ReaderSurface {
        override val fragment: Fragment get() = error("No native host is needed for source lease tests")
        var source: TextSelectionSource? = null
        override val textSelection get() = source
        override val pageNavigator: PageNavigator? = null
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) = Unit
        override fun forward() = true
        override fun backward() = true
    }
    private class Session(val surface: Surface) : ReaderSession {
        override var book = Book("one", "Book", null, BookFormat.EPUB, "one.epub", "one.epub", 1, "a".repeat(64), 1)
        override val resume: ReadingLocation? = null
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit) = surface
        override fun close() = Unit
    }
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private class Source(var captureText: suspend () -> SelectedText?) : TextSelectionSource {
        var revision = "first"
        var clears = 0
        override suspend fun capture() = captureText()
        override fun isCurrent(requestId: String?) = requestId == null || requestId == revision
        override fun clear() { clears++ }
    }
    private class Fixture(scope: TestScope) {
        val surface = Surface()
        val session = Session(surface)
        var current: ReaderSelectionOwner? = ReaderSelectionOwner(session, surface)
        var starts = 0
        var clears = 0
        var rejects = 0
        val ready = mutableListOf<SelectedTextPreview.Ready>()
        val coordinator = ReaderSelectionCoordinator(scope.backgroundScope, Owner().lifecycle, { current },
            { starts++ }, { clears++ }, { ready += it }, { rejects++ })
        fun capture(source: Source) { surface.source = source; coordinator.capture(surface, source, false) }
    }

    @Test fun replacementSourceAfterSuspensionCannotPublishTextOrDiscoverTools() = runTest {
        val result = CompletableDeferred<SelectedText?>()
        val fixture = Fixture(this)
        fixture.capture(Source { result.await() })
        runCurrent()
        assertTrue(fixture.coordinator.preview is SelectedTextPreview.Loading)
        fixture.current = ReaderSelectionOwner(Session(Surface()), Surface())
        result.complete(SelectedText("Old source text", sourceSelectionId = "first"))
        runCurrent()
        assertNull(fixture.coordinator.preview)
        assertTrue(fixture.ready.isEmpty())
        assertEquals(1, fixture.rejects)
    }

    @Test fun clearingASelectionRevokesNoncooperativeCaptureWithoutClearingANewerPreview() = runTest {
        val result = CompletableDeferred<SelectedText?>()
        val fixture = Fixture(this)
        try {
            fixture.capture(Source { withContext(NonCancellable) { result.await() } })
            runCurrent()
            fixture.coordinator.clear()
            fixture.capture(Source { SelectedText("New source text", sourceSelectionId = "first") })
            runCurrent()
            val current = fixture.coordinator.preview as SelectedTextPreview.Ready
            assertEquals("New source text", current.text)
            result.complete(SelectedText("Old source text", sourceSelectionId = "first"))
            runCurrent()
            assertSame(current, fixture.coordinator.preview)
            assertEquals(listOf(current), fixture.ready)
        } finally { result.complete(null) }
    }

    @Test fun pausedHostOrChangedBookCannotPublishACaptureAndDoNotShowACancellationError() = runTest {
        for (pause in listOf(true, false)) {
            val result = CompletableDeferred<SelectedText?>()
            val fixture = Fixture(this)
            fixture.capture(Source { result.await() })
            runCurrent()
            if (pause) fixture.current = null
            else {
                fixture.session.book = fixture.session.book.copy(sha256 = "b".repeat(64))
                fixture.current = ReaderSelectionOwner(fixture.session, fixture.surface)
            }
            result.complete(SelectedText("Unadmitted text", sourceSelectionId = "first"))
            runCurrent()
            assertNull(fixture.coordinator.preview)
            assertTrue(fixture.ready.isEmpty())
        }
    }

    @Test fun actualSourceRevisionRemainsAuthoritativeForActionsAndLateCopyStatus() = runTest {
        val fixture = Fixture(this)
        val source = Source { SelectedText("Current text", sourceSelectionId = "first") }
        fixture.capture(source)
        runCurrent()
        val first = fixture.coordinator.preview as SelectedTextPreview.Ready
        assertTrue(fixture.coordinator.isCurrent())
        source.revision = "second"
        assertFalse(fixture.coordinator.isCurrent())
        fixture.coordinator.updatePreview(first, first.copy(copyStatus = CopyStatus.COPIED))
        assertEquals(CopyStatus.NONE, (fixture.coordinator.preview as SelectedTextPreview.Ready).copyStatus)
        fixture.coordinator.clear()
        assertNull(fixture.coordinator.bookIdentity)
        assertTrue(source.clears > 0)
    }

    @Test fun invalidPdfSelectionMetadataIsAnErrorAndNeverAReadySelection() = runTest {
        val fixture = Fixture(this)
        fixture.session.book = fixture.session.book.copy(format = BookFormat.PDF)
        fixture.current = ReaderSelectionOwner(fixture.session, fixture.surface)
        fixture.capture(Source { SelectedText("PDF text", pdfPageIndex = 0, sourceSelectionId = "not-a-source-uuid") })
        runCurrent()
        assertTrue(fixture.coordinator.preview is SelectedTextPreview.Error)
        assertTrue(fixture.ready.isEmpty())
    }

    @Test fun revisionChangedInsideCaptureCancelsInsteadOfShowingAnErrorOrDeliveringText() = runTest {
        val fixture = Fixture(this)
        lateinit var source: Source
        source = Source { source.revision = "second"; SelectedText("Old revision", sourceSelectionId = "first") }
        fixture.capture(source)
        runCurrent()
        assertNull(fixture.coordinator.preview)
        assertTrue(fixture.ready.isEmpty())
        assertEquals(1, fixture.rejects)
    }
}

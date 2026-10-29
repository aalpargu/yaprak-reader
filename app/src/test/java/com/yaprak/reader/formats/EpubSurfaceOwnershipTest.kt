package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.formats.epub.*
import com.yaprak.reader.reader.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real parsed publication and Readium Fragment; these cases intentionally have no resumed host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubSurfaceOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private suspend fun open(): EpubReaderSession {
        val file = TestBooks.epub(temporary.newFile("owned.epub"))
        val publication = EpubPublicationSource(RuntimeEnvironment.getApplication().contentResolver).open(file)
        val book = Book("owned-epub", "Owned", null, BookFormat.EPUB, "owned.epub", "owned.epub",
            file.length(), "a".repeat(64), 1)
        return EpubReaderSession(book, publication, null, "", "")
    }
    private fun pendingState(surface: ReaderSurface): Any = requireNotNull(EpubNavigatorFragment::class.java
        .getDeclaredField("state").apply { isAccessible = true }.get(surface.fragment))

    @Test fun unattachedBookmarkNavigationRefusesBeforeMutatingReadiumPendingState() = runBlocking {
        open().use { session ->
            val surface = session.createSurface(null, false, {}, {})
            val location = surface.bookSearch!!.find("Güneş").hits.first().location
            val before = pendingState(surface)
            assertFalse(surface.fragment.isResumed)
            assertFalse(surface.bookmarkNavigator!!.goTo(location))
            assertSame(before, pendingState(surface)); assertNull(surface.bookmarkNavigator!!.capture())
        }
    }
    @Test fun unattachedSearchNavigationRefusesBeforeMutatingReadiumPendingState() = runBlocking {
        open().use { session ->
            val surface = session.createSurface(null, false, {}, {})
            val location = surface.bookSearch!!.find("Güneş").hits.first().location
            val before = pendingState(surface)
            assertFalse(surface.bookSearch!!.goTo(location))
            assertSame(before, pendingState(surface))
        }
    }
    @Test fun unattachedContentsNavigationRefusesBeforeMutatingReadiumPendingState() = runBlocking {
        open().use { session ->
            val surface = session.createSurface(null, false, {}, {})
            val contents = requireNotNull(surface.contentsNavigator)
            val entry = contents.contents.entries.single { it.navigable }
            val before = pendingState(surface)
            assertFalse(contents.goTo(entry.id)); assertSame(before, pendingState(surface))
        }
    }
    @Test fun replacementInvalidatesOldSearchAndSnapshotsButKeepsCurrentSourcesReadable() = runBlocking {
        open().use { session ->
            val old = session.createSurface(null, false, {}, {})
            assertTrue(old.bookSearch!!.supported)
            val current = session.createSurface(null, false, {}, {})
            assertFalse(old.bookSearch!!.supported); assertTrue(current.bookSearch!!.supported)
            val failure = runCatching { old.bookSearch!!.find("Güneş") }.exceptionOrNull()
            assertEquals(BookSearchFailure.SOURCE, (failure as? BookSearchException)?.reason)
            assertTrue(current.bookSearch!!.find("Güneş").hits.isNotEmpty())
            val oldSnapshots = mutableListOf<ReadingSnapshot>()
            val currentSnapshots = mutableListOf<ReadingSnapshot>()
            val oldJob = launch(start = CoroutineStart.UNDISPATCHED) { old.observe({ false }, oldSnapshots::add) }
            val currentJob = launch(start = CoroutineStart.UNDISPATCHED) { current.observe({ false }, currentSnapshots::add) }
            try {
                withTimeout(10_000) { while (currentSnapshots.isEmpty()) delay(10) }
                assertTrue(oldSnapshots.isEmpty()); assertEquals(1, currentSnapshots.size)
                assertTrue(currentSnapshots.single().spans.isEmpty())
            } finally { oldJob.cancelAndJoin(); currentJob.cancelAndJoin() }
        }
    }
    @Test fun closedSessionRefusesSearchAndCannotCreateAnotherNativeSurface() = runBlocking {
        val session = open()
        try {
            val surface = session.createSurface(null, false, {}, {})
            session.close()
            assertFalse(surface.bookSearch!!.supported)
            val failure = runCatching { surface.bookSearch!!.find("Güneş") }.exceptionOrNull()
            assertEquals(BookSearchFailure.SOURCE, (failure as? BookSearchException)?.reason)
            assertFalse(surface.forward()); assertFalse(surface.backward())
            assertTrue(runCatching { session.createSurface(null, false, {}, {}) }.exceptionOrNull() is IllegalStateException)
        } finally { session.close() }
    }
    @Test fun imageFilterKeepsStartupAdmissionButRefusesAReplacedOrClosedSurface() = runBlocking {
        open().use { session ->
            val old = session.createSurface(null, false, {}, {})
            old.epubImageFilter!!.apply(EpubImageFilterMode.ORIGINAL)
            val current = session.createSurface(null, false, {}, {})
            assertTrue(runCatching { old.epubImageFilter!!.apply(EpubImageFilterMode.INVERT) }.exceptionOrNull() is IllegalStateException)
            current.epubImageFilter!!.apply(EpubImageFilterMode.DARKEN)
            session.close()
            assertTrue(runCatching { current.epubImageFilter!!.apply(EpubImageFilterMode.ORIGINAL) }.exceptionOrNull() is IllegalStateException)
        }
    }


}

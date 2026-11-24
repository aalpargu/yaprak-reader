package com.yaprak.reader.feature.reader

import androidx.fragment.app.Fragment
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderNavigationCoordinatorTest {
    private val point = ReadingLocation("{\"pageIndex\":2,\"anchorY\":431250}", "Point")
    private class Surface : ReaderSurface {
        override val fragment: Fragment get() = error("Native hosting is not used by the coordinator")
        var bookmarks: BookmarkNavigator? = null
        var search: BookTextSearch? = null
        override val bookmarkNavigator get() = bookmarks
        override val bookSearch get() = search
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

    @Test fun captureReplacingTheSourceCannotNavigateOrSeedHistory() = runTest {
        val first = Surface(); val second = Surface()
        val session = Session(first)
        var owner: ReaderNavigationOwner? = ReaderNavigationOwner(session, first)
        val coordinator = ReaderNavigationCoordinator(backgroundScope) { owner }
        first.bookmarks = object : BookmarkNavigator {
            override fun capture(): BookmarkTarget? { owner = ReaderNavigationOwner(Session(second), second); return null }
            override fun goTo(location: ReadingLocation) = true
        }
        var calls = 0
        assertFalse(coordinator.navigate(true, { true }) { calls++; true })
        assertEquals(0, calls)
        assertFalse(coordinator.canReturn)
    }

    @Test fun onlyAcceptedCurrentJumpsEnterHistoryAndFailedReturnKeepsTheExactPoint() = runTest {
        val surface = Surface(); val session = Session(surface)
        val destinations = mutableListOf<ReadingLocation>()
        var acceptReturn = false
        surface.bookmarks = object : BookmarkNavigator {
            override fun capture(): BookmarkTarget? = null
            override fun goTo(location: ReadingLocation): Boolean { destinations += location; return acceptReturn }
        }
        val owner = ReaderNavigationOwner(session, surface)
        val coordinator = ReaderNavigationCoordinator(backgroundScope) { owner }
        assertTrue(coordinator.navigate(false, { true }) { true })
        assertFalse(coordinator.canReturn)
        coordinator.record(owner, point, false)
        assertFalse(coordinator.canReturn)
        coordinator.record(owner, point, true)
        assertTrue(coordinator.canReturn)
        assertFalse(coordinator.returnToPrevious())
        assertTrue(coordinator.canReturn)
        acceptReturn = true
        assertTrue(coordinator.returnToPrevious())
        assertEquals(listOf(point, point), destinations)
        assertFalse(coordinator.canReturn)
    }

    @Test fun changedBookAndRevokedRouteAfterCaptureCannotRunTheOldCommand() = runTest {
        val surface = Surface(); val session = Session(surface)
        var allowed = true
        val coordinator = ReaderNavigationCoordinator(backgroundScope) { ReaderNavigationOwner(session, surface) }
        surface.bookmarks = object : BookmarkNavigator {
            override fun capture(): BookmarkTarget? { allowed = false; return null }
            override fun goTo(location: ReadingLocation) = true
        }
        var calls = 0
        assertFalse(coordinator.navigate(true, { allowed }) { calls++; true })
        allowed = true
        surface.bookmarks = object : BookmarkNavigator {
            override fun capture(): BookmarkTarget? { session.book = session.book.copy(sha256 = "b".repeat(64)); return null }
            override fun goTo(location: ReadingLocation) = true
        }
        assertFalse(coordinator.navigate(true, { allowed }) { calls++; true })
        assertEquals(0, calls)
    }

    @Test fun sourceFailureDuringAcceptedCommandDoesNotHideTheNewReadersControlsOrRecordTheOldPoint() = runTest {
        val surface = Surface(); val session = Session(surface)
        var owner: ReaderNavigationOwner? = ReaderNavigationOwner(session, surface)
        val coordinator = ReaderNavigationCoordinator(backgroundScope) { owner }
        assertFalse(coordinator.navigate(true, { true }) { owner = null; true })
        assertFalse(coordinator.canReturn)
        owner = ReaderNavigationOwner(Session(Surface()), Surface())
        assertFalse(coordinator.returnToPrevious())
    }

    @Test fun replacingTheBookmarkCapabilityDuringCaptureRevokesTheOldJump() = runTest {
        val surface = Surface(); val session = Session(surface)
        val replacement = object : BookmarkNavigator {
            override fun capture(): BookmarkTarget? = null
            override fun goTo(location: ReadingLocation) = true
        }
        surface.bookmarks = object : BookmarkNavigator {
            override fun capture(): BookmarkTarget? { surface.bookmarks = replacement; return null }
            override fun goTo(location: ReadingLocation) = true
        }
        val coordinator = ReaderNavigationCoordinator(backgroundScope) { ReaderNavigationOwner(session, surface) }
        var requests = 0
        assertFalse(coordinator.navigate(true, { true }) { requests++; true })
        assertEquals(0, requests)
        assertFalse(coordinator.canReturn)
    }

    @Test fun acceptedPageLinkMayInvalidateItsOldOriginWithoutBecomingANavigationFailure() = runTest {
        val surface = Surface(); val session = Session(surface)
        val coordinator = ReaderNavigationCoordinator(backgroundScope) { ReaderNavigationOwner(session, surface) }
        var originPageStillCurrent = true
        assertTrue(coordinator.navigate(true, { originPageStillCurrent }) {
            // The renderer moves away from the link page; that request is no longer reusable.
            originPageStillCurrent = false
            true
        })
        assertFalse(originPageStillCurrent)
    }

    @Test fun cancellationIsNeverReportedAsARejectedNavigation() = runTest {
        val surface = Surface(); val session = Session(surface)
        val coordinator = ReaderNavigationCoordinator(backgroundScope) { ReaderNavigationOwner(session, surface) }
        try {
            coordinator.navigate(false, { true }) { throw CancellationException("owner stopped") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(coordinator.canReturn)
    }

    @Test fun cancelledSearchRetainsQueryAndExitCanJoinTheOldNoncooperativeIterator() = runTest {
        val result = CompletableDeferred<BookSearchResults>()
        val surface = Surface(); val session = Session(surface)
        val coordinator = ReaderNavigationCoordinator(backgroundScope) { ReaderNavigationOwner(session, surface) }
        surface.search = object : BookTextSearch {
            override suspend fun find(query: String) = withContext(NonCancellable) { result.await() }
            override fun goTo(location: ReadingLocation) = true
        }
        try {
            assertTrue(coordinator.search.open(surface.search!!))
            coordinator.search.edit("kept query", true)
            coordinator.search.run { true }
            runCurrent()
            coordinator.search.cancel()
            val unfinished = coordinator.search.stopJobs()
            assertEquals(1, unfinished.size)
            assertTrue(unfinished.single().isCancelled)
            assertFalse(unfinished.single().isCompleted)
            assertEquals("kept query", coordinator.search.state.query)
            result.complete(BookSearchResults(emptyList(), false))
            runCurrent()
            assertTrue(unfinished.single().isCompleted)
            assertEquals(BookSearchPhase.Idle, coordinator.search.state.phase)
        } finally { result.complete(BookSearchResults(emptyList(), false)) }
    }

    @Test fun lateSearchFromOldSessionAndEqualButUnownedHitCannotNavigate() = runTest {
        val result = CompletableDeferred<BookSearchResults>()
        val surface = Surface(); val session = Session(surface)
        var owner = ReaderNavigationOwner(session, surface)
        val coordinator = ReaderNavigationCoordinator(backgroundScope) { owner }
        val hit = BookSearchHit("Result", null, point)
        surface.search = object : BookTextSearch {
            override suspend fun find(query: String) = result.await()
            override fun goTo(location: ReadingLocation) = true
        }
        assertTrue(coordinator.search.open(surface.search!!))
        coordinator.search.edit("query", true)
        coordinator.search.run { true }
        runCurrent()
        owner = ReaderNavigationOwner(Session(surface), surface)
        result.complete(BookSearchResults(listOf(hit), false))
        runCurrent()
        assertFalse(coordinator.search.state.phase is BookSearchPhase.Ready)
        assertTrue(coordinator.search.open(surface.search!!))
        coordinator.search.edit("query", true)
        coordinator.search.run { true }
        runCurrent()
        var navigations = 0
        assertFalse(coordinator.search.select(hit.copy(), { true }) { _, _ -> navigations++; true })
        assertTrue(coordinator.search.select(hit, { true }) { _, _ -> navigations++; true })
        assertEquals(1, navigations)
    }
}

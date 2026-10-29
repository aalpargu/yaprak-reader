package com.yaprak.reader.feature

import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentsControllerTest {
    private class Source : ContentsNavigator {
        override val contents = BookContents(listOf(ContentsEntry("one", "First", 0, true), ContentsEntry("external", "URI", 0, false)))
        var calls = 0
        var load: suspend () -> BookContents = { contents }
        override suspend fun loadContents(): BookContents { calls++; return load() }
        override fun goTo(entryId: String) = error("Only Activity's deliberate jump may navigate")
    }

    @Test fun explicitLoadPublishesOnlyItsNavigableEntriesAndNeverNavigates() = runTest {
        val source = Source(); val controller = ContentsController(this)
        controller.open(source) { true }
        assertTrue(controller.state.value.loading)
        assertFalse(controller.canSelect(controller.state.value, "one"))
        runCurrent(); val ready = controller.state.value
        assertSame(source.contents, ready.contents); assertTrue(controller.canSelect(ready, "one"))
        assertFalse(controller.canSelect(ready, "external")); assertFalse(controller.canSelect(ready, "invented"))
        controller.close(); assertFalse(controller.canSelect(ready, "one"))
    }

    @Test fun failureIsNotAnEmptySuccessAndRetryUsesOnlyTheCurrentState() = runTest {
        val source = Source().apply { load = { throw ContentsLoadException(ContentsLoadFailure.LIMIT) } }
        val controller = ContentsController(this); controller.open(source) { true }; runCurrent()
        val failed = controller.state.value; assertEquals(ContentsLoadFailure.LIMIT, failed.failure); assertNull(failed.contents)
        source.load = { source.contents }; controller.retry(failed); runCurrent()
        val ready = controller.state.value; assertNull(ready.failure); assertTrue(controller.canSelect(ready, "one"))
        controller.retry(failed); assertSame(ready, controller.state.value); controller.close()
    }

    @Test fun oldStateCannotSelectAfterReloadOrSameSourceReopen() = runTest {
        val source = Source(); val controller = ContentsController(this)
        controller.open(source) { true }; runCurrent(); val old = controller.state.value
        controller.close(); controller.open(source) { true }; runCurrent()
        assertFalse(controller.canSelect(old, "one")); assertTrue(controller.canSelect(controller.state.value, "one"))
        controller.close(old.request); assertNotNull(controller.state.value.request); controller.close()
    }

    @Test fun retirementBeforeQueuedLoadPreventsTheFirstSourceCall() = runTest {
        var active = true; val source = Source(); val controller = ContentsController(this)
        controller.open(source) { active }; active = false; runCurrent()
        assertEquals(0, source.calls); assertNull(controller.state.value.request)
    }

    @Test fun lateNonCooperativeResultCannotReplaceTheNextRequest() = runTest {
        val gate = CompletableDeferred<BookContents>()
        val source = Source().apply { load = { withContext(NonCancellable) { gate.await() } } }
        val controller = ContentsController(this); controller.open(source) { true }; runCurrent()
        controller.close(); val replacement = Source(); controller.open(replacement) { true }; runCurrent()
        val ready = controller.state.value; gate.complete(source.contents); runCurrent()
        assertSame(ready, controller.state.value); controller.close()
    }

    @Test fun closingCancelsTheRequestAndCancellationIsNeverShownAsSourceFailure() = runTest {
        var released = false
        val source = Source().apply { load = { try { awaitCancellation() } finally { released = true } } }
        val controller = ContentsController(this); controller.open(source) { true }; runCurrent()
        controller.close(); runCurrent(); assertTrue(released); assertNull(controller.state.value.request)
        assertNull(controller.state.value.failure)
    }

    @Test fun ownershipLostDuringLoadDropsResultsAndDisablesOldSelection() = runTest {
        var active = true; val gate = CompletableDeferred<BookContents>()
        val source = Source().apply { load = { gate.await() } }
        val controller = ContentsController(this); controller.open(source) { active }; runCurrent()
        active = false; gate.complete(source.contents); runCurrent(); assertNull(controller.state.value.request)
    }

    @Test fun sourceCancellationReleasesModalAdmissionWithoutClosingAReopenedRequest() = runTest {
        var modalOpen = false
        val controller = ContentsController(this) { modalOpen = it }
        val source = Source().apply { load = { throw CancellationException("Source retired") } }
        controller.open(source) { true }; val expired = controller.state.value.request
        assertTrue(modalOpen); runCurrent()
        assertFalse(modalOpen); assertNull(controller.state.value.request); assertNull(controller.state.value.failure)
        source.load = { source.contents }
        controller.open(source) { true }; runCurrent(); assertTrue(modalOpen)
        controller.close(expired); assertTrue(modalOpen)
        controller.close(); assertFalse(modalOpen)
    }
}

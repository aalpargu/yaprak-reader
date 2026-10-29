package com.yaprak.reader.feature

import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfPageLinksControllerTest {
    private class Source : PdfPageLinksSource {
        override var supported = true
        var load: suspend () -> List<PdfPageLink> = { listOf(PdfPageLink("one", 1, 3)) }
        var resolve: suspend () -> ReadingLocation = { ReadingLocation("target", "pdf") }
        val calls = mutableListOf<String>()
        override suspend fun load(pageIndex: Int): List<PdfPageLink> { calls += "load:$pageIndex"; return load() }
        override suspend fun resolve(pageIndex: Int, linkId: String): ReadingLocation { calls += "resolve:$pageIndex:$linkId"; return resolve() }
        override fun goTo(location: ReadingLocation): Boolean { calls += "go"; return true }
    }

    @Test fun `loading does not navigate and explicit one-shot selection closes after accepted jump`() = runTest {
        val source = Source(); val controller = PdfPageLinksController(this)
        assertTrue(controller.open(source, 0, { true }) { _, target -> source.goTo(target) })
        assertTrue(controller.state.value.loading); runCurrent()
        val ready = controller.state.value
        assertEquals(1, ready.links.size); assertEquals(listOf("load:0"), source.calls)
        controller.select(ready, "one"); controller.select(ready, "one")
        assertEquals("one", controller.state.value.resolvingId); runCurrent()
        assertFalse(controller.state.value.open)
        assertEquals(listOf("load:0", "resolve:0:one", "go"), source.calls)
    }

    @Test fun `unsupported and ineligible requests do no native work`() = runTest {
        val source = Source(); val controller = PdfPageLinksController(this)
        source.supported = false
        assertFalse(controller.open(source, 0, { true }) { _, _ -> true })
        source.supported = true
        assertFalse(controller.open(source, 0, { false }) { _, _ -> true })
        assertFalse(controller.open(source, -1, { true }) { _, _ -> true })
        runCurrent(); assertTrue(source.calls.isEmpty()); assertFalse(controller.state.value.open)
    }

    @Test fun `empty source is ready with no fabricated rows`() = runTest {
        val source = Source().apply { load = { emptyList() } }; val controller = PdfPageLinksController(this)
        controller.open(source, 0, { true }) { _, _ -> error("Must not navigate") }; runCurrent()
        assertTrue(controller.state.value.open); assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.failure); assertTrue(controller.state.value.links.isEmpty())
        controller.select(controller.state.value, "invented"); runCurrent()
        assertEquals(listOf("load:0"), source.calls); controller.close()
    }

    @Test fun `load failure retry and rejected navigation have recoverable truthful states`() = runTest {
        val source = Source().apply { load = { throw PdfPageLinksException(PdfPageLinksFailure.LIMIT) } }
        val controller = PdfPageLinksController(this)
        controller.open(source, 2, { true }) { _, _ -> false }; runCurrent()
        assertEquals(PdfPageLinksFailure.LIMIT, controller.state.value.failure)
        source.load = { listOf(PdfPageLink("one", 1, 3)) }
        controller.retry(controller.state.value); runCurrent()
        assertNull(controller.state.value.failure)
        controller.select(controller.state.value, "one"); runCurrent()
        assertEquals(PdfPageLinksFailure.NAVIGATION, controller.state.value.failure)
        assertTrue(controller.state.value.open); assertNull(controller.state.value.resolvingId)
        controller.close()
    }

    @Test fun `stale state and arbitrary link IDs never resolve`() = runTest {
        val source = Source(); val controller = PdfPageLinksController(this)
        controller.open(source, 0, { true }) { _, _ -> true }; runCurrent()
        val old = controller.state.value
        controller.retry(old); runCurrent()
        controller.select(old, "one"); controller.retry(old)
        controller.select(controller.state.value, "foreign"); runCurrent()
        assertEquals(listOf("load:0", "load:0"), source.calls); controller.close()
    }
    @Test fun `closing a noncooperative load keeps late content out of a reopened modal`() = runTest {
        val gate = CompletableDeferred<List<PdfPageLink>>()
        val oldSource = Source().apply { load = { withContext(NonCancellable) { gate.await() } } }
        val next = Source(); val controller = PdfPageLinksController(this)
        controller.open(oldSource, 0, { true }) { _, _ -> error("Old request") }; runCurrent()
        val expired = controller.state.value.request!!
        controller.close()
        controller.open(next, 1, { true }) { _, _ -> true }; runCurrent()
        val ready = controller.state.value
        gate.complete(listOf(PdfPageLink("late", 1, 2))); runCurrent()
        assertSame(ready, controller.state.value); assertFalse(controller.isCurrent(expired))
        controller.close(expired); assertSame(ready, controller.state.value); controller.close()
    }

    @Test fun `closing a noncooperative resolve never delivers a late jump`() = runTest {
        val gate = CompletableDeferred<ReadingLocation>()
        val source = Source().apply { resolve = { withContext(NonCancellable) { gate.await() } } }
        val controller = PdfPageLinksController(this); var jumped = false
        controller.open(source, 0, { true }) { _, _ -> jumped = true; true }; runCurrent()
        controller.select(controller.state.value, "one"); runCurrent()
        controller.close(); gate.complete(ReadingLocation("late", "pdf")); runCurrent()
        assertFalse(jumped); assertFalse(controller.state.value.open)
    }

    @Test fun `page or foreground ownership lost during resolve closes without navigation`() = runTest {
        val gate = CompletableDeferred<ReadingLocation>(); var active = true
        val source = Source().apply { resolve = { gate.await() } }
        val controller = PdfPageLinksController(this); var jumped = false
        controller.open(source, 0, { active }) { _, _ -> jumped = true; true }; runCurrent()
        controller.select(controller.state.value, "one"); runCurrent()
        active = false; gate.complete(ReadingLocation("late", "pdf")); runCurrent()
        assertFalse(jumped); assertFalse(controller.state.value.open)
    }

    @Test fun `native source exception is shown without swallowing cancellation`() = runTest {
        val source = Source().apply { resolve = { throw java.io.IOException("Unreadable") } }
        val controller = PdfPageLinksController(this)
        controller.open(source, 0, { true }) { _, _ -> error("Failed resolve") }; runCurrent()
        controller.select(controller.state.value, "one"); runCurrent()
        assertEquals(PdfPageLinksFailure.SOURCE, controller.state.value.failure)
        assertNull(controller.state.value.resolvingId); controller.close()
        var cancelled = false
        source.load = { try { awaitCancellation() } finally { cancelled = true } }
        controller.open(source, 0, { true }) { _, _ -> true }; runCurrent()
        controller.close(); runCurrent()
        assertTrue(cancelled); assertFalse(controller.state.value.open)
    }

    @Test fun `ownership loss before queued native work prevents the first call`() = runTest {
        var active = true; val source = Source(); val controller = PdfPageLinksController(this)
        controller.open(source, 0, { active }) { _, _ -> error("Inactive") }
        active = false; runCurrent()
        assertTrue(source.calls.isEmpty()); assertFalse(controller.state.value.open)
    }
}

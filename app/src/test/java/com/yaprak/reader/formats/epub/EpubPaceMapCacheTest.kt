package com.yaprak.reader.formats.epub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpubPaceMapCacheTest {
    private fun mapping() = checkNotNull(EpubPaceMap.create(listOf("one.xhtml", "two.xhtml"), listOf(0.0, .25)))

    @Test fun `independent simultaneous collectors share one catalogue read`() = runTest {
        var calls = 0
        val gate = CompletableDeferred<EpubPaceMap?>()
        val cache = EpubPaceMapCache({ true }) { calls++; gate.await() }
        val first = async { cache.get() }
        val second = async { cache.get() }
        runCurrent(); assertEquals(1, calls)
        val expected = mapping(); gate.complete(expected)
        assertSame(expected, first.await()); assertSame(expected, second.await())
        assertSame(expected, cache.get()); assertEquals(1, calls)
    }

    @Test fun `completed unavailable catalogue is not read repeatedly`() = runTest {
        var calls = 0
        val cache = EpubPaceMapCache({ true }) { calls++; null }
        repeat(3) { assertNull(cache.get()) }
        assertEquals(1, calls)
    }

    @Test fun `cancelled collector releases lookup and does not cache a partial result`() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val expected = mapping()
        val cache = EpubPaceMapCache({ true }) { if (++calls == 1) gate.await(); expected }
        val first = async { cache.get() }
        runCurrent(); assertEquals(1, calls)
        first.cancel(); first.join()
        assertSame(expected, cache.get()); assertEquals(2, calls)
    }

    @Test fun `retired owner cannot start IO or return previously cached mapping`() = runTest {
        var current = false
        var calls = 0
        val cache = EpubPaceMapCache({ current }) { calls++; mapping() }
        assertNull(cache.get()); assertEquals(0, calls)
        current = true; assertNotNull(cache.get()); assertEquals(1, calls)
        current = false; assertNull(cache.get()); assertEquals(1, calls)
    }

    @Test fun `owner retired during IO cannot publish its result`() = runTest {
        var current = true
        val gate = CompletableDeferred<EpubPaceMap?>()
        val cache = EpubPaceMapCache({ current }) { gate.await() }
        val lookup = async { cache.get() }
        runCurrent(); current = false; gate.complete(mapping())
        assertNull(lookup.await()); assertNull(cache.get())
    }

    @Test fun `unexpected loader failure remains a failure and is not memoized as unavailable`() = runTest {
        var calls = 0
        val expected = mapping()
        val cache = EpubPaceMapCache({ true }) {
            if (++calls == 1) throw IllegalStateException("controlled failure")
            expected
        }
        try { cache.get(); fail("Loader failure must propagate") } catch (_: IllegalStateException) { }
        assertSame(expected, cache.get()); assertEquals(2, calls)
    }
}

package com.yaprak.reader.feature

import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.statistics.*
import com.yaprak.reader.feature.reader.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDurationControllerTest {
    private val book = Book("book", "Book", null, BookFormat.EPUB, "b.epub", "b.epub", 1, "a".repeat(64), 0)
    private val wall = Instant.parse("2026-10-05T09:00:00Z").toEpochMilli()
    private class Storage : ReadingDurationRepository {
        val stored = mutableMapOf<String, ReadingDurationReceipt>()
        var save: suspend (ReadingDurationReceipt) -> Unit = {}
        var attempts = 0
        override fun observe() = flowOf(ReadingDurationSnapshot(emptyList(), emptyList(), 0))
        override suspend fun record(receipt: ReadingDurationReceipt) {
            attempts++; save(receipt); validateReadingDurationReceipt(receipt)
            val old = stored[receipt.id]
            if (old == null || receipt.elapsedMillis >= old.elapsedMillis) stored[receipt.id] = receipt
        }
    }

    @Test fun `normal flush waits for persistence and cumulative checkpoints never double count`() = runTest {
        val storage = Storage(); val gate = CompletableDeferred<Unit>(); storage.save = { gate.await() }
        val clock = ReadingDurationClock { DurationMoment(testScheduler.currentTime, wall + testScheduler.currentTime, "UTC") }
        val controller = ReaderDurationController(storage, clock, backgroundScope)
        controller.claim("one"); controller.update("one", book, true)
        advanceTimeBy(10_001); runCurrent()
        controller.suspend("one")
        val flush = async { controller.flush() }; runCurrent(); assertFalse(flush.isCompleted)
        gate.complete(Unit); runCurrent()
        assertTrue(flush.await()); assertEquals(10_001L, storage.stored.values.sumOf { it.elapsedMillis })
        assertEquals(1, storage.stored.size); assertTrue(storage.attempts >= 1)
    }

    @Test fun `write failure stops measurement synchronously and retry retains pending cumulative receipt`() = runTest {
        val storage = Storage(); storage.save = { throw ReadingDurationException(ReadingDurationFailure.SOURCE) }
        val controller = ReaderDurationController(storage, ReadingDurationClock {
            DurationMoment(testScheduler.currentTime, wall + testScheduler.currentTime, "UTC") }, backgroundScope)
        controller.claim("one"); controller.update("one", book, true)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(ReadingDurationFailure.SOURCE, controller.failure.value)
        advanceTimeBy(5000); runCurrent()
        assertFalse(controller.flush()); assertTrue(storage.stored.isEmpty())
        storage.save = {}; controller.retry(); runCurrent()
        assertTrue(controller.flush()); assertEquals(10_000L, storage.stored.values.sumOf { it.elapsedMillis })
        advanceTimeBy(2000); runCurrent()
        assertEquals(10_000L, storage.stored.values.sumOf { it.elapsedMillis })
        controller.claim("one"); controller.update("one", book, true); advanceTimeBy(1000); controller.suspend("one"); runCurrent()
        assertTrue(controller.flush()); assertEquals(11_000L, storage.stored.values.sumOf { it.elapsedMillis })
    }

    @Test fun `old activity release cannot stop successor owner`() = runTest {
        val storage = Storage()
        val controller = ReaderDurationController(storage, ReadingDurationClock {
            DurationMoment(testScheduler.currentTime, wall + testScheduler.currentTime, "UTC") }, backgroundScope)
        controller.claim("old"); controller.update("old", book, true); advanceTimeBy(1000)
        controller.claim("new"); controller.update("new", book, true)
        controller.update("old", book, false); controller.release("old"); advanceTimeBy(2000)
        controller.suspend("old"); controller.suspend("new"); runCurrent()
        assertTrue(controller.flush()); assertEquals(3000L, storage.stored.values.sumOf { it.elapsedMillis })
        assertEquals(2, storage.stored.size)
    }

    @Test fun `bounded backlog retains overflow final receipt and explicit retry saves it`() = runTest {
        val storage = Storage(); val gate = CompletableDeferred<Unit>(); storage.save = { gate.await() }
        val controller = ReaderDurationController(storage, ReadingDurationClock {
            DurationMoment(testScheduler.currentTime, wall + testScheduler.currentTime, "UTC") }, backgroundScope)
        repeat(65) {
            controller.claim("one"); controller.update("one", book, true); advanceTimeBy(100); controller.suspend("one"); runCurrent()
        }
        assertEquals(ReadingDurationFailure.LIMIT, controller.failure.value)
        assertFalse(controller.flush()); assertTrue(storage.stored.isEmpty())
        gate.complete(Unit); controller.retry(); runCurrent()
        assertTrue(controller.flush()); assertEquals(65, storage.stored.size)
        assertEquals(6500L, storage.stored.values.sumOf { it.elapsedMillis })
    }

    @Test fun `scope cancellation never claims queued receipt persisted`() = runTest {
        val storage = Storage(); storage.save = { awaitCancellation() }
        val job = SupervisorJob(); val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        val controller = ReaderDurationController(storage, ReadingDurationClock {
            DurationMoment(testScheduler.currentTime, wall + testScheduler.currentTime, "UTC") }, scope)
        controller.claim("one"); controller.update("one", book, true); advanceTimeBy(1000); controller.suspend("one"); runCurrent()
        job.cancel(); runCurrent(); assertTrue(storage.stored.isEmpty())
    }
}

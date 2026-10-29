package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.feature.reader.ReaderViewModel
import com.yaprak.reader.reader.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSnapshotQueueTest {
    private class Session(format: BookFormat) : ReaderSession {
        override val book = Book("book", "Book", null, format, "book", "book", 1, "a".repeat(64), 1)
        override val resume: ReadingLocation? = null
        var closed = false
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface = error("No surface")
        override fun close() { closed = true }
    }
    private class Storage : ReadingStateRepository {
        val started = mutableListOf<ReadingSnapshot>()
        val written = mutableListOf<ReadingSnapshot>()
        var gate: CompletableDeferred<Unit>? = null
        var fail: (ReadingSnapshot) -> Boolean = { false }
        override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
        override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) {
            assertEquals("book", bookId); assertEquals("a".repeat(64), contentSha256)
            started += snapshot
            gate?.await()
            if (fail(snapshot)) throw IOException("Controlled write failure")
            written += snapshot
        }
    }
    private fun point(n: Int, spans: List<ObservedTextSpan> = emptyList()) =
        ReadingSnapshot(ReadingLocation("point-$n", "pdf"), spans)
    private val span = ObservedTextSpan("one.xhtml", "0/1", 0, 4, "b".repeat(64))
    private fun TestScope.withModel(format: BookFormat = BookFormat.PDF, immediate: Boolean = false,
        block: suspend TestScope.(ReaderViewModel, Storage, Session, ViewModelStore) -> Unit) = runTestBody(format, immediate, block)
    private fun TestScope.runTestBody(format: BookFormat, immediate: Boolean,
        block: suspend TestScope.(ReaderViewModel, Storage, Session, ViewModelStore) -> Unit): Job = launch {
        Dispatchers.setMain(if (immediate) UnconfinedTestDispatcher(testScheduler) else StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val session = Session(format)
        val storage = Storage()
        val model = ReaderViewModel("book", ReaderLoader { session }, storage)
        store.put("reader", model)
        try { runCurrent(); block(model, storage, session, store) }
        finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun tenThousandPendingPdfPositionsKeepImmediateLatestAndOnlyOneQueuedWrite() = runTest {
        withModel { model, storage, _, _ ->
            repeat(10_000) { model.observe(point(it)) }
            assertEquals(point(9999).location, model.latestLocation)
            assertTrue(storage.started.isEmpty())
            val flushed = async { model.flush() }
            advanceUntilIdle()
            assertTrue(flushed.await())
            assertEquals(listOf(point(9999)), storage.written)
        }.join()
    }

    @Test fun inFlightPdfWriteIsImmutableAndOneLatestPendingPointSurvivesSlowStorage() = runTest {
        withModel { model, storage, _, _ ->
            val gate = CompletableDeferred<Unit>(); storage.gate = gate
            model.observe(point(0)); runCurrent()
            assertEquals(listOf(point(0)), storage.started)
            repeat(10_000) { model.observe(point(it + 1)) }
            val flushed = async { model.flush() }; runCurrent()
            assertFalse(flushed.isCompleted); assertEquals(1, storage.started.size)
            gate.complete(Unit); advanceUntilIdle()
            assertTrue(flushed.await())
            assertEquals(listOf(point(0), point(10_000)), storage.written)
        }.join()
    }

    @Test fun immediateReceiverCannotLeaveAnAlreadyConsumedPdfSlotMutable() = runTest {
        withModel(immediate = true) { model, storage, _, _ ->
            model.observe(point(1)); model.observe(point(2)); model.observe(point(3))
            assertTrue(model.flush())
            assertEquals(listOf(point(1), point(2), point(3)), storage.written)
        }.join()
    }

    @Test fun flushSealsPdfPointAndCapturesItsOwnResultBeforeALaterFailure() = runTest {
        withModel { model, storage, _, _ ->
            val gate = CompletableDeferred<Unit>(); storage.gate = gate
            model.observe(point(0)); runCurrent()
            model.observe(point(1))
            val firstFlush = async { model.flush() }; runCurrent()
            storage.fail = { it == point(3) }
            model.observe(point(2)); model.observe(point(3))
            val secondFlush = async { model.flush() }; runCurrent()
            gate.complete(Unit); advanceUntilIdle()
            assertTrue(firstFlush.await()); assertFalse(secondFlush.await())
            assertEquals(listOf(point(0), point(1), point(3)), storage.started)
            assertEquals(listOf(point(0), point(1)), storage.written)
            assertTrue(model.saveFailed.value)
        }.join()
    }

    @Test fun allObservedSpansAndNonPdfLocationsRetainTheirOrder() = runTest {
        for (format in BookFormat.entries) {
            withModel(format) { model, storage, _, _ ->
                model.observe(point(1)); model.observe(point(2))
                model.observe(point(3, listOf(span))); model.observe(point(4, listOf(span.copy(startOffset = 4, endOffset = 8))))
                model.observe(point(5)); model.observe(point(6))
                val flushed = async { model.flush() }; advanceUntilIdle(); assertTrue(flushed.await())
                val expected = if (format == BookFormat.PDF) listOf(point(2), point(3, listOf(span)),
                    point(4, listOf(span.copy(startOffset = 4, endOffset = 8))), point(6))
                    else listOf(point(1), point(2), point(3, listOf(span)),
                        point(4, listOf(span.copy(startOffset = 4, endOffset = 8))), point(5), point(6))
                assertEquals(format.name, expected, storage.written)
            }.join()
        }
    }

    @Test fun explicitRetrySealsItsSnapshotAndRecoversAfterLatestPdfFailure() = runTest {
        withModel { model, storage, _, _ ->
            storage.fail = { true }
            model.observe(point(1)); model.observe(point(2)); advanceUntilIdle()
            assertTrue(model.saveFailed.value)
            storage.fail = { false }
            model.retrySave(); model.observe(point(3)); model.observe(point(4))
            val flushed = async { model.flush() }; advanceUntilIdle()
            assertTrue(flushed.await()); assertFalse(model.saveFailed.value)
            assertEquals(listOf(point(2), point(4)), storage.written)
        }.join()
    }

    @Test fun cancelledFlushWaiterDoesNotConsumeLaterPointsOrPreventNextFlush() = runTest {
        withModel { model, storage, _, _ ->
            val gate = CompletableDeferred<Unit>(); storage.gate = gate
            model.observe(point(1)); runCurrent()
            val abandoned = async { model.flush() }; runCurrent(); abandoned.cancel(); runCurrent()
            model.observe(point(2)); val next = async { model.flush() }; runCurrent()
            gate.complete(Unit); advanceUntilIdle()
            assertTrue(abandoned.isCancelled); assertTrue(next.await())
            assertEquals(listOf(point(1), point(2)), storage.written)
        }.join()
    }

    @Test fun clearingOwnerCancelsQueuedFlushAndClosesSessionWithoutLateWrites() = runTest {
        withModel { model, storage, session, store ->
            storage.gate = CompletableDeferred()
            model.observe(point(1)); runCurrent()
            model.observe(point(2)); val flush = async { model.flush() }; runCurrent()
            store.clear(); runCurrent()
            assertTrue(session.closed); assertTrue(flush.isCancelled)
            storage.gate!!.complete(Unit); advanceUntilIdle()
            assertTrue(storage.written.isEmpty())
            assertEquals(listOf(point(1)), storage.started)
        }.join()
    }

    @Test fun clearingBeforeTheWriterStartsStillCancelsAnAlreadyQueuedFlush() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val session = Session(BookFormat.PDF)
        val storage = Storage()
        try {
            val model = ReaderViewModel("book", ReaderLoader { session }, storage)
            store.put("reader", model)
            model.observe(point(1))
            val flushed = async(UnconfinedTestDispatcher(testScheduler)) { model.flush() }
            assertFalse(flushed.isCompleted)
            store.clear(); runCurrent()
            assertTrue(flushed.isCancelled)
            assertTrue(storage.started.isEmpty())
            // Loading was cancelled before acquiring the session.
            assertFalse(session.closed)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}

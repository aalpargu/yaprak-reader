package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private class Session : ReaderSession {
        override val book = Book("id", "Book", null, BookFormat.EPUB, "book.epub", "id.epub", 1, "hash", 0)
        override val resume = ReadingLocation("saved", "one.xhtml")
        var closed = false
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface = error("No UI in this test")
        override fun close() { closed = true }
    }
    private class Storage : ReadingStateRepository {
        val writes = mutableListOf<ReadingSnapshot>()
        var fail = false
        override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
        override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) {
            delay(10)
            if (fail) throw java.io.IOException("Test disk error")
            writes += snapshot
        }
    }

    @Test fun `exit flush waits for ordered writes and session closes with owner`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val session = Session()
            val storage = Storage()
            val model = ReaderViewModel("id", ReaderLoader { session }, storage)
            store.put("reader", model)
            runCurrent()
            assertEquals(session.resume, model.latestLocation)
            model.observe(ReadingSnapshot(ReadingLocation("first", "one.xhtml")))
            model.observe(ReadingSnapshot(ReadingLocation("jump", "three.xhtml")))
            val flushed = async { model.flush() }
            runCurrent()
            assertFalse(flushed.isCompleted)
            advanceUntilIdle()
            assertTrue(flushed.await())
            assertEquals(listOf("first", "jump"), storage.writes.map { it.location.locatorJson })
            store.clear()
            assertTrue(session.closed)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `disk failure is visible and explicit retry persists latest location`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val storage = Storage().apply { fail = true }
            val model = ReaderViewModel("id", ReaderLoader { Session() }, storage)
            store.put("reader", model)
            runCurrent()
            model.observe(ReadingSnapshot(ReadingLocation("latest", "three.xhtml")))
            advanceUntilIdle()
            assertTrue(model.saveFailed.value)
            val failedExit = async { model.flush() }
            advanceUntilIdle()
            assertFalse(failedExit.await())
            storage.fail = false
            model.retrySave()
            advanceUntilIdle()
            assertFalse(model.saveFailed.value)
            assertEquals("latest", storage.writes.single().location.locatorJson)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `missing file shows actionable error and retry can open`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var missing = true
            val model = ReaderViewModel("id", ReaderLoader {
                if (missing) throw ReaderOpenException(ReaderFailure.MISSING)
                Session()
            }, Storage())
            store.put("reader", model)
            runCurrent()
            assertEquals(ReaderState.Error(ReaderFailure.MISSING), model.state.value)
            missing = false
            model.retryOpen()
            runCurrent()
            assertTrue(model.state.value is ReaderState.Ready)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}

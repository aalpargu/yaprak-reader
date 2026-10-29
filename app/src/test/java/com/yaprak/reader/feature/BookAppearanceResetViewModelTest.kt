package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookAppearanceResetRepository
import com.yaprak.reader.feature.settings.BookAppearanceResetViewModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookAppearanceResetViewModelTest {
    private val book = Book("book", "Book", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)

    private class Repository : BookAppearanceResetRepository {
        var writes = 0
        var committed = false
        var fail = false
        var lastKey: Pair<String, String>? = null

        override suspend fun reset(bookId: String, contentSha256: String) {
            writes++
            lastKey = bookId to contentSha256
            delay(100)
            if (fail) throw IOException("write")
            committed = true
        }
    }

    @Test fun `completion follows commit and waits for reader acknowledgment`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val vm = BookAppearanceResetViewModel(repository).also { owner.put("reset", it) }
            vm.open()
            assertFalse(vm.state.value.open)
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(format = BookFormat.PDF)) }
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(sha256 = "")) }
            vm.bind(book)
            vm.bind(book)
            vm.open()
            assertTrue(vm.state.value.open)

            vm.confirm()
            assertTrue(vm.state.value.saving)
            assertFalse(vm.state.value.completed)
            vm.confirm()
            vm.dismiss()
            vm.open()
            assertTrue(vm.state.value.open)
            runCurrent()
            assertEquals(1, repository.writes)
            assertFalse(repository.committed)
            assertFalse(vm.state.value.completed)

            advanceUntilIdle()
            assertTrue(repository.committed)
            assertEquals(book.id to book.sha256, repository.lastKey)
            assertFalse(vm.state.value.open)
            assertFalse(vm.state.value.saving)
            assertTrue(vm.state.value.completed)
            vm.open()
            assertFalse(vm.state.value.open)
            vm.dismiss()
            assertTrue(vm.state.value.completed)
            vm.ackApplied()
            assertFalse(vm.state.value.completed)
            vm.open()
            assertTrue(vm.state.value.open)
            vm.dismiss()
            assertFalse(vm.state.value.open)
            assertEquals(1, repository.writes)
            assertThrows(IllegalStateException::class.java) { vm.bind(book.copy(id = "another")) }
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `dismiss does not write and failed reset remains open for retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { fail = true }
            val vm = BookAppearanceResetViewModel(repository).also { owner.put("reset", it) }
            vm.bind(book)
            vm.open()
            vm.dismiss()
            assertEquals(0, repository.writes)
            vm.open()
            vm.confirm()
            advanceUntilIdle()
            assertFalse(repository.committed)
            assertTrue(vm.state.value.open)
            assertTrue(vm.state.value.saveFailed)
            assertFalse(vm.state.value.saving)
            assertFalse(vm.state.value.completed)

            repository.fail = false
            vm.confirm()
            assertTrue(vm.state.value.saving)
            assertFalse(vm.state.value.saveFailed)
            advanceUntilIdle()
            assertEquals(2, repository.writes)
            assertTrue(repository.committed)
            assertFalse(vm.state.value.open)
            assertTrue(vm.state.value.completed)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `view model cancellation reaches repository without reporting success`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val gate = CompletableDeferred<Unit>()
            var cancelled = false
            val repository = object : BookAppearanceResetRepository {
                override suspend fun reset(bookId: String, contentSha256: String) {
                    try {
                        gate.await()
                    } catch (cause: CancellationException) {
                        cancelled = true
                        throw cause
                    }
                }
            }
            val vm = BookAppearanceResetViewModel(repository).also { owner.put("reset", it) }
            vm.bind(book)
            vm.open()
            vm.confirm()
            runCurrent()
            assertTrue(vm.state.value.saving)
            owner.clear()
            runCurrent()
            assertTrue(cancelled)
            assertFalse(vm.state.value.completed)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}

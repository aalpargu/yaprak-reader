package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookTextAlignmentRepository
import com.yaprak.reader.domain.settings.ReadingTextAlignment
import com.yaprak.reader.feature.settings.BookTextAlignmentViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookTextAlignmentViewModelTest {
    private val book = Book("book", "Book", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)

    @Test fun clearingOwnerCancelsPendingSaveWithoutPublishingFailureOrChangingRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { values.value = ReadingTextAlignment.JUSTIFY }
            val vm = BookTextAlignmentViewModel(repository).also { owner.put("alignment", it) }
            val txt = book.copy(format = BookFormat.TXT)
            vm.bind(txt); runCurrent(); vm.open(); vm.edit(ReadingTextAlignment.PUBLISHER); vm.save()
            runCurrent(); assertTrue(vm.state.value.saving); assertEquals(1, repository.writes)
            owner.clear(); advanceUntilIdle()
            assertEquals(ReadingTextAlignment.JUSTIFY, repository.values.value)
            assertEquals(ReadingTextAlignment.JUSTIFY, vm.state.value.saved)
            assertFalse(vm.state.value.saveFailed)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    private class Repository : BookTextAlignmentRepository {
        val values = MutableStateFlow<ReadingTextAlignment?>(null)
        var failRead = false
        var failWrite = false
        var writes = 0
        var observedBook: Pair<String, String>? = null
        override fun observe(bookId: String, contentSha256: String): Flow<ReadingTextAlignment?> = flow {
            observedBook = bookId to contentSha256
            if (failRead) throw IOException("read")
            emitAll(values)
        }
        override suspend fun save(bookId: String, contentSha256: String, alignment: ReadingTextAlignment?) {
            writes++
            delay(100)
            if (failWrite) throw IOException("write")
            values.value = alignment
        }
    }

    @Test fun `editor binds one reflowable book and keeps inherited alignment separate from explicit publisher alignment`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val vm = BookTextAlignmentViewModel(repository).also { owner.put("alignment", it) }
            vm.open()
            assertFalse(vm.state.value.open)
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(format = BookFormat.PDF)) }
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(sha256 = "")) }
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(format = BookFormat.CBZ)) }
            vm.bind(book)
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(format = BookFormat.CBZ)) }
            vm.bind(book)
            runCurrent()
            assertEquals(book.id to book.sha256, repository.observedBook)
            assertFalse(vm.state.value.loading)
            assertNull(vm.state.value.saved)

            vm.open()
            vm.edit(ReadingTextAlignment.PUBLISHER)
            assertNull(vm.state.value.saved)
            assertEquals(ReadingTextAlignment.PUBLISHER, vm.state.value.draft)
            vm.dismiss()
            assertNull(vm.state.value.draft)
            assertEquals(0, repository.writes)

            vm.open()
            vm.edit(ReadingTextAlignment.START)
            vm.save()
            vm.save()
            runCurrent()
            assertTrue(vm.state.value.saving)
            vm.dismiss()
            assertTrue(vm.state.value.open)
            assertEquals(1, repository.writes)
            advanceUntilIdle()
            assertEquals(ReadingTextAlignment.START, vm.state.value.saved)
            assertFalse(vm.state.value.open)

            vm.open()
            vm.edit(null)
            vm.save()
            advanceUntilIdle()
            assertNull(repository.values.value)
            assertNull(vm.state.value.saved)
            assertEquals(2, repository.writes)
            assertThrows(IllegalStateException::class.java) { vm.bind(book.copy(id = "other")) }
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `failed save preserves saved alignment and draft until retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { values.value = ReadingTextAlignment.JUSTIFY; failWrite = true }
            val vm = BookTextAlignmentViewModel(repository).also { owner.put("alignment", it) }
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(format = BookFormat.CBZ)) }
            vm.bind(book)
            runCurrent()
            vm.open()
            vm.edit(ReadingTextAlignment.PUBLISHER)
            vm.save()
            advanceUntilIdle()
            assertTrue(vm.state.value.saveFailed)
            assertTrue(vm.state.value.open)
            assertEquals(ReadingTextAlignment.JUSTIFY, vm.state.value.saved)
            assertEquals(ReadingTextAlignment.PUBLISHER, vm.state.value.draft)
            repository.failWrite = false
            vm.save()
            advanceUntilIdle()
            assertEquals(ReadingTextAlignment.PUBLISHER, vm.state.value.saved)
            assertFalse(vm.state.value.saveFailed)
            assertFalse(vm.state.value.open)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `read failure blocks writes and retry loads the persisted book alignment`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failRead = true; values.value = ReadingTextAlignment.START }
            val vm = BookTextAlignmentViewModel(repository).also { owner.put("alignment", it) }
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(format = BookFormat.CBZ)) }
            vm.bind(book)
            runCurrent()
            vm.open()
            vm.edit(ReadingTextAlignment.PUBLISHER)
            vm.save()
            assertTrue(vm.state.value.loadFailed)
            assertEquals(0, repository.writes)
            repository.failRead = false
            vm.reload()
            runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertEquals(ReadingTextAlignment.START, vm.state.value.saved)
            assertEquals(ReadingTextAlignment.START, vm.state.value.draft)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}


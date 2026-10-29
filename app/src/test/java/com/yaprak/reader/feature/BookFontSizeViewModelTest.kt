package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookFontSizeRepository
import com.yaprak.reader.feature.settings.BookFontSizeViewModel
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
class BookFontSizeViewModelTest {
    private val book = Book("book", "Book", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)

    private class Repository : BookFontSizeRepository {
        val values = MutableStateFlow<Int?>(null)
        var failRead = false
        var failWrite = false
        var writes = 0
        var observedBook: Pair<String, String>? = null

        override fun observe(bookId: String, contentSha256: String): Flow<Int?> = flow {
            observedBook = bookId to contentSha256
            if (failRead) throw IOException("read")
            emitAll(values)
        }

        override suspend fun save(bookId: String, contentSha256: String, fontSizePercent: Int?) {
            writes++
            delay(100)
            if (failWrite) throw IOException("write")
            values.value = fontSizePercent
        }
    }

    @Test fun `editor keeps inherited size separate from explicit 100 and discards cancelled draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val vm = BookFontSizeViewModel(repository).also { owner.put("fontSize", it) }
            vm.open()
            assertFalse(vm.state.value.open)
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(format = BookFormat.PDF)) }
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(sha256 = "")) }
            vm.bind(book)
            vm.bind(book)
            runCurrent()
            assertEquals(book.id to book.sha256, repository.observedBook)
            assertFalse(vm.state.value.loading)
            assertNull(vm.state.value.saved)

            vm.open()
            vm.edit(100)
            assertNull(vm.state.value.saved)
            assertEquals(100, vm.state.value.draft)
            vm.dismiss()
            assertNull(vm.state.value.draft)
            assertEquals(0, repository.writes)
            assertThrows(IllegalArgumentException::class.java) { vm.edit(101) }

            vm.open()
            vm.edit(100)
            vm.save()
            vm.save()
            runCurrent()
            assertTrue(vm.state.value.saving)
            vm.dismiss()
            assertTrue(vm.state.value.open)
            assertEquals(1, repository.writes)
            advanceUntilIdle()
            assertEquals(100, vm.state.value.saved)
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

    @Test fun `failed write leaves saved size intact and retries draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { values.value = 125; failWrite = true }
            val vm = BookFontSizeViewModel(repository).also { owner.put("fontSize", it) }
            vm.bind(book)
            runCurrent()
            vm.open()
            vm.edit(150)
            vm.save()
            advanceUntilIdle()
            assertTrue(vm.state.value.saveFailed)
            assertTrue(vm.state.value.open)
            assertEquals(125, vm.state.value.saved)
            assertEquals(150, vm.state.value.draft)
            repository.failWrite = false
            vm.save()
            advanceUntilIdle()
            assertEquals(150, vm.state.value.saved)
            assertFalse(vm.state.value.saveFailed)
            assertFalse(vm.state.value.open)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `read failure and invalid persisted size block writes until valid reload`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failRead = true; values.value = 135 }
            val vm = BookFontSizeViewModel(repository).also { owner.put("fontSize", it) }
            vm.bind(book)
            runCurrent()
            vm.open()
            vm.edit(145)
            vm.save()
            assertTrue(vm.state.value.loadFailed)
            assertEquals(0, repository.writes)
            repository.failRead = false
            repository.values.value = 132
            vm.reload()
            runCurrent()
            assertTrue(vm.state.value.loadFailed)
            repository.values.value = 135
            vm.reload()
            runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertEquals(135, vm.state.value.saved)
            assertEquals(135, vm.state.value.draft)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}

package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookSpacingChoice
import com.yaprak.reader.domain.settings.BookSpacingSettings
import com.yaprak.reader.domain.settings.BookSpacingRepository
import com.yaprak.reader.feature.settings.BookSpacingViewModel
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
class BookSpacingViewModelTest {
    private val book = Book("book", "Book", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)

    private class Repository : BookSpacingRepository {
        val values = MutableStateFlow<BookSpacingSettings>(BookSpacingSettings())
        var failRead = false
        var failWrite = false
        var writes = 0
        var observedBook: Pair<String, String>? = null

        override fun observe(bookId: String, contentSha256: String): Flow<BookSpacingSettings> = flow {
            observedBook = bookId to contentSha256
            if (failRead) throw IOException("read")
            emitAll(values)
        }

        override suspend fun save(bookId: String, contentSha256: String, choice: BookSpacingSettings) {
            writes++
            delay(100)
            if (failWrite) throw IOException("write")
            values.value = choice
        }
    }

    @Test fun `inherit publisher and explicit percentage remain distinct and cancel discards draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val vm = BookSpacingViewModel(repository).also { owner.put("line", it) }
            vm.open()
            assertFalse(vm.state.value.open)
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(format = BookFormat.PDF)) }
            assertThrows(IllegalArgumentException::class.java) { vm.bind(book.copy(sha256 = "")) }
            vm.bind(book)
            vm.bind(book)
            runCurrent()
            assertEquals(book.id to book.sha256, repository.observedBook)
            assertEquals(BookSpacingSettings(), vm.state.value.saved)

            vm.open()
            vm.edit(BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Publisher))
            assertEquals(BookSpacingSettings(), vm.state.value.saved)
            vm.dismiss()
            assertEquals(BookSpacingSettings(), vm.state.value.draft)
            assertEquals(0, repository.writes)
            assertThrows(IllegalArgumentException::class.java) { BookSpacingSettings(BookSpacingChoice.Custom(105), BookSpacingChoice.Custom(0)) }
            assertThrows(IllegalArgumentException::class.java) { BookSpacingSettings(BookSpacingChoice.Custom(210), BookSpacingChoice.Custom(0)) }

            vm.open()
            vm.edit(BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Publisher))
            vm.save()
            vm.save()
            runCurrent()
            assertTrue(vm.state.value.saving)
            vm.dismiss()
            assertTrue(vm.state.value.open)
            assertEquals(1, repository.writes)
            advanceUntilIdle()
            assertEquals(BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Publisher), vm.state.value.saved)
            assertFalse(vm.state.value.open)

            vm.open()
            vm.edit(BookSpacingSettings(BookSpacingChoice.Custom(100), BookSpacingChoice.Custom(0)))
            vm.save()
            advanceUntilIdle()
            assertEquals(BookSpacingSettings(BookSpacingChoice.Custom(100), BookSpacingChoice.Custom(0)), vm.state.value.saved)
            vm.open()
            vm.edit(BookSpacingSettings())
            vm.save()
            advanceUntilIdle()
            assertEquals(BookSpacingSettings(), repository.values.value)
            assertEquals(3, repository.writes)
            assertThrows(IllegalStateException::class.java) { vm.bind(book.copy(id = "other")) }
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `failed write preserves saved choice and draft for retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply {
                values.value = BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Publisher)
                failWrite = true
            }
            val vm = BookSpacingViewModel(repository).also { owner.put("line", it) }
            vm.bind(book)
            runCurrent()
            vm.open()
            vm.edit(BookSpacingSettings(BookSpacingChoice.Custom(170), BookSpacingChoice.Custom(0)))
            vm.save()
            advanceUntilIdle()
            assertTrue(vm.state.value.saveFailed)
            assertTrue(vm.state.value.open)
            assertEquals(BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Publisher), vm.state.value.saved)
            assertEquals(BookSpacingSettings(BookSpacingChoice.Custom(170), BookSpacingChoice.Custom(0)), vm.state.value.draft)
            repository.failWrite = false
            vm.save()
            advanceUntilIdle()
            assertEquals(BookSpacingSettings(BookSpacingChoice.Custom(170), BookSpacingChoice.Custom(0)), vm.state.value.saved)
            assertFalse(vm.state.value.open)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `read failure blocks save until reload and live change does not erase open draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failRead = true }
            val vm = BookSpacingViewModel(repository).also { owner.put("line", it) }
            vm.bind(book)
            runCurrent()
            vm.open()
            vm.edit(BookSpacingSettings(BookSpacingChoice.Custom(150), BookSpacingChoice.Custom(0)))
            vm.save()
            assertTrue(vm.state.value.loadFailed)
            assertEquals(0, repository.writes)
            repository.failRead = false
            repository.values.value = BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Publisher)
            vm.reload()
            runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertEquals(BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Publisher), vm.state.value.saved)
            vm.edit(BookSpacingSettings(BookSpacingChoice.Custom(180), BookSpacingChoice.Custom(0)))
            repository.values.value = BookSpacingSettings(BookSpacingChoice.Custom(160), BookSpacingChoice.Custom(0))
            runCurrent()
            assertEquals(BookSpacingSettings(BookSpacingChoice.Custom(160), BookSpacingChoice.Custom(0)), vm.state.value.saved)
            assertEquals(BookSpacingSettings(BookSpacingChoice.Custom(180), BookSpacingChoice.Custom(0)), vm.state.value.draft)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}

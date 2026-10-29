package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookFontRepository
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.feature.settings.BookFontViewModel
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
class BookFontViewModelTest {
    private val book = Book("book", "Book", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)

    private class Repository : BookFontRepository {
        val values = MutableStateFlow<ReadingFont?>(null)
        var failRead = false
        var failWrite = false
        var writes = 0
        var observedBook: Pair<String, String>? = null
        override fun observe(bookId: String, contentSha256: String): Flow<ReadingFont?> = flow {
            observedBook = bookId to contentSha256
            if (failRead) throw IOException("read")
            emitAll(values)
        }
        override suspend fun save(bookId: String, contentSha256: String, font: ReadingFont?) {
            writes++
            delay(100)
            if (failWrite) throw IOException("write")
            values.value = font
        }
    }

    @Test fun `editor binds one EPUB and keeps inherited font separate from explicit publisher font`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val vm = BookFontViewModel(repository).also { owner.put("font", it) }
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
            vm.edit(ReadingFont.ORIGINAL)
            assertNull(vm.state.value.saved)
            assertEquals(ReadingFont.ORIGINAL, vm.state.value.draft)
            vm.dismiss()
            assertNull(vm.state.value.draft)
            assertEquals(0, repository.writes)

            vm.open()
            vm.edit(ReadingFont.SERIF)
            vm.save()
            vm.save()
            runCurrent()
            assertTrue(vm.state.value.saving)
            vm.dismiss()
            assertTrue(vm.state.value.open)
            assertEquals(1, repository.writes)
            advanceUntilIdle()
            assertEquals(ReadingFont.SERIF, vm.state.value.saved)
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

    @Test fun `failed save preserves saved font and draft until retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { values.value = ReadingFont.SANS_SERIF; failWrite = true }
            val vm = BookFontViewModel(repository).also { owner.put("font", it) }
            vm.bind(book)
            runCurrent()
            vm.open()
            vm.edit(ReadingFont.ORIGINAL)
            vm.save()
            advanceUntilIdle()
            assertTrue(vm.state.value.saveFailed)
            assertTrue(vm.state.value.open)
            assertEquals(ReadingFont.SANS_SERIF, vm.state.value.saved)
            assertEquals(ReadingFont.ORIGINAL, vm.state.value.draft)
            repository.failWrite = false
            vm.save()
            advanceUntilIdle()
            assertEquals(ReadingFont.ORIGINAL, vm.state.value.saved)
            assertFalse(vm.state.value.saveFailed)
            assertFalse(vm.state.value.open)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `read failure blocks writes and retry loads the persisted book font`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failRead = true; values.value = ReadingFont.SERIF }
            val vm = BookFontViewModel(repository).also { owner.put("font", it) }
            vm.bind(book)
            runCurrent()
            vm.open()
            vm.edit(ReadingFont.ORIGINAL)
            vm.save()
            assertTrue(vm.state.value.loadFailed)
            assertEquals(0, repository.writes)
            repository.failRead = false
            vm.reload()
            runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertEquals(ReadingFont.SERIF, vm.state.value.saved)
            assertEquals(ReadingFont.SERIF, vm.state.value.draft)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}


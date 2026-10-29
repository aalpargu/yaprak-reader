package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookThemeRepository
import com.yaprak.reader.domain.settings.ReadingTheme
import com.yaprak.reader.feature.settings.BookThemeViewModel
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
class BookThemeViewModelTest {
    @Test fun amoledBookDraftCanCancelRetrySaveAndReturnToInheritance() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val model = BookThemeViewModel(repository).also { owner.put("amoled", it) }
            model.bind(book); runCurrent(); model.open(); model.edit(ReadingTheme.AMOLED)
            model.dismiss(); assertEquals(0, repository.writes); assertNull(repository.values.value)
            model.open(); model.edit(ReadingTheme.AMOLED); repository.failWrite = true
            model.save(); advanceUntilIdle()
            assertTrue(model.state.value.saveFailed); assertEquals(ReadingTheme.AMOLED, model.state.value.draft)
            assertNull(repository.values.value)
            repository.failWrite = false; model.save(); advanceUntilIdle()
            assertEquals(ReadingTheme.AMOLED, repository.values.value)
            model.open(); model.edit(null); model.save(); advanceUntilIdle()
            assertNull(repository.values.value)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    private val book = Book("book", "Book", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)

    private class Repository : BookThemeRepository {
        val values = MutableStateFlow<ReadingTheme?>(null)
        var failRead = false
        var failWrite = false
        var writes = 0
        var observedBook: Pair<String, String>? = null
        override fun observe(bookId: String, contentSha256: String): Flow<ReadingTheme?> = flow {
            observedBook = bookId to contentSha256
            if (failRead) throw IOException("read")
            emitAll(values)
        }
        override suspend fun save(bookId: String, contentSha256: String, theme: ReadingTheme?) {
            writes++
            delay(100)
            if (failWrite) throw IOException("write")
            values.value = theme
        }
    }

    @Test fun `editor binds one EPUB and keeps inherited theme separate from explicit system`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val vm = BookThemeViewModel(repository).also { owner.put("theme", it) }
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
            vm.edit(ReadingTheme.SYSTEM)
            assertNull(vm.state.value.saved)
            assertEquals(ReadingTheme.SYSTEM, vm.state.value.draft)
            vm.dismiss()
            assertNull(vm.state.value.draft)
            assertEquals(0, repository.writes)

            vm.open()
            vm.edit(ReadingTheme.SEPIA)
            vm.save()
            vm.save()
            runCurrent()
            assertTrue(vm.state.value.saving)
            vm.dismiss()
            assertTrue(vm.state.value.open)
            assertEquals(1, repository.writes)
            advanceUntilIdle()
            assertEquals(ReadingTheme.SEPIA, vm.state.value.saved)
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

    @Test fun `failed save preserves saved theme and draft until retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { values.value = ReadingTheme.DARK; failWrite = true }
            val vm = BookThemeViewModel(repository).also { owner.put("theme", it) }
            vm.bind(book)
            runCurrent()
            vm.open()
            vm.edit(ReadingTheme.LIGHT)
            vm.save()
            advanceUntilIdle()
            assertTrue(vm.state.value.saveFailed)
            assertTrue(vm.state.value.open)
            assertEquals(ReadingTheme.DARK, vm.state.value.saved)
            assertEquals(ReadingTheme.LIGHT, vm.state.value.draft)
            repository.failWrite = false
            vm.save()
            advanceUntilIdle()
            assertEquals(ReadingTheme.LIGHT, vm.state.value.saved)
            assertFalse(vm.state.value.saveFailed)
            assertFalse(vm.state.value.open)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `read failure blocks writes and retry loads the persisted book theme`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failRead = true; values.value = ReadingTheme.SEPIA }
            val vm = BookThemeViewModel(repository).also { owner.put("theme", it) }
            vm.bind(book)
            runCurrent()
            vm.open()
            vm.edit(ReadingTheme.LIGHT)
            vm.save()
            assertTrue(vm.state.value.loadFailed)
            assertEquals(0, repository.writes)
            repository.failRead = false
            vm.reload()
            runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertEquals(ReadingTheme.SEPIA, vm.state.value.saved)
            assertEquals(ReadingTheme.SEPIA, vm.state.value.draft)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}

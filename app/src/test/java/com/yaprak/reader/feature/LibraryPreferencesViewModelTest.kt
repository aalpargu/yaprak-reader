package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookImporter
import com.yaprak.reader.domain.library.ImportResult
import com.yaprak.reader.domain.library.LibraryPreferences
import com.yaprak.reader.domain.library.LibraryPreferencesRepository
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.feature.library.LibraryFilter
import com.yaprak.reader.feature.library.LibraryLayout
import com.yaprak.reader.feature.library.LibraryPreferencesStatus
import com.yaprak.reader.feature.library.LibrarySort
import com.yaprak.reader.feature.library.LibraryState
import com.yaprak.reader.feature.library.LibraryViewModel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPreferencesViewModelTest {
    private val book = Book("1", "Ada", "Deniz", BookFormat.EPUB, "1.epub", "1.epub", 1, "hash", 1)
    private val books = object : LibraryRepository {
        override fun observeBooks(): Flow<List<Book>> = flowOf(listOf(book))
        override suspend fun findById(id: String): Book? = error("Preferences must not find a book")
        override suspend fun findByHash(sha256: String): Book? = error("Preferences must not find a book")
        override suspend fun storedFileNames(): Set<String> = error("Preferences must not inspect files")
        override suspend fun add(book: Book) = error("Preferences must not add a book")
        override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?): Boolean =
            error("Preferences must not edit a book")
    }
    private val importer = object : BookImporter {
        override suspend fun import(documentUri: String): ImportResult = error("Preferences must not import")
    }

    private class PreferencesStore(initial: LibraryPreferences = LibraryPreferences()) : LibraryPreferencesRepository {
        var persisted = initial
        var readAction: suspend () -> LibraryPreferences = { persisted }
        var reads = 0
        val writes = mutableListOf<Write>()
        var activeWrites = 0
        var maximumActiveWrites = 0
        override val libraryPreferences: Flow<LibraryPreferences> = flow {
            reads++
            emit(readAction())
        }
        override suspend fun saveLibraryPreferences(preferences: LibraryPreferences) {
            val write = Write(preferences)
            writes += write
            activeWrites++
            maximumActiveWrites = maxOf(maximumActiveWrites, activeWrites)
            try {
                write.completion.await()
                persisted = preferences
            } finally {
                activeWrites--
            }
        }
    }

    private class Write(val preferences: LibraryPreferences) {
        val completion = CompletableDeferred<Unit>()
    }

    private suspend fun TestScope.withModel(
        store: PreferencesStore? = null,
        savedState: SavedStateHandle = SavedStateHandle(),
        action: suspend (LibraryViewModel, ViewModelStore) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val vm = LibraryViewModel(books, importer, savedState, dispatcher, libraryPreferences = store)
                .also { owner.put("library", it) }
            action(vm, owner)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `fresh screen loads persisted choices without writing or persisting search and filter`() = runTest {
        val disk = LibraryPreferences(LibraryLayout.LIST, LibrarySort.AUTHOR)
        val store = PreferencesStore(disk)
        val read = CompletableDeferred<LibraryPreferences>()
        store.readAction = { read.await() }
        val saved = SavedStateHandle(mapOf("libraryQuery" to "deniz", "libraryFilter" to "EPUB"))
        withModel(store, saved) { vm, _ ->
            backgroundScope.launch { vm.library.collect() }
            assertEquals(LibraryPreferencesStatus.Loading, vm.preferenceStatus.value)
            runCurrent()
            assertTrue(vm.library.value is LibraryState.Ready)
            assertTrue(store.writes.isEmpty())
            read.complete(disk)
            runCurrent()
            assertEquals(LibraryLayout.LIST, vm.layout.value)
            assertEquals(LibrarySort.AUTHOR, vm.sort.value)
            assertEquals(LibraryPreferencesStatus.Ready, vm.preferenceStatus.value)
            assertEquals("deniz", vm.query.value)
            assertEquals(LibraryFilter.EPUB, vm.filter.value)
            assertEquals("LIST", saved.get<String>("libraryLayout"))
            assertEquals("AUTHOR", saved.get<String>("librarySort"))
            vm.search("ada")
            vm.chooseFilter(LibraryFilter.ALL)
            runCurrent()
            assertTrue(store.writes.isEmpty())
            assertEquals(disk, store.persisted)
        }
    }

    @Test fun `valid restored choices and newer selection win over delayed disk values`() = runTest {
        val store = PreferencesStore()
        val read = CompletableDeferred<LibraryPreferences>()
        store.readAction = { read.await() }
        val saved = SavedStateHandle(mapOf("libraryLayout" to "LIST", "librarySort" to "TITLE"))
        withModel(store, saved) { vm, _ ->
            runCurrent()
            assertEquals(LibraryLayout.LIST, vm.layout.value)
            assertEquals(LibrarySort.TITLE, vm.sort.value)
            vm.chooseSort(LibrarySort.AUTHOR)
            assertEquals(LibrarySort.AUTHOR, vm.sort.value)
            assertEquals("AUTHOR", saved.get<String>("librarySort"))
            assertTrue(store.writes.isEmpty())
            read.complete(LibraryPreferences(LibraryLayout.GRID, LibrarySort.LAST_READ))
            runCurrent()
            val latest = LibraryPreferences(LibraryLayout.LIST, LibrarySort.AUTHOR)
            assertEquals(latest, store.writes.single().preferences)
            assertEquals(LibraryPreferencesStatus.Saving, vm.preferenceStatus.value)
            store.writes.single().completion.complete(Unit)
            runCurrent()
            assertEquals(latest, store.persisted)
            assertEquals(LibraryPreferencesStatus.Ready, vm.preferenceStatus.value)
        }
    }

    @Test fun `only a valid restored field overrides persisted preferences`() = runTest {
        val store = PreferencesStore(LibraryPreferences(LibraryLayout.LIST, LibrarySort.AUTHOR))
        val saved = SavedStateHandle(mapOf("librarySort" to "TITLE", "libraryLayout" to "REMOVED_LAYOUT"))
        withModel(store, saved) { vm, _ ->
            runCurrent()
            assertEquals(LibraryLayout.LIST, vm.layout.value)
            assertEquals(LibrarySort.TITLE, vm.sort.value)
            val merged = LibraryPreferences(LibraryLayout.LIST, LibrarySort.TITLE)
            assertEquals(merged, store.writes.single().preferences)
            store.writes.single().completion.complete(Unit)
            runCurrent()
            assertEquals(merged, store.persisted)
        }
    }

    @Test fun `explicit default choice before disk read also wins`() = runTest {
        val store = PreferencesStore()
        val read = CompletableDeferred<LibraryPreferences>()
        store.readAction = { read.await() }
        withModel(store) { vm, _ ->
            runCurrent()
            vm.chooseLayout(LibraryLayout.GRID)
            read.complete(LibraryPreferences(LibraryLayout.LIST, LibrarySort.TITLE))
            runCurrent()
            assertEquals(LibraryLayout.GRID, vm.layout.value)
            assertEquals(LibrarySort.TITLE, vm.sort.value)
            assertEquals(LibraryPreferences(LibraryLayout.GRID, LibrarySort.TITLE), store.writes.single().preferences)
            store.writes.single().completion.complete(Unit)
            runCurrent()
        }
    }

    @Test fun `rapid choices stay immediate and serialize only the final pending preference`() = runTest {
        val store = PreferencesStore()
        withModel(store) { vm, _ ->
            runCurrent()
            vm.chooseLayout(LibraryLayout.LIST)
            assertEquals(LibraryLayout.LIST, vm.layout.value)
            assertEquals(LibraryPreferencesStatus.Saving, vm.preferenceStatus.value)
            runCurrent()
            vm.chooseSort(LibrarySort.TITLE)
            vm.chooseSort(LibrarySort.LAST_READ)
            vm.chooseLayout(LibraryLayout.GRID)
            vm.chooseSort(LibrarySort.AUTHOR)
            vm.chooseLayout(LibraryLayout.LIST)
            assertEquals(LibrarySort.AUTHOR, vm.sort.value)
            assertEquals(LibraryLayout.LIST, vm.layout.value)
            runCurrent()
            assertEquals(1, store.writes.size)
            store.writes[0].completion.complete(Unit)
            runCurrent()
            val latest = LibraryPreferences(LibraryLayout.LIST, LibrarySort.AUTHOR)
            assertEquals(listOf(LibraryPreferences(LibraryLayout.LIST, LibrarySort.RECENT), latest),
                store.writes.map(Write::preferences))
            assertEquals(LibrarySort.AUTHOR, vm.sort.value)
            assertEquals(LibraryPreferencesStatus.Saving, vm.preferenceStatus.value)
            store.writes[1].completion.complete(Unit)
            runCurrent()
            assertEquals(latest, store.persisted)
            assertEquals(1, store.maximumActiveWrites)
            assertEquals(LibraryPreferencesStatus.Ready, vm.preferenceStatus.value)
        }
    }

    @Test fun `read failure never overwrites store and retry preserves a newer selection`() = runTest {
        val disk = LibraryPreferences(LibraryLayout.LIST, LibrarySort.AUTHOR)
        val store = PreferencesStore(disk)
        store.readAction = { throw IOException("Unreadable preferences") }
        withModel(store) { vm, _ ->
            backgroundScope.launch { vm.library.collect() }
            runCurrent()
            assertEquals(LibraryPreferencesStatus.LoadFailed, vm.preferenceStatus.value)
            assertTrue(vm.library.value is LibraryState.Ready)
            vm.chooseSort(LibrarySort.TITLE)
            runCurrent()
            assertEquals(LibraryPreferencesStatus.LoadFailed, vm.preferenceStatus.value)
            assertTrue(store.writes.isEmpty())
            assertEquals(disk, store.persisted)
            store.readAction = { disk }
            vm.retryPreferences()
            assertEquals(LibraryPreferencesStatus.Loading, vm.preferenceStatus.value)
            runCurrent()
            val latest = LibraryPreferences(LibraryLayout.LIST, LibrarySort.TITLE)
            assertEquals(LibraryLayout.LIST, vm.layout.value)
            assertEquals(LibrarySort.TITLE, vm.sort.value)
            assertEquals(latest, store.writes.single().preferences)
            assertEquals(2, store.reads)
            store.writes.single().completion.complete(Unit)
            runCurrent()
            assertEquals(latest, store.persisted)
            assertEquals(LibraryPreferencesStatus.Ready, vm.preferenceStatus.value)
        }
    }

    @Test fun `failed save keeps choices visible and retry saves the latest desired pair`() = runTest {
        val store = PreferencesStore()
        withModel(store) { vm, _ ->
            runCurrent()
            vm.chooseLayout(LibraryLayout.LIST)
            runCurrent()
            store.writes[0].completion.completeExceptionally(IOException("Write failed"))
            runCurrent()
            assertEquals(LibraryPreferencesStatus.SaveFailed, vm.preferenceStatus.value)
            assertEquals(LibraryLayout.LIST, vm.layout.value)
            assertEquals(LibraryPreferences(), store.persisted)
            vm.chooseSort(LibrarySort.TITLE)
            assertEquals(LibrarySort.TITLE, vm.sort.value)
            runCurrent()
            val latest = LibraryPreferences(LibraryLayout.LIST, LibrarySort.TITLE)
            assertEquals(latest, store.writes[1].preferences)
            store.writes[1].completion.completeExceptionally(IOException("Still unavailable"))
            runCurrent()
            assertEquals(LibraryPreferencesStatus.SaveFailed, vm.preferenceStatus.value)
            vm.retryPreferences()
            assertEquals(LibraryPreferencesStatus.Saving, vm.preferenceStatus.value)
            runCurrent()
            assertEquals(latest, store.writes[2].preferences)
            store.writes[2].completion.complete(Unit)
            runCurrent()
            assertEquals(latest, store.persisted)
            assertEquals(LibraryPreferencesStatus.Ready, vm.preferenceStatus.value)
            assertEquals(1, store.reads)
        }
    }

    @Test fun `older failed write cannot fail a newer queued selection`() = runTest {
        val store = PreferencesStore()
        withModel(store) { vm, _ ->
            runCurrent()
            vm.chooseLayout(LibraryLayout.LIST)
            runCurrent()
            vm.chooseSort(LibrarySort.AUTHOR)
            vm.chooseLayout(LibraryLayout.GRID)
            store.writes[0].completion.completeExceptionally(IOException("Old choice failed"))
            runCurrent()
            val latest = LibraryPreferences(LibraryLayout.GRID, LibrarySort.AUTHOR)
            assertEquals(latest, store.writes[1].preferences)
            assertEquals(LibraryPreferencesStatus.Saving, vm.preferenceStatus.value)
            assertEquals(LibrarySort.AUTHOR, vm.sort.value)
            store.writes[1].completion.complete(Unit)
            runCurrent()
            assertEquals(latest, store.persisted)
            assertEquals(LibraryPreferencesStatus.Ready, vm.preferenceStatus.value)
        }
    }

    @Test fun `returning to original choice during a failed write still persists the latest request`() = runTest {
        val store = PreferencesStore()
        withModel(store) { vm, _ ->
            runCurrent()
            vm.chooseLayout(LibraryLayout.LIST)
            runCurrent()
            vm.chooseLayout(LibraryLayout.GRID)
            store.writes[0].completion.completeExceptionally(IOException("Old choice failed"))
            runCurrent()
            assertEquals(LibraryPreferences(), store.writes[1].preferences)
            assertEquals(LibraryLayout.GRID, vm.layout.value)
            store.writes[1].completion.completeExceptionally(IOException("Latest choice also failed"))
            runCurrent()
            assertEquals(LibraryPreferencesStatus.SaveFailed, vm.preferenceStatus.value)
            vm.retryPreferences()
            runCurrent()
            assertEquals(LibraryPreferences(), store.writes[2].preferences)
            store.writes[2].completion.complete(Unit)
            runCurrent()
            assertEquals(LibraryPreferences(), store.persisted)
            assertEquals(LibraryPreferencesStatus.Ready, vm.preferenceStatus.value)
        }
    }

    @Test fun `clearing ViewModel cancels a delayed read without failure or writes`() = runTest {
        val store = PreferencesStore()
        val read = CompletableDeferred<LibraryPreferences>()
        var cancelledRead = false
        store.readAction = {
            try { read.await() } finally { cancelledRead = true }
        }
        withModel(store) { vm, owner ->
            runCurrent()
            vm.chooseLayout(LibraryLayout.LIST)
            owner.clear()
            runCurrent()
            assertTrue(cancelledRead)
            assertEquals(LibraryPreferencesStatus.Loading, vm.preferenceStatus.value)
            assertTrue(store.writes.isEmpty())
        }
    }

    @Test fun `clearing ViewModel cancels a pending write without false success or failure`() = runTest {
        val store = PreferencesStore()
        withModel(store) { vm, owner ->
            runCurrent()
            vm.chooseLayout(LibraryLayout.LIST)
            runCurrent()
            assertEquals(1, store.activeWrites)
            owner.clear()
            runCurrent()
            assertEquals(0, store.activeWrites)
            assertEquals(LibraryPreferences(), store.persisted)
            assertEquals(LibraryPreferencesStatus.Saving, vm.preferenceStatus.value)
            assertEquals(1, store.writes.size)
        }
    }

    @Test fun `absent repository preserves SavedState selections and ready status`() = runTest {
        val saved = SavedStateHandle(mapOf("libraryLayout" to "LIST", "librarySort" to "TITLE"))
        withModel(savedState = saved) { vm, _ ->
            assertEquals(LibraryLayout.LIST, vm.layout.value)
            assertEquals(LibrarySort.TITLE, vm.sort.value)
            assertEquals(LibraryPreferencesStatus.Ready, vm.preferenceStatus.value)
            vm.chooseLayout(LibraryLayout.GRID)
            vm.chooseSort(LibrarySort.AUTHOR)
            assertEquals(LibraryLayout.GRID, vm.layout.value)
            assertEquals(LibrarySort.AUTHOR, vm.sort.value)
            assertEquals("GRID", saved.get<String>("libraryLayout"))
            assertEquals("AUTHOR", saved.get<String>("librarySort"))
            vm.retryPreferences()
            runCurrent()
            assertFalse(vm.preferenceStatus.value is LibraryPreferencesStatus.Loading)
        }
    }
}

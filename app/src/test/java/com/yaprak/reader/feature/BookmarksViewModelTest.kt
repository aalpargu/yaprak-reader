package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.bookmarks.*
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.bookmarks.BookmarkSort
import com.yaprak.reader.feature.bookmarks.BookmarksViewModel
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModelTest {
    private val target = BookmarkTarget("Chapter", ReadingLocation("locator", "one.xhtml"))
    private class Repository : BookmarkRepository {
        val entries = MutableStateFlow<List<Bookmark>>(emptyList())
        var calls = 0
        var fail = false
        var failRead = false
        var lastRenamedId: String? = null
        override fun observe(bookId: String, contentSha256: String) = flow {
            if (failRead) throw IOException()
            emitAll(entries)
        }
        override suspend fun add(bookId: String, contentSha256: String, target: BookmarkTarget) {
            calls++; delay(100); if (fail) throw IOException()
            entries.value = listOf(Bookmark("id", target, 1))
        }
        override suspend fun remove(bookId: String, contentSha256: String, bookmarkId: String) {
            calls++; delay(100); if (fail) throw IOException()
            entries.value = entries.value.filterNot { it.id == bookmarkId }
        }
        override suspend fun rename(bookId: String, contentSha256: String, bookmarkId: String, label: String) {
            calls++; lastRenamedId = bookmarkId; delay(100); if (fail) throw IOException()
            entries.value = entries.value.map { if (it.id == bookmarkId) it.copy(target = it.target.copy(label = label)) else it }
        }
    }
    @Test fun `failed add remains retryable and duplicate operations are suppressed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { fail = true }
            val vm = BookmarksViewModel("book", "hash", repository).also { owner.put("vm", it) }
            runCurrent(); vm.selectPosition(target); vm.add(); vm.add(); advanceUntilIdle()
            assertEquals(1, repository.calls)
            assertTrue(vm.state.value.entries.isEmpty())
            assertTrue(vm.state.value.canAdd)
            assertEquals("İşlem tamamlanamadı. Yeniden deneyebilirsin.", vm.state.value.message)
            repository.fail = false
            vm.add(); advanceUntilIdle()
            assertEquals(1, vm.state.value.entries.size)
            assertFalse(vm.state.value.canAdd)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun `deletion needs explicit confirmation and failed delete preserves entry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(Bookmark("id", target, 1)) }
            val vm = BookmarksViewModel("book", "hash", repository).also { owner.put("vm", it) }
            runCurrent(); vm.requestDelete("id"); vm.cancelDelete(); vm.delete(); runCurrent()
            assertEquals(0, repository.calls)
            repository.fail = true
            vm.requestDelete("id"); vm.delete(); advanceUntilIdle()
            assertNotNull(vm.state.value.pendingDelete)
            assertEquals(1, vm.state.value.entries.size)
            repository.fail = false
            vm.delete(); advanceUntilIdle()
            assertTrue(vm.state.value.entries.isEmpty())
            assertNull(vm.state.value.pendingDelete)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun `read error disables add until retry succeeds`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failRead = true }
            val vm = BookmarksViewModel("book", "hash", repository).also { owner.put("vm", it) }
            runCurrent(); vm.selectPosition(target); vm.add()
            assertTrue(vm.state.value.loadFailed)
            assertEquals(0, repository.calls)
            repository.failRead = false
            vm.reload(); runCurrent()
            assertTrue(vm.state.value.canAdd)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `rename validates draft preserves failed edit and updates only chosen duplicate title`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val other = Bookmark("other", target, 1)
            val chosen = Bookmark("chosen", target.copy(location = ReadingLocation("second", "two.xhtml")), 2)
            val repository = Repository().apply { entries.value = listOf(other, chosen) }
            val vm = BookmarksViewModel("book", "hash", repository).also { owner.put("vm", it) }
            runCurrent()
            vm.requestRename(chosen.id)
            vm.editRename("   "); vm.saveRename()
            vm.editRename("x".repeat(201)); vm.saveRename()
            assertEquals(0, repository.calls)
            vm.editRename("  Yeni ad  ")
            repository.fail = true
            vm.saveRename(); vm.saveRename(); runCurrent()
            assertTrue(vm.state.value.busy)
            assertEquals(1, repository.calls)
            vm.cancelRename()
            assertNotNull(vm.state.value.pendingRename)
            advanceUntilIdle()
            assertEquals("  Yeni ad  ", vm.state.value.renameDraft)
            assertEquals("Chapter", vm.state.value.entries[1].target.label)
            assertEquals("İşlem tamamlanamadı. Yeniden deneyebilirsin.", vm.state.value.message)
            repository.fail = false
            vm.saveRename(); advanceUntilIdle()
            assertEquals(chosen.id, repository.lastRenamedId)
            assertEquals("Chapter", vm.state.value.entries[0].target.label)
            assertEquals("Yeni ad", vm.state.value.entries[1].target.label)
            assertEquals(chosen.target.location, vm.state.value.entries[1].target.location)
            assertNull(vm.state.value.pendingRename)
            vm.requestRename(other.id); vm.editRename("Taslak"); vm.cancelRename()
            assertNull(vm.state.value.pendingRename)
            assertEquals("", vm.state.value.renameDraft)
            assertEquals(2, repository.calls)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `search is transient preserves reload and never writes repository`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(
                Bookmark("first", BookmarkTarget("IŞIK", ReadingLocation("first", "one.xhtml")), 2),
                Bookmark("second", BookmarkTarget("Sessiz", ReadingLocation("second", "two.xhtml")), 1),
            ) }
            val vm = BookmarksViewModel("book", "hash", repository).also { owner.put("vm", it) }
            runCurrent()
            vm.search("ışık")
            assertEquals("ışık", vm.state.value.query)
            assertEquals(listOf("first"), vm.state.value.visibleEntries.map { it.id })
            assertEquals(2, vm.state.value.entries.size)
            vm.reload(); runCurrent()
            assertEquals("ışık", vm.state.value.query)
            assertEquals(listOf("first"), vm.state.value.visibleEntries.map { it.id })
            repository.entries.value = repository.entries.value +
                Bookmark("third", BookmarkTarget("Işık devam", ReadingLocation("third", "three.xhtml")), 3)
            runCurrent()
            assertEquals(listOf("first", "third"), vm.state.value.visibleEntries.map { it.id })
            vm.clearSearch()
            assertEquals("", vm.state.value.query)
            assertEquals(3, vm.state.value.visibleEntries.size)
            vm.search("x".repeat(199) + "😀y")
            assertEquals("x".repeat(199), vm.state.value.query)
            vm.clearSession()
            assertEquals("", vm.state.value.query)
            assertEquals(0, repository.calls)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `filtered list does not change duplicate guard or ID based actions`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val hidden = Bookmark("hidden", target, 2)
            val visible = Bookmark("visible", BookmarkTarget("Işık", ReadingLocation("second", "two.xhtml")), 1)
            val repository = Repository().apply { entries.value = listOf(hidden, visible) }
            val vm = BookmarksViewModel("book", "hash", repository).also { owner.put("vm", it) }
            runCurrent(); vm.selectPosition(target); vm.search("ışık")
            assertEquals(listOf("visible"), vm.state.value.visibleEntries.map { it.id })
            assertFalse(vm.state.value.canAdd)
            vm.requestRename("hidden")
            assertEquals("hidden", vm.state.value.pendingRename?.id)
            vm.cancelRename()
            vm.requestDelete("visible")
            assertEquals("visible", vm.state.value.pendingDelete?.id)
            vm.clearSession()
            assertNull(vm.state.value.pendingDelete)
            assertEquals("", vm.state.value.query)
            assertEquals(0, repository.calls)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `sort applies after search survives reload and resets on close without writes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(
                Bookmark("z", BookmarkTarget("Işık Zeytin", ReadingLocation("z", "z.xhtml")), 3),
                Bookmark("other", BookmarkTarget("Sessiz", ReadingLocation("other", "other.xhtml")), 2),
                Bookmark("c", BookmarkTarget("Işık Çınar", ReadingLocation("c", "c.xhtml")), 1),
            ) }
            val vm = BookmarksViewModel("book", "hash", repository).also { owner.put("vm", it) }
            runCurrent()
            assertEquals(BookmarkSort.RECORD_ORDER, vm.state.value.sort)
            assertEquals(listOf("z", "other", "c"), vm.state.value.visibleEntries.map { it.id })
            vm.search("ışık")
            vm.setSort(BookmarkSort.TITLE_ASC)
            assertEquals(listOf("c", "z"), vm.state.value.visibleEntries.map { it.id })
            assertEquals(listOf("z", "other", "c"), vm.state.value.entries.map { it.id })
            vm.reload(); runCurrent()
            assertEquals("ışık", vm.state.value.query)
            assertEquals(BookmarkSort.TITLE_ASC, vm.state.value.sort)
            assertEquals(listOf("c", "z"), vm.state.value.visibleEntries.map { it.id })
            vm.clearSearch()
            assertEquals(listOf("c", "z", "other"), vm.state.value.visibleEntries.map { it.id })
            vm.clearSession()
            assertEquals("", vm.state.value.query)
            assertEquals(BookmarkSort.RECORD_ORDER, vm.state.value.sort)
            assertEquals(listOf("z", "other", "c"), vm.state.value.visibleEntries.map { it.id })
            assertEquals(0, repository.calls)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `book and content hash scopes do not share search state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val first = Bookmark("first", BookmarkTarget("Işık", ReadingLocation("one", "one.xhtml")), 1)
            val second = Bookmark("second", BookmarkTarget("Gece", ReadingLocation("two", "two.xhtml")), 2)
            val repository = ScopedRepository(mapOf(
                ("book" to "old-hash") to listOf(first),
                ("book" to "new-hash") to listOf(second),
            ))
            val old = BookmarksViewModel("book", "old-hash", repository).also { owner.put("old", it) }
            val newer = BookmarksViewModel("book", "new-hash", repository).also { owner.put("new", it) }
            runCurrent()
            old.search("ışık")
            assertEquals(listOf("first"), old.state.value.visibleEntries.map { it.id })
            assertEquals(listOf("second"), newer.state.value.visibleEntries.map { it.id })
            assertEquals("", newer.state.value.query)
            assertEquals(setOf("book" to "old-hash", "book" to "new-hash"), repository.observed.toSet())
            assertEquals(0, repository.writes)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    private class ScopedRepository(source: Map<Pair<String, String>, List<Bookmark>>) : BookmarkRepository {
        private val entries = source.mapValues { MutableStateFlow(it.value) }
        val observed = mutableListOf<Pair<String, String>>()
        var writes = 0

        override fun observe(bookId: String, contentSha256: String): Flow<List<Bookmark>> {
            val scope = bookId to contentSha256
            observed += scope
            return requireNotNull(entries[scope])
        }
        override suspend fun add(bookId: String, contentSha256: String, target: BookmarkTarget) { writes++ }
        override suspend fun remove(bookId: String, contentSha256: String, bookmarkId: String) { writes++ }
        override suspend fun rename(bookId: String, contentSha256: String, bookmarkId: String, label: String) { writes++ }
    }
}

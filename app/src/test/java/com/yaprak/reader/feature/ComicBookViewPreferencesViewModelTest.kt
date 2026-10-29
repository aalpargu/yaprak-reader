package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.feature.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComicBookViewPreferencesViewModelTest {
    private val book = Book("comic", "Comic", null, BookFormat.CBZ, "comic.cbz", "comic.cbz", 1, "a".repeat(64), 1, 5)
    private class Store : BookComicViewPreferencesRepository {
        val rows = mutableMapOf<Pair<String, String>, MutableStateFlow<BookComicViewPreferences>>()
        val writes = mutableListOf<Triple<String, String, Boolean>>()
        var readGate: CompletableDeferred<Unit>? = null
        var readFailure: BookComicViewPreferencesFailure? = null
        var writeFailure: BookComicViewPreferencesFailure? = null
        var beforeWrite: suspend () -> Unit = {}
        var afterWrite: suspend () -> Unit = {}
        fun row(id: String, hash: String) = rows.getOrPut(id to hash) { MutableStateFlow(BookComicViewPreferences()) }
        override fun observe(bookId: String, contentSha256: String): Flow<BookComicViewPreferences> = flow {
            readGate?.await(); readFailure?.let { throw BookComicViewPreferencesException(it) }
            emitAll(row(bookId, contentSha256))
        }
        override suspend fun saveSpread(bookId: String, contentSha256: String, doublePagePreferred: Boolean) {
            writes += Triple(bookId, contentSha256, doublePagePreferred); beforeWrite()
            writeFailure?.let { throw BookComicViewPreferencesException(it) }
            row(bookId, contentSha256).value = BookComicViewPreferences(doublePagePreferred); afterWrite()
        }
    }
    private suspend fun TestScope.withModel(store: Store = Store(), test: suspend (ComicBookViewPreferencesViewModel, Store) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val owner = ViewModelStore()
        try { test(ComicBookViewPreferencesViewModel(store).also { owner.put("comic", it) }, store) }
        finally { owner.clear(); Dispatchers.resetMain() }
    }
    private fun TestScope.ready(vm: ComicBookViewPreferencesViewModel, target: Book = book) {
        vm.bind(target); runCurrent(); assertEquals(ComicBookViewStatus.APPLYING, vm.state.value.status)
        vm.applied(vm.state.value); assertEquals(ComicBookViewStatus.READY, vm.state.value.status)
    }
    @Test fun initialReadDoesNotApplyOrWriteUntilRealSourceAndNativeAcknowledgement() = runTest {
        val store = Store().apply { readGate = CompletableDeferred(); row(book.id, book.sha256).value = BookComicViewPreferences(true) }
        withModel(store) { vm, source ->
            vm.bind(book); runCurrent(); assertEquals(ComicBookViewStatus.LOADING, vm.state.value.status)
            assertNull(vm.state.value.preferences); assertNull(vm.state.value.applied); assertFalse(vm.state.value.canEdit)
            source.readGate!!.complete(Unit); runCurrent()
            assertTrue(vm.state.value.preferences!!.doublePagePreferred); assertNull(vm.state.value.applied)
            assertFalse(vm.state.value.canEdit); vm.applied(vm.state.value)
            assertEquals(vm.state.value.preferences, vm.state.value.applied); assertTrue(source.writes.isEmpty())
        }
    }
    @Test fun saveIsWriteFirstRejectsConcurrentOrStaleCommandsAndRequeriesActualCommit() = runTest {
        withModel { vm, source ->
            ready(vm); val old = vm.state.value; val gate = CompletableDeferred<Unit>()
            source.beforeWrite = { gate.await() }; source.afterWrite = { source.row(book.id, book.sha256).value = BookComicViewPreferences(false) }
            val save = async { vm.saveSpread(old, true) }; runCurrent()
            assertEquals(ComicBookViewStatus.SAVING, vm.state.value.status); assertEquals(old.preferences, vm.state.value.preferences)
            assertEquals(old.applied, vm.state.value.applied); assertNull(vm.saveSpread(old, false))
            assertNull(vm.saveSpread(vm.state.value, false)); gate.complete(Unit); runCurrent()
            val accepted = save.await()!!; assertEquals(ComicBookViewStatus.APPLYING, accepted.status)
            assertFalse(accepted.preferences!!.doublePagePreferred); assertEquals(old.applied, accepted.applied)
            vm.applied(old); assertEquals(ComicBookViewStatus.APPLYING, vm.state.value.status)
            vm.applied(accepted); assertTrue(vm.state.value.canEdit)
            assertEquals(listOf(Triple(book.id, book.sha256, true)), source.writes)
        }
    }
    @Test fun sourceFailureIsNotDefaultAndReadRetryDoesNotWrite() = runTest {
        withModel(Store().apply { readFailure = BookComicViewPreferencesFailure.SOURCE }) { vm, source ->
            vm.bind(book); runCurrent(); val failed = vm.state.value
            assertEquals(ComicBookViewStatus.LOAD_FAILED, failed.status); assertNull(failed.preferences)
            assertEquals(BookComicViewPreferencesFailure.SOURCE, failed.failure); assertNull(vm.saveSpread(failed, true))
            source.readFailure = null; source.row(book.id, book.sha256).value = BookComicViewPreferences(true)
            vm.retry(); runCurrent(); assertTrue(vm.state.value.preferences!!.doublePagePreferred)
            assertEquals(ComicBookViewStatus.APPLYING, vm.state.value.status); assertTrue(source.writes.isEmpty())
        }
    }
    @Test fun failedDisablePreservesSavedTrueAndRetryRetainsPendingFalse() = runTest {
        withModel { vm, source ->
            source.row(book.id, book.sha256).value = BookComicViewPreferences(true); ready(vm)
            val old = vm.state.value; source.writeFailure = BookComicViewPreferencesFailure.SOURCE
            val save = async { vm.saveSpread(old, false) }; runCurrent(); assertNull(save.await())
            assertEquals(ComicBookViewStatus.SAVE_FAILED, vm.state.value.status); assertEquals(old.applied, vm.state.value.applied)
            assertTrue(vm.state.value.preferences!!.doublePagePreferred); assertNull(vm.saveSpread(old, false))
            source.writeFailure = null; val retry = async { vm.retry() }; runCurrent(); retry.await()
            assertEquals(ComicBookViewStatus.APPLYING, vm.state.value.status); assertFalse(vm.state.value.preferences!!.doublePagePreferred)
            assertEquals(listOf(false, false), source.writes.map { it.third }); vm.applied(vm.state.value)
            assertFalse(vm.state.value.applied!!.doublePagePreferred)
        }
    }
    @Test fun nativeApplyFailureRetriesOnlyCurrentSavedValueWithoutAnotherWrite() = runTest {
        withModel { vm, source ->
            vm.bind(book); runCurrent(); val old = vm.state.value; vm.applyFailed(old)
            assertEquals(ComicBookViewStatus.APPLY_FAILED, vm.state.value.status); assertNull(vm.state.value.applied)
            vm.retry(); val retry = vm.state.value; assertNotEquals(old.revision, retry.revision)
            vm.applied(old); assertEquals(ComicBookViewStatus.APPLYING, vm.state.value.status)
            vm.applied(retry); assertEquals(ComicBookViewStatus.READY, vm.state.value.status); assertTrue(source.writes.isEmpty())
        }
    }
    @Test fun deactivateReopenOtherHashAndOtherFormatsCannotReuseOldForegroundLease() = runTest {
        withModel { vm, source ->
            source.row(book.id, book.sha256).value = BookComicViewPreferences(true); ready(vm); val old = vm.state.value
            vm.deactivate(); ready(vm); assertNotEquals(old.owner, vm.state.value.owner)
            assertTrue(vm.state.value.applied!!.doublePagePreferred); assertNull(vm.saveSpread(old, false))
            ready(vm, book.copy(sha256 = "b".repeat(64))); assertFalse(vm.state.value.applied!!.doublePagePreferred)
            ready(vm, book.copy(format = BookFormat.CBR)); assertTrue(vm.state.value.applied!!.doublePagePreferred)
            for (format in BookFormat.entries.filter { it !in setOf(BookFormat.CBZ, BookFormat.CBR) }) {
                vm.bind(book.copy(format = format)); runCurrent(); assertEquals(ComicBookViewStatus.INACTIVE, vm.state.value.status)
            }
            assertTrue(source.writes.isEmpty())
        }
    }
    @Test fun cancellationReloadsActualDurableRowAndLateCommitCannotAcknowledgeNewBook() = runTest {
        withModel { vm, source ->
            ready(vm); val gate = CompletableDeferred<Unit>(); source.beforeWrite = { withContext(NonCancellable) { gate.await() } }
            val save = async { vm.saveSpread(vm.state.value, true) }; runCurrent(); vm.deactivate()
            ready(vm, book.copy(id = "other")); val other = vm.state.value
            gate.complete(Unit); runCurrent(); assertTrue(save.isCancelled); assertEquals(other, vm.state.value)
            assertTrue(source.row(book.id, book.sha256).value.doublePagePreferred)
            ready(vm); assertTrue(vm.state.value.applied!!.doublePagePreferred)
        }
    }
    @Test fun cancelledUncommittedWriteRemainsCancellationAndReloadsOldTrue() = runTest {
        withModel { vm, source ->
            source.row(book.id, book.sha256).value = BookComicViewPreferences(true); ready(vm)
            val gate = CompletableDeferred<Unit>(); source.beforeWrite = { gate.await() }
            val save = async { vm.saveSpread(vm.state.value, false) }; runCurrent(); save.cancel(); runCurrent()
            assertTrue(save.isCancelled); assertEquals(ComicBookViewStatus.APPLYING, vm.state.value.status)
            assertTrue(vm.state.value.preferences!!.doublePagePreferred); assertNull(vm.state.value.failure)
            vm.applied(vm.state.value); assertTrue(vm.state.value.applied!!.doublePagePreferred)
        }
    }
}

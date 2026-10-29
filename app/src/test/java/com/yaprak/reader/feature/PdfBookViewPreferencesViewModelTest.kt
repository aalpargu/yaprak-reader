package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.settings.BookPdfViewPreferences
import com.yaprak.reader.domain.settings.BookPdfViewPreferencesException
import com.yaprak.reader.domain.settings.BookPdfViewPreferencesFailure
import com.yaprak.reader.domain.settings.BookPdfViewPreferencesRepository
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.domain.settings.PdfNavigationMode
import com.yaprak.reader.feature.reader.PdfBookViewPreferencesViewModel
import com.yaprak.reader.feature.reader.PdfBookViewStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfBookViewPreferencesViewModelTest {
    private val hash = "a".repeat(64)
    private data class Write(val bookId: String, val hash: String, val field: String)
    private class Store : BookPdfViewPreferencesRepository {
        val rows = mutableMapOf<Pair<String, String>, MutableStateFlow<BookPdfViewPreferences>>()
        val writes = mutableListOf<Write>()
        var readGate: CompletableDeferred<Unit>? = null
        var readFailure: BookPdfViewPreferencesFailure? = null
        var beforeWrite: suspend () -> Unit = {}
        var writeFailure: BookPdfViewPreferencesFailure? = null
        fun row(id: String, hash: String) = rows.getOrPut(id to hash) { MutableStateFlow(BookPdfViewPreferences()) }
        override fun observe(bookId: String, contentSha256: String): Flow<BookPdfViewPreferences> = flow {
            readGate?.await()
            readFailure?.let { throw BookPdfViewPreferencesException(it) }
            emitAll(row(bookId, contentSha256))
        }
        private suspend fun write(id: String, hash: String, field: String, change: (BookPdfViewPreferences) -> BookPdfViewPreferences) {
            writes += Write(id, hash, field)
            beforeWrite()
            writeFailure?.let { throw BookPdfViewPreferencesException(it) }
            row(id, hash).value = change(row(id, hash).value)
        }
        override suspend fun saveCrop(bookId: String, contentSha256: String, horizontalPercent: Int, verticalPercent: Int) =
            write(bookId, contentSha256, "crop") { it.copy(horizontalPercent = horizontalPercent, verticalPercent = verticalPercent) }
        override suspend fun saveFit(bookId: String, contentSha256: String, fitMode: PdfFitPreference) =
            write(bookId, contentSha256, "fit") { it.copy(fitMode = fitMode) }
        override suspend fun saveSpread(bookId: String, contentSha256: String, doublePagePreferred: Boolean) =
            write(bookId, contentSha256, "spread") { it.copy(doublePagePreferred = doublePagePreferred) }
        override suspend fun saveNavigation(bookId: String, contentSha256: String, mode: com.yaprak.reader.domain.settings.PdfNavigationMode) =
            write(bookId, contentSha256, "navigation") { it.copy(navigationMode = mode) }
    }

    private suspend fun TestScope.withModel(store: Store = Store(), test: suspend (PdfBookViewPreferencesViewModel, Store) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val vm = PdfBookViewPreferencesViewModel(store).also { owner.put("pdf", it) }
            test(vm, store)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    private fun TestScope.ready(vm: PdfBookViewPreferencesViewModel, id: String = "book") {
        vm.bind(id, hash); runCurrent()
        assertEquals(PdfBookViewStatus.APPLYING, vm.state.value.status)
        vm.applied(vm.state.value)
        assertEquals(PdfBookViewStatus.READY, vm.state.value.status)
    }

    @Test fun horizontalCommitIsAppliedOnlyAfterRequeryAndAnOldBookCallbackCannotRetargetIt() = runTest {
        withModel { vm, store ->
            ready(vm); val previous = vm.state.value
            val gate = CompletableDeferred<Unit>(); store.beforeWrite = { gate.await() }
            val saving = async { vm.saveNavigation(previous, PdfNavigationMode.HORIZONTAL_SCROLL) }; runCurrent()
            assertEquals(PdfBookViewStatus.SAVING, vm.state.value.status)
            assertEquals(PdfNavigationMode.PAGED, vm.state.value.applied!!.navigationMode)
            gate.complete(Unit); runCurrent(); val committed = saving.await()!!
            assertEquals(PdfNavigationMode.HORIZONTAL_SCROLL, committed.preferences!!.navigationMode)
            assertEquals(PdfNavigationMode.PAGED, committed.applied!!.navigationMode)
            vm.applied(committed); assertEquals(PdfNavigationMode.HORIZONTAL_SCROLL, vm.state.value.applied!!.navigationMode)
            vm.deactivate(); ready(vm); assertEquals(PdfNavigationMode.HORIZONTAL_SCROLL, vm.state.value.applied!!.navigationMode)
            ready(vm, "other")
            assertNull(vm.saveNavigation(previous, PdfNavigationMode.HORIZONTAL_SCROLL))
            assertEquals(PdfNavigationMode.PAGED, vm.state.value.preferences!!.navigationMode)
            assertEquals(listOf("navigation"), store.writes.map { it.field })
        }
    }

    @Test fun externalPreferenceEditRevokesFailedHorizontalRetryWithoutReplayingAnOldChoice() = runTest {
        withModel { vm, store ->
            ready(vm); val old = vm.state.value; store.writeFailure = BookPdfViewPreferencesFailure.SOURCE
            val saving = async { vm.saveNavigation(old, PdfNavigationMode.HORIZONTAL_SCROLL) }; runCurrent()
            assertNull(saving.await()); assertEquals(PdfBookViewStatus.SAVE_FAILED, vm.state.value.status)
            val external = BookPdfViewPreferences(8, 12, PdfFitPreference.WIDTH, true)
            store.row("book", hash).value = external; runCurrent()
            assertEquals(PdfBookViewStatus.APPLYING, vm.state.value.status); assertEquals(external, vm.state.value.preferences)
            store.writeFailure = null
            vm.retry(); runCurrent()
            assertEquals(listOf("navigation"), store.writes.map { it.field })
            assertEquals(PdfNavigationMode.PAGED, store.row("book", hash).value.navigationMode)
            assertEquals(old.applied, vm.state.value.applied)
            vm.applied(vm.state.value); assertEquals(external, vm.state.value.applied)
        }
    }

    @Test fun horizontalSaveFailureRetainsTheAppliedAxisAndRetryPreservesOtherCommittedPreferences() = runTest {
        withModel { vm, store ->
            ready(vm); val old = vm.state.value; store.writeFailure = BookPdfViewPreferencesFailure.SOURCE
            val saving = async { vm.saveNavigation(old, PdfNavigationMode.HORIZONTAL_SCROLL) }; runCurrent()
            assertNull(saving.await()); assertEquals(PdfNavigationMode.PAGED, vm.state.value.applied!!.navigationMode)
            // A concurrent field commit inside the accepted write must survive the navigation merge.
            // Changing an observed row before retry would correctly revoke the pending action instead.
            store.beforeWrite = { store.row("book", hash).value = BookPdfViewPreferences(8, 12, PdfFitPreference.WIDTH, true) }
            store.writeFailure = null
            val retry = async { vm.retry() }; runCurrent(); retry.await()
            assertEquals(BookPdfViewPreferences(8, 12, PdfFitPreference.WIDTH, true, PdfNavigationMode.HORIZONTAL_SCROLL), vm.state.value.preferences)
            assertEquals(PdfNavigationMode.PAGED, vm.state.value.applied!!.navigationMode)
            vm.applied(vm.state.value)
            assertEquals(PdfNavigationMode.HORIZONTAL_SCROLL, vm.state.value.applied!!.navigationMode)
            assertEquals(listOf("navigation", "navigation"), store.writes.map { it.field })
        }
    }

    @Test fun navigationSaveWaitsForCommitAndRequeryWithoutAcknowledgingAnOptimisticNativeMode() = runTest {
        withModel { vm, store ->
            ready(vm); val previous = vm.state.value
            val gate = CompletableDeferred<Unit>(); store.beforeWrite = { gate.await() }
            val saving = async { vm.saveNavigation(previous, PdfNavigationMode.VERTICAL_SCROLL) }; runCurrent()
            assertEquals(PdfBookViewStatus.SAVING, vm.state.value.status)
            assertEquals(previous.preferences, vm.state.value.preferences); assertEquals(previous.applied, vm.state.value.applied)
            assertNull(vm.saveNavigation(previous, PdfNavigationMode.PAGED))
            store.row("book", hash).value = BookPdfViewPreferences(6, 9, PdfFitPreference.WIDTH, true)
            gate.complete(Unit); runCurrent(); val committed = saving.await()!!
            assertEquals(BookPdfViewPreferences(6, 9, PdfFitPreference.WIDTH, true, PdfNavigationMode.VERTICAL_SCROLL), committed.preferences)
            assertEquals(previous.applied, vm.state.value.applied); assertEquals(PdfBookViewStatus.APPLYING, vm.state.value.status)
            assertEquals(previous.fitCommand, committed.fitCommand); assertEquals(previous.cropCommand, committed.cropCommand)
            vm.applied(committed); assertEquals(committed.preferences, vm.state.value.applied)
            assertEquals(listOf("navigation"), store.writes.map { it.field })
        }
    }
    @Test fun failedNavigationSaveRetainsTheOldAppliedModeAndRetriesOnlyThatPendingChoice() = runTest {
        withModel { vm, store ->
            ready(vm); val old = vm.state.value; store.writeFailure = BookPdfViewPreferencesFailure.SOURCE
            val saving = async { vm.saveNavigation(old, PdfNavigationMode.VERTICAL_SCROLL) }; runCurrent(); assertNull(saving.await())
            assertEquals(PdfBookViewStatus.SAVE_FAILED, vm.state.value.status); assertEquals(old.applied, vm.state.value.applied)
            assertNull(vm.saveNavigation(old, PdfNavigationMode.PAGED)); store.writeFailure = null
            val retry = async { vm.retry() }; runCurrent(); retry.await()
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, vm.state.value.preferences!!.navigationMode)
            assertEquals(old.applied, vm.state.value.applied); assertEquals(PdfBookViewStatus.APPLYING, vm.state.value.status)
            assertEquals(listOf("navigation", "navigation"), store.writes.map { it.field })
            vm.applied(vm.state.value); assertEquals(PdfNavigationMode.VERTICAL_SCROLL, vm.state.value.applied!!.navigationMode)
        }
    }
    @Test fun aNavigationCommitCancelledDuringRequeryIsReloadedBeforeAnyApplyAcknowledgement() = runTest {
        withModel { vm, store ->
            ready(vm); val old = vm.state.value; val gate = CompletableDeferred<Unit>()
            store.beforeWrite = { store.readGate = gate }
            val saving = async { vm.saveNavigation(old, PdfNavigationMode.VERTICAL_SCROLL) }; runCurrent()
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, store.row("book", hash).value.navigationMode)
            assertEquals(PdfBookViewStatus.SAVING, vm.state.value.status); assertEquals(old.applied, vm.state.value.applied)
            saving.cancel(); runCurrent(); saving.join(); assertTrue(saving.isCancelled)
            assertEquals(PdfBookViewStatus.LOADING, vm.state.value.status)
            gate.complete(Unit); runCurrent()
            assertEquals(PdfBookViewStatus.APPLYING, vm.state.value.status)
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, vm.state.value.preferences!!.navigationMode)
            assertEquals(old.applied, vm.state.value.applied); assertNull(vm.saveNavigation(old, PdfNavigationMode.PAGED))
            assertEquals(1, store.writes.size)
        }
    }
    @Test fun reopenedForegroundAndOtherBookModesCannotBeRetargetedByAnOldNavigationCallback() = runTest {
        withModel { vm, store ->
            ready(vm); val old = vm.state.value
            val saving = async { vm.saveNavigation(old, PdfNavigationMode.VERTICAL_SCROLL) }; runCurrent(); vm.applied(saving.await()!!)
            vm.deactivate(); ready(vm)
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, vm.state.value.applied!!.navigationMode)
            assertNotEquals(old.owner, vm.state.value.owner); assertNull(vm.saveNavigation(old, PdfNavigationMode.PAGED))
            ready(vm, "other"); assertEquals(PdfNavigationMode.PAGED, vm.state.value.applied!!.navigationMode)
            assertNull(vm.saveNavigation(old, PdfNavigationMode.VERTICAL_SCROLL)); assertEquals(1, store.writes.size)
        }
    }

    @Test fun `initial read is explicit and durable values are acknowledged only after native apply`() = runTest {
        val store = Store().apply { readGate = CompletableDeferred(); row("book", hash).value = BookPdfViewPreferences(4, 8, PdfFitPreference.WIDTH, true) }
        withModel(store) { vm, repository ->
            vm.bind("book", hash); runCurrent()
            assertEquals(PdfBookViewStatus.LOADING, vm.state.value.status)
            assertNull(vm.state.value.preferences); assertNull(vm.state.value.applied)
            assertFalse(vm.state.value.canEdit); assertTrue(repository.writes.isEmpty())
            repository.readGate!!.complete(Unit); runCurrent()
            assertEquals(repository.row("book", hash).value, vm.state.value.preferences)
            assertNull(vm.state.value.applied); assertFalse(vm.state.value.canEdit)
            vm.applied(vm.state.value)
            assertEquals(vm.state.value.preferences, vm.state.value.applied)
            assertTrue(vm.state.value.canEdit); assertTrue(repository.writes.isEmpty())
        }
    }

    @Test fun `field save waits for commit and requery preserves fields changed while writing`() = runTest {
        withModel { vm, store ->
            ready(vm)
            val previous = vm.state.value
            val gate = CompletableDeferred<Unit>(); store.beforeWrite = { gate.await() }
            val saving = async { vm.saveCrop(previous, 6, 9) }
            runCurrent()
            assertEquals(PdfBookViewStatus.SAVING, vm.state.value.status)
            assertEquals(previous.preferences, vm.state.value.preferences)
            assertEquals(previous.applied, vm.state.value.applied)
            assertNull(vm.saveSpread(previous, true))
            store.row("book", hash).value = BookPdfViewPreferences(fitMode = PdfFitPreference.WIDTH, doublePagePreferred = true)
            gate.complete(Unit); runCurrent()
            val accepted = saving.await()!!
            assertEquals(BookPdfViewPreferences(6, 9, PdfFitPreference.WIDTH, true), accepted.preferences)
            assertEquals(previous.applied, vm.state.value.applied)
            assertEquals(PdfBookViewStatus.APPLYING, vm.state.value.status)
            vm.applied(accepted)
            assertEquals(accepted.preferences, vm.state.value.applied)
            assertEquals(listOf("crop"), store.writes.map { it.field })
        }
    }

    @Test fun `new foreground leases reload stored values and another book starts from its own verified row`() = runTest {
        withModel { vm, store ->
            ready(vm)
            val save = async { vm.saveSpread(vm.state.value, true) }; runCurrent(); vm.applied(save.await()!!)
            val first = vm.state.value
            vm.deactivate(); vm.bind("book", hash); runCurrent(); vm.applied(vm.state.value)
            assertNotEquals(first.owner, vm.state.value.owner)
            assertTrue(vm.state.value.preferences!!.doublePagePreferred)
            store.row("other", hash).value = BookPdfViewPreferences(2, 3, PdfFitPreference.WIDTH)
            ready(vm, "other")
            assertEquals(store.row("other", hash).value, vm.state.value.applied)
            assertNull(vm.saveCrop(first, 1, 1))
            assertEquals(1, store.writes.size)
        }
    }

    @Test fun `source failure never reads as defaults and explicit retry does not write`() = runTest {
        val store = Store().apply { readFailure = BookPdfViewPreferencesFailure.SOURCE }
        withModel(store) { vm, repository ->
            vm.bind("book", hash); runCurrent()
            assertEquals(PdfBookViewStatus.LOAD_FAILED, vm.state.value.status)
            assertEquals(BookPdfViewPreferencesFailure.SOURCE, vm.state.value.failure)
            assertNull(vm.state.value.preferences); assertFalse(vm.state.value.canEdit)
            assertNull(vm.saveFit(vm.state.value, PdfFitPreference.WIDTH))
            repository.readFailure = null
            vm.retry(); runCurrent()
            assertEquals(PdfBookViewStatus.APPLYING, vm.state.value.status)
            assertEquals(BookPdfViewPreferences(), vm.state.value.preferences)
            assertTrue(repository.writes.isEmpty())
        }
    }

    @Test fun `failed write retains last applied view and explicit retry confirms the pending field`() = runTest {
        withModel { vm, store ->
            ready(vm)
            val old = vm.state.value
            store.writeFailure = BookPdfViewPreferencesFailure.SOURCE
            val saving = async { vm.saveFit(old, PdfFitPreference.WIDTH) }; runCurrent()
            assertNull(saving.await())
            assertEquals(PdfBookViewStatus.SAVE_FAILED, vm.state.value.status)
            assertEquals(old.applied, vm.state.value.applied)
            assertNotEquals(old.revision, vm.state.value.revision)
            assertNull(vm.saveSpread(old, true))
            store.writeFailure = null
            val retry = async { vm.retry() }; runCurrent(); retry.await()
            assertEquals(PdfBookViewStatus.APPLYING, vm.state.value.status)
            assertEquals(PdfFitPreference.WIDTH, vm.state.value.preferences!!.fitMode)
            assertEquals(listOf("fit", "fit"), store.writes.map { it.field })
        }
    }

    @Test fun `observed external change invalidates failed write retry instead of overwriting the newer row`() = runTest {
        withModel { vm, store ->
            ready(vm)
            store.writeFailure = BookPdfViewPreferencesFailure.SOURCE
            val saving = async { vm.saveSpread(vm.state.value, true) }; runCurrent(); saving.await()
            val failed = vm.state.value
            store.row("book", hash).value = BookPdfViewPreferences(11, 12, PdfFitPreference.WIDTH, false)
            runCurrent()
            assertEquals(PdfBookViewStatus.APPLYING, vm.state.value.status)
            assertFalse(vm.isCurrent(failed))
            vm.applied(vm.state.value); vm.retry(); runCurrent()
            assertEquals(1, store.writes.size)
            assertFalse(store.row("book", hash).value.doublePagePreferred)
        }
    }

    @Test fun `stale source after failed write removes pending retry and cannot claim a loaded preference`() = runTest {
        withModel { vm, store ->
            ready(vm)
            store.writeFailure = BookPdfViewPreferencesFailure.STALE_BOOK
            store.readFailure = BookPdfViewPreferencesFailure.STALE_BOOK
            val saving = async { vm.saveCrop(vm.state.value, 3, 4) }; runCurrent(); saving.await()
            assertEquals(PdfBookViewStatus.LOAD_FAILED, vm.state.value.status)
            assertEquals(BookPdfViewPreferencesFailure.STALE_BOOK, vm.state.value.failure)
            assertFalse(vm.state.value.canEdit)
            vm.retry(); runCurrent()
            assertEquals(1, store.writes.size)
            assertEquals(PdfBookViewStatus.LOAD_FAILED, vm.state.value.status)
        }
    }

    @Test fun `cancelled writer completing a durable commit cannot relabel another book or new lease`() = runTest {
        withModel { vm, store ->
            ready(vm)
            val release = CompletableDeferred<Unit>()
            store.beforeWrite = { withContext(NonCancellable) { release.await() } }
            val old = vm.state.value
            val saving = async { vm.saveSpread(old, true) }; runCurrent()
            vm.deactivate(); ready(vm, "other")
            val current = vm.state.value
            release.complete(Unit); runCurrent()
            assertTrue(saving.isCancelled)
            assertEquals(current, vm.state.value)
            assertEquals(listOf("book"), store.writes.map { it.bookId })
            assertFalse(vm.state.value.applied!!.doublePagePreferred)
        }
    }

    @Test fun `caller cancellation stays cancellation and rechecks the same durable row`() = runTest {
        withModel { vm, store ->
            ready(vm)
            store.beforeWrite = { throw CancellationException("cancelled") }
            val saving = async { vm.saveCrop(vm.state.value, 2, 3) }; runCurrent()
            assertTrue(saving.isCancelled)
            assertEquals(PdfBookViewStatus.APPLYING, vm.state.value.status)
            assertEquals(BookPdfViewPreferences(), vm.state.value.preferences)
            assertNull(vm.state.value.failure)
        }
    }

    @Test fun `repeated fit remains a fresh native command while stale saved actions are rejected`() = runTest {
        withModel { vm, store ->
            ready(vm)
            val first = vm.state.value
            val one = async { vm.saveFit(first, PdfFitPreference.PAGE) }; runCurrent(); vm.applied(one.await()!!)
            val second = vm.state.value
            val two = async { vm.saveFit(second, PdfFitPreference.PAGE) }; runCurrent(); vm.applied(two.await()!!)
            assertEquals(first.preferences, vm.state.value.preferences)
            assertEquals(first.fitCommand + 2, vm.state.value.fitCommand)
            assertNull(vm.saveFit(first, PdfFitPreference.WIDTH))
            assertEquals(2, store.writes.size)
        }
    }

    @Test fun `native apply failure requires explicit reapply and never rewrites storage`() = runTest {
        withModel { vm, store ->
            vm.bind("book", hash); runCurrent()
            val first = vm.state.value
            vm.applyFailed(first)
            assertNull(vm.state.value.applied); assertFalse(vm.state.value.canEdit)
            assertNull(vm.saveSpread(vm.state.value, true))
            vm.retry(); runCurrent()
            assertEquals(PdfBookViewStatus.APPLYING, vm.state.value.status)
            assertNotEquals(first.revision, vm.state.value.revision)
            vm.applied(first)
            assertNull(vm.state.value.applied)
            vm.applied(vm.state.value)
            assertTrue(vm.state.value.canEdit); assertTrue(store.writes.isEmpty())
        }
    }

    @Test fun `explicit same crop apply remains a fresh reset command while initial observation does not write`() = runTest {
        withModel { vm, store ->
            ready(vm)
            val initial = vm.state.value
            val saving = async { vm.saveCrop(initial, 0, 0) }; runCurrent()
            val accepted = saving.await()!!
            assertEquals(initial.preferences, accepted.preferences)
            assertEquals(initial.cropCommand + 1, accepted.cropCommand)
            assertEquals(initial.fitCommand, accepted.fitCommand)
            assertEquals(listOf("crop"), store.writes.map { it.field })
        }
    }
}

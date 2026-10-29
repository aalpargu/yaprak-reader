package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.librarylabels.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryLabelsViewModelTest {
    private val member = LibraryLabelMember("book", "a".repeat(64), BookOrganization(tags = listOf("İş"), collections = listOf("Raf")))
    private class Source(initial: LibraryLabelSnapshot) : LibraryLabelsRepository {
        val snapshots = MutableStateFlow(initial)
        var failure: Exception? = null
        var collectors = 0
        val calls = mutableListOf<String?>()
        var write: suspend () -> Int = { 1 }
        override fun observe() = flow {
            collectors++
            try { failure?.let { throw it }; emitAll(snapshots) } finally { collectors-- }
        }
        override suspend fun change(kind: LibraryLabelKind, source: String, target: String?, expected: List<LibraryLabelMember>): Int {
            calls += target; return write()
        }
    }
    private suspend fun TestScope.using(block: suspend TestScope.(LibraryLabelsViewModel, Source, Any) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val repo = Source(LibraryLabelSnapshot(listOf(member)))
        val vm = LibraryLabelsViewModel(repo).also { store.put("labels", it) }
        val token = Any()
        try { vm.attach(token); vm.active(token, true); runCurrent(); block(vm, repo, token) }
        finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun open(vm: LibraryLabelsViewModel, token: Any) {
        vm.open(token, vm.state.value.revision, "İş")
    }
    private fun edit(vm: LibraryLabelsViewModel, token: Any, value: String) {
        val state = vm.state.value; vm.edit(token, state.editor!!.id, state.revision, target = value)
    }
    private fun save(vm: LibraryLabelsViewModel, token: Any) {
        val state = vm.state.value; vm.save(token, state.editor!!.id, state.revision)
    }
    @Test fun invalidNoopAndOldCallbacksCannotWriteAndDoubleSubmitHasOneCall() = runTest { using { vm, repo, token ->
        open(vm, token)
        save(vm, token); runCurrent(); assertTrue(repo.calls.isEmpty())
        edit(vm, token, "bir,iki"); save(vm, token); runCurrent(); assertTrue(repo.calls.isEmpty())
        val old = vm.state.value
        edit(vm, token, " Yeni ")
        vm.edit(token, old.editor!!.id, old.revision, target = "Eski olay")
        assertEquals(" Yeni ", vm.state.value.editor!!.target)
        val pending = CompletableDeferred<Int>(); repo.write = { pending.await() }
        save(vm, token); save(vm, token); runCurrent(); assertEquals(listOf("Yeni"), repo.calls)
        assertTrue(vm.state.value.saving)
        val busy = vm.state.value; vm.dismiss(token, busy.editor!!.id, busy.revision)
        assertNotNull(vm.state.value.editor)
        pending.complete(1); runCurrent(); assertNull(vm.state.value.editor)
        assertEquals(1, vm.state.value.changedBooks)
    } }
    @Test fun completeMembershipAndOtherMetadataChangeInvalidatesPreviewWithoutRebasing() = runTest { using { vm, repo, token ->
        open(vm, token); edit(vm, token, "Yeni")
        val captured = vm.state.value.editor!!.expected
        repo.snapshots.value = LibraryLabelSnapshot(listOf(member.copy(organization = member.organization.copy(favorite = true))))
        runCurrent(); assertFalse(vm.state.value.fresh(vm.state.value.editor!!))
        save(vm, token); runCurrent(); assertTrue(repo.calls.isEmpty())
        assertEquals(captured, vm.state.value.editor!!.expected)
        val state = vm.state.value; vm.dismiss(token, state.editor!!.id, state.revision)
        open(vm, token); edit(vm, token, "Yeni"); save(vm, token); runCurrent()
        assertEquals(1, repo.calls.size)
    } }
    @Test fun sourceErrorIsDistinctFromEmptyAndExplicitRetryRetainsDraft() = runTest { using { vm, repo, token ->
        open(vm, token); edit(vm, token, "Taslak")
        vm.active(token, false); runCurrent(); assertEquals(0, repo.collectors)
        repo.failure = LibraryLabelException(LibraryLabelFailure.RECOVERY)
        vm.active(token, true); runCurrent()
        assertEquals(LibraryLabelFailure.RECOVERY, vm.state.value.failure); assertNull(vm.state.value.snapshot)
        assertEquals("Taslak", vm.state.value.editor!!.target)
        save(vm, token); runCurrent(); assertTrue(repo.calls.isEmpty())
        repo.failure = null; vm.retry(token, vm.state.value.revision); runCurrent()
        assertTrue(vm.state.value.canSave(vm.state.value.editor!!))
        val state = vm.state.value; vm.dismiss(token, state.editor!!.id, state.revision)
        repo.snapshots.value = LibraryLabelSnapshot(emptyList()); runCurrent()
        assertNull(vm.state.value.failure); assertTrue(vm.state.value.snapshot!!.members.isEmpty())
    } }
    @Test fun conflictRetainsDraftForReviewAndWrongAffectedCountNeverReportsSuccess() = runTest { using { vm, repo, token ->
        open(vm, token); edit(vm, token, "Yeni")
        repo.write = { throw LibraryLabelException(LibraryLabelFailure.CONFLICT) }
        save(vm, token); runCurrent()
        assertEquals(LibraryLabelFailure.CONFLICT, vm.state.value.writeFailure); assertNotNull(vm.state.value.editor)
        assertNull(vm.state.value.changedBooks)
        repo.write = { 0 }; save(vm, token); runCurrent()
        assertEquals(LibraryLabelFailure.SOURCE, vm.state.value.writeFailure); assertNull(vm.state.value.changedBooks)
        repo.write = { 1 }; save(vm, token); runCurrent(); assertEquals(1, vm.state.value.changedBooks)
    } }
    @Test fun replacementOwnerRejectsOldActionsAndRevalidatesRetainedDraftBeforeRemove() = runTest { using { vm, repo, token ->
        open(vm, token)
        val old = vm.state.value
        val next = Any(); vm.attach(next); vm.active(next, true)
        assertNull(vm.state.value.snapshot); assertFalse(vm.state.value.canSave(vm.state.value.editor!!))
        vm.edit(token, old.editor!!.id, old.revision, remove = true); runCurrent()
        assertFalse(vm.state.value.editor!!.remove)
        vm.detach(token); assertTrue(vm.isActive(next)); assertEquals(1, repo.collectors)
        val ready = vm.state.value; vm.edit(next, ready.editor!!.id, ready.revision, remove = true)
        save(vm, next); runCurrent(); assertEquals(listOf<String?>(null), repo.calls)
    } }
    @Test fun lateCancelledWriterCannotClearNewWriteAfterSameOwnerResumes() = runTest { using { vm, repo, token ->
        open(vm, token); edit(vm, token, "Yeni")
        val first = CompletableDeferred<Int>(); val second = CompletableDeferred<Int>()
        repo.write = { withContext(NonCancellable) { first.await() } }
        save(vm, token); runCurrent(); assertTrue(vm.state.value.saving)
        vm.active(token, false); vm.active(token, true); runCurrent()
        assertFalse(vm.state.value.saving); assertNotNull(vm.state.value.editor)
        repo.write = { second.await() }; save(vm, token); runCurrent()
        first.complete(1); runCurrent()
        assertTrue(vm.state.value.saving); assertNotNull(vm.state.value.editor); assertNull(vm.state.value.changedBooks)
        second.complete(1); runCurrent(); assertEquals(1, vm.state.value.changedBooks)
    } }
}

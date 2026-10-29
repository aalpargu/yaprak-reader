package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.feature.settings.TapZoneViewModel
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TapZoneViewModelTest {
    private class Repository : TapZoneSettingsRepository {
        val values = MutableStateFlow(TapZoneSettings())
        var failRead = false
        var failWrite = false
        var cancelWrite = false
        var writes = 0
        override val tapZones = flow { if (failRead) throw IOException(); emitAll(values) }
        override suspend fun saveTapZones(settings: TapZoneSettings) {
            writes++; delay(100)
            if (cancelWrite) throw CancellationException("Cancelled")
            if (failWrite) throw IOException()
            values.value = settings
        }
    }
    private val custom = TapZoneSettings(true, TapZoneAction.NONE, TapZoneAction.NEXT, TapZoneAction.MENU)
    private fun scenario(block: suspend kotlinx.coroutines.test.TestScope.(Repository, TapZoneViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repo = Repository()
            val vm = TapZoneViewModel(repo).also { owner.put("zones", it) }
            runCurrent(); block(repo, vm)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun explicitSaveIsAtomicAndCancelDiscardsDraft() = scenario { repo, vm ->
        vm.open(); vm.edit(custom); vm.dismiss()
        assertEquals(TapZoneSettings(), vm.state.value.saved); assertEquals(0, repo.writes)
        vm.open(); assertEquals(TapZoneSettings(), vm.state.value.draft)
        vm.edit(custom); vm.save(); vm.save(); runCurrent()
        vm.edit(TapZoneSettings()); vm.dismiss(); vm.reload()
        assertTrue(vm.state.value.saving && vm.state.value.open); assertEquals(custom, vm.state.value.draft)
        assertEquals(1, repo.writes); advanceUntilIdle()
        assertEquals(custom, vm.state.value.saved); assertFalse(vm.state.value.open || vm.state.value.saving)
    }
    @Test fun menuCannotBeRemovedAndLiveUpdatesDoNotOverwriteAnOpenDraft() = scenario { repo, vm ->
        vm.open(); vm.edit(custom.copy(right = TapZoneAction.NONE)); vm.save(); runCurrent()
        assertFalse(vm.state.value.draft.valid); assertEquals(0, repo.writes)
        repo.values.value = custom; runCurrent()
        assertEquals(custom, vm.state.value.saved); assertFalse(vm.state.value.draft.valid)
        vm.dismiss(); vm.open(); assertEquals(custom, vm.state.value.draft)
    }
    @Test fun writeFailureRetainsDraftForExplicitRetry() = scenario { repo, vm ->
        repo.failWrite = true; vm.open(); vm.edit(custom); vm.save(); advanceUntilIdle()
        assertEquals(TapZoneSettings(), vm.state.value.saved)
        assertEquals(custom, vm.state.value.draft); assertTrue(vm.state.value.saveFailed && vm.state.value.open)
        repo.failWrite = false; vm.save(); advanceUntilIdle()
        assertEquals(custom, vm.state.value.saved); assertFalse(vm.state.value.saveFailed || vm.state.value.open)
    }
    @Test fun unreadablePreferenceBlocksWritingUntilReloadAndCancellationIsNotSuccess() = scenario { repo, vm ->
        repo.values.value = custom; repo.failRead = true; vm.reload(); runCurrent()
        vm.open(); vm.edit(TapZoneSettings()); vm.save(); assertEquals(0, repo.writes)
        assertTrue(vm.state.value.loadFailed)
        repo.failRead = false; vm.reload(); runCurrent(); assertEquals(custom, vm.state.value.draft)
        repo.cancelWrite = true; vm.edit(TapZoneSettings()); vm.save(); advanceUntilIdle()
        assertEquals(custom, repo.values.value)
        assertFalse(vm.state.value.saving || vm.state.value.saveFailed); assertTrue(vm.state.value.open)
    }
}
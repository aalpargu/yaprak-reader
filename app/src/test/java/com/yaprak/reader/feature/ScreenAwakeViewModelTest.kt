package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.settings.ScreenAwakeSettingsRepository
import com.yaprak.reader.feature.settings.ScreenAwakeViewModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenAwakeViewModelTest {
    private class Repository : ScreenAwakeSettingsRepository {
        val values = MutableStateFlow(false)
        var failRead = false
        var failWrite = false
        var cancelWrite = false
        var writes = 0
        override val keepScreenOn = flow { if (failRead) throw IOException(); emitAll(values) }
        override suspend fun saveKeepScreenOn(enabled: Boolean) {
            writes++
            delay(100)
            if (cancelWrite) throw CancellationException("Cancelled preference write")
            if (failWrite) throw IOException()
            values.value = enabled
        }
    }

    @Test fun `cancel discards draft and saving blocks duplicate writes and dismissal`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val vm = ScreenAwakeViewModel(repository).also { owner.put("awake", it) }
            runCurrent()
            vm.open(); vm.edit(true); vm.dismiss()
            assertFalse(vm.state.value.saved)
            assertEquals(0, repository.writes)
            vm.open()
            assertFalse(vm.state.value.draft)
            vm.edit(true); vm.save(); vm.save()
            runCurrent()
            assertTrue(vm.state.value.saving && vm.state.value.open)
            assertFalse(vm.state.value.saved)
            vm.dismiss(); vm.edit(false); vm.open(); vm.reload()
            assertTrue(vm.state.value.open && vm.state.value.draft)
            assertEquals(1, repository.writes)
            advanceUntilIdle()
            assertTrue(vm.state.value.saved)
            assertFalse(vm.state.value.saving || vm.state.value.open)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `failed write preserves saved preference and draft until retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failWrite = true }
            val vm = ScreenAwakeViewModel(repository).also { owner.put("awake", it) }
            runCurrent(); vm.open(); vm.edit(true); vm.save()
            advanceUntilIdle()
            assertFalse(vm.state.value.saved)
            assertTrue(vm.state.value.draft && vm.state.value.open && vm.state.value.saveFailed)
            repository.failWrite = false
            vm.save(); advanceUntilIdle()
            assertTrue(vm.state.value.saved)
            assertFalse(vm.state.value.saveFailed || vm.state.value.open)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `read failure prevents overwrite and reload obtains persisted value`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failRead = true; values.value = true }
            val vm = ScreenAwakeViewModel(repository).also { owner.put("awake", it) }
            runCurrent(); vm.open(); vm.edit(true); vm.save()
            assertTrue(vm.state.value.loadFailed)
            assertEquals(0, repository.writes)
            repository.failRead = false
            vm.reload(); runCurrent()
            assertFalse(vm.state.value.loading || vm.state.value.loadFailed)
            assertTrue(vm.state.value.saved && vm.state.value.draft)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `cancelled write never reports success or preference error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { cancelWrite = true }
            val vm = ScreenAwakeViewModel(repository).also { owner.put("awake", it) }
            runCurrent(); vm.open(); vm.edit(true); vm.save()
            advanceUntilIdle()
            assertFalse(vm.state.value.saved || vm.state.value.saveFailed)
            assertFalse(repository.values.value)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}

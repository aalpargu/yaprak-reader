package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.settings.PdfNightSettingsRepository
import com.yaprak.reader.feature.reader.PdfNightModeStatus
import com.yaprak.reader.feature.reader.PdfNightModeViewModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfNightModeViewModelTest {
    private class Store(initial: Boolean = false) : PdfNightSettingsRepository {
        var persisted = initial
        var reads = 0
        var readAction: suspend () -> Boolean = { persisted }
        var writeAction: suspend (Write) -> Unit = { it.completion.await() }
        val writes = mutableListOf<Write>()
        var activeWrites = 0
        var maximumActiveWrites = 0
        override val pdfNightMode: Flow<Boolean> = flow {
            reads++
            emit(readAction())
        }
        override suspend fun savePdfNightMode(enabled: Boolean) {
            val write = Write(enabled)
            writes += write
            activeWrites++
            maximumActiveWrites = maxOf(maximumActiveWrites, activeWrites)
            try {
                writeAction(write)
                persisted = enabled
            } finally {
                activeWrites--
            }
        }
    }

    private class Write(val enabled: Boolean) {
        val completion = CompletableDeferred<Unit>()
    }

    private suspend fun TestScope.withModel(
        store: Store,
        saved: SavedStateHandle = SavedStateHandle(),
        action: suspend (PdfNightModeViewModel, ViewModelStore) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val vm = PdfNightModeViewModel(store, saved).also { owner.put("pdfNight", it) }
            action(vm, owner)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `fresh reader waits for saved preference without writing default`() = runTest {
        val read = CompletableDeferred<Boolean>()
        val store = Store(true).apply { readAction = { read.await() } }
        val saved = SavedStateHandle()
        withModel(store, saved) { vm, _ ->
            assertFalse(vm.state.value.enabled || vm.state.value.canToggle)
            assertEquals(PdfNightModeStatus.Loading, vm.state.value.status)
            assertNull(vm.saveInstanceChoice())
            runCurrent()
            assertTrue(store.writes.isEmpty())
            read.complete(true)
            runCurrent()
            assertTrue(vm.state.value.enabled && vm.state.value.canToggle)
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
            assertEquals(true, saved.get<Boolean>(PdfNightModeViewModel.SAVED_STATE_KEY))
            assertEquals(true, vm.saveInstanceChoice())
            assertEquals(1, store.reads)
            assertTrue(store.writes.isEmpty())
        }
    }

    @Test fun `restored night choice survives late original preference and is persisted`() = runTest {
        val read = CompletableDeferred<Boolean>()
        val store = Store().apply { readAction = { read.await() } }
        val saved = SavedStateHandle(mapOf(PdfNightModeViewModel.SAVED_STATE_KEY to true))
        withModel(store, saved) { vm, _ ->
            runCurrent()
            assertTrue(vm.state.value.enabled)
            assertFalse(vm.state.value.canToggle)
            assertEquals(true, vm.saveInstanceChoice())
            read.complete(false)
            runCurrent()
            assertEquals(listOf(true), store.writes.map(Write::enabled))
            assertTrue(vm.state.value.enabled && vm.state.value.canToggle)
            assertEquals(PdfNightModeStatus.Saving, vm.state.value.status)
            store.writes.single().completion.complete(Unit)
            runCurrent()
            assertTrue(store.persisted)
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
        }
    }

    @Test fun `restored original choice is valid and wins over saved night preference`() = runTest {
        val store = Store(true)
        val saved = SavedStateHandle(mapOf(PdfNightModeViewModel.SAVED_STATE_KEY to false))
        withModel(store, saved) { vm, _ ->
            runCurrent()
            assertFalse(vm.state.value.enabled)
            assertEquals(false, vm.saveInstanceChoice())
            assertEquals(listOf(false), store.writes.map(Write::enabled))
            store.writes.single().completion.complete(Unit)
            runCurrent()
            assertFalse(store.persisted)
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
        }
    }

    @Test fun `invalid restored value does not replace persisted Boolean`() = runTest {
        val store = Store(true)
        val saved = SavedStateHandle(mapOf(PdfNightModeViewModel.SAVED_STATE_KEY to "false"))
        withModel(store, saved) { vm, _ ->
            assertNull(vm.saveInstanceChoice())
            runCurrent()
            assertTrue(vm.state.value.enabled)
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
            assertEquals(true, vm.saveInstanceChoice())
            assertTrue(store.writes.isEmpty())
        }
    }

    @Test fun `new selection beats both restored choice and delayed read`() = runTest {
        val read = CompletableDeferred<Boolean>()
        val store = Store(true).apply { readAction = { read.await() } }
        val saved = SavedStateHandle(mapOf(PdfNightModeViewModel.SAVED_STATE_KEY to true))
        withModel(store, saved) { vm, _ ->
            runCurrent()
            vm.toggle()
            assertFalse(vm.state.value.enabled)
            assertEquals(false, vm.saveInstanceChoice())
            assertTrue(store.writes.isEmpty())
            read.complete(true)
            runCurrent()
            assertFalse(vm.state.value.enabled)
            assertEquals(listOf(false), store.writes.map(Write::enabled))
            store.writes.single().completion.complete(Unit)
            runCurrent()
            assertFalse(store.persisted)
        }
    }

    @Test fun `explicit original choice before read is retained rather than mistaken for fallback`() = runTest {
        val read = CompletableDeferred<Boolean>()
        val store = Store(true).apply { readAction = { read.await() } }
        withModel(store) { vm, _ ->
            runCurrent()
            vm.toggle()
            vm.toggle()
            assertFalse(vm.state.value.enabled)
            read.complete(true)
            runCurrent()
            assertEquals(listOf(false), store.writes.map(Write::enabled))
            store.writes.single().completion.complete(Unit)
            runCurrent()
            assertFalse(store.persisted)
        }
    }

    @Test fun `rapid selections before writer starts persist only latest desired value`() = runTest {
        val store = Store()
        withModel(store) { vm, _ ->
            runCurrent()
            vm.toggle()
            vm.toggle()
            vm.toggle()
            assertTrue(vm.state.value.enabled && vm.state.value.canToggle)
            assertEquals(PdfNightModeStatus.Saving, vm.state.value.status)
            runCurrent()
            assertEquals(listOf(true), store.writes.map(Write::enabled))
            store.writes.single().completion.complete(Unit)
            runCurrent()
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
            assertEquals(1, store.maximumActiveWrites)
        }
    }

    @Test fun `pending rapid choices are immediate and use a single serialized writer`() = runTest {
        val store = Store()
        withModel(store) { vm, _ ->
            runCurrent()
            vm.toggle()
            runCurrent()
            vm.toggle()
            vm.toggle()
            vm.toggle()
            assertFalse(vm.state.value.enabled)
            assertEquals(PdfNightModeStatus.Saving, vm.state.value.status)
            runCurrent()
            assertEquals(1, store.writes.size)
            store.writes[0].completion.complete(Unit)
            runCurrent()
            assertEquals(listOf(true, false), store.writes.map(Write::enabled))
            assertFalse(vm.state.value.enabled)
            assertEquals(1, store.activeWrites)
            store.writes[1].completion.complete(Unit)
            runCurrent()
            assertFalse(store.persisted)
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
            assertEquals(1, store.maximumActiveWrites)
        }
    }

    @Test fun `returning to in flight value needs no redundant successful write`() = runTest {
        val store = Store()
        withModel(store) { vm, _ ->
            runCurrent()
            vm.toggle()
            runCurrent()
            vm.toggle()
            vm.toggle()
            store.writes.single().completion.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.enabled && store.persisted)
            assertEquals(1, store.writes.size)
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
        }
    }

    @Test fun `unreadable preference never writes fallback and retry preserves newer choice`() = runTest {
        val store = Store().apply { readAction = { throw IOException("Unreadable preferences") } }
        withModel(store) { vm, _ ->
            runCurrent()
            assertEquals(PdfNightModeStatus.LoadFailed, vm.state.value.status)
            assertFalse(vm.state.value.canToggle)
            vm.toggle()
            runCurrent()
            assertTrue(vm.state.value.enabled)
            assertEquals(PdfNightModeStatus.LoadFailed, vm.state.value.status)
            assertTrue(store.writes.isEmpty())
            val retryRead = CompletableDeferred<Boolean>()
            store.readAction = { retryRead.await() }
            vm.retry()
            vm.retry()
            assertEquals(PdfNightModeStatus.Loading, vm.state.value.status)
            runCurrent()
            assertEquals(2, store.reads)
            retryRead.complete(false)
            runCurrent()
            assertEquals(listOf(true), store.writes.map(Write::enabled))
            store.writes.single().completion.complete(Unit)
            runCurrent()
            assertTrue(store.persisted)
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
        }
    }

    @Test fun `latest write failure keeps selected display and exposes a single retry`() = runTest {
        val store = Store()
        withModel(store) { vm, _ ->
            runCurrent()
            vm.toggle()
            runCurrent()
            store.writes.single().completion.completeExceptionally(IOException("Write failed"))
            runCurrent()
            assertTrue(vm.state.value.enabled && vm.state.value.canToggle)
            assertFalse(store.persisted)
            assertEquals(PdfNightModeStatus.SaveFailed, vm.state.value.status)
            vm.retry()
            vm.retry()
            assertEquals(PdfNightModeStatus.Saving, vm.state.value.status)
            runCurrent()
            assertEquals(listOf(true, true), store.writes.map(Write::enabled))
            store.writes[1].completion.complete(Unit)
            runCurrent()
            assertTrue(store.persisted)
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
            assertEquals(1, store.reads)
            assertEquals(1, store.maximumActiveWrites)
        }
    }

    @Test fun `old write failure cannot fail latest choice even when it matches original disk value`() = runTest {
        val store = Store()
        withModel(store) { vm, _ ->
            runCurrent()
            vm.toggle()
            runCurrent()
            vm.toggle()
            store.writes[0].completion.completeExceptionally(IOException("Old write failed"))
            runCurrent()
            assertEquals(listOf(true, false), store.writes.map(Write::enabled))
            assertFalse(vm.state.value.enabled)
            assertEquals(PdfNightModeStatus.Saving, vm.state.value.status)
            store.writes[1].completion.completeExceptionally(IOException("Latest write also failed"))
            runCurrent()
            assertEquals(PdfNightModeStatus.SaveFailed, vm.state.value.status)
            vm.retry()
            runCurrent()
            assertEquals(listOf(true, false, false), store.writes.map(Write::enabled))
            store.writes[2].completion.complete(Unit)
            runCurrent()
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
            assertFalse(store.persisted)
        }
    }

    @Test fun `same value chosen again after old write starts is retried rather than failed`() = runTest {
        val store = Store()
        withModel(store) { vm, _ ->
            runCurrent()
            vm.toggle()
            runCurrent()
            vm.toggle()
            vm.toggle()
            store.writes[0].completion.completeExceptionally(IOException("Old write failed"))
            runCurrent()
            assertTrue(vm.state.value.enabled)
            assertEquals(PdfNightModeStatus.Saving, vm.state.value.status)
            assertEquals(listOf(true, true), store.writes.map(Write::enabled))
            store.writes[1].completion.complete(Unit)
            runCurrent()
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
        }
    }

    @Test fun `clearing reader cancels delayed load without error or fallback writes`() = runTest {
        val read = CompletableDeferred<Boolean>()
        var cancelled = false
        val store = Store(true).apply {
            readAction = { try { read.await() } finally { cancelled = true } }
        }
        withModel(store) { vm, owner ->
            runCurrent()
            owner.clear()
            runCurrent()
            assertTrue(cancelled)
            assertEquals(PdfNightModeStatus.Loading, vm.state.value.status)
            assertNull(vm.saveInstanceChoice())
            assertTrue(store.writes.isEmpty())
        }
    }

    @Test fun `clearing reader cancels pending writer without success or preference error`() = runTest {
        val store = Store()
        withModel(store) { vm, owner ->
            runCurrent()
            vm.toggle()
            runCurrent()
            assertEquals(1, store.activeWrites)
            owner.clear()
            runCurrent()
            assertEquals(0, store.activeWrites)
            assertFalse(store.persisted)
            assertTrue(vm.state.value.enabled)
            assertEquals(PdfNightModeStatus.Saving, vm.state.value.status)
            assertEquals(1, store.writes.size)
        }
    }

    @Test fun `repository cancellation is rethrown and a newer choice confirms uncertain value`() = runTest {
        val store = Store()
        withModel(store) { vm, _ ->
            runCurrent()
            vm.toggle()
            runCurrent()
            // Simulate cancellation reported after an underlying store committed.
            store.persisted = true
            store.writes[0].completion.completeExceptionally(CancellationException("Cancelled writer"))
            runCurrent()
            assertEquals(PdfNightModeStatus.Saving, vm.state.value.status)
            vm.toggle()
            runCurrent()
            assertEquals(listOf(true, false), store.writes.map(Write::enabled))
            store.writes[1].completion.complete(Unit)
            runCurrent()
            assertFalse(store.persisted)
            assertEquals(PdfNightModeStatus.Ready, vm.state.value.status)
        }
    }

    @Test fun `cancelled owner never reports success even when repository finishes its commit`() = runTest {
        val store = Store().apply {
            writeAction = { write -> withContext(NonCancellable) { write.completion.await() } }
        }
        withModel(store) { vm, owner ->
            runCurrent()
            vm.toggle()
            runCurrent()
            owner.clear()
            store.writes.single().completion.complete(Unit)
            runCurrent()
            assertTrue(store.persisted)
            assertEquals(0, store.activeWrites)
            assertEquals(PdfNightModeStatus.Saving, vm.state.value.status)
            assertTrue(vm.state.value.enabled)
        }
    }
}

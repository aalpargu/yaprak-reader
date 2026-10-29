package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.settings.ReaderOrientation
import com.yaprak.reader.domain.settings.ReaderOrientationSettingsRepository
import com.yaprak.reader.feature.settings.ReaderOrientationViewModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class ReaderOrientationViewModelTest {
    private class Repository : ReaderOrientationSettingsRepository {
        val values = MutableStateFlow(ReaderOrientation.LANDSCAPE)
        var failRead = false
        var failWrite = false
        var cancelWrite = false
        var hold: CompletableDeferred<Unit>? = null
        var emitBeforeReturn = false
        val writes = mutableListOf<ReaderOrientation>()
        override val orientation = flow { if (failRead) throw IOException(); emitAll(values) }
        override suspend fun saveOrientation(orientation: ReaderOrientation) {
            writes += orientation
            if (emitBeforeReturn) values.value = orientation
            hold?.await()
            if (cancelWrite) throw CancellationException("cancelled write")
            if (failWrite) throw IOException()
            values.value = orientation
        }
    }

    @Test fun `draft and cancel never publish or persist a target`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val model = ReaderOrientationViewModel(repository).also { owner.put("orientation", it) }
            runCurrent()
            assertEquals(ReaderOrientation.LANDSCAPE, model.state.value.saved)
            model.open(); val revision = model.state.value.revision
            model.edit(ReaderOrientation.PORTRAIT)
            assertEquals(ReaderOrientation.LANDSCAPE, model.state.value.saved)
            model.dismiss(); model.open()
            assertEquals(ReaderOrientation.LANDSCAPE, model.state.value.draft)
            assertTrue(model.state.value.revision > revision)
            assertTrue(repository.writes.isEmpty())
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `save waits for durable return even when flow emits early and blocks every competing edit`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { hold = CompletableDeferred(); emitBeforeReturn = true }
            val model = ReaderOrientationViewModel(repository).also { owner.put("orientation", it) }
            runCurrent(); model.open(); model.edit(ReaderOrientation.PORTRAIT); model.save(); runCurrent()
            assertTrue(model.state.value.saving && model.state.value.open)
            assertEquals(ReaderOrientation.LANDSCAPE, model.state.value.saved)
            model.edit(ReaderOrientation.AUTO); model.dismiss(); model.reload(); model.save(); model.open()
            assertEquals(ReaderOrientation.PORTRAIT, model.state.value.draft)
            assertEquals(listOf(ReaderOrientation.PORTRAIT), repository.writes)
            repository.hold!!.complete(Unit); advanceUntilIdle()
            assertEquals(ReaderOrientation.PORTRAIT, model.state.value.saved)
            assertFalse(model.state.value.open || model.state.value.saving)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `failed write preserves former saved target and allows retry of the draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failWrite = true }
            val model = ReaderOrientationViewModel(repository).also { owner.put("orientation", it) }
            runCurrent(); model.open(); model.edit(ReaderOrientation.AUTO); model.save(); runCurrent()
            assertTrue(model.state.value.saveFailed && model.state.value.open)
            assertEquals(ReaderOrientation.LANDSCAPE, model.state.value.saved)
            assertEquals(ReaderOrientation.AUTO, model.state.value.draft)
            repository.failWrite = false; model.save(); runCurrent()
            assertEquals(ReaderOrientation.AUTO, model.state.value.saved)
            assertFalse(model.state.value.open || model.state.value.saveFailed)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `source error retains prior target and retry loads without overwriting an unsaved draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val model = ReaderOrientationViewModel(repository).also { owner.put("orientation", it) }
            runCurrent(); repository.failRead = true; model.reload(); runCurrent()
            model.open(); model.edit(ReaderOrientation.PORTRAIT); model.save()
            assertTrue(model.state.value.loadFailed)
            assertEquals(ReaderOrientation.LANDSCAPE, model.state.value.saved)
            assertTrue(repository.writes.isEmpty())
            repository.failRead = false; model.reload(); runCurrent()
            model.edit(ReaderOrientation.PORTRAIT); repository.values.value = ReaderOrientation.AUTO; runCurrent()
            assertEquals(ReaderOrientation.AUTO, model.state.value.saved)
            assertEquals(ReaderOrientation.PORTRAIT, model.state.value.draft)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `cancelled write does not report success or persistence failure and releases busy state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { cancelWrite = true }
            val model = ReaderOrientationViewModel(repository).also { owner.put("orientation", it) }
            runCurrent(); model.open(); model.edit(ReaderOrientation.PORTRAIT); model.save(); advanceUntilIdle()
            assertEquals(ReaderOrientation.LANDSCAPE, model.state.value.saved)
            assertTrue(model.state.value.open)
            assertFalse(model.state.value.saving || model.state.value.saveFailed)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `source completing without a preference is an error and cannot silently save AUTO`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            var writes = 0
            val repository = object : ReaderOrientationSettingsRepository {
                override val orientation = emptyFlow<ReaderOrientation>()
                override suspend fun saveOrientation(orientation: ReaderOrientation) { writes++ }
            }
            val model = ReaderOrientationViewModel(repository).also { owner.put("orientation", it) }
            runCurrent(); model.open(); model.save()
            assertTrue(model.state.value.loadFailed)
            assertFalse(model.state.value.loading)
            assertEquals(0, writes)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `replaced noncooperative source cannot deliver an old value or old error into the new load`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            for (lateError in listOf(false, true)) {
                val release = CompletableDeferred<Unit>()
                var collects = 0
                val repository = object : ReaderOrientationSettingsRepository {
                    override val orientation = object : Flow<ReaderOrientation> {
                        override suspend fun collect(collector: FlowCollector<ReaderOrientation>) {
                            val request = ++collects
                            collector.emit(if (request == 1) ReaderOrientation.LANDSCAPE else ReaderOrientation.AUTO)
                            try { awaitCancellation() }
                            catch (cancelled: CancellationException) {
                                if (request != 1) throw cancelled
                                withContext(NonCancellable) {
                                    release.await()
                                    if (lateError) throw IOException("old source failure")
                                    collector.emit(ReaderOrientation.PORTRAIT)
                                }
                            }
                        }
                    }
                    override suspend fun saveOrientation(orientation: ReaderOrientation) = Unit
                }
                val model = ReaderOrientationViewModel(repository).also { owner.put("orientation-$lateError", it) }
                runCurrent(); model.reload(); runCurrent()
                assertEquals(ReaderOrientation.AUTO, model.state.value.saved)
                release.complete(Unit); runCurrent()
                assertEquals(ReaderOrientation.AUTO, model.state.value.saved)
                assertFalse(model.state.value.loadFailed || model.state.value.loading)
            }
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}

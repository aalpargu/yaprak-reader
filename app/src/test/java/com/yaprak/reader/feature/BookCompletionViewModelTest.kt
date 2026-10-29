package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.statistics.*
import com.yaprak.reader.feature.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookCompletionViewModelTest {
    @Test fun retryReplacesErrorAndCollectionStopsWhenHistoryIsClosed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        var fail = true; var active = 0
        val repo = object : BookCompletionRepository {
            override fun observe() = flow {
                active++
                try { if (fail) throw BookCompletionException(BookCompletionFailure.RECOVERY)
                    emit(BookCompletionSnapshot(0, emptyList())); awaitCancellation()
                } finally { active-- }
            }
        }
        try {
            val vm = BookCompletionViewModel(repo).also { owner.put("history", it) }
            val collect = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            runCurrent(); assertEquals(BookCompletionFailure.RECOVERY, vm.state.value.failure)
            assertNull(vm.state.value.snapshot); assertEquals(0, active)
            fail = false; vm.retry(); runCurrent()
            assertEquals(0, vm.state.value.snapshot!!.total); assertEquals(1, active)
            collect.cancel(); runCurrent(); assertEquals(0, active)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun malformedSnapshotNeverLooksLikeAnEmptySuccessfulHistory() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        val repo = object : BookCompletionRepository {
            override fun observe() = flowOf(BookCompletionSnapshot(1, emptyList()))
        }
        try {
            val vm = BookCompletionViewModel(repo).also { owner.put("history", it) }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            runCurrent()
            assertEquals(BookCompletionFailure.SOURCE, vm.state.value.failure)
            assertFalse(vm.state.value.loading); assertNull(vm.state.value.snapshot)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}

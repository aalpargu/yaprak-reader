package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.statistics.*
import com.yaprak.reader.feature.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class DailyReadingGoalViewModelTest {
    private class Repository : DailyReadingGoalRepository {
        val source = MutableStateFlow<Int?>(null)
        val writes = mutableListOf<Int?>()
        var subscriptions = 0
        var failure: Exception? = null
        var save: suspend (Int?) -> Unit = {}
        var overrideFlow: Flow<Int?>? = null
        override val dailyMinutes: Flow<Int?> get() = overrideFlow ?: flow {
            subscriptions++
            failure?.let { throw it }
            source.collect { emit(it) }
        }
        override suspend fun saveDailyMinutes(minutes: Int?) {
            validateDailyReadingGoal(minutes)
            writes += minutes
            save(minutes)
            source.value = minutes
        }
    }
    private suspend fun TestScope.withModel(block: suspend TestScope.(DailyReadingGoalViewModel, Repository, Any) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val repository = Repository()
        val owner = Any()
        try {
            val model = DailyReadingGoalViewModel(repository).also { store.put("goal", it) }
            model.attachLifecycleOwner(owner); model.setLifecycleOwnerActive(owner, true); runCurrent()
            block(model, repository, owner)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun open(model: DailyReadingGoalViewModel, owner: Any): DailyReadingGoalDraft {
        model.open(owner, model.state.value.revision)
        return checkNotNull(model.state.value.editor)
    }
    private fun enable(model: DailyReadingGoalViewModel, owner: Any): DailyReadingGoalDraft {
        val draft = open(model, owner)
        model.editEnabled(owner, draft.requestId, draft.revision, true)
        return checkNotNull(model.state.value.editor)
    }

    @Test fun `missing persisted key means off only after read and first unsaved editor seeds fifteen minutes`() = runTest {
        withModel { model, repository, owner ->
            assertTrue(model.state.value.known); assertNull(model.state.value.savedMinutes)
            val draft = open(model, owner)
            assertFalse(draft.enabled); assertEquals("15", draft.minutesText)
            model.dismiss(owner, draft.requestId, draft.revision)
            assertNull(model.state.value.editor); assertNull(repository.source.value); assertTrue(repository.writes.isEmpty())
        }
    }
    @Test fun `only completed durable write becomes saved and a repeated stale apply cannot start another write`() = runTest {
        withModel { model, repository, owner ->
            val gate = CompletableDeferred<Unit>(); repository.save = { gate.await() }
            val draft = enable(model, owner)
            model.save(owner, draft.requestId, draft.revision); runCurrent()
            assertTrue(model.state.value.saving); assertNull(model.state.value.savedMinutes)
            model.save(owner, draft.requestId, draft.revision); runCurrent()
            assertEquals(listOf(15), repository.writes)
            gate.complete(Unit); runCurrent()
            assertFalse(model.state.value.saving); assertEquals(15, model.state.value.savedMinutes)
            assertNull(model.state.value.editor); assertEquals(15, repository.source.value)
        }
    }
    @Test fun `disable writes null even when an inactive minute draft is invalid`() = runTest {
        withModel { model, repository, owner ->
            repository.source.value = 20; runCurrent()
            var draft = open(model, owner)
            model.editMinutes(owner, draft.requestId, draft.revision, "not minutes")
            draft = checkNotNull(model.state.value.editor)
            model.editEnabled(owner, draft.requestId, draft.revision, false)
            draft = checkNotNull(model.state.value.editor)
            assertTrue(draft.valid)
            model.save(owner, draft.requestId, draft.revision); runCurrent()
            assertEquals(listOf<Int?>(null), repository.writes); assertNull(model.state.value.savedMinutes)
        }
    }
    @Test fun `empty signed decimal unicode overflow and out of range input never writes a hidden replacement goal`() = runTest {
        withModel { model, repository, owner ->
            enable(model, owner)
            for (invalid in listOf("", "0", "1001", "10000", "-1", "+15", "1.5", "1e2", "١٥", "９９", "9999999999999999999999")) {
                var draft = checkNotNull(model.state.value.editor)
                model.editMinutes(owner, draft.requestId, draft.revision, invalid)
                draft = checkNotNull(model.state.value.editor)
                assertEquals(invalid, draft.minutesText); assertFalse(draft.valid)
                model.save(owner, draft.requestId, draft.revision); runCurrent()
                assertFalse(model.state.value.saving); assertTrue(repository.writes.isEmpty())
            }
            assertEquals(1, parseDailyReadingGoalMinutes("1")); assertEquals(1000, parseDailyReadingGoalMinutes("1000"))
            assertEquals(15, parseDailyReadingGoalMinutes(" 15 ")); assertEquals(1, parseDailyReadingGoalMinutes("0001"))
        }
    }
    @Test fun `saved preference changes advance UI revision even off fifteen off ABA and stale open is rejected`() = runTest {
        withModel { model, repository, owner ->
            val original = model.state.value.revision
            repository.source.value = 15; runCurrent(); repository.source.value = null; runCurrent()
            assertTrue(model.state.value.revision > original)
            model.open(owner, original); assertNull(model.state.value.editor)
            val draft = enable(model, owner)
            model.editEnabled(owner, draft.requestId, draft.revision, false)
            var current = checkNotNull(model.state.value.editor)
            model.editEnabled(owner, current.requestId, current.revision, true)
            current = checkNotNull(model.state.value.editor)
            assertEquals(draft.enabled, current.enabled); assertTrue(current.revision > draft.revision)
            model.save(owner, draft.requestId, draft.revision); runCurrent(); assertTrue(repository.writes.isEmpty())
        }
    }
    @Test fun `invalid stored value and storage read failure are distinct unknown states and retry needs current revision`() = runTest {
        withModel { model, repository, owner ->
            repository.source.value = 1001; runCurrent()
            assertEquals(DailyReadingGoalReadFailure.INVALID_VALUE, model.state.value.readFailure)
            assertFalse(model.state.value.known); model.open(owner, model.state.value.revision); assertNull(model.state.value.editor)
            val old = model.state.value.revision
            repository.failure = IllegalStateException("read unavailable")
            model.retry(owner, old); runCurrent()
            assertEquals(DailyReadingGoalReadFailure.SOURCE, model.state.value.readFailure)
            val subscriptions = repository.subscriptions
            model.retry(owner, old); runCurrent(); assertEquals(subscriptions, repository.subscriptions)
            repository.failure = null; repository.source.value = 30
            model.retry(owner, model.state.value.revision); runCurrent()
            assertTrue(model.state.value.known); assertEquals(30, model.state.value.savedMinutes)
        }
    }
    @Test fun `failed write retains exact draft and persisted choice until explicit retry succeeds`() = runTest {
        withModel { model, repository, owner ->
            repository.source.value = 40; runCurrent()
            var draft = open(model, owner)
            model.editMinutes(owner, draft.requestId, draft.revision, "12")
            draft = checkNotNull(model.state.value.editor)
            repository.save = { throw IllegalStateException("write unavailable") }
            model.save(owner, draft.requestId, draft.revision); runCurrent()
            assertTrue(model.state.value.writeFailed); assertFalse(model.state.value.saving)
            assertEquals(draft, model.state.value.editor); assertEquals(40, model.state.value.savedMinutes)
            repository.save = {}
            model.save(owner, draft.requestId, draft.revision); runCurrent()
            assertFalse(model.state.value.writeFailed); assertEquals(12, model.state.value.savedMinutes)
            assertNull(model.state.value.editor); assertEquals(listOf(12, 12), repository.writes)
        }
    }
    @Test fun `pause discards unsaved draft and old UUID cannot edit save or dismiss a resumed replacement editor`() = runTest {
        withModel { model, repository, owner ->
            val old = enable(model, owner)
            model.setLifecycleOwnerActive(owner, false)
            assertNull(model.state.value.editor); assertFalse(model.state.value.active)
            model.save(owner, old.requestId, old.revision)
            model.setLifecycleOwnerActive(owner, true); runCurrent()
            val current = open(model, owner)
            assertNotEquals(old.requestId, current.requestId)
            model.editMinutes(owner, old.requestId, old.revision, "22")
            model.save(owner, old.requestId, old.revision); model.dismiss(owner, old.requestId, old.revision)
            assertEquals(current, model.state.value.editor); assertTrue(repository.writes.isEmpty())
        }
    }
    @Test fun `owner handoff admits a durable in flight save without inheriting or closing a new owner's draft`() = runTest {
        withModel { model, repository, owner ->
            val gate = CompletableDeferred<Unit>(); repository.save = { gate.await() }
            val draft = enable(model, owner)
            model.save(owner, draft.requestId, draft.revision); runCurrent()
            val newer = Any(); model.attachLifecycleOwner(newer); model.setLifecycleOwnerActive(newer, true); runCurrent()
            assertTrue(model.state.value.saving); assertNull(model.state.value.editor)
            model.open(newer, model.state.value.revision); assertNull(model.state.value.editor)
            model.setLifecycleOwnerActive(owner, false); model.detachLifecycleOwner(owner)
            assertTrue(model.isLifecycleOwnerActive(newer))
            gate.complete(Unit); runCurrent()
            assertEquals(15, model.state.value.savedMinutes); assertFalse(model.state.value.saving)
            val current = open(model, newer)
            model.dismiss(owner, current.requestId, current.revision)
            model.save(owner, draft.requestId, draft.revision)
            assertEquals(current, model.state.value.editor); assertEquals(listOf(15), repository.writes)
        }
    }
    @Test fun `cancelled save is propagated and never labeled a failed or completed durable write`() = runTest {
        withModel { model, repository, owner ->
            repository.save = { throw CancellationException("cancelled atomic edit") }
            val draft = enable(model, owner)
            model.save(owner, draft.requestId, draft.revision); runCurrent()
            assertFalse(model.state.value.saving); assertFalse(model.state.value.writeFailed)
            assertEquals(draft, model.state.value.editor); assertNull(model.state.value.savedMinutes)
            assertNull(repository.source.value)
        }
    }
    @Test fun `obsolete observation cannot replace post save source even when its cancelled collector emits late`() = runTest {
        withModel { model, repository, owner ->
            val collectors = mutableListOf<FlowCollector<Int?>>()
            repository.overrideFlow = object : Flow<Int?> {
                override suspend fun collect(collector: FlowCollector<Int?>) {
                    collectors += collector; collector.emit(repository.source.value); awaitCancellation()
                }
            }
            model.setLifecycleOwnerActive(owner, false); model.setLifecycleOwnerActive(owner, true); runCurrent()
            val stale = collectors.single()
            val draft = enable(model, owner)
            model.save(owner, draft.requestId, draft.revision); runCurrent()
            assertEquals(2, collectors.size); assertEquals(15, model.state.value.savedMinutes)
            stale.emit(999)
            assertEquals(15, model.state.value.savedMinutes); assertNull(model.state.value.editor)
        }
    }
    @Test fun `remaining floor percent and achieved use exact Long boundaries through maximum duration`() {
        val zero = dailyReadingGoalProgress(1, 0)
        assertEquals(60_000L, zero.remainingMillis); assertEquals(0, zero.percent); assertFalse(zero.achieved)
        for (minutes in listOf(1, 1000)) {
            val target = minutes.toLong() * 60_000
            val before = dailyReadingGoalProgress(minutes, target - 1)
            assertEquals(1L, before.remainingMillis); assertEquals(99, before.percent); assertFalse(before.achieved)
            val at = dailyReadingGoalProgress(minutes, target)
            assertEquals(0L, at.remainingMillis); assertEquals(100, at.percent); assertTrue(at.achieved)
            val over = dailyReadingGoalProgress(minutes, MAX_READING_DURATION_TOTAL_MILLIS)
            assertEquals(100, over.percent); assertEquals(0L, over.remainingMillis); assertTrue(over.achieved)
        }
        assertTrue(runCatching { dailyReadingGoalProgress(0, 0) }.exceptionOrNull() is DailyReadingGoalException)
        assertTrue(runCatching { dailyReadingGoalProgress(1001, 0) }.exceptionOrNull() is DailyReadingGoalException)
        assertTrue(runCatching { dailyReadingGoalProgress(1, -1) }.exceptionOrNull() is IllegalArgumentException)
    }
}

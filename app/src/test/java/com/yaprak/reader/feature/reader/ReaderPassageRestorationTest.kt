package com.yaprak.reader.feature.reader

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.passages.*
import com.yaprak.reader.feature.passages.EpubPassagesViewModel
import com.yaprak.reader.reader.EpubSourceAnchor
import com.yaprak.reader.reader.SourceTextAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPassageRestorationTest {
    private val passage = EpubPassage("saved", "Original text", SourceTextAnchor("chapter.xhtml", 0, 13,
        "a".repeat(64)), 1, EpubHighlightColor.YELLOW, "Stored note", 3)
    private class Repository(entry: EpubPassage) : EpubPassageRepository {
        val records = MutableStateFlow(listOf(entry))
        var writes = 0
        var updateGate: CompletableDeferred<Unit>? = null
        var updateFails = false
        var commitUpdate = false
        override fun observe(bookId: String, contentSha256: String) = records
        override suspend fun add(bookId: String, contentSha256: String, text: String, anchor: EpubSourceAnchor) { writes++ }
        override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
        override suspend fun updateAnnotation(bookId: String, contentSha256: String, expected: EpubPassage,
            highlightColor: EpubHighlightColor?, attachedNote: String?): EpubPassage {
            writes++; updateGate?.await()
            if (updateFails) error("Controlled update failure")
            val saved = expected.copy(highlightColor = highlightColor, attachedNote = attachedNote, revision = expected.revision + 1)
            if (commitUpdate) records.value = records.value.map { if (it.id == expected.id) saved else it }
            return saved
        }
    }

    @Test fun busyExistingEditRestoresExactNoteAndColorInAFreshModelWithoutReplayingTheWrite() = checkModel { store, repository, model ->
        repository.updateGate = CompletableDeferred()
        model.startSession("old"); model.requestEdit(passage)
        model.editNote("  Exact pending\nnote  "); model.editColor(EpubHighlightColor.GREEN)
        model.update(); runCurrent()
        assertTrue(model.state.value.busy); assertEquals(1, repository.writes)
        val snapshot = model.snapshot(4, 8)
        val reopened = Repository(passage)
        val fresh = EpubPassagesViewModel("book", "hash", reopened).also { store.put("fresh", it) }
        runCurrent(); fresh.startSession("fresh"); assertTrue(fresh.restorePresentation(snapshot)); runCurrent()
        assertEquals(passage, fresh.state.value.pendingEdit)
        assertEquals("  Exact pending\nnote  ", fresh.state.value.editNote)
        assertEquals(EpubHighlightColor.GREEN, fresh.state.value.editColor)
        assertTrue(fresh.state.value.canUpdate); assertEquals(0, reopened.writes)
        fresh.cancelEdit(); assertNull(fresh.state.value.pendingEdit)
        assertEquals(listOf(passage), reopened.records.value); assertEquals(0, reopened.writes)
    }

    @Test fun commitBeforeRestorationKeepsTheCurrentRowAndTheRetainedSuccessWithoutAnyReplay() = checkModel { store, repository, model ->
        repository.updateGate = CompletableDeferred(); repository.commitUpdate = true
        model.startSession("old"); model.requestEdit(passage); model.editNote("Committed annotation")
        model.update(); runCurrent()
        val snapshot = model.snapshot(0, 0)
        model.releaseSession("old"); model.startSession("recreated")
        repository.updateGate!!.complete(Unit); runCurrent()
        val success = model.state.value
        assertTrue(model.restorePresentation(snapshot)); assertEquals(success, model.state.value)
        assertNull(model.state.value.pendingEdit); assertNotNull(model.state.value.message)
        val saved = repository.records.value.single()
        val reopened = Repository(saved)
        val fresh = EpubPassagesViewModel("book", "hash", reopened).also { store.put("fresh", it) }
        runCurrent(); fresh.startSession("fresh"); assertTrue(fresh.restorePresentation(snapshot))
        fresh.update(); runCurrent()
        assertNull(fresh.state.value.pendingEdit); assertFalse(fresh.state.value.canUpdate)
        assertEquals("Committed annotation", fresh.state.value.recoveredNote)
        assertEquals(saved, reopened.records.value.single()); assertEquals(0, reopened.writes)
        assertEquals(1, repository.writes)
    }

    @Test fun releasingTheBusyExistingEditorRevokesItsOldSessionButPreservesFailureRetryAndCancel() = checkModel { _, repository, model ->
        repository.updateGate = CompletableDeferred(); repository.updateFails = true
        model.startSession("old"); model.requestEdit(passage); model.editNote("Exact note after recreation")
        model.update(); runCurrent()
        val snapshot = model.snapshot(0, 0)
        model.releaseSession("old")
        assertNotEquals("old", model.state.value.sessionId)
        model.startSession("new")
        model.releaseSession("old") // Late disposal cannot clear the new editor or its admitted write.
        repository.updateGate!!.complete(Unit); runCurrent()
        assertTrue(model.restorePresentation(snapshot))
        assertEquals("new", model.state.value.sessionId)
        assertEquals("Exact note after recreation", model.state.value.editNote)
        assertEquals(passage, model.state.value.pendingEdit)
        assertNotNull(model.state.value.message); assertTrue(model.state.value.canUpdate)
        assertEquals(1, repository.writes)
        model.update(); runCurrent()
        assertEquals(2, repository.writes); assertTrue(model.state.value.canUpdate)
        model.cancelEdit()
        assertNull(model.state.value.pendingEdit); assertEquals(listOf(passage), repository.records.value)
    }
    private fun checkModel(block: TestScope.(ViewModelStore, Repository, EpubPassagesViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val repository = Repository(passage)
        val model = EpubPassagesViewModel("book", "hash", repository).also { store.put("model", it) }
        try { runCurrent(); block(store, repository, model) } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun encodedRecoveryAndMatchingPassageEditSurviveRouteRebindThenBecomeTwoUnboundEditsThroughRepeatedRestoreAndEitherDiscardOrder() = checkModel { store, repository, model ->
        model.startSession("original"); model.requestEdit(passage)
        model.editNote("  Exact note B  "); model.editColor(EpubHighlightColor.GREEN)
        val encoded = model.snapshot(0, 0).copy(secondaryDraft = "  Protected note A  ", recoveredDraft = "PINK")
        model.cancelEdit(); assertTrue(model.restorePresentation(encoded))
        assertEquals(passage, model.state.value.pendingEdit); assertEquals("  Exact note B  ", model.state.value.editNote)
        assertEquals("  Protected note A  ", model.state.value.recoveredNote); assertEquals("PINK", model.state.value.recoveredColor)
        model.releaseSession("original"); model.startSession("rebound")
        assertTrue(model.restorePresentation(encoded))
        assertEquals("rebound", model.state.value.sessionId)
        assertEquals(passage, model.state.value.pendingEdit); assertEquals("  Exact note B  ", model.state.value.editNote)
        assertEquals("  Protected note A  ", model.state.value.recoveredNote)
        assertTrue(model.state.value.canUpdate)
        repository.records.value = listOf(passage.copy(attachedNote = "Changed row", revision = 4)); runCurrent()
        assertFalse(model.state.value.canUpdate); model.update(); runCurrent()
        val stale = model.snapshot(0, 0)
        fun restored(key: String, snapshot: ReaderDraftSnapshot): EpubPassagesViewModel {
            val result = EpubPassagesViewModel("book", "hash", repository).also { store.put(key, it) }
            runCurrent(); result.startSession(key); assertTrue(result.restorePresentation(snapshot)); return result
        }
        val first = restored("first", stale)
        first.reload(); runCurrent()
        val orphan = first.snapshot(0, 0)
        assertNull(orphan.editId); assertNull(orphan.expectedFingerprint)
        assertEquals("  Exact note B  ", orphan.editDraft); assertEquals("GREEN", orphan.secondaryEditDraft)
        val second = restored("second", orphan)
        assertEquals("  Protected note A  ", second.state.value.recoveredNote); assertEquals("PINK", second.state.value.recoveredColor)
        assertEquals("  Exact note B  ", second.state.value.additionalRecoveredEdit?.note)
        assertEquals("GREEN", second.state.value.additionalRecoveredEdit?.color)
        assertNull(second.state.value.pendingEdit); second.update(); runCurrent()
        for (primaryFirst in listOf(true, false)) {
            val branch = restored("branch-$primaryFirst", second.snapshot(0, 0))
            branch.discardAdditionalRecoveredEdit(checkNotNull(second.state.value.additionalRecoveredEdit))
            assertNotNull(branch.state.value.additionalRecoveredEdit)
            if (primaryFirst) branch.discardRecoveredNote(checkNotNull(branch.state.value.recoveredNote), branch.state.value.recoveryRevision)
            else branch.discardAdditionalRecoveredEdit(checkNotNull(branch.state.value.additionalRecoveredEdit))
            branch.requestEdit(branch.state.value.entries.single()); assertNull(branch.state.value.pendingEdit)
            val survivor = restored("survivor-$primaryFirst", branch.snapshot(0, 0))
            assertEquals(if (primaryFirst) null else "  Protected note A  ", survivor.state.value.recoveredNote)
            assertEquals(if (primaryFirst) "  Exact note B  " else null, survivor.state.value.additionalRecoveredEdit?.note)
            if (primaryFirst) survivor.discardAdditionalRecoveredEdit(checkNotNull(survivor.state.value.additionalRecoveredEdit))
            else survivor.discardRecoveredNote(checkNotNull(survivor.state.value.recoveredNote), survivor.state.value.recoveryRevision)
            survivor.requestEdit(survivor.state.value.entries.single()); assertEquals("Changed row", survivor.state.value.editNote)
        }
        assertEquals(0, repository.writes)
    }

    @Test fun newSessionRehydratesOnlyAnExactCurrentRecordAndNeverWritesOnRestoreOrCancel() = checkModel { _, repository, model ->
        model.startSession("old"); model.search("Original"); model.requestEdit(passage)
        model.editNote("Uncommitted note"); model.editColor(EpubHighlightColor.GREEN)
        val snapshot = model.snapshot(12, 17)
        model.clearSession("old"); model.startSession("new")
        assertTrue(model.restorePresentation(snapshot))
        assertEquals("new", model.state.value.sessionId)
        assertEquals(passage, model.state.value.pendingEdit)
        assertEquals("Original", model.state.value.query)
        assertEquals("Uncommitted note", model.state.value.editNote)
        assertEquals(EpubHighlightColor.GREEN, model.state.value.editColor)
        assertTrue(model.state.value.canUpdate)
        assertEquals(0, repository.writes)
        model.cancelEdit()
        assertEquals(listOf(passage), repository.records.value)
        assertEquals(0, repository.writes)
    }

    @Test fun changedExpectedRowRecoversUserNoteWithoutReattachingTheOldAnchorOrAllowingAWrite() = checkModel { _, repository, model ->
        model.requestEdit(passage); model.editNote("User note to preserve")
        val snapshot = model.snapshot(1, 2)
        model.startSession("new")
        repository.records.value = listOf(passage.copy(attachedNote = "New saved note", revision = 4)); runCurrent()
        assertTrue(model.restorePresentation(snapshot))
        assertNull(model.state.value.pendingEdit)
        assertEquals("User note to preserve", model.state.value.recoveredNote)
        assertNotNull(model.state.value.message)
        model.update(); runCurrent()
        assertEquals(0, repository.writes)
        assertEquals("New saved note", repository.records.value.single().attachedNote)
    }

    @Test fun recoveredPassageNoteSurvivesAnotherSessionAndRestoreAndBlocksANewEditUntilExplicitDiscard() = checkModel { store, repository, model ->
        val other = passage.copy(id = "other", text = "Another saved passage", revision = 1)
        model.startSession("first"); model.requestEdit(passage); model.editNote("  Earlier recovered note  ")
        val edit = model.snapshot(0, 0)
        repository.records.value = listOf(passage.copy(attachedNote = "Current row", revision = 4), other); runCurrent()
        model.startSession("second"); assertTrue(model.restorePresentation(edit))
        val recoveryRevision = model.state.value.recoveryRevision
        model.requestEdit(other)
        assertNull(model.state.value.pendingEdit)
        assertEquals("  Earlier recovered note  ", model.state.value.recoveredNote)
        repository.records.value = listOf(passage.copy(attachedNote = "Current row", revision = 4)); runCurrent()
        model.releaseSession("second"); model.startSession("third")
        assertEquals("  Earlier recovered note  ", model.state.value.recoveredNote)
        val reopened = Repository(repository.records.value.single())
        val fresh = EpubPassagesViewModel("book", "hash", reopened).also { store.put("fresh", it) }
        runCurrent(); fresh.startSession("fresh"); assertTrue(fresh.restorePresentation(model.snapshot(0, 0)))
        assertEquals("  Earlier recovered note  ", fresh.state.value.recoveredNote)
        val row = fresh.state.value.entries.single()
        fresh.requestEdit(row); assertNull(fresh.state.value.pendingEdit)
        fresh.discardRecoveredNote("  Earlier recovered note  ", fresh.state.value.recoveryRevision + 1)
        assertNotNull(fresh.state.value.recoveredNote)
        fresh.discardRecoveredNote("  Earlier recovered note  ", fresh.state.value.recoveryRevision)
        assertNull(fresh.state.value.recoveredNote)
        // Reintroducing the same plaintext advances its identity; an old callback cannot discard it.
        assertTrue(fresh.restorePresentation(model.snapshot(0, 0)))
        fresh.discardRecoveredNote("  Earlier recovered note  ", recoveryRevision)
        assertNotNull(fresh.state.value.recoveredNote)
        fresh.discardRecoveredNote("  Earlier recovered note  ", fresh.state.value.recoveryRevision)
        fresh.requestEdit(row); assertEquals(row, fresh.state.value.pendingEdit)
        assertEquals(0, repository.writes); assertEquals(0, reopened.writes)
    }

    @Test fun admittedRetainedFormWinsAndPendingDeleteIsNotReplayed() = checkModel { _, repository, model ->
        model.requestEdit(passage); model.editNote("Older Bundle")
        val old = model.snapshot(0, 0)
        model.editNote("Latest retained input")
        assertTrue(model.restorePresentation(old))
        assertEquals("Latest retained input", model.state.value.editNote)
        model.cancelEdit(); model.requestDelete(passage.id)
        val deleteSnapshot = model.snapshot(0, 0)
        model.startSession("new")
        assertTrue(model.restorePresentation(deleteSnapshot))
        assertNull(model.state.value.pendingDelete)
        assertNull(model.state.value.pendingEdit)
        assertEquals(0, repository.writes)
    }

    @Test fun everyRecordMustStillMatchEvenWhenTheDisplayQueryHidesIt() = checkModel { _, repository, model ->
        model.requestEdit(passage); model.editNote("Saved draft")
        val snapshot = model.snapshot(0, 0).copy(query = "no matching row")
        model.startSession("new")
        assertTrue(model.restorePresentation(snapshot))
        assertTrue(model.state.value.visibleEntries.isEmpty())
        assertEquals(passage, model.state.value.pendingEdit)
        repository.records.value = emptyList(); runCurrent()
        assertTrue(model.state.value.editChanged)
        assertFalse(model.state.value.canUpdate)
        model.update(); runCurrent()
        assertEquals(0, repository.writes)
    }
}

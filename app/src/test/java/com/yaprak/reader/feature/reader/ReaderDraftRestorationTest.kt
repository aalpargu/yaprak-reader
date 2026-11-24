package com.yaprak.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.bookmarks.*
import com.yaprak.reader.domain.notes.*
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.vocabulary.*
import com.yaprak.reader.feature.bookmarks.BookmarksViewModel
import com.yaprak.reader.feature.notes.BookNotesViewModel
import com.yaprak.reader.feature.vocabulary.SavedWordsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Restoring a form never writes, replays a delete, or overwrites a newer retained form. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDraftRestorationTest {
    private class Notes(val records: MutableStateFlow<List<BookNote>>) : BookNoteRepository {
        var writes = 0
        var updateGate: CompletableDeferred<Unit>? = null
        var commitUpdate = false
        override fun observe(bookId: String, contentSha256: String) = records
        override suspend fun add(bookId: String, contentSha256: String, text: String) { writes++ }
        override suspend fun update(bookId: String, contentSha256: String, id: String, text: String) {
            writes++; updateGate?.await()
            if (commitUpdate) records.value = records.value.map { if (it.id == id) it.copy(text = text) else it }
        }
        override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
    }
    private class Words(val records: MutableStateFlow<List<SavedWord>>) : SavedWordRepository {
        var writes = 0
        var updateGate: CompletableDeferred<Unit>? = null
        var commitUpdate = false
        override fun observe(bookId: String, contentSha256: String) = records
        override suspend fun add(bookId: String, contentSha256: String, english: String, turkish: String) { writes++ }
        override suspend fun update(bookId: String, contentSha256: String, id: String, english: String, turkish: String) {
            writes++; updateGate?.await()
            if (commitUpdate) records.value = records.value.map {
                if (it.id == id) it.copy(english = english, turkish = turkish, revision = it.revision + 1) else it
            }
        }
        override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
    }
    private class Marks(val records: MutableStateFlow<List<Bookmark>>) : BookmarkRepository {
        var writes = 0
        var updateGate: CompletableDeferred<Unit>? = null
        var commitUpdate = false
        override fun observe(bookId: String, contentSha256: String) = records
        override suspend fun add(bookId: String, contentSha256: String, target: BookmarkTarget) { writes++ }
        override suspend fun rename(bookId: String, contentSha256: String, bookmarkId: String, label: String) {
            writes++; updateGate?.await()
            if (commitUpdate) records.value = records.value.map {
                if (it.id == bookmarkId) it.copy(target = it.target.copy(label = label)) else it
            }
        }
        override suspend fun remove(bookId: String, contentSha256: String, bookmarkId: String) { writes++ }
    }

    private fun TestScope.fixture(block: (ViewModelStore) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try { block(store) } finally { store.clear(); Dispatchers.resetMain() }
    }
    private fun <T : ViewModel> ViewModelStore.keep(model: T): T = model.also { put("model", it) }

    @Test fun encodedWordRecoveryAndMatchingEditBecomeTwoUnboundRecoveriesAtTheTotalBudgetAndSurviveEitherDiscardOrder() = runTest {
        fixture { store ->
            val row = SavedWord("word", "Stored", "Kayıtlı", 1, 4)
            val repository = Words(MutableStateFlow(listOf(row)))
            val aEnglish = "a".repeat(4096); val aTurkish = "b".repeat(4096)
            val bEnglish = "c".repeat(8192); val bTurkish = "d".repeat(8192)
            fun restored(key: String, snapshot: ReaderDraftSnapshot): SavedWordsViewModel {
                val result = SavedWordsViewModel("book", "hash", repository).also { store.put(key, it) }
                runCurrent(); result.restorePresentation(snapshot); return result
            }
            val matching = restored("matching", ReaderDraftSnapshot("", "RECENT", draft = "e".repeat(4096), secondaryDraft = "f".repeat(4096),
                recoveredDraft = aEnglish, secondaryRecoveredDraft = aTurkish, editId = row.id,
                expectedFingerprint = readerDraftFingerprint(row), editDraft = bEnglish, secondaryEditDraft = bTurkish))
            assertEquals(row, matching.state.value.pendingEdit)
            assertEquals(aEnglish, matching.state.value.recoveredEdit?.english)
            assertEquals(bEnglish, matching.state.value.editEnglishDraft)
            val encoded = matching.snapshot(0, 0)
            repository.records.value = listOf(row.copy(english = "Changed row", revision = 5)); runCurrent()
            val first = restored("first", encoded)
            first.editEnglish("g" + first.state.value.englishDraft.drop(1))
            val secondSnapshot = first.snapshot(0, 0)
            assertNull(secondSnapshot.editId); assertNull(secondSnapshot.expectedFingerprint)
            assertEquals(bEnglish, secondSnapshot.editDraft); assertEquals(bTurkish, secondSnapshot.secondaryEditDraft)
            val second = restored("second", secondSnapshot)
            assertEquals("g" + "e".repeat(4095), second.state.value.englishDraft)
            assertEquals("f".repeat(4096), second.state.value.turkishDraft)
            assertEquals(aEnglish, second.state.value.recoveredEdit?.english); assertEquals(aTurkish, second.state.value.recoveredEdit?.turkish)
            assertEquals(bEnglish, second.state.value.additionalRecoveredEdit?.english); assertEquals(bTurkish, second.state.value.additionalRecoveredEdit?.turkish)
            assertNull(second.state.value.pendingEdit); second.saveEdit(); runCurrent()
            for (primaryFirst in listOf(true, false)) {
                val branch = restored("branch-$primaryFirst", second.snapshot(0, 0))
                branch.discardRecoveredEdit(checkNotNull(second.state.value.additionalRecoveredEdit))
                assertNotNull(branch.state.value.additionalRecoveredEdit)
                branch.discardRecoveredEdit(checkNotNull(if (primaryFirst) branch.state.value.recoveredEdit else branch.state.value.additionalRecoveredEdit))
                branch.requestEdit(row.id); assertNull(branch.state.value.pendingEdit)
                val survivor = restored("survivor-$primaryFirst", branch.snapshot(0, 0))
                assertEquals(if (primaryFirst) null else aEnglish, survivor.state.value.recoveredEdit?.english)
                assertEquals(if (primaryFirst) bEnglish else null, survivor.state.value.additionalRecoveredEdit?.english)
                survivor.discardRecoveredEdit(checkNotNull(if (primaryFirst) survivor.state.value.additionalRecoveredEdit else survivor.state.value.recoveredEdit))
                survivor.requestEdit(row.id); assertEquals("Changed row", survivor.state.value.editEnglishDraft)
            }
            assertEquals(0, repository.writes)
        }
    }

    @Test fun encodedBookmarkRecoveryAndMatchingRenamePreserveBothLabelsAfterRowRemovalAndEitherDiscardOrder() = runTest {
        fixture { store ->
            val row = Bookmark("bookmark", BookmarkTarget("Stored", ReadingLocation("old", "page")), 1)
            val other = row.copy(id = "other", target = row.target.copy(label = "Other"))
            val repository = Marks(MutableStateFlow(listOf(row, other)))
            fun restored(key: String, snapshot: ReaderDraftSnapshot): BookmarksViewModel {
                val result = BookmarksViewModel("book", "hash", repository).also { store.put(key, it) }
                runCurrent(); result.restorePresentation(snapshot); return result
            }
            val matching = restored("matching", ReaderDraftSnapshot("", "RECORD_ORDER", recoveredDraft = "  Recovery A  ",
                editId = row.id, expectedFingerprint = readerDraftFingerprint(row), editDraft = "  Exact rename B  "))
            assertEquals(row, matching.state.value.pendingRename); assertEquals("  Exact rename B  ", matching.state.value.renameDraft)
            val encoded = matching.snapshot(0, 0)
            repository.records.value = listOf(other); runCurrent()
            val first = restored("first", encoded)
            first.search("Other")
            val orphan = first.snapshot(0, 0)
            assertNull(orphan.editId); assertNull(orphan.expectedFingerprint)
            assertEquals("  Exact rename B  ", orphan.editDraft)
            val second = restored("second", orphan)
            assertEquals("  Recovery A  ", second.state.value.recoveredRename?.label)
            assertEquals("  Exact rename B  ", second.state.value.additionalRecoveredRename?.label)
            assertNull(second.state.value.pendingRename); second.saveRename(); runCurrent()
            for (primaryFirst in listOf(true, false)) {
                val branch = restored("branch-$primaryFirst", second.snapshot(0, 0))
                branch.discardRecoveredRename(checkNotNull(second.state.value.additionalRecoveredRename))
                assertNotNull(branch.state.value.additionalRecoveredRename)
                branch.discardRecoveredRename(checkNotNull(if (primaryFirst) branch.state.value.recoveredRename else branch.state.value.additionalRecoveredRename))
                branch.requestRename(other.id); assertNull(branch.state.value.pendingRename)
                val survivor = restored("survivor-$primaryFirst", branch.snapshot(0, 0))
                assertEquals(if (primaryFirst) null else "  Recovery A  ", survivor.state.value.recoveredRename?.label)
                assertEquals(if (primaryFirst) "  Exact rename B  " else null, survivor.state.value.additionalRecoveredRename?.label)
                survivor.discardRecoveredRename(checkNotNull(if (primaryFirst) survivor.state.value.additionalRecoveredRename else survivor.state.value.recoveredRename))
                survivor.requestRename(other.id); assertEquals(other, survivor.state.value.pendingRename)
            }
            assertEquals(listOf(other), repository.records.value); assertEquals(0, repository.writes)
        }
    }

    @Test fun encodedNoteRecoveryAndOversizedMatchingEditKeepThreeExactBoundedInputsAcrossTwoRestoresAndEitherDiscardOrder() = runTest {
        fixture { store ->
            val row = BookNote("note", "Stored", 1)
            val repository = Notes(MutableStateFlow(listOf(row)))
            val base = "n".repeat(8192); val a = "a".repeat(8192); val b = "b".repeat(8192)
            fun restored(key: String, snapshot: ReaderDraftSnapshot): BookNotesViewModel {
                val result = BookNotesViewModel("book", "hash", repository).also { store.put(key, it) }
                runCurrent(); result.restorePresentation(snapshot); return result
            }
            val matching = restored("matching", ReaderDraftSnapshot("", "RECORD_ORDER", draft = base, recoveredDraft = a,
                editId = row.id, expectedFingerprint = readerDraftFingerprint(row), editDraft = b))
            assertEquals(row, matching.state.value.pendingEdit); assertEquals(b, matching.state.value.editDraft)
            val encoded = matching.snapshot(0, 0)
            repository.records.value = listOf(row.copy(text = "Changed row")); runCurrent()
            val first = restored("first", encoded)
            first.editDraft("x" + base.drop(1))
            val orphan = first.snapshot(0, 0)
            assertNull(orphan.editId); assertNull(orphan.expectedFingerprint)
            assertEquals(8192, orphan.draft.length); assertEquals(a, orphan.recoveredDraft); assertEquals(b, orphan.editDraft)
            val second = restored("second", orphan)
            assertEquals("x" + base.drop(1), second.state.value.draft)
            assertEquals(a, second.state.value.recoveredEdit?.text); assertEquals(b, second.state.value.additionalRecoveredEdit?.text)
            assertNull(second.state.value.pendingEdit); second.saveEdit(); runCurrent()
            for (primaryFirst in listOf(true, false)) {
                val branch = restored("branch-$primaryFirst", second.snapshot(0, 0))
                branch.discardRecoveredEdit(checkNotNull(second.state.value.additionalRecoveredEdit))
                assertNotNull(branch.state.value.additionalRecoveredEdit)
                branch.discardRecoveredEdit(checkNotNull(if (primaryFirst) branch.state.value.recoveredEdit else branch.state.value.additionalRecoveredEdit))
                branch.requestEdit(row.id); assertNull(branch.state.value.pendingEdit)
                val survivor = restored("survivor-$primaryFirst", branch.snapshot(0, 0))
                assertEquals(if (primaryFirst) null else a, survivor.state.value.recoveredEdit?.text)
                assertEquals(if (primaryFirst) b else null, survivor.state.value.additionalRecoveredEdit?.text)
                survivor.discardRecoveredEdit(checkNotNull(if (primaryFirst) survivor.state.value.additionalRecoveredEdit else survivor.state.value.recoveredEdit))
                survivor.requestEdit(row.id); assertEquals("Changed row", survivor.state.value.editDraft)
                assertEquals("x" + base.drop(1), survivor.state.value.draft)
            }
            assertEquals(0, repository.writes)
        }
    }

    @Test fun pendingNoteUpdateRestoresExactInputIntoAFreshModelWithoutReplayingAWrite() = runTest {
        fixture { store ->
            val row = BookNote("note", "Stored", 1)
            val original = Notes(MutableStateFlow(listOf(row))).apply { updateGate = CompletableDeferred() }
            val model = store.keep(BookNotesViewModel("book", "hash", original))
            runCurrent(); model.restorePresentation(null); model.requestEdit(row.id)
            model.editSelectedDraft("  Uncommitted\nexact note  "); model.saveEdit(); runCurrent()
            assertTrue(model.state.value.busy); assertEquals(1, original.writes)
            val snapshot = model.snapshot(7, 11)
            val reopened = Notes(original.records)
            val fresh = BookNotesViewModel("book", "hash", reopened).also { store.put("fresh", it) }
            runCurrent(); fresh.restorePresentation(snapshot); runCurrent()
            assertEquals(row, fresh.state.value.pendingEdit)
            assertEquals("  Uncommitted\nexact note  ", fresh.state.value.editDraft)
            assertTrue(fresh.state.value.canUpdate); assertEquals(0, reopened.writes)
            fresh.cancelEdit(); assertEquals(listOf(row), reopened.records.value); assertEquals(0, reopened.writes)
        }
    }

    @Test fun committedNoteUpdateWinsOverItsEarlierBusySnapshot() = runTest {
        fixture { store ->
            val row = BookNote("note", "Stored", 1)
            val original = Notes(MutableStateFlow(listOf(row))).apply {
                updateGate = CompletableDeferred(); commitUpdate = true
            }
            val model = store.keep(BookNotesViewModel("book", "hash", original))
            runCurrent(); model.restorePresentation(null); model.requestEdit(row.id)
            model.editSelectedDraft("Committed note"); model.saveEdit(); runCurrent()
            val snapshot = model.snapshot(0, 0)
            original.updateGate!!.complete(Unit); runCurrent()
            val success = model.state.value
            model.restorePresentation(snapshot); assertEquals(success, model.state.value)
            val reopened = Notes(original.records)
            val fresh = BookNotesViewModel("book", "hash", reopened).also { store.put("fresh", it) }
            runCurrent(); fresh.restorePresentation(snapshot); fresh.saveEdit(); runCurrent()
            assertNull(fresh.state.value.pendingEdit); assertFalse(fresh.state.value.canUpdate)
            assertEquals("Committed note", reopened.records.value.single().text)
            assertEquals(0, reopened.writes); assertEquals(1, original.writes)
        }
    }

    @Test fun pendingWordUpdateRestoresBothExactFieldsWithoutReplayingAWrite() = runTest {
        fixture { store ->
            val row = SavedWord("word", "leaf", "yaprak", 1, 4)
            val original = Words(MutableStateFlow(listOf(row))).apply { updateGate = CompletableDeferred() }
            val model = store.keep(SavedWordsViewModel("book", "hash", original))
            runCurrent(); model.restorePresentation(null); model.requestEdit(row.id)
            model.editSelectedEnglish("  exact leaf  "); model.editSelectedTurkish("  tam taslak  ")
            model.saveEdit(); runCurrent()
            assertTrue(model.state.value.busy); assertEquals(1, original.writes)
            val reopened = Words(original.records)
            val fresh = SavedWordsViewModel("book", "hash", reopened).also { store.put("fresh", it) }
            runCurrent(); fresh.restorePresentation(model.snapshot(3, 6)); runCurrent()
            assertEquals(row, fresh.state.value.pendingEdit)
            assertEquals("  exact leaf  ", fresh.state.value.editEnglishDraft)
            assertEquals("  tam taslak  ", fresh.state.value.editTurkishDraft)
            assertTrue(fresh.state.value.canUpdate); assertEquals(0, reopened.writes)
            fresh.cancelEdit(); assertEquals(listOf(row), reopened.records.value); assertEquals(0, reopened.writes)
        }
    }

    @Test fun committedWordUpdateCannotBeReplayedByItsEarlierBusySnapshot() = runTest {
        fixture { store ->
            val row = SavedWord("word", "leaf", "yaprak", 1, 4)
            val original = Words(MutableStateFlow(listOf(row))).apply {
                updateGate = CompletableDeferred(); commitUpdate = true
            }
            val model = store.keep(SavedWordsViewModel("book", "hash", original))
            runCurrent(); model.restorePresentation(null); model.requestEdit(row.id)
            model.editSelectedEnglish("new leaf"); model.editSelectedTurkish("yeni yaprak"); model.saveEdit(); runCurrent()
            val snapshot = model.snapshot(0, 0)
            original.updateGate!!.complete(Unit); runCurrent()
            val success = model.state.value
            model.restorePresentation(snapshot); assertEquals(success, model.state.value)
            val reopened = Words(original.records)
            val fresh = SavedWordsViewModel("book", "hash", reopened).also { store.put("fresh", it) }
            runCurrent(); fresh.restorePresentation(snapshot); fresh.saveEdit(); runCurrent()
            assertNull(fresh.state.value.pendingEdit); assertFalse(fresh.state.value.canUpdate)
            assertEquals(row.copy(english = "new leaf", turkish = "yeni yaprak", revision = 5), reopened.records.value.single())
            assertEquals(0, reopened.writes); assertEquals(1, original.writes)
        }
    }

    @Test fun pendingBookmarkRenameRestoresExactLabelWithoutReplayingAWrite() = runTest {
        fixture { store ->
            val row = Bookmark("bookmark", BookmarkTarget("Stored", ReadingLocation("old", "page")), 1)
            val original = Marks(MutableStateFlow(listOf(row))).apply { updateGate = CompletableDeferred() }
            val model = store.keep(BookmarksViewModel("book", "hash", original))
            runCurrent(); model.restorePresentation(null); model.requestRename(row.id)
            model.editRename("  Exact new label  "); model.saveRename(); runCurrent()
            assertTrue(model.state.value.busy); assertEquals(1, original.writes)
            val reopened = Marks(original.records)
            val fresh = BookmarksViewModel("book", "hash", reopened).also { store.put("fresh", it) }
            runCurrent(); fresh.restorePresentation(model.snapshot(2, 5)); runCurrent()
            assertEquals(row, fresh.state.value.pendingRename)
            assertEquals("  Exact new label  ", fresh.state.value.renameDraft)
            assertTrue(fresh.state.value.canRename); assertEquals(0, reopened.writes)
            fresh.cancelRename(); assertEquals(listOf(row), reopened.records.value); assertEquals(0, reopened.writes)
        }
    }

    @Test fun committedBookmarkRenameCannotOverwriteTheCurrentRowFromAnEarlierBusySnapshot() = runTest {
        fixture { store ->
            val row = Bookmark("bookmark", BookmarkTarget("Stored", ReadingLocation("old", "page")), 1)
            val original = Marks(MutableStateFlow(listOf(row))).apply {
                updateGate = CompletableDeferred(); commitUpdate = true
            }
            val model = store.keep(BookmarksViewModel("book", "hash", original))
            runCurrent(); model.restorePresentation(null); model.requestRename(row.id)
            model.editRename("Committed label"); model.saveRename(); runCurrent()
            val snapshot = model.snapshot(0, 0)
            original.updateGate!!.complete(Unit); runCurrent()
            val success = model.state.value
            model.restorePresentation(snapshot); assertEquals(success, model.state.value)
            val reopened = Marks(original.records)
            val fresh = BookmarksViewModel("book", "hash", reopened).also { store.put("fresh", it) }
            runCurrent(); fresh.restorePresentation(snapshot); fresh.saveRename(); runCurrent()
            assertNull(fresh.state.value.pendingRename); assertFalse(fresh.state.value.canRename)
            assertEquals("Committed label", reopened.records.value.single().target.label)
            assertEquals("Committed label", fresh.state.value.recoveredRename?.label)
            assertEquals(0, reopened.writes); assertEquals(1, original.writes)
        }
    }

    @Test fun conflictingWordEditSurvivesNewDraftTypingAndAnotherFreshRestoreAlongsideBothNewFields() = runTest {
        fixture { store ->
            val old = SavedWord("word", "leaf", "yaprak", 1, 4)
            val repository = Words(MutableStateFlow(listOf(old.copy(turkish = "Current row", revision = 5))))
            val first = store.keep(SavedWordsViewModel("book", "hash", repository))
            runCurrent()
            first.restorePresentation(ReaderDraftSnapshot("", "RECENT", draft = "new word", secondaryDraft = "yeni kelime",
                editId = old.id, expectedFingerprint = readerDraftFingerprint(old), editDraft = "  Old edited word  ",
                secondaryEditDraft = "  Eski düzenleme  "))
            val oldRecovery = checkNotNull(first.state.value.recoveredEdit)
            first.editEnglish("new word typed"); first.editTurkish("yeni kelime yazıldı")
            assertNull(first.state.value.message)
            val fresh = SavedWordsViewModel("book", "hash", repository).also { store.put("fresh", it) }
            runCurrent(); fresh.restorePresentation(first.snapshot(0, 0)); runCurrent()
            assertEquals("new word typed", fresh.state.value.englishDraft)
            assertEquals("yeni kelime yazıldı", fresh.state.value.turkishDraft)
            assertEquals("  Old edited word  ", fresh.state.value.recoveredEdit?.english)
            assertEquals("  Eski düzenleme  ", fresh.state.value.recoveredEdit?.turkish)
            assertNull(fresh.state.value.pendingEdit); assertNull(fresh.state.value.pendingDelete)
            fresh.discardRecoveredEdit(oldRecovery) // An old Window cannot consume a newly restored recovery value.
            assertNotNull(fresh.state.value.recoveredEdit)
            fresh.discardRecoveredEdit(checkNotNull(fresh.state.value.recoveredEdit))
            assertNull(fresh.state.value.recoveredEdit)
            assertEquals("new word typed", fresh.state.value.englishDraft)
            assertEquals(0, repository.writes)
        }
    }

    @Test fun staleBookmarkRecoverySurvivesTwoRestoresAndBlocksAnotherRenameUntilExplicitDiscard() = runTest {
        fixture { store ->
            val old = Bookmark("bookmark", BookmarkTarget("Old", ReadingLocation("old", "page")), 1)
            val other = Bookmark("other", BookmarkTarget("Other label", ReadingLocation("other", "page")), 2)
            val repository = Marks(MutableStateFlow(listOf(old.copy(target = old.target.copy(label = "Current row")), other)))
            val first = store.keep(BookmarksViewModel("book", "hash", repository))
            runCurrent(); first.restorePresentation(ReaderDraftSnapshot("", "RECORD_ORDER", editId = old.id,
                expectedFingerprint = readerDraftFingerprint(old), editDraft = "  Recovered label  "))
            val oldRecovery = checkNotNull(first.state.value.recoveredRename)
            val second = BookmarksViewModel("book", "hash", repository).also { store.put("second", it) }
            runCurrent(); second.restorePresentation(first.snapshot(0, 0))
            second.requestRename(other.id); second.editRename("Other edited label")
            assertNull(second.state.value.pendingRename)
            assertEquals("", second.state.value.renameDraft)
            assertEquals("  Recovered label  ", second.state.value.recoveredRename?.label)
            repository.records.value = listOf(other.copy(target = other.target.copy(label = "Other row changed")))
            runCurrent()
            val third = BookmarksViewModel("book", "hash", repository).also { store.put("third", it) }
            runCurrent(); third.restorePresentation(second.snapshot(0, 0))
            assertEquals("  Recovered label  ", third.state.value.recoveredRename?.label)
            assertNull(third.state.value.pendingRename)
            third.discardRecoveredRename(oldRecovery)
            assertNotNull(third.state.value.recoveredRename)
            third.discardRecoveredRename(checkNotNull(third.state.value.recoveredRename))
            third.requestRename(other.id); third.editRename("Other edited label")
            assertEquals(other.id, third.state.value.pendingRename?.id)
            assertEquals("Other edited label", third.state.value.renameDraft)
            assertEquals("Other row changed", repository.records.value.single().target.label)
            assertEquals(0, repository.writes)
        }
    }

    @Test fun anUnboundWordRecoveryBlocksAnotherExistingEditUntilExplicitDiscardWhileNewDraftTypingContinues() = runTest {
        fixture { store ->
            val missing = SavedWord("missing", "old", "eski", 1)
            val other = SavedWord("other", "current", "güncel", 2)
            val repository = Words(MutableStateFlow(listOf(other)))
            val model = store.keep(SavedWordsViewModel("book", "hash", repository))
            runCurrent(); model.restorePresentation(ReaderDraftSnapshot("", "RECENT", draft = "New draft", secondaryDraft = "Yeni taslak",
                editId = missing.id, expectedFingerprint = readerDraftFingerprint(missing), editDraft = "Earlier edit", secondaryEditDraft = "Eski düzenleme"))
            model.requestEdit(other.id)
            assertNull(model.state.value.pendingEdit)
            model.editEnglish("New draft typed")
            repository.records.value = listOf(other.copy(english = "Changed elsewhere", revision = 1)); runCurrent()
            val fresh = SavedWordsViewModel("book", "hash", repository).also { store.put("fresh", it) }
            runCurrent(); fresh.restorePresentation(model.snapshot(0, 0))
            assertEquals("Earlier edit", fresh.state.value.recoveredEdit?.english)
            assertEquals("Eski düzenleme", fresh.state.value.recoveredEdit?.turkish)
            assertEquals("New draft typed", fresh.state.value.englishDraft)
            fresh.requestEdit(other.id); assertNull(fresh.state.value.pendingEdit)
            fresh.discardRecoveredEdit(checkNotNull(fresh.state.value.recoveredEdit)); fresh.requestEdit(other.id)
            assertEquals("Changed elsewhere", fresh.state.value.editEnglishDraft)
            assertEquals("New draft typed", fresh.state.value.englishDraft)
            assertEquals(0, repository.writes)
        }
    }

    @Test fun restoredNoteIsAnUncommittedFormAndCancelPreservesStorage() = runTest {
        fixture { store ->
            val entry = BookNote("note", "Stored note", 1, 3)
            val repository = Notes(MutableStateFlow(listOf(entry)))
            val model = store.keep(BookNotesViewModel("book", "hash", repository))
            runCurrent()
            model.restorePresentation(ReaderDraftSnapshot("Stored", "RECORD_ORDER", draft = "Another draft",
                editId = entry.id, expectedFingerprint = readerDraftFingerprint(entry), editDraft = "Dirty edit"))
            runCurrent()
            assertEquals(entry, model.state.value.pendingEdit)
            assertEquals("Dirty edit", model.state.value.editDraft)
            assertEquals("Another draft", model.state.value.draft)
            assertTrue(model.state.value.canUpdate)
            assertEquals(0, repository.writes)
            model.cancelEdit()
            assertEquals(listOf(entry), repository.records.value)
            assertEquals(0, repository.writes)
        }
    }

    @Test fun changedNoteCannotReceiveOldEditAndBothUserDraftsRemainVisible() = runTest {
        fixture { store ->
            val old = BookNote("note", "Old content", 1)
            val repository = Notes(MutableStateFlow(listOf(old.copy(text = "External update"))))
            val model = store.keep(BookNotesViewModel("book", "hash", repository))
            runCurrent()
            model.restorePresentation(ReaderDraftSnapshot("", "RECORD_ORDER", draft = "New note draft",
                editId = old.id, expectedFingerprint = readerDraftFingerprint(old), editDraft = "Old dirty edit"))
            model.saveEdit()
            runCurrent()
            assertNull(model.state.value.pendingEdit)
            assertTrue(model.state.value.draft.contains("New note draft"))
            assertTrue(model.state.value.draft.contains("Old dirty edit"))
            assertNotNull(model.state.value.message)
            assertEquals(0, repository.writes)
        }
    }

    @Test fun twoMaximumBoundedNoteInputsRemainSeparatelyRecoverableAcrossAnotherSnapshotWithoutExceedingAFieldLimit() = runTest {
        fixture { store ->
            val old = BookNote("note", "Old row", 1)
            val repository = Notes(MutableStateFlow(listOf(old.copy(text = "Current row"))))
            val first = store.keep(BookNotesViewModel("book", "hash", repository))
            val newInput = "a".repeat(8192)
            val editedInput = "b".repeat(8192)
            runCurrent(); first.restorePresentation(ReaderDraftSnapshot("", "RECORD_ORDER", draft = newInput,
                editId = old.id, expectedFingerprint = readerDraftFingerprint(old), editDraft = editedInput))
            assertEquals(newInput, first.state.value.draft)
            assertEquals(editedInput, first.state.value.recoveredEdit?.text)
            first.requestEdit(old.id); assertNull(first.state.value.pendingEdit)
            val saved = first.snapshot(0, 0)
            assertEquals(8192, saved.draft.length); assertEquals(8192, saved.recoveredDraft.length)
            val fresh = BookNotesViewModel("book", "hash", repository).also { store.put("fresh", it) }
            runCurrent(); fresh.restorePresentation(saved)
            assertEquals(newInput, fresh.state.value.draft)
            assertEquals(editedInput, fresh.state.value.recoveredEdit?.text)
            assertFalse(fresh.state.value.canSave); assertFalse(fresh.state.value.canUpdate)
            fresh.discardRecoveredEdit(checkNotNull(first.state.value.recoveredEdit))
            assertNotNull(fresh.state.value.recoveredEdit)
            fresh.discardRecoveredEdit(checkNotNull(fresh.state.value.recoveredEdit)); fresh.requestEdit(old.id)
            assertEquals("Current row", fresh.state.value.editDraft)
            assertEquals(newInput, fresh.state.value.draft)
            assertEquals(0, repository.writes)
        }
    }

    @Test fun newerRetainedNoteDraftWinsOverOlderBundle() = runTest {
        fixture { store ->
            val entry = BookNote("note", "Stored", 1)
            val repository = Notes(MutableStateFlow(listOf(entry)))
            val model = store.keep(BookNotesViewModel("book", "hash", repository))
            runCurrent()
            model.restorePresentation(null)
            model.requestEdit(entry.id)
            model.editSelectedDraft("Latest retained text")
            model.restorePresentation(ReaderDraftSnapshot("", "RECORD_ORDER", editId = entry.id,
                expectedFingerprint = readerDraftFingerprint(entry), editDraft = "Older Bundle text"))
            assertEquals("Latest retained text", model.state.value.editDraft)
            assertEquals(0, repository.writes)
        }
    }

    @Test fun refreshingNewNativeBookmarkTargetDoesNotDestroyDirtyRename() = runTest {
        fixture { store ->
            val entry = Bookmark("bookmark", BookmarkTarget("Stored", ReadingLocation("old", "page")), 1)
            val repository = Marks(MutableStateFlow(listOf(entry)))
            val model = store.keep(BookmarksViewModel("book", "hash", repository))
            runCurrent()
            model.restorePresentation(ReaderDraftSnapshot("", "RECORD_ORDER", editId = entry.id,
                expectedFingerprint = readerDraftFingerprint(entry), editDraft = "Dirty title"))
            val newTarget = BookmarkTarget("Current native page", ReadingLocation("current", "page"))
            model.refreshPosition(newTarget)
            assertEquals(entry, model.state.value.pendingRename)
            assertEquals("Dirty title", model.state.value.renameDraft)
            assertEquals(newTarget, model.state.value.target)
            repository.records.value = listOf(entry.copy(target = entry.target.copy(label = "External rename")))
            runCurrent()
            assertFalse(model.state.value.canRename)
            model.saveRename()
            runCurrent()
            assertEquals(0, repository.writes)
        }
    }

    @Test fun wordRevisionChangeRevokesRestoredEditWithoutWriting() = runTest {
        fixture { store ->
            val entry = SavedWord("word", "word", "kelime", 1, 4)
            val repository = Words(MutableStateFlow(listOf(entry)))
            val model = store.keep(SavedWordsViewModel("book", "hash", repository))
            runCurrent()
            model.restorePresentation(ReaderDraftSnapshot("word", "RECENT", editId = entry.id,
                expectedFingerprint = readerDraftFingerprint(entry), editDraft = "dirtyword", secondaryEditDraft = "taslak"))
            assertTrue(model.state.value.canUpdate)
            assertEquals("dirtyword", model.state.value.editEnglishDraft)
            assertEquals("taslak", model.state.value.editTurkishDraft)
            repository.records.value = listOf(entry.copy(revision = 5))
            runCurrent()
            assertFalse(model.state.value.canUpdate)
            model.saveEdit()
            runCurrent()
            assertEquals(0, repository.writes)
        }
    }

    @Test fun removedWordRecoversTypedFieldsWithoutDeleteOrTranslationAuthority() = runTest {
        fixture { store ->
            val old = SavedWord("word", "word", "kelime", 1, 4)
            val repository = Words(MutableStateFlow(emptyList()))
            val model = store.keep(SavedWordsViewModel("book", "hash", repository))
            runCurrent()
            model.restorePresentation(ReaderDraftSnapshot("", "RECENT", editId = old.id,
                expectedFingerprint = readerDraftFingerprint(old), editDraft = "dirtyword", secondaryEditDraft = "taslak"))
            assertNull(model.state.value.pendingEdit)
            assertNull(model.state.value.pendingDelete)
            assertNull(model.state.value.pendingTranslationDraft)
            assertEquals("dirtyword", model.state.value.englishDraft)
            assertEquals("taslak", model.state.value.turkishDraft)
            assertEquals(0, repository.writes)
        }
    }
}

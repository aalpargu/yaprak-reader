package com.yaprak.reader.feature.reader

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.passages.*
import com.yaprak.reader.feature.passages.EpubPassagesViewModel
import com.yaprak.reader.reader.EpubSourceAnchor
import com.yaprak.reader.reader.SourceTextAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ReaderDraftSnapshotTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun legacyVersionOnePassageRecoveryPlusStaleEditorSurvivesActualBundleDecodingAndRepeatedFreshModels() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val row = EpubPassage("saved", "Original", SourceTextAnchor("chapter.xhtml", 0, 8, "a".repeat(64)),
            1, EpubHighlightColor.YELLOW, "Stored note", 3)
        val records = MutableStateFlow(listOf(row))
        var writes = 0
        val repository = object : EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = records
            override suspend fun add(bookId: String, contentSha256: String, text: String, anchor: EpubSourceAnchor) { writes++ }
            override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
            override suspend fun updateAnnotation(bookId: String, contentSha256: String, expected: EpubPassage,
                highlightColor: EpubHighlightColor?, attachedNote: String?): EpubPassage { writes++; error("No automatic write is authorized") }
        }
        try {
            val original = EpubPassagesViewModel("book", "hash", repository).also { store.put("original", it) }
            runCurrent(); original.startSession("original"); original.requestEdit(row)
            original.editNote("  Legacy edit B  "); original.editColor(EpubHighlightColor.GREEN)
            val legacy = checkNotNull(original.snapshot(0, 0).toBundle()).apply {
                putInt("version", 1); remove("recoveredDraft"); remove("secondaryRecoveredDraft")
                putString("secondaryDraft", "  Legacy recovery A  ")
            }
            records.value = listOf(row.copy(revision = 4, attachedNote = "Current row")); runCurrent()
            fun restored(key: String, bundle: android.os.Bundle): EpubPassagesViewModel {
                val result = EpubPassagesViewModel("book", "hash", repository).also { store.put(key, it) }
                runCurrent(); result.startSession(key)
                assertTrue(result.restorePresentation(checkNotNull(ReaderDraftSnapshot.fromBundle(bundle))))
                return result
            }
            val first = restored("first", legacy)
            assertEquals("  Legacy recovery A  ", first.state.value.recoveredNote)
            assertEquals("  Legacy edit B  ", first.state.value.additionalRecoveredEdit?.note)
            assertEquals("GREEN", first.state.value.additionalRecoveredEdit?.color)
            val snapshot = first.snapshot(0, 0)
            assertNull(snapshot.editId); assertNull(snapshot.expectedFingerprint)
            val second = restored("second", checkNotNull(snapshot.toBundle()))
            assertNull(second.state.value.pendingEdit); second.update(); runCurrent()
            second.discardRecoveredNote(checkNotNull(second.state.value.recoveredNote), second.state.value.recoveryRevision)
            val third = restored("third", checkNotNull(second.snapshot(0, 0).toBundle()))
            assertNull(third.state.value.recoveredNote)
            assertEquals("  Legacy edit B  ", third.state.value.additionalRecoveredEdit?.note)
            assertEquals("GREEN", third.state.value.additionalRecoveredEdit?.color)
            assertEquals("Current row", records.value.single().attachedNote); assertEquals(0, writes)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun boundedInvalidInputDraftSurvivesWithoutAcquiringACommand() {
        // Invalid form text is still user input: rotation must not silently trim it to a valid write.
        val snapshot = ReaderDraftSnapshot("query", "RECORD_ORDER", draft = " ".repeat(3000),
            editId = "row", expectedFingerprint = readerDraftFingerprint("old row"),
            editDraft = "unsaved", listIndex = 20, listOffset = 13)
        val bundle = checkNotNull(snapshot.toBundle())
        assertEquals(snapshot, ReaderDraftSnapshot.fromBundle(bundle))
        assertFalse(bundle.containsKey("delete"))
        assertFalse(bundle.containsKey("selection"))
    }

    @Test fun wrongTypesExtraCommandsAndOrphanEditIdentityAreRejected() {
        val valid = checkNotNull(ReaderDraftSnapshot("", "RECENT").toBundle())
        assertNull(ReaderDraftSnapshot.fromBundle(android.os.Bundle(valid).apply { putString("listIndex", "1") }))
        assertNull(ReaderDraftSnapshot.fromBundle(android.os.Bundle(valid).apply { putBoolean("delete", true) }))
        assertNull(ReaderDraftSnapshot.fromBundle(android.os.Bundle(valid).apply { putString("editId", "row") }))
        assertNull(ReaderDraftSnapshot.fromBundle(android.os.Bundle(valid).apply { putInt("listOffset", -1) }))
    }

    @Test fun boundedCaptureRefusesExcessiveInputAndUnknownVersions() {
        assertNull(ReaderDraftSnapshot("", "RECENT", editDraft = "a".repeat(8193)).toBundle())
        val bundle = checkNotNull(ReaderDraftSnapshot("", "RECENT").toBundle()).apply { putInt("version", 3) }
        assertNull(ReaderDraftSnapshot.fromBundle(bundle))
    }

    @Test fun recoveredPlaintextRoundTripsAlongsideAnActiveEditAndKeepsTheOriginalTotalBudget() {
        val snapshot = ReaderDraftSnapshot("", "RECENT", draft = "New word", secondaryDraft = "Yeni kelime",
            editId = "other row", expectedFingerprint = readerDraftFingerprint("current row"), editDraft = "Current edit",
            recoveredDraft = "  Earlier edit  ", secondaryRecoveredDraft = "  Eski taslak  ")
        assertEquals(snapshot, ReaderDraftSnapshot.fromBundle(checkNotNull(snapshot.toBundle())))
        val twoBoundedNotes = ReaderDraftSnapshot("", "RECORD_ORDER", draft = "a".repeat(8192), recoveredDraft = "b".repeat(8192))
        assertEquals(twoBoundedNotes, ReaderDraftSnapshot.fromBundle(checkNotNull(twoBoundedNotes.toBundle())))
        val fullTwoSlotWord = ReaderDraftSnapshot("", "RECENT", draft = "a".repeat(4096), secondaryDraft = "b".repeat(4096),
            recoveredDraft = "c".repeat(4096), secondaryRecoveredDraft = "d".repeat(4096),
            editDraft = "e".repeat(8192), secondaryEditDraft = "f".repeat(8192))
        assertEquals(fullTwoSlotWord, ReaderDraftSnapshot.fromBundle(checkNotNull(fullTwoSlotWord.toBundle())))
        assertNull(fullTwoSlotWord.copy(draft = "a".repeat(4097)).toBundle())
        assertNull(snapshot.copy(recoveredDraft = "x".repeat(8193)).toBundle())
        assertNull(snapshot.copy(draft = "x".repeat(8192), secondaryDraft = "x".repeat(8192),
            editDraft = "x".repeat(8192), secondaryEditDraft = "x".repeat(8192), recoveredDraft = "x").toBundle())
        val wrong = checkNotNull(snapshot.toBundle()).apply { putInt("recoveredDraft", 1) }
        assertNull(ReaderDraftSnapshot.fromBundle(wrong))
    }

    @Test fun originalVersionOneDraftRemainsReadableWithoutRecoveryFieldsOrCommands() {
        val snapshot = ReaderDraftSnapshot("query", "RECORD_ORDER", editId = "row",
            expectedFingerprint = readerDraftFingerprint("row"), editDraft = "Original saved input")
        val old = checkNotNull(snapshot.toBundle()).apply {
            putInt("version", 1); remove("recoveredDraft"); remove("secondaryRecoveredDraft")
        }
        assertEquals(snapshot, ReaderDraftSnapshot.fromBundle(old))
        assertNull(ReaderDraftSnapshot.fromBundle(android.os.Bundle(old).apply { putString("recoveredDraft", "unversioned") }))
    }

    @Test fun fingerprintDoesNotConfuseFieldSeparatorsOrConcatenations() {
        assertNotEquals(readerDraftFingerprint("a", "bc"), readerDraftFingerprint("ab", "c"))
        assertNotEquals(readerDraftFingerprint("a|b", "c"), readerDraftFingerprint("a", "b|c"))
    }
}

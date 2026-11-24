package com.yaprak.reader.feature.reader

import android.app.Application
import android.os.Bundle
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ReaderPresentationSavedStateTest {
    private val book = Book("book", "Original", null, BookFormat.EPUB, "book.epub", "book.epub", 1, "a".repeat(64), 0)
    private fun capture(): Bundle {
        val state = ReaderPresentationState(controlsVisible = true, owner = "retired surface", epoch = 99,
            selection = ReaderSelectionRef("native-selection"),
            panels = listOf(ReaderPanelEntry(40, ReaderPanelKind.CONTENT), ReaderPanelEntry(41, ReaderPanelKind.PASSAGES)))
        val draft = checkNotNull(ReaderDraftSnapshot("search", "RECORD_ORDER", editId = "row",
            expectedFingerprint = readerDraftFingerprint("original row"), editDraft = "Uncommitted note",
            listIndex = 12, listOffset = 23).toBundle())
        return Bundle().also { checkNotNull(ReaderPresentationSavedState.capture(book, state,
            mapOf(ReaderPanelKind.PASSAGES to draft))).save(it) }
    }

    @Test fun roundTripContainsOnlySafePresentationAndUserInputForTheExactBook() {
        val bundle = capture()
        val restored = checkNotNull(ReaderPresentationSavedState.restore(bundle, book.id))
        assertTrue(restored.matches(book))
        assertFalse(restored.matches(book.copy(sha256 = "b".repeat(64))))
        assertEquals(listOf(ReaderPanelKind.CONTENT, ReaderPanelKind.PASSAGES), restored.panels)
        assertEquals("Uncommitted note", ReaderDraftSnapshot.fromBundle(restored.drafts[ReaderPanelKind.PASSAGES])?.editDraft)
        assertNull(ReaderPresentationSavedState.restore(bundle, "different-book"))
        assertEquals(setOf("version", "book", "hash", "controls", "panels", "drafts"),
            bundle.getBundle("reader.presentation.v1")!!.keySet())
    }

    @Test fun staleEffectsSelectionAndNativeRequestsAreNeverCaptured() {
        val menu = ReaderPanelEntry(1, ReaderPanelKind.CONTENT)
        val state = ReaderPresentationState(controlsVisible = true, owner = "retired", epoch = 30,
            panels = listOf(menu, ReaderPanelEntry(2, ReaderPanelKind.SELECTION), ReaderPanelEntry(3, ReaderPanelKind.PDF_ANNOTATION)),
            pending = ReaderPanelEffect(4, 30, menu), returnFocus = ReaderReturnFocus(5, 30, "old"),
            selection = ReaderSelectionRef("old selection"))
        val saved = checkNotNull(ReaderPresentationSavedState.capture(book, state, emptyMap()))
        assertEquals(listOf(ReaderPanelKind.CONTENT), saved.panels)
        val new = ReaderPresentationController().apply { attach("new surface") }
        assertTrue(new.restorePanels(saved.controlsVisible, saved.panels, emptySet()))
        assertNull(new.state.value.selection)
        assertNull(new.state.value.pending)
        assertNull(new.state.value.returnFocus)
        assertFalse(new.consume(state.pending!!))
    }

    @Test fun malformedOrUnboundedSavedPresentationCannotCreateAWindow() {
        fun corrupt(block: Bundle.() -> Unit): Bundle = capture().apply { getBundle("reader.presentation.v1")!!.block() }
        assertNull(ReaderPresentationSavedState.restore(corrupt { putInt("version", 2) }, book.id))
        assertNull(ReaderPresentationSavedState.restore(corrupt { putString("controls", "true") }, book.id))
        assertNull(ReaderPresentationSavedState.restore(corrupt { putStringArrayList("panels", arrayListOf("CONTENT", "SELECTION")) }, book.id))
        assertNull(ReaderPresentationSavedState.restore(corrupt { putStringArrayList("panels", arrayListOf("CONTENT", "TOOLS")) }, book.id))
        assertNull(ReaderPresentationSavedState.restore(corrupt { putStringArrayList("panels", ArrayList(List(9) { "NOTES" })) }, book.id))
        assertNull(ReaderPresentationSavedState.restore(corrupt { putString("sourceAnchor", "untrusted") }, book.id))
    }

    @Test fun unrelatedDraftAndMutatedInputAreRejectedInsteadOfSilentlyApplied() {
        val unrelated = capture().apply {
            getBundle("reader.presentation.v1")!!.getBundle("drafts")!!.putBundle("NOTES",
                ReaderDraftSnapshot("", "RECORD_ORDER", draft = "orphan").toBundle())
        }
        assertNull(ReaderPresentationSavedState.restore(unrelated, book.id))
        val invalid = capture().apply {
            getBundle("reader.presentation.v1")!!.getBundle("drafts")!!.getBundle("PASSAGES")!!.putString("editDraft", "x".repeat(8193))
        }
        assertNull(ReaderPresentationSavedState.restore(invalid, book.id))
    }
}

package com.yaprak.reader.feature

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.notes.LibraryNote
import com.yaprak.reader.domain.notes.LibraryNoteSource
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.domain.notes.LibraryNotesRepository
import com.yaprak.reader.domain.notes.LibraryNotesResult
import com.yaprak.reader.feature.library.DiscoveryState
import com.yaprak.reader.feature.library.LibraryScreen
import com.yaprak.reader.feature.library.LibraryState
import com.yaprak.reader.feature.librarynotes.*
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.copyPlainSelectedText
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryNotesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val note = LibraryNote("one", "book-id", "a".repeat(64), "İstanbul", "Deniz", BookFormat.PDF,
        "İ/ı 😀\nİkinci satır e\u0301", 10, 7)

    @Test fun `colliding source ids have distinct rows and old attached callback cannot act on replaced revision`() {
        val ordinary = note.copy(bookFormat = BookFormat.EPUB, pdfPageIndex = null, text = "Ordinary note")
        val attached = ordinary.copy(source = LibraryNoteSource.EPUB_PASSAGE, text = "Attached 🌿",
            sourceText = "Exact quote", sourceAnchor = SourceTextAnchor("OPS/chapter.xhtml", 0, 11, "b".repeat(64)), sourceRevision = 7)
        var state by mutableStateOf(LibraryNotesState(entries = listOf(ordinary, attached), totalCount = 2, loading = false, active = true))
        val copies = mutableListOf<LibraryNote>()
        compose.setContent { YaprakTheme { LibraryNotesScreen(state, {}, {}, copies::add, {}, {}) } }
        val old = action("library-note-copy-epub-passage-one")
        scrollToText(text(R.string.library_notes_attached_source)).assertIsDisplayed()
        scrollToText("Exact quote").assertIsDisplayed()
        scrollToTag("library-note-copy-one").performClick()
        assertEquals(listOf(ordinary), copies)
        compose.runOnIdle { state = state.copy(entries = listOf(ordinary, attached.copy(sourceRevision = 8))) }
        compose.waitForIdle()
        compose.runOnIdle { old() }
        assertEquals(listOf(ordinary), copies)
        scrollToTag("library-note-copy-epub-passage-one").performClick()
        assertEquals(attached.copy(sourceRevision = 8), copies.last())
    }


    @Test fun `full count cap PDF page and ordinary book labels stay explicit`() {
        val epub = note.copy(id = "epub", bookFormat = BookFormat.EPUB, bookTitle = "EPUB", pdfPageIndex = null)
        compose.setContent { YaprakTheme { LibraryNotesScreen(
            LibraryNotesState(entries = listOf(note, epub), totalCount = 209, loading = false, active = true), {}, {}, {}, {}, {}) } }
        scrollToText(text(R.string.library_notes_count, 209)).assertIsDisplayed()
        scrollToText(text(R.string.library_notes_limit, 200, 209)).assertIsDisplayed()
        scrollToText(text(R.string.library_notes_pdf_page, 8)).assertIsDisplayed()
        scrollToText(text(R.string.library_notes_book_note, "EPUB")).assertIsDisplayed()
        compose.onNodeWithTag("library-notes-back").assertIsDisplayed()
    }

    @Test fun `old Material copy and open actions cannot retarget edited same id text or changed hash`() {
        var state by mutableStateOf(LibraryNotesState(entries = listOf(note), totalCount = 1, loading = false, active = true))
        val copied = mutableListOf<LibraryNote>()
        val opened = mutableListOf<LibraryNote>()
        compose.setContent { YaprakTheme { LibraryNotesScreen(state, {}, {}, copied::add, opened::add, {}) } }
        val oldCopy = action("library-note-copy-one")
        val oldOpen = action("library-note-open-one")
        val updated = note.copy(text = "Yeni kayıtlı metin", contentSha256 = "b".repeat(64))
        compose.runOnIdle { state = state.copy(entries = listOf(updated)) }
        compose.runOnIdle { oldCopy(); oldOpen() }
        assertTrue(copied.isEmpty())
        assertTrue(opened.isEmpty())
        scrollToTag("library-note-copy-one").performClick()
        scrollToTag("library-note-open-one").performClick()
        assertEquals(listOf(updated), copied)
        assertEquals(listOf(updated), opened)
    }

    @Test fun `live lifecycle busy loading and failed gates reject previously captured actions`() {
        var state by mutableStateOf(LibraryNotesState(entries = listOf(note), totalCount = 1, loading = false, active = true))
        var effects = 0
        compose.setContent { YaprakTheme { LibraryNotesScreen(state, {}, {}, { effects++ }, { effects++ }, {}) } }
        val oldCopy = action("library-note-copy-one")
        val oldOpen = action("library-note-open-one")
        listOf(state.copy(active = false), state.copy(action = note), state.copy(loading = true), state.copy(loadFailed = true)).forEach { gated ->
            compose.runOnIdle { state = gated }
            compose.runOnIdle { oldCopy(); oldOpen() }
            assertEquals(0, effects)
        }
        scrollToText(text(R.string.library_notes_load_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).performClick()
        compose.onNodeWithText(text(R.string.library_notes_empty)).assertDoesNotExist()
    }

    @Test fun `200 note list displays complete action labels at double type and fixed back remains reachable`() {
        val notes = (0 until 200).map { note.copy(id = "note-$it", bookTitle = "Kitap $it") }
        var copied: LibraryNote? = null
        var opened: LibraryNote? = null
        var backs = 0
        var state by mutableStateOf(LibraryNotesState(query = "Kitap", entries = notes, totalCount = 300, loading = false, active = true))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            YaprakTheme { LibraryNotesScreen(state,
                {}, {}, { copied = it }, { opened = it }, { backs++ }) }
        } }
        compose.onNodeWithTag("library-notes-back").assertIsDisplayed()
        compose.onNodeWithTag("library-notes-list").performScrollToIndex(203)
        scrollToTag("library-note-copy-note-199").assertIsDisplayed().performClick()
        scrollToTag("library-note-open-note-199").assertIsDisplayed().performClick()
        val copyLabel = compose.onNode(hasText(text(R.string.library_notes_copy)) and
            hasAnyAncestor(hasTestTag("library-note-copy-note-199")), useUnmergedTree = true)
        assertReadable(copyLabel)
        val openLabel = compose.onNode(hasText(text(R.string.library_notes_open)) and
            hasAnyAncestor(hasTestTag("library-note-open-note-199")), useUnmergedTree = true)
        assertReadable(openLabel)
        assertReadable(compose.onNode(hasText(text(R.string.library_notes_back)), useUnmergedTree = true))
        scrollToText(text(R.string.library_notes_reload)).assertIsDisplayed()
        assertReadable(compose.onNode(hasText(text(R.string.library_notes_reload)), useUnmergedTree = true))
        scrollToText(text(R.string.clear_search)).assertIsDisplayed()
        assertReadable(compose.onNode(hasText(text(R.string.clear_search)), useUnmergedTree = true))
        compose.runOnIdle { state = state.copy(entries = emptyList(), loadFailed = true) }
        scrollToText(text(R.string.retry)).assertIsDisplayed()
        assertReadable(compose.onNode(hasText(text(R.string.retry)), useUnmergedTree = true))
        compose.onNodeWithTag("library-notes-back").assertIsDisplayed().performClick()
        assertEquals(notes.last(), copied)
        assertEquals(notes.last(), opened)
        assertEquals(1, backs)
    }

    @Test fun `fixed back consumes a nonzero Android navigation inset`() {
        compose.setContent { YaprakTheme {
            Box(Modifier.fillMaxSize().testTag("root"), contentAlignment = Alignment.BottomCenter) {
                LibraryNotesFooter({}, WindowInsets(bottom = 48.dp))
            }
        } }
        val rootBottom = compose.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot.bottom
        val backBottom = compose.onNodeWithTag("library-notes-back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Back action overlaps navigation area", rootBottom - backBottom >= 48f)
    }

    @Test fun `library all notes entry is optional and old event respects live mutation and missing callback`() {
        var discovery by mutableStateOf<DiscoveryState>(DiscoveryState.Idle)
        var available by mutableStateOf(true)
        var effects = 0
        compose.setContent { YaprakTheme { LibraryScreen(LibraryState.Ready(emptyList()), false,
            SnackbarHostState(), {}, {}, {}, "", {}, discoveryState = discovery,
            onAllNotes = if (available) ({ effects++ }) else null) } }
        val old = compose.onNodeWithText(text(R.string.library_notes_title)).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { discovery = DiscoveryState.Choosing("picker") }
        compose.onNodeWithText(text(R.string.library_notes_title)).assertIsNotEnabled()
        compose.runOnIdle { old() }
        assertEquals(0, effects)
        compose.runOnIdle { discovery = DiscoveryState.Idle; available = false }
        compose.onNodeWithText(text(R.string.library_notes_title)).assertDoesNotExist()
        compose.runOnIdle { old() }
        assertEquals(0, effects)
    }

    @Test fun `route writes actual multiline clipboard only explicitly and opens normal Reader intent`() {
        val repository = Repository()
        val model = LibraryNotesViewModel(repository, SavedStateHandle())
        compose.activity.viewModelStore.put("notes", model)
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("before", "Before"))
        compose.setContent { YaprakTheme { LibraryNotesRoute(model,
            { copyPlainSelectedText(compose.activity, it, "Yaprak notu") },
            { compose.activity.startActivity(ReaderActivity.intent(compose.activity, it)) }, {}) } }
        waitForNotes()
        assertEquals("Before", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        scrollToTag("library-note-copy-one").performClick()
        compose.waitUntil { clipboard.primaryClip!!.getItemAt(0).text.toString() == note.text }
        scrollToText(text(R.string.library_notes_copied)).assertIsDisplayed()
        scrollToTag("library-note-open-one").performClick()
        compose.waitUntil { repository.finds >= 2 }
        compose.waitForIdle()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(ReaderActivity::class.java.name, intent.component!!.className)
        assertEquals(note.bookId, intent.getStringExtra("bookId"))
        assertEquals(setOf("bookId"), intent.extras!!.keySet())
    }

    @Test fun `real lifecycle pause cancels pending route copy and resumes fresh source`() {
        val repository = Repository()
        val pending = CompletableDeferred<LibraryNote>()
        repository.pendingFind = pending
        val model = LibraryNotesViewModel(repository, SavedStateHandle())
        compose.activity.viewModelStore.put("notes", model)
        var effects = 0
        compose.setContent { YaprakTheme { LibraryNotesRoute(model, { effects++ }, { effects++ }, {}) } }
        waitForNotes()
        scrollToTag("library-note-copy-one").performClick()
        compose.waitUntil { repository.finds == 1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        assertFalse(model.state.value.active)
        pending.complete(note)
        compose.waitForIdle()
        assertEquals(0, effects)
        repository.result.value = LibraryNotesResult(listOf(note.copy(text = "Resume update")), 1)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil { model.state.value.entries.singleOrNull()?.text == "Resume update" }
        assertTrue(model.state.value.active)
    }

    private inner class Repository : LibraryNotesRepository {
        val result = MutableStateFlow(LibraryNotesResult(listOf(note), 1))
        var finds = 0
        var pendingFind: CompletableDeferred<LibraryNote>? = null
        override fun observe(query: String) = result
        override suspend fun find(bookId: String, contentSha256: String, noteId: String): LibraryNote? {
            finds++
            return pendingFind?.await() ?: result.value.entries.singleOrNull { it.id == noteId }
        }
    }

    private fun waitForNotes() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("library-note-copy-one").fetchSemanticsNodes().isNotEmpty()
    }

    private fun action(tag: String) = scrollToTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun scrollToTag(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("library-notes-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun scrollToText(value: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("library-notes-list").performScrollToNode(hasText(value))
        return compose.onNodeWithText(value)
    }
    private fun assertReadable(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertEquals(2f, layouts.first().layoutInput.density.fontScale)
        val layout = layouts.first()
        assertFalse("Label '${layout.layoutInput.text.text}': size=${layout.size}, " +
            "constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, " +
            "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
            "lineBottom=${layout.getLineBottom(layout.lineCount - 1)}", layout.hasVisualOverflow)
    }
    private fun text(resource: Int, vararg args: Any) = compose.activity.getString(resource, *args)
}

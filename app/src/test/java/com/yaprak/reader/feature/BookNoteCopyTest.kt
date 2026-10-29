package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.notes.BookNote
import com.yaprak.reader.domain.notes.BookNoteRepository
import com.yaprak.reader.feature.notes.BookNotesDialog
import com.yaprak.reader.feature.notes.BookNotesRoute
import com.yaprak.reader.feature.notes.BookNotesState
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookNoteCopyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun normalFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) }
        compose.activityRule.scenario.recreate()
    }

    @Test fun `route copies current saved id after search sort and update without any writes`() {
        val ada = BookNote("ada", "Ortak Ada notu\n😀", 1, 4)
        val zebra = BookNote("zebra", "Ortak Zebra notu\n🌿", 2)
        val repository = Repository(listOf(zebra, ada, BookNote("other", "Başka not", 3)))
        var open by mutableStateOf(true)
        val copied = mutableListOf<String>()
        compose.setContent { YaprakTheme { if (open) BookNotesRoute(book, repository,
            { open = false }, prefillText = "Kaydedilmemiş taslak", onCopy = { copied += it }) } }
        assertTrue(copied.isEmpty())
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-search"))
        compose.onNodeWithTag("book-note-search").performTextReplacement("Ortak")
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-sort"))
        compose.onNodeWithTag("book-note-sort").performClick()
        compose.onNodeWithText("Not metni A–Z").performClick()
        copyNode(ada.id).assert(hasContentDescription(description(1, ada))).performClick()
        assertEquals(listOf(ada.text), copied)
        compose.onNodeWithText(success).assertExists()
        val updated = ada.copy(text = "Ortak Ada düzenlendi\nİ ı 😀 e\u0301")
        compose.runOnIdle { repository.entries.value = listOf(zebra, updated) }
        compose.onNodeWithText(success).assertDoesNotExist()
        copyNode(updated.id).assert(hasContentDescription(description(1, updated))).performClick()
        assertEquals(listOf(ada.text, updated.text), copied)
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-draft"))
        compose.onNodeWithTag("book-note-draft")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,
                AnnotatedString("Kaydedilmemiş taslak")))
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { open = true }
        compose.onNodeWithText(success).assertDoesNotExist()
        assertEquals(0, repository.writes)
        assertEquals(listOf(zebra, updated), repository.entries.value)
    }

    @Test fun `failed copy keeps multiline Unicode saved text retryable and feedback clears on removal`() {
        val note = BookNote("unicode", "İ/ı 😀\n" + "漢字 e\u0301 🌿 ".repeat(25), 1, 8)
        val repository = Repository(listOf(note))
        var failCopy = true
        val attempts = mutableListOf<String>()
        compose.setContent { YaprakTheme { BookNotesRoute(book, repository, {}, onCopy = {
            attempts += it
            check(!failCopy) { "Clipboard failure" }
        }) } }
        copyNode(note.id).performClick()
        compose.onNodeWithText(failure).assertExists()
        compose.onNodeWithText(success).assertDoesNotExist()
        assertEquals(listOf(note.text), attempts)
        failCopy = false
        copyNode(note.id).performClick()
        compose.onNodeWithText(success).assertExists()
        assertEquals(listOf(note.text, note.text), attempts)
        compose.runOnIdle { repository.entries.value = emptyList() }
        compose.onNodeWithText(success).assertDoesNotExist()
        compose.onNodeWithTag("book-note-copy-${note.id}").assertDoesNotExist()
        assertEquals(0, repository.writes)
    }

    @Test fun `copy is optional blocked by pending actions and absent before a valid load`() {
        val note = BookNote("one", "Kayıtlı not", 1)
        var state by mutableStateOf(BookNotesState(entries = listOf(note), loading = false))
        var callback by mutableStateOf<((String) -> Unit)?>(null)
        val copied = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            BookNotesDialog(state, {}, {}, {}, {}, {}, {}, {}, onCopy = callback)
        } }
        compose.onNodeWithTag("book-note-copy-one").assertDoesNotExist()
        compose.runOnIdle { callback = { copied += it } }
        copyNode(note.id).assertIsEnabled()
        listOf(state.copy(busy = true), state.copy(pendingEdit = note, editDraft = note.text),
            state.copy(pendingDelete = note)).forEach { gated ->
            compose.runOnIdle { state = gated }
            compose.onNodeWithTag("book-note-copy-one").assertIsNotEnabled()
            assertTrue(copied.isEmpty())
        }
        listOf(state.copy(pendingDelete = null, loading = true),
            state.copy(pendingDelete = null, loadFailed = true)).forEach { unavailable ->
            compose.runOnIdle { state = unavailable }
            compose.onNodeWithTag("book-note-copy-one").assertDoesNotExist()
        }
        compose.runOnIdle { state = BookNotesState(entries = listOf(note), loading = false) }
        copyNode(note.id).performClick()
        assertEquals(listOf(note.id), copied)
    }

    @Test fun `copy and PDF navigation rethrow cancellation without a success or failure notice`() {
        val note = BookNote("cancel", "İptal denemesi", 1, 0)
        val repository = Repository(listOf(note))
        val cancellation = CancellationException("Cancelled")
        compose.setContent { YaprakTheme { BookNotesRoute(book, repository, {},
            onGoToPdfPage = { throw cancellation }, onCopy = { throw cancellation }) } }
        val copyAction = copyNode(note.id).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val navigation = "1. not: PDF sayfası 1, sayfaya git"
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription(navigation))
        val navigationAction = compose.onNodeWithContentDescription(navigation)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            listOf(copyAction, navigationAction).forEach { action ->
                try { action(); fail("Cancellation must propagate") }
                catch (actual: CancellationException) { assertSame(cancellation, actual) }
            }
        }
        compose.onNodeWithText(success).assertDoesNotExist()
        compose.onNodeWithText(failure).assertDoesNotExist()
        compose.onNodeWithText("PDF sayfasına gidilemedi. Yeniden deneyebilirsin.").assertDoesNotExist()
        assertEquals(0, repository.writes)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `double font short screen reaches copy feedback and close in a long note list`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        val notes = (1..30).map { BookNote("note-$it", "Not $it\nİ/ı 😀 e\u0301", it.toLong()) }
        val copied = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            BookNotesDialog(BookNotesState(entries = notes, loading = false), {}, {}, {}, {}, {}, {}, {},
                onCopy = { copied += it })
        } }
        copyNode("note-30").assertIsDisplayed()
        val label = hasText("Kopyala") and hasAnyAncestor(hasTestTag("book-note-copy-note-30"))
        compose.onNode(label, useUnmergedTree = true).assertFontScale(2f)
        readable(compose.onNode(label, useUnmergedTree = true))
        copyNode("note-30").performClick()
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasText(success))
        compose.onNodeWithText(success).assertIsDisplayed().assertFontScale(2f)
        readable(compose.onNodeWithText(success))
        compose.onNodeWithText("Kapat", useUnmergedTree = true).assertFontScale(2f)
        readable(compose.onNodeWithText("Kapat", useUnmergedTree = true))
        compose.onNodeWithText("Kapat").assertIsDisplayed()
        assertEquals(listOf("note-30"), copied)
    }

    private fun copyNode(id: String): SemanticsNodeInteraction {
        val tag = "book-note-copy-$id"
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun readable(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.lineCount > 0 && layout.size.width > 0)
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    private class Repository(notes: List<BookNote>) : BookNoteRepository {
        val entries = MutableStateFlow(notes)
        var writes = 0
        override fun observe(bookId: String, contentSha256: String) = entries
        override suspend fun add(bookId: String, contentSha256: String, text: String) { writes++ }
        override suspend fun update(bookId: String, contentSha256: String, id: String, text: String) { writes++ }
        override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
    }

    private companion object {
        val book = Book("copy-notes", "Kitap", null, BookFormat.PDF, "book.pdf", "stored.pdf", 100, "hash", 1)
        const val success = "Not panoya kopyalandı."
        const val failure = "Not kopyalanamadı. Yeniden deneyebilirsin."
        fun description(index: Int, note: BookNote) = "$index. not: ${note.text.replace('\n', ' ').take(40)}, kopyala"
    }
}

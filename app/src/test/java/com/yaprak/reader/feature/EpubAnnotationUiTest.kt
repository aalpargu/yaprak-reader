package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.domain.passages.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.passages.*
import com.yaprak.reader.feature.reader.SelectedTextDialog
import com.yaprak.reader.feature.reader.SelectedTextPreview
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubAnnotationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val anchor = SourceTextAnchor("chapter.xhtml", 0, 4, "a".repeat(64))

    @Test fun `color choices expose selected semantics and note keeps exact text until explicit save`() {
        var color by mutableStateOf<EpubHighlightColor?>(EpubHighlightColor.YELLOW)
        var note by mutableStateOf("  Çığ\n😀  ")
        var revision by mutableStateOf(0L)
        var saves = 0
        compose.setContent { YaprakTheme {
            EpubAnnotationDialog("session", "Leaf", color, note, false, true, revision,
                onColor = { color = it; revision++ }, onNote = { note = it; revision++ }, onSave = { saves++ }, onDismiss = {})
        } }
        compose.onNodeWithText("Sarı").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Pembe").performScrollTo().performClick().assertIsSelected()
        assertEquals(EpubHighlightColor.PINK, color); assertEquals("  Çığ\n😀  ", note); assertEquals(0, saves)
        compose.onNodeWithTag("epub-annotation-save").assertIsDisplayed().performClick()
        assertEquals(1, saves)
    }

    @Test fun `independent color and note clear preserve each other before save`() {
        var color by mutableStateOf<EpubHighlightColor?>(EpubHighlightColor.GREEN)
        var note by mutableStateOf("attached")
        var revision by mutableStateOf(0L)
        compose.setContent { YaprakTheme {
            EpubAnnotationDialog("edit", "Leaf", color, note, false, true, revision, editing = true,
                onColor = { color = it; revision++ }, onNote = { note = it; revision++ }, onSave = {}, onDismiss = {})
        } }
        compose.onNodeWithText("Vurgu yok").performScrollTo().performClick()
        assertNull(color); assertEquals("attached", note)
        compose.onNodeWithText("Bağlı notu temizle").performScrollTo().performClick()
        assertEquals("", note); assertNull(color)
        compose.onNodeWithText("Bağlı notu temizle").assertDoesNotExist()
    }

    @Test fun `old callback cannot edit save or dismiss a repeated selection with new UUID`() {
        var session by mutableStateOf("old")
        var revision by mutableStateOf(0L)
        var edits = 0; var saves = 0; var dismisses = 0
        compose.setContent { YaprakTheme {
            EpubAnnotationDialog(session, "Leaf", EpubHighlightColor.YELLOW, "", false, true, revision,
                onColor = { edits++ }, onNote = {}, onSave = { saves++ }, onDismiss = { dismisses++ })
        } }
        val save = click("Kaydet")
        val dismiss = click("Vazgeç")
        compose.onNodeWithText("Yeşil").performScrollTo()
        val edit = click("Yeşil")
        compose.runOnIdle { session = "new"; revision++ }
        compose.runOnIdle { edit(); save(); dismiss() }
        assertEquals(0, edits); assertEquals(0, saves); assertEquals(0, dismisses)
        compose.onNodeWithTag("epub-annotation-save").performClick(); assertEquals(1, saves)
    }

    @Test fun `captured save cannot retarget changed draft in same session and text field keeps focus`() {
        var color by mutableStateOf<EpubHighlightColor?>(EpubHighlightColor.YELLOW)
        var note by mutableStateOf("")
        var revision by mutableStateOf(0L)
        val saves = mutableListOf<Pair<EpubHighlightColor?, String>>()
        compose.setContent { YaprakTheme {
            EpubAnnotationDialog("same", "Leaf", color, note, false, true, revision,
                onColor = { color = it; revision++ }, onNote = { note = it; revision++ },
                onSave = { saves += color to note }, onDismiss = {})
        } }
        val oldSave = click("Kaydet")
        compose.onNodeWithTag("epub-annotation-note").performScrollTo().performClick()
        compose.onNodeWithTag("epub-annotation-note").performTextReplacement(" new draft 😀 ")
        compose.onNodeWithTag("epub-annotation-note").assertIsFocused()
        compose.runOnIdle { color = EpubHighlightColor.PINK; revision++ }
        compose.onNodeWithTag("epub-annotation-note").assertIsFocused()
        compose.runOnIdle { oldSave() }; assertTrue(saves.isEmpty())
        compose.onNodeWithTag("epub-annotation-save").performClick()
        assertEquals(listOf(EpubHighlightColor.PINK to " new draft 😀 "), saves)
    }

    @Test fun `busy and externally changed editor disable mutation while changed draft can be cancelled`() {
        var busy by mutableStateOf(false)
        var changed by mutableStateOf(false)
        var revision by mutableStateOf(0L)
        var saves = 0; var dismisses = 0
        compose.setContent { YaprakTheme {
            EpubAnnotationDialog("edit", "Leaf", EpubHighlightColor.YELLOW, "draft", busy, true, revision,
                changed = changed, onColor = {}, onNote = {}, onSave = { saves++ }, onDismiss = { dismisses++ })
        } }
        val oldSave = click("Kaydet")
        compose.runOnIdle { busy = true; revision++ }
        compose.onNodeWithTag("epub-annotation-save").assertIsNotEnabled()
        compose.onNodeWithTag("epub-annotation-cancel").assertIsNotEnabled()
        compose.runOnIdle { oldSave() }; assertEquals(0, saves)
        compose.runOnIdle { busy = false; changed = true; revision++ }
        compose.onNodeWithTag("epub-annotation-save").assertIsNotEnabled()
        compose.onNodeWithText("Sarı").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("epub-annotation-cancel").assertIsEnabled().performClick()
        assertEquals(1, dismisses)
    }

    @Test fun `annotation route admits only live session and leaves successful write separate from rendered paint`() {
        var allowed by mutableStateOf(false)
        var dismissed = 0; var creates = 0
        val repository = object : EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = MutableStateFlow<List<EpubPassage>>(emptyList())
            override suspend fun add(bookId: String, contentSha256: String, text: String, anchor: com.yaprak.reader.reader.EpubSourceAnchor) = Unit
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
            override suspend fun createAnnotation(bookId: String, contentSha256: String, text: String,
                anchor: com.yaprak.reader.reader.EpubSourceAnchor, highlightColor: EpubHighlightColor?, attachedNote: String?): EpubPassage {
                creates++; return EpubPassage("saved", text, anchor, 1, highlightColor, attachedNote)
            }
        }
        compose.setContent { YaprakTheme { EpubAnnotationRoute(
            EpubAnnotationRequest("session", "book", "hash", "Leaf", anchor), repository,
            canMutate = { allowed }, onDismiss = { dismissed++ }) } }
        compose.onNodeWithTag("epub-annotation-save").assertIsNotEnabled()
        assertEquals(0, creates)
        compose.runOnIdle { allowed = true }
        val admittedClick = click("Kaydet")
        compose.runOnIdle { allowed = false }
        compose.runOnIdle { admittedClick() }; assertEquals(0, creates)
        compose.runOnIdle { allowed = true }
        compose.onNodeWithTag("epub-annotation-save").performClick()
        compose.waitUntil(5_000) { dismissed == 1 }
        assertEquals(1, creates)
        compose.onNodeWithText("Açık bölümde 1 kayıtlı vurgu gösteriliyor.").assertDoesNotExist()
    }

    @Test fun `annotation action requires exact source range and old selection action cannot target repeated new selection`() {
        var preview by mutableStateOf<SelectedTextPreview?>(SelectedTextPreview.Ready("Leaf", sourceAnchor = anchor, sessionId = "old"))
        var annotations = 0
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, {}, {},
            onAnnotate = { annotations++ }, showAnnotationHint = true) } }
        compose.onNodeWithText("Vurgu ve bağlı not ekle").performScrollTo()
        val old = click("Vurgu ve bağlı not ekle")
        compose.runOnIdle { preview = SelectedTextPreview.Ready("Leaf", sourceAnchor = anchor, sessionId = "new") }
        compose.runOnIdle { old() }; assertEquals(0, annotations)
        compose.onNodeWithText("Vurgu ve bağlı not ekle").performScrollTo().performClick()
        assertEquals(1, annotations)
        compose.runOnIdle { preview = SelectedTextPreview.Ready("Leaf", sourceAnchor = anchor.copy(end = 5)) }
        compose.onNodeWithText("Vurgu ve bağlı not ekle").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(com.yaprak.reader.R.string.annotation_anchor_unavailable)).assertExists()
    }

    @Test fun `narrow form with double font scale keeps all choices and fixed actions readable`() {
        largeFont(); showLongForm()
        listOf("Vurgu yok", "Sarı", "Yeşil", "Mavi", "Pembe").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed(); readable(it)
        }
        listOf("Vazgeç", "Kaydet").forEach { compose.onNodeWithText(it).assertIsDisplayed(); readable(it) }
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short landscape with double font scale keeps body scrolling and footer fully visible`() {
        largeFont(); showLongForm()
        compose.onNodeWithText("Pembe").performScrollTo().assertIsDisplayed(); readable("Pembe")
        compose.onNodeWithTag("epub-annotation-note").performScrollTo().assertIsDisplayed()
        listOf("Vazgeç", "Kaydet").forEach { compose.onNodeWithText(it).assertIsDisplayed(); readable(it) }
    }

    @Test fun `list stale edit and delete confirmation callbacks cannot act on a changed revision`() {
        val entry = EpubPassage("one", "Leaf", anchor, 1, EpubHighlightColor.BLUE, "linked", 1)
        var state by mutableStateOf(EpubPassagesState(entries = listOf(entry), loading = false))
        var edits = 0; var deletes = 0
        compose.setContent { YaprakTheme {
            EpubPassagesDialog(state, {}, {}, {}, { deletes++ }, {}, {}, onEdit = { edits++ })
        } }
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-annotation-edit-one"))
        val oldEdit = click("Düzenle")
        val next = entry.copy(revision = 2)
        compose.runOnIdle { state = state.copy(entries = listOf(next)) }
        compose.runOnIdle { oldEdit() }; assertEquals(0, edits)
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-annotation-edit-one"))
        compose.onNodeWithTag("epub-annotation-edit-one").performClick(); assertEquals(1, edits)
        compose.runOnIdle { state = state.copy(pendingDelete = next) }
        val oldDelete = click("Pasajı sil")
        compose.runOnIdle { state = state.copy(entries = listOf(next.copy(revision = 3))) }
        compose.runOnIdle { oldDelete() }; assertEquals(0, deletes)
        compose.onNodeWithText("Pasajı sil").assertIsNotEnabled()
    }

    @Test fun `changed row during chapter verification rejects delivery and releases navigation busy gate`() {
        val entry = EpubPassage("one", "Leaf", anchor, 1, EpubHighlightColor.BLUE, "linked", 1)
        val entries = MutableStateFlow(listOf(entry))
        val result = CompletableDeferred<Boolean>()
        var closes = 0
        val repository = object : EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = entries
            override suspend fun add(bookId: String, contentSha256: String, text: String, anchor: com.yaprak.reader.reader.EpubSourceAnchor) = Unit
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
        }
        val book = Book("book", "Book", null, BookFormat.EPUB, "book.epub", "book.epub", 1, "hash", 1)
        compose.setContent { YaprakTheme { EpubPassagesRoute(book, repository, { closes++ }, onOpenChapter = { result.await() }) } }
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasContentDescription("1. pasaj: Leaf, bölümünü aç"))
        compose.onNodeWithContentDescription("1. pasaj: Leaf, bölümünü aç").performClick()
        compose.onNodeWithText("Kapat").assertIsNotEnabled()
        compose.runOnIdle { entries.value = listOf(entry.copy(revision = 2)) }
        compose.waitForIdle()
        compose.runOnIdle { result.complete(true) }
        compose.onNodeWithText("Kapat").assertIsEnabled().performClick()
        assertEquals(1, closes)
    }

    private fun showLongForm() {
        compose.setContent { YaprakTheme {
            EpubAnnotationDialog("long", "Uzun seçili metin. ".repeat(80), EpubHighlightColor.YELLOW,
                "not", false, true, 0, onColor = {}, onNote = {}, onSave = {}, onDismiss = {})
        } }
    }
    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
    }
    private fun click(label: String) = compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun readable(label: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue(results.isNotEmpty()); assertFalse("Text overflow: $label", results.single().hasVisualOverflow)
    }
}

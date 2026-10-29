package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.R
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookAssistantFormatsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun resetFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }
    private fun request(format: BookFormat): BookQuestionRequest {
        val hit = F.hit.copy(book = F.book.copy(format = format, pageCount = if (format == BookFormat.PDF) 3 else null),
            readingOrderIndex = 2, chapterTitle = if (format == BookFormat.TXT || format == BookFormat.FB2) "Metin 3" else null,
            target = F.hit.target.copy(sourceFormat = format,
                resourceHref = when (format) { BookFormat.PDF -> ""; BookFormat.FB2 -> "Text/b1-p3.xhtml"; else -> "Text/part0003.xhtml" },
                pdfPageIndex = if (format == BookFormat.PDF) 2 else null))
        return BookQuestionRequest.create(F.sources(hit), "Ne oldu?")
    }
    private fun ready(request: BookQuestionRequest) = BookAssistantState(active = true,
        book = request.sources.references.single().hit.book, keywords = "Güneş", question = request.question,
        sources = request.sources, revision = 7)
    @Composable private fun Screen(state: BookAssistantState, onSend: (Long) -> Unit = {},
        onMode: (BookAssistantSourceMode, Long) -> Unit = { _, _ -> }) =
        BookAssistantScreen(state, {}, {}, {}, {}, {}, onSend, {}, { _, _ -> }, {}, onMode = onMode)
    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag(tag)); return compose.onNodeWithTag(tag)
    }
    private fun readable(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse(layout.hasVisualOverflow)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        val bounds = node.fetchSemanticsNode().boundsInRoot; val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left && bounds.right <= root.right && bounds.top >= root.top && bounds.bottom <= root.bottom)
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView; val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap)); val directory = File(System.getProperty("yaprak.screenshotDir"),
                if (name.startsWith("fb2")) "fb2-assistant-0.152.0" else "book-rag-0.130.0").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
    private fun native(format: BookFormat, name: String) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        val request = request(format); var state by mutableStateOf(ready(request)); var sends = 0
        compose.setContent { YaprakTheme { Screen(state, onSend = { sends++ }) } }
        scroll("assistant-mode-section").assertIsDisplayed()
        compose.onNodeWithTag("assistant-mode-chapter").assertDoesNotExist()
        compose.onNodeWithTag("assistant-list").performScrollToKey("source:S1")
        readable(if (format == BookFormat.PDF) "S1 · PDF sayfası 3" else "S1 · Metin 3")
        capture("$name-preview"); assertEquals(0, sends)
        compose.runOnIdle { state = state.copy(answer = F.answer(request)) }
        scroll("assistant-open-0-S1")
        readable(RuntimeEnvironment.getApplication().getString(when (format) {
            BookFormat.PDF -> R.string.assistant_pdf_open; BookFormat.FB2 -> R.string.assistant_fb2_open; else -> R.string.assistant_txt_open
        }))
        capture("$name-citation"); assertEquals(0, sends)
    }
    @Test fun narrowPdfDoubleTypeShowsTheRealPageAndAWholeReachableCitationAction() { native(BookFormat.PDF, "pdf") }
    @Test fun nativeFb2DoubleTypeShowsOriginalPartAndCitationWithoutAutomaticSend() { native(BookFormat.FB2, "fb2") }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun shortTxtLandscapeDoubleTypeShowsTheCanonicalPartAndWholeCitationAction() { native(BookFormat.TXT, "txt-landscape") }
    @Test fun aRemovedEpubChapterActionCannotBeReplayedAgainstTheNewPdfOwner() {
        var state by mutableStateOf(BookAssistantState(active = true, book = F.book, revision = 7))
        val modes = mutableListOf<BookAssistantSourceMode>()
        compose.setContent { YaprakTheme { Screen(state, onMode = { mode, _ -> modes += mode }) } }
        scroll("assistant-mode-chapter")
        val old = compose.onNodeWithTag("assistant-mode-chapter").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = ready(request(BookFormat.PDF)).copy(revision = 8) }
        compose.runOnIdle { old() }; assertTrue(modes.isEmpty())
        compose.onNodeWithTag("assistant-mode-chapter").assertDoesNotExist()
    }
}

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
import com.yaprak.reader.fixtures.CompleteFormatSources as C
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

/** Native screen geometry/actions with detached sources. Actual Room/original proof is tested separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookAssistantCompleteFormatsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun resetFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }
    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag(tag)); return compose.onNodeWithTag(tag)
    }
    private fun action(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun readable(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse(layout.hasVisualOverflow); assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        val bounds = node.fetchSemanticsNode().boundsInRoot; val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left && bounds.right <= root.right && bounds.top >= root.top && bounds.bottom <= root.bottom)
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView; val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap)); val directory = File(System.getProperty("yaprak.screenshotDir"),
                when { name.startsWith("docx") -> "docx-assistant-0.165.0"; name.startsWith("fb2") -> "fb2-assistant-0.152.0"; else -> "complete-formats-0.135.0" }).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
    private fun native(format: BookFormat, name: String) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        val section = C.section(format); val full = C.sources(section); val book = C.book(format)
        var state by mutableStateOf(BookAssistantState(active = true, book = book, revision = 7))
        var rendered by mutableStateOf(true); var preparations = 0; var sends = 0; val tasks = mutableListOf<BookStudyTask>()
        compose.setContent { YaprakTheme { if (rendered) BookAssistantScreen(state, {}, {}, {}, {},
            onPrepare = { preparations++; state = state.copy(sources = full, revision = state.revision + 1) },
            onSend = { sends++ }, onCancel = {}, onOpen = { _, _ -> }, onBack = {},
            onMode = { mode, _ -> state = state.copy(sourceMode = mode, sections = listOf(section), sectionsLoaded = true,
                revision = state.revision + 1) },
            onSection = { selected, _ -> state = state.copy(section = selected, revision = state.revision + 1) },
            onStudy = { task, _ -> tasks += task; state = state.copy(question = task.questionForSection(format), revision = state.revision + 1) }) } }
        scroll("assistant-mode-section")
        readable(compose.activity.getString(when (format) {
            BookFormat.PDF -> R.string.assistant_mode_pdf_page; BookFormat.FB2 -> R.string.assistant_mode_fb2_part; BookFormat.DOCX -> R.string.assistant_mode_docx_document; else -> R.string.assistant_mode_txt_part
        }))
        capture("$name-mode"); assertEquals(0, preparations); assertEquals(0, sends)
        compose.onNodeWithTag("assistant-mode-section").performClick()
        compose.onNodeWithTag("assistant-mode-chapter").assertDoesNotExist()
        scroll("assistant-section-" + section.readingOrderIndex)
        readable(compose.activity.getString(when (format) {
            BookFormat.PDF -> R.string.assistant_pdf_page_choose; BookFormat.FB2 -> R.string.assistant_fb2_part_choose; BookFormat.DOCX -> R.string.assistant_docx_document_choose; else -> R.string.assistant_txt_part_choose
        }))
        compose.onNodeWithTag("assistant-section-" + section.readingOrderIndex).performClick(); assertEquals(0, preparations)
        scroll("assistant-prepare").performClick(); assertEquals(1, preparations); assertEquals(0, sends)
        compose.onNodeWithTag("assistant-list").performScrollToKey("source:S1")
        readable(when (format) { BookFormat.PDF -> "S1 · PDF sayfası 3"; BookFormat.DOCX -> "S1 · Belge metni"; else -> "S1 · Metin 3" })
        capture("$name-preview")
        scroll("assistant-study-summary")
        readable(compose.activity.getString(when (format) {
            BookFormat.PDF -> R.string.assistant_pdf_page_summary; BookFormat.FB2 -> R.string.assistant_fb2_part_summary; BookFormat.DOCX -> R.string.assistant_docx_document_summary; else -> R.string.assistant_txt_part_summary
        }))
        capture("$name-summary"); val oldSummary = action("assistant-study-summary")
        compose.onNodeWithTag("assistant-study-summary").performClick()
        assertEquals(BookStudyTask.SUMMARY.questionForSection(format), state.question); assertEquals(0, sends)
        compose.runOnIdle { oldSummary() }; assertEquals(listOf(BookStudyTask.SUMMARY), tasks)
        scroll("assistant-study-practice").performClick()
        assertEquals(BookStudyTask.PRACTICE.questionForSection(format), state.question); assertEquals(0, sends)
        val request = BookQuestionRequest.create(full, state.question); val source = full.references.last()
        compose.runOnIdle { state = state.copy(answer = BookAnswer(full.requestId, BookAnswerStatus.ANSWERED,
            listOf(BookAnswerStatement("Kaynağa bağlı açıklama", listOf(BookAnswerCitation(source, source.text)))), ""), revision = state.revision + 1) }
        validateBookAnswer(request, state.answer!!)
        scroll("assistant-open-0-S12")
        readable(compose.activity.getString(when (format) {
            BookFormat.PDF -> R.string.assistant_pdf_open; BookFormat.FB2 -> R.string.assistant_fb2_open; BookFormat.DOCX -> R.string.assistant_docx_open; else -> R.string.assistant_txt_open
        }))
        capture("$name-citation"); assertEquals(0, sends)
        scroll("assistant-study-practice"); val oldPractice = action("assistant-study-practice")
        compose.runOnIdle { state = state.copy(active = false, revision = state.revision + 1) }
        compose.runOnIdle { oldPractice() }; assertEquals(2, tasks.size)
        compose.runOnIdle { rendered = false }; compose.runOnIdle { oldPractice() }; assertEquals(2, tasks.size)
    }
    @Test fun nativeNarrowDocxDocumentPreviewStudyDraftAndCitationKeepDocumentScopeAndExplicitSend() { native(BookFormat.DOCX, "docx-document") }
    @Test fun nativeNarrowPdfPageSelectionFullPreviewAndSummaryDraftKeepRealPageAndReachableCitation() { native(BookFormat.PDF, "pdf") }
    @Test fun nativeFb2CompletePartPreviewAndStudyDraftKeepOriginalScopeAndExplicitSend() { native(BookFormat.FB2, "fb2-complete") }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun nativeShortLandscapeTxtPartSelectionAndStudyDraftNeverClaimAChapterOrSendAutomatically() { native(BookFormat.TXT, "txt-landscape") }
}

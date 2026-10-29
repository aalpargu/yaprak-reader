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
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.ChapterAssistantFixtures as C
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
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
class BookAssistantChapterScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ready = BookAssistantState(active = true, book = F.book, sourceMode = BookAssistantSourceMode.CHAPTER,
        chapters = listOf(C.chapter), chaptersLoaded = true, chapter = C.chapter, sources = C.sources(),
        question = BookStudyTask.SUMMARY.questionForChapter(), revision = 7)

    @Test fun excerptPreviewChapterScopeStudyAndSendControlsRemainReadableAtDoubleFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { ChapterScreen(ready) } }
        scroll("assistant-chapter-0"); readable("Seçilen bölüm")
        compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag("assistant-source-S1"))
        readable("Güneş doğdu.")
        compose.onNodeWithText(C.raw, useUnmergedTree = true).assertDoesNotExist()
        scroll("assistant-study-summary"); readable("Seçilen bölümün özetini iste"); capture("assistant-chapter-scope-font2")
        val help = compose.activity.getString(R.string.assistant_chapter_send_help)
        compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag("assistant-send-help"))
        readable(help); scroll("assistant-send").assertIsEnabled(); readable("Soruyu ve kaynakları gönder")
        readable("Aramaya dön"); capture("assistant-chapter-send-font2")
    }

    @Test fun oldChapterModeAndStudyCallbacksCannotActAfterRevisionBusyOrDisposal() {
        var state by mutableStateOf(ready); var rendered by mutableStateOf(true)
        val chapters = mutableListOf<BookSourceChapter>(); val tasks = mutableListOf<BookStudyTask>()
        val modes = mutableListOf<BookAssistantSourceMode>()
        compose.setContent { YaprakTheme { if (rendered) ChapterScreen(state, chapters::add, tasks::add, modes::add) } }
        assertTrue(chapters.isEmpty()); assertTrue(tasks.isEmpty()); assertTrue(modes.isEmpty())
        scroll("assistant-chapter-0"); val oldChoice = action("assistant-chapter-0")
        scroll("assistant-mode-chapter"); val oldMode = action("assistant-mode-chapter")
        scroll("assistant-study-summary"); val oldTask = action("assistant-study-summary")
        compose.runOnIdle { state = ready.copy(revision = 8) }
        compose.runOnIdle { oldChoice(); oldMode(); oldTask() }
        assertTrue(chapters.isEmpty()); assertTrue(tasks.isEmpty()); assertTrue(modes.isEmpty())
        scroll("assistant-study-practice").performClick(); assertEquals(listOf(BookStudyTask.PRACTICE), tasks)
        val fresh = action("assistant-study-practice")
        compose.runOnIdle { state = ready.copy(revision = 9, busy = true) }
        scroll("assistant-chapter-0").assertIsNotEnabled(); scroll("assistant-prepare").assertIsNotEnabled()
        compose.runOnIdle { rendered = false }; compose.runOnIdle { fresh() }
        assertEquals(1, tasks.size)
    }

    @Composable private fun ChapterScreen(state: BookAssistantState, onChapter: (BookSourceChapter) -> Unit = {},
        onStudy: (BookStudyTask) -> Unit = {}, onMode: (BookAssistantSourceMode) -> Unit = {}) =
        BookAssistantScreen(state, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {},
            onChapter = { chapter, _ -> onChapter(chapter) }, onStudy = { task, _ -> onStudy(task) },
            onMode = { mode, _ -> onMode(mode) })
    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun action(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun readable(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse("Overflow: " + text, layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val target = File("build/reports/screenshots", name + ".png"); target.parentFile!!.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}

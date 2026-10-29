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
import com.yaprak.reader.domain.assistant.BookStudyTask
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.util.UUID
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
class BookAssistantSectionScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val section = BookSourceSection(F.scope, UUID.randomUUID().toString(), 0,
        F.hit.target.resourceHref, F.hit.target.resourceSha256, F.hit.chapterTitle, 0, 1)
    private val sources = PreparedBookSources(UUID.randomUUID().toString(), F.scope, "", 1,
        listOf(BookSourceReference("S1", F.hit.copy(generation = section.generation))), section)
    private val ready = BookAssistantState(active = true, book = F.book, sourceMode = BookAssistantSourceMode.SECTION,
        sections = listOf(section), sectionsLoaded = true, section = section, sources = sources, revision = 7)

    @Test fun oldSectionAndStudyCallbacksCannotRetargetAfterRevisionOrDisposal() {
        var state by mutableStateOf(ready); var rendered by mutableStateOf(true)
        val chosen = mutableListOf<BookSourceSection>(); val tasks = mutableListOf<BookStudyTask>()
        compose.setContent { YaprakTheme { if (rendered) SectionScreen(state,
            onSection = { chosen += it }, onStudy = { tasks += it }) } }
        assertTrue(chosen.isEmpty()); assertTrue(tasks.isEmpty())
        scroll("assistant-section-0"); val oldChoice = action("assistant-section-0")
        scroll("assistant-study-summary"); val oldTask = action("assistant-study-summary")
        compose.runOnIdle { state = ready.copy(revision = 8) }
        compose.runOnIdle { oldChoice(); oldTask() }; assertTrue(chosen.isEmpty()); assertTrue(tasks.isEmpty())
        compose.runOnIdle { state = ready.copy(revision = 9) }
        compose.runOnIdle { oldChoice(); oldTask() }; assertTrue(chosen.isEmpty()); assertTrue(tasks.isEmpty())
        scroll("assistant-study-practice").performClick(); assertEquals(listOf(BookStudyTask.PRACTICE), tasks)
        val freshTask = action("assistant-study-practice")
        compose.runOnIdle { state = ready.copy(revision = 10, busy = true) }
        scroll("assistant-prepare").assertIsNotEnabled()
        scroll("assistant-study-practice").assertIsNotEnabled()
        compose.runOnIdle { rendered = false }; compose.runOnIdle { freshTask() }
        assertEquals(1, tasks.size)
    }

    @Test fun completeResourceControlsAndOversizeRefusalAreReadableAtDoubleFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var state by mutableStateOf(ready)
        compose.setContent { YaprakTheme { SectionScreen(state) } }
        scroll("assistant-section-0"); readable("Seçilen dosya")
        scroll("assistant-study-summary"); readable("Seçilen dosyanın özetini iste")
        scroll("assistant-study-practice"); readable("5 çalışma sorusu ve kısa cevap iste")
        readable("Aramaya dön"); capture("assistant-section-font2")
        compose.runOnIdle { state = ready.copy(revision = 8, sources = null,
            section = section.copy(chunkCount = 65), sourceFailure = BookSourceFailure.LIMIT) }
        compose.onNodeWithTag("assistant-list").performScrollToNode(hasText(
            "Kaynaklar boyut sınırını aşıyor; dosyanın tamamı gönderilemez. Başka dosya seç veya anahtar kelime kaynaklarına dön. Kısmi metin tam bölüm diye hazırlanmadı."))
        readable("Kaynaklar boyut sınırını aşıyor; dosyanın tamamı gönderilemez. Başka dosya seç veya anahtar kelime kaynaklarına dön. Kısmi metin tam bölüm diye hazırlanmadı.")
        capture("assistant-section-limit-font2")
    }

    @Test fun fullLongResourceCountAndUpdatedServerDisclosureRemainReadableAtDoubleFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        val long = section.copy(chunkCount = 64)
        val full = PreparedBookSources(UUID.randomUUID().toString(), F.scope, "", 64,
            (0 until 64).map { index -> BookSourceReference("S" + (index + 1),
                F.hit.copy(id = index + 1L, generation = long.generation, chunkIndex = index)) }, long)
        com.yaprak.reader.domain.assistant.BookQuestionRequest.create(full, BookStudyTask.SUMMARY.question)
        compose.setContent { YaprakTheme { SectionScreen(ready.copy(section = long, sections = listOf(long),
            sources = full, question = BookStudyTask.SUMMARY.question)) } }
        val count = "Seçilen dosyanın bütün çıkarılmış metni · kaynak parçası: 64. Parçalar sınırda örtüşebilir; yalnız bu dosya kullanılır."
        compose.onNodeWithTag("assistant-list").performScrollToNode(hasText(count)); readable(count)
        val help = compose.activity.getString(com.yaprak.reader.R.string.assistant_section_send_help)
        compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag("assistant-send-help"))
        readable(help); scroll("assistant-send").assertIsEnabled()
        readable("Soruyu ve kaynakları gönder"); readable("Aramaya dön")
        capture("assistant-long-section-send-font2")
    }

    @Composable private fun SectionScreen(state: BookAssistantState,
        onSection: (BookSourceSection) -> Unit = {}, onStudy: (BookStudyTask) -> Unit = {}) = BookAssistantScreen(
        state, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {},
        onSection = { section, _ -> onSection(section) }, onStudy = { task, _ -> onStudy(task) })
    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun action(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun readable(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse("Overflow: $text", layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

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
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
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
class BookAssistantScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val request = F.request()
    private val ready = BookAssistantState(active = true, book = F.book, keywords = "Güneş", question = "Ne oldu?",
        sources = request.sources, revision = 7)
    @Test fun `preview has no automatic send and obsolete rendered send cannot act after ABA or disposal`() {
        var state by mutableStateOf(ready); var sends = 0; var rendered by mutableStateOf(true)
        compose.setContent { YaprakTheme { if (rendered) screen(state, onSend = { sends++ }) } }
        scroll("assistant-send"); val old = action("assistant-send"); assertEquals(0, sends)
        compose.runOnIdle { state = ready.copy(revision = 8) }; compose.runOnIdle { old() }; assertEquals(0, sends)
        compose.runOnIdle { state = ready.copy(revision = 9) }; compose.runOnIdle { old() }; assertEquals(0, sends)
        scroll("assistant-send").performClick(); assertEquals(1, sends)
        val fresh = action("assistant-send"); compose.runOnIdle { rendered = false }; compose.runOnIdle { fresh() }; assertEquals(1, sends)
    }
    @Test fun `no sources inactive busy gates send and displayed credential is password transformed`() {
        var state by mutableStateOf(ready.copy(sources = null)); var sends = 0
        compose.setContent { YaprakTheme { screen(state, onSend = { sends++ }) } }
        scroll("assistant-send").assertIsNotEnabled()
        compose.runOnIdle { state = ready.copy(busy = true) }; scroll("assistant-send").assertIsNotEnabled()
        compose.onNodeWithTag("assistant-cancel").assertIsDisplayed()
        compose.runOnIdle { state = ready.copy(accessToken = F.connection().accessToken) }
        scroll("assistant-connection").performClick(); scroll("assistant-token").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.runOnIdle { state = ready.copy(active = false) }; scroll("assistant-send").assertIsNotEnabled(); assertEquals(0, sends)
    }
    @Test fun `narrow double type source citation and fixed back remain fully readable`() {
        largeFont()
        compose.setContent { YaprakTheme { screen(ready.copy(answer = F.answer(request))) } }
        scroll("assistant-open-0-S1").assertIsDisplayed()
        readable("Alıntının bölümünü aç · bölüm başlangıcı"); readable("Aramaya dön")
        capture("assistant-narrow-double-type")
        scroll("assistant-prepare"); readable("Kaynakları cihazda hazırla")
    }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short landscape with large type reaches citation action independently from long answer text`() {
        largeFont()
        val statement = com.yaprak.reader.domain.assistant.BookAnswerStatement("Uzun açıklama. ".repeat(50), request.sources.references.map {
            com.yaprak.reader.domain.assistant.BookAnswerCitation(it, it.hit.text)
        })
        val answer = com.yaprak.reader.domain.assistant.BookAnswer(request.sources.requestId,
            com.yaprak.reader.domain.assistant.BookAnswerStatus.ANSWERED, listOf(statement), "")
        compose.setContent { YaprakTheme { screen(ready.copy(answer = answer)) } }
        scroll("assistant-open-0-S1").assertIsDisplayed()
        readable("Alıntının bölümünü aç · bölüm başlangıcı"); readable("Aramaya dön")
        capture("assistant-landscape-double-type")
    }
    @Composable private fun screen(state: BookAssistantState, onSend: (Long) -> Unit = {}) = BookAssistantScreen(state,
        {}, {}, {}, {}, {}, onSend, {}, { _, _ -> }, {})
    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag(tag)); return compose.onNodeWithTag(tag)
    }
    private fun action(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
    }
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
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

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
import com.yaprak.reader.domain.assistant.BookAnswerFailure
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.feature.reader.SelectedTextDialog
import com.yaprak.reader.feature.reader.SelectedTextPreview
import com.yaprak.reader.fixtures.SelectedAssistantFixtures as F
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionAssistantUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun questionEditingKeepsFocusAcrossStateRevisions() {
        var state by mutableStateOf(ready())
        compose.setContent { YaprakTheme { PanelInSelection(state, onQuestion = { expected, value ->
            if (state === expected) state = state.copy(question = value, revision = state.revision + 1)
        }) } }
        val field = compose.onNodeWithTag("selected-ai-question").performScrollTo()
        field.performClick(); field.performTextClearance(); field.performTextInput("Bir soru")
        field.assertIsFocused(); assertEquals("Bir soru", state.question)
        field.performTextInput(" daha"); field.assertIsFocused(); assertEquals("Bir soru daha", state.question)
    }
    @Test fun retainedSendAndPresetsCannotTargetNewSelectionOrAbaState() {
        var state by mutableStateOf(ready()); var sends = 0; var edits = 0
        compose.setContent { YaprakTheme { PanelInSelection(state, onSend = { sends++ }, onQuestion = { _, _ -> edits++ }) } }
        val oldSend = click("selected-ai-send"); val oldPreset = click("selected-ai-simplify")
        compose.runOnIdle { state = state.copy(sending = true, revision = 1) }
        compose.onNodeWithTag("selected-ai-send").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(sending = false, revision = 2) }
        compose.runOnIdle { oldSend(); oldPreset() }
        assertEquals(0, sends); assertEquals(0, edits)
        val replaced = click("selected-ai-send")
        compose.runOnIdle { state = ready() }
        compose.runOnIdle { replaced() }; assertEquals(0, sends)
        compose.onNodeWithTag("selected-ai-send").performScrollTo().performClick(); assertEquals(1, sends)
    }
    @Test fun narrowDoubleFontSourceErrorsActionsAndFooterRemainReadable() {
        largeFont(); showFailure(); checkLayout()
        compose.onNodeWithTag("selected-ai-close").performScrollTo()
        capture("selection-ai-narrow-font2")
    }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun shortLandscapeDoubleFontKeepsQuestionAndFixedCopyClose() {
        largeFont(); showFailure(); checkLayout()
        compose.onNodeWithTag("selected-ai-send").performScrollTo()
        capture("selection-ai-landscape-font2")
    }

    @Composable private fun PanelInSelection(state: SelectionAssistantState,
        onSend: (SelectionAssistantState) -> Unit = {}, onQuestion: (SelectionAssistantState, String) -> Unit = { _, _ -> }) {
        SelectedTextDialog(SelectedTextPreview.Ready(state.request.preview, sourceAnchor = state.request.anchor,
            sessionId = state.request.selectionId), {}, {}, assistantContent = {
            SelectionAssistantPanel(state, { true }, {}, {}, onSend, {}, onQuestion, { _, _ -> }, { _, _ -> })
        })
    }
    private fun ready(): SelectionAssistantState {
        val request = F.request()
        return SelectionAssistantState(request, "Soru", sources = F.sources(request))
    }
    private fun showFailure() {
        val state = ready().copy(failure = BookAnswerFailure.SOURCE)
        compose.setContent { YaprakTheme { PanelInSelection(state) } }
    }
    private fun checkLayout() {
        for (id in listOf(R.string.selected_ai_title, R.string.selected_ai_scope, R.string.selected_ai_explain,
            R.string.selected_ai_simplify, R.string.assistant_question, R.string.assistant_endpoint,
            R.string.assistant_token, R.string.selected_ai_send_help, R.string.selected_ai_prepare,
            R.string.assistant_send, R.string.selected_ai_source_error, R.string.selected_ai_close)) readable(compose.activity.getString(id))
        compose.onNodeWithText("Panoya kopyala").assertIsDisplayed()
        compose.onNodeWithText("Kapat").assertIsDisplayed()
        val body = compose.onNodeWithTag("selected-text-body").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithText("Kapat").fetchSemanticsNode().boundsInRoot.top >= body.bottom)
    }
    private fun readable(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        if (layout.hasVisualOverflow) capture("selection-ai-overflow-${compose.activity.resources.configuration.screenWidthDp}")
        assertFalse("Overflow: $text; size=${layout.size}; lines=${layout.lineCount}; lastBottom=${layout.getLineBottom(layout.lineCount - 1)}; width=${layout.didOverflowWidth}; height=${layout.didOverflowHeight}", layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun largeFont() {
        val configuration = RuntimeEnvironment.getApplication().resources.configuration
        configuration.fontScale = 2f
        @Suppress("DEPRECATION") RuntimeEnvironment.getApplication().resources.updateConfiguration(configuration,
            RuntimeEnvironment.getApplication().resources.displayMetrics)
    }
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
        .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun capture(name: String) = compose.runOnIdle {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            val dir = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}

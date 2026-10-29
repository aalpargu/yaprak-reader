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
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.fixtures.PdfSelectedAssistantFixtures as P
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfSelectionAssistantUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun resetFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }
    private fun readable(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale); assertFalse(layout.hasVisualOverflow)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left && bounds.right <= root.right && bounds.top >= root.top && bounds.bottom <= root.bottom)
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap)); val directory = File(System.getProperty("yaprak.screenshotDir"), "pdf-selection-ai-0.136.0").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
    private fun native(name: String) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        val request = P.request(); val ready = SelectedTextPreview.Ready(request.preview, sessionId = request.selectionId,
            pdfPageIndex = 2, sourceSelectionId = request.sourceSelectionId, pdfSourceAnchor = P.anchor)
        var preview by mutableStateOf(ready); var state by mutableStateOf(SelectionAssistantState(request, "Açıkla", sources = P.sources(request)))
        var sends = 0; var edits = 0
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, {}, {}, assistantContent = {
            SelectionAssistantPanel(state, { true }, {}, {}, { sends++ }, {}, { _, question -> edits++; state = state.copy(question = question) }, { _, _ -> }, { _, _ -> })
        }) } }
        readable(compose.activity.getString(R.string.selected_ai_pdf_source, 3)); capture("$name-source")
        readable(compose.activity.getString(R.string.selected_ai_explain)); readable(compose.activity.getString(R.string.selected_ai_simplify))
        val old = compose.onNodeWithTag("selected-ai-simplify").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("selected-ai-simplify").performClick(); assertEquals(1, edits); assertEquals(0, sends)
        compose.runOnIdle { old() }; assertEquals(1, edits)
        compose.onNodeWithTag("selected-ai-send").performScrollTo(); readable(compose.activity.getString(R.string.assistant_send))
        compose.onNodeWithText("Panoya kopyala").assertIsDisplayed(); compose.onNodeWithText("Kapat").assertIsDisplayed()
        capture("$name-send"); assertEquals(0, sends)
        compose.runOnIdle { preview = ready.copy(pdfOcr = true) }
        compose.onNodeWithTag("selected-ai-panel").assertDoesNotExist(); compose.runOnIdle { old() }; assertEquals(1, edits)
        compose.runOnIdle { preview = ready.copy(pdfSourceAnchor = null) }
        compose.onNodeWithTag("selected-ai-panel").assertDoesNotExist()
    }
    @Test fun nativeNarrowPdfSelectionShowsRealSourceAndWholeActionsAndRejectsOcrOrMissingGeometry() { native("narrow") }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun nativeShortLandscapePdfSelectionKeepsScrollAndFixedCopyCloseWithoutAutomaticSend() { native("landscape") }
}

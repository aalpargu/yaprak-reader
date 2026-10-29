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
import com.yaprak.reader.domain.dictionary.*
import com.yaprak.reader.feature.dictionary.*
import com.yaprak.reader.feature.reader.*
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
class DictionaryPanelUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val preview = SelectedTextPreview.Ready("leaf")
    private val result = DictionaryResult("leaf", listOf(DictionaryEntry("leaf", "/liːf/",
        listOf(DictionaryDefinition("noun", "Controlled definition with İ/ı, e\u0301, 🌿 and literal <b>text</b>.", "A leaf fell.")),
        DictionaryLicense("CC BY-SA 3.0", "https://creativecommons.org/licenses/by-sa/3.0/"), listOf("https://en.wiktionary.org/wiki/leaf"))))
    private val ready = DictionaryLookupState(preview.sessionId, "attempt-one", "leaf", result = result)
    private fun text(id: Int) = compose.activity.getString(id)
    @Test fun `lookup is explicit and loading not found error retry result and stale actions stay distinct`() {
        var state by mutableStateOf<DictionaryLookupState?>(null); var shown by mutableStateOf(true)
        var calls = 0; val retries = mutableListOf<DictionaryLookupState>()
        compose.setContent { YaprakTheme { if (shown) SelectedTextDialog(preview, {}, {}, dictionaryState = state,
            onDictionary = { calls++ }, onRetryDictionary = retries::add) } }
        assertEquals(0, calls)
        val lookup = compose.onNodeWithTag("dictionary-lookup").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("dictionary-lookup").performClick(); assertEquals(1, calls)
        compose.runOnIdle { state = ready.copy(result = null, loading = true) }
        compose.runOnIdle { lookup() }; assertEquals(1, calls)
        compose.onNodeWithText(text(R.string.dictionary_loading)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dictionary-lookup").assertIsNotEnabled()
        val failed = ready.copy(result = null, failure = DictionaryFailure.NOT_FOUND)
        compose.runOnIdle { state = failed }
        compose.onNodeWithText(text(R.string.dictionary_not_found)).performScrollTo().assertIsDisplayed()
        val retry = compose.onNodeWithTag("dictionary-retry").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("dictionary-retry").performClick(); assertEquals(listOf(failed), retries)
        compose.runOnIdle { state = ready }
        compose.onNodeWithText(result.entries.single().definitions.single().definition).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { retry() }; assertEquals(listOf(failed), retries)
        compose.runOnIdle { state = failed }; compose.runOnIdle { retry() }; assertEquals(listOf(failed), retries)
        compose.runOnIdle { shown = false }; compose.runOnIdle { lookup(); retry() }; assertEquals(1, calls)
    }
    @Test fun `native 320 double type real literal result and attribution scroll above fixed close`() {
        largeFont()
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, {}, {}, dictionaryState = ready,
            onDictionary = {}, onRetryDictionary = {}) } }
        readable(text(R.string.dictionary_transfer_help)); readable(result.entries.single().definitions.single().definition)
        readable(compose.activity.getString(R.string.dictionary_license, "CC BY-SA 3.0"))
        assertFooter(); capture("dictionary-narrow-double-type")
    }
    @Test @Config(qualifiers = "w640dp-h320dp-mdpi")
    fun `native short landscape double type timeout has explicit retry and fixed close`() {
        largeFont()
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, {}, {}, dictionaryState = ready.copy(result = null,
            failure = DictionaryFailure.TIMEOUT), onDictionary = {}, onRetryDictionary = {}) } }
        readable(text(R.string.dictionary_timeout)); readable(text(R.string.dictionary_retry))
        assertFooter(); capture("dictionary-landscape-double-type")
    }
    private fun largeFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate() }
    private fun readable(value: String) {
        val node = compose.onNodeWithText(value, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale); assertFalse(layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun assertFooter() {
        compose.onNodeWithText("Panoya kopyala").assertIsDisplayed(); compose.onNodeWithText("Kapat").assertIsDisplayed()
        val body = compose.onNodeWithTag("selected-text-body").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithText("Kapat").fetchSemanticsNode().boundsInRoot.top >= body.bottom)
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

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
class SelectedTextToolsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val preview = SelectedTextPreview.Ready("İ/ı 😀\nSeçili özgün e\u0301 metin")
    private val tool = SelectedTextTool("dictionary.fixture", "dictionary.fixture.Process", "Özgün sözlük ve çeviri aracı 🌿\nİkinci satır")
    @Test fun selectedBookSearchOpensAnEditableShortenedDraftWithoutSearchingUntilTheExplicitAction() {
        val original = SelectedTextPreview.Ready("x".repeat(199) + "😀 tail")
        var selection by mutableStateOf<SelectedTextPreview?>(original)
        var draft by mutableStateOf<BookSearchState?>(null)
        val searched = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            SelectedTextDialog(selection, {}, {}, onBookSearch = {
                draft = selectedBookSearchDraft(original.text); selection = null
            })
            draft?.let { state ->
                BookSearchDialog(state, { value -> draft = state.copy(query = limitBookSearchQuery(value), selectionDraftTruncated = false) },
                    { searched += requireNotNull(draft).query }, {}, {}, { draft = null })
            }
        } }
        assertTrue(searched.isEmpty())
        compose.onNodeWithTag("selected-text-book-search").performScrollTo().performClick()
        compose.onNodeWithTag("selected-text-book-search").assertDoesNotExist()
        compose.onNodeWithTag("book-search-query").assertTextContains("x".repeat(199))
        compose.onNodeWithText(compose.activity.getString(R.string.selected_text_book_search_shortened)).performScrollTo().assertIsDisplayed()
        assertTrue(searched.isEmpty())
        compose.onNodeWithTag("book-search-query").performScrollTo().performTextReplacement("dependency injection")
        compose.onNodeWithText(compose.activity.getString(R.string.selected_text_book_search_shortened)).assertDoesNotExist()
        assertTrue(searched.isEmpty())
        compose.onNodeWithText("Ara").performScrollTo().performClick()
        assertEquals(listOf("dependency injection"), searched)
    }

    @Test fun retainedBookSearchCallbackCannotUseAReplacedCapabilityOrAClosedSelection() {
        val actions = mutableListOf<String>()
        var handler by mutableStateOf<(() -> Unit)?>({ actions += "old" })
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, {}, {}, onBookSearch = handler) } }
        val button = compose.onNodeWithTag("selected-text-book-search")
        val old = button.performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { handler = { actions += "current" } }
        compose.runOnIdle { old() }; assertTrue(actions.isEmpty())
        button.performClick(); assertEquals(listOf("current"), actions)
        val current = button.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { handler = null }
        button.assertDoesNotExist()
        compose.runOnIdle { current() }; assertEquals(listOf("current"), actions)
    }

    private val loaded = SelectedTextToolsState(preview.sessionId, loading = false, tools = listOf(tool))
    private val toolTag = "selected-tool-${tool.packageName}/${tool.activityName}"

    @Test fun `optional tools launch only explicitly and absence failure retry remain distinct`() {
        var state by mutableStateOf(loaded.copy(loading = true, tools = emptyList()))
        var web = 0; var retry = 0; val processed = mutableListOf<SelectedTextTool>()
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, {}, {}, toolsState = state,
            onWebSearch = { web++ }, onTextTool = processed::add, onRetryTools = { retry++ }) } }
        assertEquals(0, web); assertTrue(processed.isEmpty())
        compose.onNodeWithText(text(R.string.selected_tools_loading)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("selected-text-web").performScrollTo().performClick(); assertEquals(1, web)
        compose.runOnIdle { state = loaded }
        compose.onNodeWithTag(toolTag).performScrollTo().performClick(); assertEquals(listOf(tool), processed)
        compose.runOnIdle { state = loaded.copy(tools = emptyList()) }
        compose.onNodeWithText(text(R.string.selected_tools_empty)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(toolTag).assertDoesNotExist()
        compose.runOnIdle { state = loaded.copy(discoveryFailure = SelectedTextToolFailure.SOURCE, tools = emptyList()) }
        compose.onNodeWithTag("selected-tools-retry").performScrollTo().performClick(); assertEquals(1, retry)
        compose.runOnIdle { state = loaded.copy(launchFailure = SelectedTextToolFailure.NO_HANDLER) }
        compose.onNodeWithText(text(R.string.selected_tools_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(preview.text).assertExists()
        compose.onNodeWithText("Panoya kopyala").assertIsDisplayed(); compose.onNodeWithText("Kapat").assertIsDisplayed()
    }

    @Test fun `old tools and web callbacks reject replacement state callback preview and disposed composition`() {
        var shown by mutableStateOf(true); var state by mutableStateOf(loaded)
        var currentPreview by mutableStateOf<SelectedTextPreview>(preview); var changed by mutableStateOf(false)
        var effects = 0
        val webOne: () -> Unit = { effects++ }; val webTwo: () -> Unit = { effects += 100 }
        val process: (SelectedTextTool) -> Unit = { effects++ }
        compose.setContent { YaprakTheme { if (shown) SelectedTextDialog(currentPreview, {}, {}, toolsState = state,
            onWebSearch = if (changed) webTwo else webOne, onTextTool = process) } }
        val web = action("selected-text-web"); val toolAction = action(toolTag)
        val close = compose.onNodeWithText("Kapat").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = loaded.copy(launchFailure = SelectedTextToolFailure.SOURCE); changed = true }
        compose.runOnIdle { web(); toolAction() }; assertEquals(0, effects)
        compose.runOnIdle { state = loaded; changed = false }
        compose.runOnIdle { web(); toolAction() }; assertEquals(0, effects)
        compose.runOnIdle { currentPreview = SelectedTextPreview.Ready(preview.text) }
        compose.runOnIdle { web(); toolAction() }; assertEquals(0, effects)
        compose.runOnIdle { currentPreview = preview; state = loaded; changed = false; shown = false }
        compose.runOnIdle { web(); toolAction(); close() }; assertEquals(0, effects)
    }

    @Test fun `native narrow double type original tool label and transfer help scroll with fixed copy close`() {
        largeFont()
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, {}, {}, toolsState = loaded,
            onWebSearch = {}, onTextTool = {}) } }
        readableScroll(text(R.string.selected_tools_transfer_help)); readableScroll(tool.label)
        readableScroll(text(R.string.selected_tools_web)); assertFooter()
        capture("selected-tools-narrow-double-type")
    }

    @Test @Config(qualifiers = "w640dp-h320dp-mdpi")
    fun `native short landscape double type unavailable handler is readable and close remains fixed`() {
        largeFont()
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, {}, {}, toolsState = loaded.copy(
            launchFailure = SelectedTextToolFailure.NO_HANDLER), onWebSearch = {}, onTextTool = {}) } }
        readableScroll(text(R.string.selected_tools_unavailable)); assertFooter()
        capture("selected-tools-landscape-double-type")
    }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun action(tag: String): () -> Boolean = compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun largeFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate() }
    private fun readableScroll(value: String) { compose.onNodeWithText(value, useUnmergedTree = true).performScrollTo().assertIsDisplayed(); readable(value) }
    private fun readable(value: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(value, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse("Overflow: $value", layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun assertFooter() {
        compose.onNodeWithText("Panoya kopyala").assertIsDisplayed(); readable("Panoya kopyala")
        compose.onNodeWithText("Kapat").assertIsDisplayed(); readable("Kapat")
        val body = compose.onNodeWithTag("selected-text-body").fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithText("Kapat").fetchSemanticsNode().boundsInRoot
        assertTrue(close.top >= body.bottom)
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

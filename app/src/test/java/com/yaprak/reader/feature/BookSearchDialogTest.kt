package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import org.robolectric.shadows.ShadowDialog
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.BookSearchHit
import com.yaprak.reader.reader.BookSearchResults
import com.yaprak.reader.reader.BookSearchKind
import com.yaprak.reader.R
import com.yaprak.reader.ui.theme.YaprakTheme
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
class BookSearchDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `typing does not start search and explicit search shows navigable snippets`() {
        var state by mutableStateOf(BookSearchState())
        var searched = 0
        var selected: BookSearchHit? = null
        val hit = BookSearchHit("Sabah", "Güneş doğdu. Yeni bir gün başladı.",
            ReadingLocation("{}", "EPUB/chapter.xhtml"))
        compose.setContent { YaprakTheme { BookSearchDialog(state,
            { state = state.copy(query = limitBookSearchQuery(it), phase = BookSearchPhase.Idle) },
            { searched++; state = state.copy(phase = BookSearchPhase.Ready(BookSearchResults(listOf(hit), false))) },
            {}, { selected = it }, {}) } }
        compose.onNodeWithTag("book-search-query").performTextReplacement("Güneş")
        assertEquals(0, searched)
        compose.onNodeWithText("Ara").performClick()
        assertEquals(1, searched)
        compose.onNodeWithText("Güneş doğdu. Yeni bir gün başladı.").assertIsDisplayed()
        compose.onNodeWithTag("book-search-hit").performClick()
        assertEquals(hit, selected)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `large type short screen keeps cancel and close reachable`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        var cancelled = 0
        var dismissed = 0
        compose.setContent { YaprakTheme { BookSearchDialog(BookSearchState("aranan", BookSearchPhase.Searching),
            {}, {}, { cancelled++ }, {}, { dismissed++ }) } }
        compose.onNodeWithTag("book-search-list").performScrollToNode(hasText("Aramayı durdur"))
        compose.onNodeWithText("Aramayı durdur").assertIsDisplayed().performClick()
        compose.onNodeWithTag("book-search-list").performScrollToNode(hasText("Kapat"))
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertEquals(1, cancelled)
        assertEquals(1, dismissed)
    }

    @Test fun `query cap keeps surrogate pair intact and empty search is disabled`() {
        assertEquals("x".repeat(199), limitBookSearchQuery("x".repeat(199) + "😀x"))
        compose.setContent { YaprakTheme { BookSearchDialog(BookSearchState(), {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Ara").assertIsNotEnabled()
    }

    @Test fun pdfUnsupportedAndLimitStayDistinctFromEmptyResults() {
        var state by mutableStateOf(BookSearchState("word", BookSearchPhase.Unsupported))
        var calls = 0
        compose.setContent { YaprakTheme { BookSearchDialog(state, {}, { calls++ }, {}, {}, {}, BookSearchKind.PDF) } }
        compose.onNodeWithTag("book-search-list").performScrollToNode(hasText("Ara"))
        compose.onNodeWithText("Ara").assertIsNotEnabled()
        compose.onNodeWithTag("book-search-list").performScrollToNode(hasText(compose.activity.getString(R.string.pdf_search_unsupported)))
        compose.onNodeWithText(compose.activity.getString(R.string.pdf_search_unsupported)).assertIsDisplayed()
        compose.onNodeWithText("Bu kitapta eşleşme bulunamadı.").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(phase = BookSearchPhase.Limit) }
        compose.onNodeWithTag("book-search-list").performScrollToNode(hasText(compose.activity.getString(R.string.pdf_search_limit)))
        compose.onNodeWithText(compose.activity.getString(R.string.pdf_search_limit)).assertIsDisplayed()
        assertEquals(0, calls)
    }
    @Test fun retainedHitAndActionsCannotNavigateNewResultsOrAba() {
        val hit = BookSearchHit("Sayfa 1", "a word", ReadingLocation("{}", "pdf"))
        var state by mutableStateOf(BookSearchState("word", BookSearchPhase.Ready(BookSearchResults(listOf(hit), false))))
        var selections = 0; var searches = 0; var dismissals = 0
        compose.setContent { YaprakTheme { BookSearchDialog(state, {}, { searches++ }, {}, { selections++ }, { dismissals++ }) } }
        compose.onNodeWithTag("book-search-list").performScrollToNode(hasTestTag("book-search-hit"))
        val oldHit = compose.onNodeWithTag("book-search-hit").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("book-search-list").performScrollToNode(hasText("Ara"))
        val oldSearch = compose.onNodeWithText("Ara").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("book-search-list").performScrollToNode(hasText("Kapat"))
        val oldClose = compose.onNodeWithText("Kapat").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(phase = BookSearchPhase.Searching) }
        compose.runOnIdle { state = state.copy(phase = BookSearchPhase.Ready(BookSearchResults(listOf(hit.copy()), false))) }
        compose.runOnIdle { oldHit(); oldSearch(); oldClose() }
        assertEquals(0, selections); assertEquals(0, searches); assertEquals(0, dismissals)
        compose.onNodeWithTag("book-search-list").performScrollToNode(hasTestTag("book-search-hit"))
        compose.onNodeWithTag("book-search-hit").performClick(); assertEquals(1, selections)
    }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun pdfDoubleFontShortLandscapeKeepsHelpAndErrorFullyLaidOut() {
        checkPdfLargeFontLayout("pdf-search-landscape-font2")
    }
    @Test fun pdfDoubleFontNarrowKeepsHelpAndErrorFullyLaidOut() {
        checkPdfLargeFontLayout("pdf-search-narrow-font2")
    }
    private fun checkPdfLargeFontLayout(name: String) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { BookSearchDialog(BookSearchState("word", BookSearchPhase.Limit), {}, {}, {}, {}, {}, BookSearchKind.PDF) } }
        for (text in listOf("Kitapta ara", "Kapat", "Aranacak metin", "Ara", compose.activity.getString(R.string.pdf_search_scope),
            compose.activity.getString(R.string.pdf_search_limit))) {
            compose.onNodeWithTag("book-search-list").performScrollToNode(hasText(text))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(2f, layout.layoutInput.density.fontScale)
            assertFalse(text, layout.hasVisualOverflow)
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        }
        compose.runOnIdle {
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val dir = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
                File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
    @Test fun pdfTypingKeepsFocusAndNeverStartsAScan() {
        var state by mutableStateOf(BookSearchState()); var searches = 0
        compose.setContent { YaprakTheme { BookSearchDialog(state, { state = state.copy(query = it) }, { searches++ }, {}, {}, {}, BookSearchKind.PDF) } }
        compose.onNodeWithTag("book-search-list").performScrollToNode(hasTestTag("book-search-query"))
        val field = compose.onNodeWithTag("book-search-query")
        field.performClick(); field.performTextInput("alpha"); field.assertIsFocused()
        field.performTextInput(" beta"); field.assertIsFocused()
        assertEquals("alpha beta", state.query); assertEquals(0, searches)
    }
}

package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryTextSearchFormatsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun resetFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }
    private val scope = F.scope
    private fun result(format: BookFormat): LibraryTextSearchResult {
        val book = F.book.copy(format = format, pageCount = if (format == BookFormat.PDF) 3 else null)
        val hit = F.hit.copy(book = book, readingOrderIndex = 2, chapterTitle = if (format == BookFormat.TXT) "Metin 3" else null,
            target = F.hit.target.copy(sourceFormat = format, resourceHref = if (format == BookFormat.PDF) "" else "Text/part0003.xhtml",
                pdfPageIndex = if (format == BookFormat.PDF) 2 else null))
        return LibraryTextSearchResult("Güneş", listOf(hit), 1, listOf(LibraryTextBookStatus(book, LibraryTextBookState.READY)), scope)
    }
    private fun action() = compose.onNodeWithTag("library-text-list").performScrollToNode(hasTestTag("library-text-assistant"))
        .let { compose.onNodeWithTag("library-text-assistant") }
    private fun route(format: BookFormat) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        val source = MutableStateFlow(result(format))
        val index = object : LibraryTextIndex {
            override fun observe(query: String) = error("Exact scope required")
            override fun observeBook(query: String, scope: BookTextScope) = source.map { it.copy(query = query, scope = scope) }
            override suspend fun rebuild(onProgress: (LibraryTextIndexProgress) -> Unit) = error("Launch must not rebuild")
            override suspend fun resolve(hit: LibraryTextHit) = error("Launch must not open a page")
        }
        val model = LibraryTextSearchViewModel(index, SavedStateHandle(mapOf(
            LibraryTextSearchViewModel.QUERY to "Güneş", LibraryTextSearchViewModel.SCOPE_BOOK to scope.bookId,
            LibraryTextSearchViewModel.SCOPE_HASH to scope.contentSha256)))
        compose.activity.viewModelStore.put("format-search", model)
        val launches = mutableListOf<Pair<BookTextScope, String>>()
        compose.setContent { YaprakTheme { LibraryTextSearchRoute(model, {}, {}, onAssistant = { selected, query -> launches += selected to query }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library-text-list").fetchSemanticsNodes(); model.state.value.canSearch && !model.state.value.loading }
        compose.onNodeWithTag("library-text-list").performScrollToNode(hasTestTag("library-text-search"))
        compose.onNodeWithTag("library-text-search").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library-text-list").fetchSemanticsNodes(); model.state.value.actionsEnabled }
        val button = action().assertIsDisplayed()
        val text = compose.activity.getString(R.string.assistant_title)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale); assertFalse(layout.hasVisualOverflow)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        val bounds = button.fetchSemanticsNode().boundsInRoot; val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left && bounds.right <= root.right && bounds.top >= root.top && bounds.bottom <= root.bottom)
        button.performClick(); assertEquals(listOf(scope to "Güneş"), launches)
        val old = action().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnUiThread { old() }; assertEquals(1, launches.size)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { model.state.value.actionsEnabled }
        action().performClick(); assertEquals(listOf(scope to "Güneş", scope to "Güneş"), launches)
        compose.runOnIdle { source.value = result(BookFormat.CBZ) }
        compose.waitForIdle(); compose.onNodeWithTag("library-text-assistant").assertDoesNotExist()
        compose.runOnIdle { old() }; assertEquals(2, launches.size)
    }
    @Test fun currentPdfScopeLaunchesOnlyExplicitlyThroughTheResumedRoute() { route(BookFormat.PDF) }
    @Test fun currentTxtScopeLaunchesOnlyExplicitlyThroughTheResumedRoute() { route(BookFormat.TXT) }
    @Test fun replacementGenerationAndGlobalEmptyUnsupportedOrPausedResultsRevokeTheOldAction() {
        val original = result(BookFormat.PDF)
        val ready = LibraryTextSearchState(query = original.query, searchedQuery = original.query, result = original,
            loading = false, active = true, revision = 7, scope = scope)
        var state by mutableStateOf(ready); var launches = 0
        compose.setContent { YaprakTheme { LibraryTextSearchScreen(state, {}, { _, _ -> }, {}, {}, {}, {}, {},
            onAssistant = { _, _, _ -> launches++ }) } }
        val old = action().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = ready.copy(result = original.copy(hits = original.hits.map { it.copy(generation = "replacement") })) }
        compose.runOnIdle { old() }; assertEquals(0, launches)
        action().performClick(); assertEquals(1, launches)
        val fresh = action().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        for (gated in listOf(ready.copy(active = false), ready.copy(scope = null, result = original.copy(scope = null)),
            ready.copy(result = original.copy(hits = emptyList(), totalMatches = 0)), ready.copy(result = result(BookFormat.CBZ)))) {
            compose.runOnIdle { state = gated }; compose.runOnIdle { old(); fresh() }
            assertEquals(1, launches); compose.onNodeWithTag("library-text-assistant").assertDoesNotExist()
        }
    }
}

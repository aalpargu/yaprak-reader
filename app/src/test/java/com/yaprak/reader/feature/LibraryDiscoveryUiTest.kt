package com.yaprak.reader.feature

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.library.*
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryDiscoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `1000 row preview keeps fixed import and close reachable after last row selection at double type`() {
        val candidates = (0 until 1000).map(::candidate)
        var state by mutableStateOf<DiscoveryState>(DiscoveryState.Preview("large", candidates, 1100))
        var imports = 0
        var closes = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                YaprakTheme { BookDiscoveryDialog(state, { _, _ -> }, { id, candidate, checked ->
                    val preview = state as DiscoveryState.Preview
                    assertEquals(preview.sessionId, id)
                    state = preview.copy(selectedIds = if (checked) preview.selectedIds + candidate else preview.selectedIds - candidate)
                }, {}, {}, { imports++ }, { closes++ }, {}) }
            }
        }
        compose.onNodeWithText(text(R.string.discovery_import_selected, 0)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.cancel_remove)).assertIsDisplayed()
        awaitRows()
        // Jump near the end without walking all 1000 rows, then locate the actual last book.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(candidates.lastIndex)
            .performScrollToNode(hasText(candidates.last().name))
        compose.onNodeWithText(candidates.last().name).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.discovery_import_selected, 1)).assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.cancel_remove)).assertIsDisplayed().performClick()
        assertEquals(setOf(candidates.last().id), (state as DiscoveryState.Preview).selectedIds)
        assertEquals(1, imports)
        assertEquals(1, closes)
    }

    @Test fun `old Material import close and candidate callbacks cannot touch new session`() {
        var state by mutableStateOf<DiscoveryState>(DiscoveryState.Preview("old", listOf(candidate(0)), 1,
            selectedIds = setOf("candidate-0")))
        var imports = 0
        var closes = 0
        var selections = 0
        compose.setContent { YaprakTheme { BookDiscoveryDialog(state, { _, _ -> }, { _, _, _ -> selections++ }, {}, {},
            { imports++ }, { closes++ }, {}) } }
        awaitRows()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(candidate(0).name))
        val oldSelect = compose.onNodeWithText(candidate(0).name).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val oldImport = compose.onNodeWithText(text(R.string.discovery_import_selected, 1))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val oldClose = compose.onNodeWithText(text(R.string.cancel_remove))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = DiscoveryState.Preview("new", listOf(candidate(0).copy(documentUri = "content://new-current")), 1,
            selectedIds = setOf("candidate-0")) }
        compose.runOnIdle { oldSelect(); oldImport(); oldClose() }
        assertEquals(0, selections)
        assertEquals(0, imports)
        assertEquals(0, closes)
        compose.onNodeWithText(text(R.string.discovery_import_selected, 1)).performClick()
        assertEquals(1, imports)
    }

    @Test fun `interrupted scanning reports cleanup and disables stop with polite progress`() {
        var state by mutableStateOf<DiscoveryState>(DiscoveryState.Scanning("scan", DiscoveryProgress(50, 10)))
        var stops = 0
        compose.setContent { YaprakTheme { BookDiscoveryDialog(state, { _, _ -> }, { _, _, _ -> }, {}, {}, {}, {}, {
            stops++
            state = (state as DiscoveryState.Scanning).copy(cancelRequested = true)
        }) } }
        val progress = compose.onNodeWithText(text(R.string.discovery_progress, 50, 10)).performScrollTo().assertIsDisplayed()
        assertEquals(LiveRegionMode.Polite, progress.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        compose.onNodeWithText(text(R.string.discovery_stop)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.discovery_stopping)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.discovery_stop)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.discovery_import_selected, 0)).assertDoesNotExist()
        assertEquals(1, stops)
    }

    @Test fun `partial and empty results are explicit and require selection before import`() {
        var state by mutableStateOf<DiscoveryState>(DiscoveryState.Preview("partial", emptyList(), 10000, DiscoveryLimit.DOCUMENTS))
        compose.setContent { YaprakTheme { BookDiscoveryDialog(state, { _, _ -> }, { _, _, _ -> }, {}, {}, {}, {}, {}) } }
        previewText(R.string.discovery_limit_documents).assertIsDisplayed()
        previewText(R.string.discovery_empty).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.discovery_import_selected, 0)).assertIsNotEnabled()
        compose.runOnIdle { state = DiscoveryState.Preview("complete", listOf(candidate(0)), 1, query = "No match") }
        previewText(R.string.discovery_no_matches).assertIsDisplayed()
        previewText(R.string.discovery_select_all, 100).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.discovery_empty)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.discovery_limit_documents)).assertDoesNotExist()
    }

    @Test fun `checked row stays removable at selection cap while unchecked row is disabled`() {
        val candidates = (0..100).map(::candidate)
        var state by mutableStateOf<DiscoveryState>(DiscoveryState.Preview("cap", candidates, 101,
            selectedIds = candidates.take(100).map(BookCandidate::id).toSet()))
        var deselected: String? = null
        compose.setContent { YaprakTheme { BookDiscoveryDialog(state, { _, _ -> }, { _, id, checked ->
            assertFalse(checked)
            deselected = id
            state = (state as DiscoveryState.Preview).let { it.copy(selectedIds = it.selectedIds - id) }
        }, {}, {}, {}, {}, {}) } }
        awaitRows()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(candidates.lastIndex)
            .performScrollToNode(hasText(candidates.last().name))
        compose.onNodeWithText(candidates.last().name).assertIsNotEnabled()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(candidates[99].name))
        compose.onNodeWithText(candidates[99].name).assertIsEnabled().performClick()
        assertEquals(candidates[99].id, deselected)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(candidates.last().name))
        compose.onNodeWithText(candidates.last().name).assertIsEnabled()
    }

    @Test fun `discovery reservation blocks library import and missing capability hides discovery`() {
        var available by mutableStateOf(true)
        compose.setContent { YaprakTheme { LibraryScreen(LibraryState.Ready(emptyList()), false, SnackbarHostState(),
            {}, {}, {}, "", {}, discoveryState = DiscoveryState.Choosing("picker"),
            onDiscover = if (available) ({}) else null) } }
        compose.onNodeWithText(text(R.string.add_book)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.discovery_open_folder)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.discovery_selecting)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { available = false }
        compose.onNodeWithText(text(R.string.discovery_open_folder)).assertDoesNotExist()
    }

    @Test fun `tree picker requests local SAF folder without OPENABLE and cancellation yields no tree`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val contract = OpenLocalBookFolder()
        val intent = contract.createIntent(context, null)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertFalse(intent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(Uri.parse("content://ignored"))))
        assertEquals(Uri.parse("content://books/tree/selected"),
            contract.parseResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://books/tree/selected"))))
    }

    private fun awaitRows() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.discovery_select_all, 100)))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text(R.string.discovery_select_all, 100)).fetchSemanticsNodes()
                .any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        compose.waitForIdle()
    }

    private fun previewText(resource: Int, vararg args: Any): SemanticsNodeInteraction {
        val label = text(resource, *args)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        return compose.onNodeWithText(label)
    }

    private fun candidate(index: Int) = BookCandidate("candidate-$index", "Kitap $index.pdf", "content://candidate/$index",
        null, BookFormat.PDF)
    private fun text(resource: Int, vararg args: Any) = compose.activity.getString(resource, *args)
}

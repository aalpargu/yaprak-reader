package com.yaprak.reader.feature

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.notes.*
import com.yaprak.reader.feature.librarynotes.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Actual route, owner, chooser and SAF intent with controlled source/exporter; Room data is tested separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryNotesAnkiUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { RuntimeEnvironment.setFontScale(1f) }
    private val query = "istanbul Deniz"
    private val note = LibraryNote("note", "book", "a".repeat(64), "İstanbul", "Deniz", BookFormat.EPUB, "own", 1)

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun narrowNativeFont200AnkiExportIsExplicitAndClosedChooserCannotRetargetTheReopenedScope() =
        verify("narrow", LibraryNotesExportFormat.ANKI_TSV, false)

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun shortLandscapeNativeFont200AnkiExportInvalidatesChangedMetadataAndShowsTheActualTotalNotice() =
        verify("landscape", LibraryNotesExportFormat.ANKI_TSV, true)

    private fun verify(name: String, format: LibraryNotesExportFormat, changeSource: Boolean) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        val result = MutableStateFlow(LibraryNotesResult(List(200) { note.copy(id = "note-$it") }, 230))
        val repository = object : LibraryNotesRepository {
            override fun observe(query: String) = result
            override suspend fun find(bookId: String, contentSha256: String, noteId: String): LibraryNote? = null
        }
        val calls = mutableListOf<Triple<String, String, LibraryNotesExportFormat>>()
        val exporter = LibraryNotesExporter { scope, uri, chosen -> calls += Triple(scope, uri, chosen); StudyExportResult.Exported(230, 0, 0) }
        val store = ViewModelStore()
        val model = LibraryNotesViewModel(repository, SavedStateHandle(mapOf(LibraryNotesViewModel.QUERY to query)), exporter)
        store.put("notes", model)
        val registry = Registry(compose.activity)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        try {
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { YaprakTheme {
                LibraryNotesRoute(model, {}, {}, {})
            } } }
            compose.waitUntil(10_000) { model.state.value.active && !model.state.value.loading }
            fun open() {
                compose.onNodeWithTag("library-notes-list").performScrollToNode(hasTestTag("library-notes-export"))
                compose.onNodeWithTag("library-notes-export").assertIsEnabled().performClick()
            }
            open(); assertTrue(calls.isEmpty()); assertTrue(registry.launches.isEmpty())
            val tag = "library-notes-export-format-${format.name}"
            val help = compose.activity.getString(R.string.library_notes_export_anki_help)
            compose.onNodeWithText(help).performScrollTo().assertIsDisplayed(); completeText(help); capture("$name-help")
            val helpScroll = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(help)))
            helpScroll.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 100_000f)) }
            compose.waitForIdle()
            val scrollRange = helpScroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(scrollRange.maxValue() > 0f)
            assertEquals(scrollRange.maxValue(), scrollRange.value(), 1f)
            compose.onNodeWithText(help).assertIsDisplayed()
            capture("$name-help-end")
            compose.onNodeWithTag("library-notes-export-cancel").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            val label = compose.activity.getString(R.string.library_notes_export_anki)
            completeText(label)
            val bounds = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { val view = ShadowDialog.getLatestDialog().window!!.decorView
                assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= view.width && bounds.bottom <= view.height) }
            capture(name)
            val oldAction = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            if (changeSource) {
                compose.runOnIdle { result.value = result.value.copy(entries = result.value.entries.map { it.copy(bookTitle = "İstanbul Güncel") }) }
                compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
                compose.runOnIdle { oldAction() }; assertTrue(registry.launches.isEmpty())
                compose.onNodeWithText(compose.activity.getString(R.string.library_notes_export_stale)).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithTag("library-notes-export-cancel").performClick(); open()
            compose.runOnIdle { oldAction() }; assertTrue(calls.isEmpty()); assertTrue(registry.launches.isEmpty())
            compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle()
            val launch = registry.launches.single()
            assertEquals(format.mimeType, launch.intent.type); assertEquals(Intent.ACTION_CREATE_DOCUMENT, launch.intent.action)
            assertEquals("Yaprak-kutuphane-notlari.${format.extension}", launch.intent.getStringExtra(Intent.EXTRA_TITLE))
            compose.runOnIdle { registry.dispatchResult(launch.code, Activity.RESULT_OK, Intent().setData(Uri.parse("content://library-notes/$name"))) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("library-notes-export-result").fetchSemanticsNodes().isNotEmpty() }
            val success = compose.activity.getString(R.string.library_notes_export_success, 230)
            compose.onNodeWithText(success).performScrollTo().assertIsDisplayed(); completeText(success); capture("$name-result")
            assertEquals(listOf(Triple(query, "content://library-notes/$name", format)), calls)
            compose.onNodeWithTag("library-notes-export-result-close").performClick()
            compose.onNodeWithTag("library-notes-list").performScrollToNode(hasTestTag("library-notes-export"))
            compose.onNodeWithTag("library-notes-export").assertIsEnabled()
        } finally { compose.runOnIdle { store.clear() } }
    }
    private fun completeText(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single(); assertFalse(layout.hasVisualOverflow)
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String) {
        compose.waitForIdle(); compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView; assertEquals(2f, view.resources.configuration.fontScale, 0f)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
            val directory = File(System.getProperty("yaprak.screenshotDir"), "library-notes-anki-0.140.0").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private class Registry(val context: Context) : ActivityResultRegistry() {
        data class Launch(val code: Int, val intent: Intent)
        val launches = mutableListOf<Launch>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += Launch(requestCode, contract.createIntent(context, input))
        }
    }
}

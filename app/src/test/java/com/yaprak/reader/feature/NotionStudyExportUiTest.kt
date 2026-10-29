package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.library.*
import com.yaprak.reader.feature.library.LibraryLayout
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotionStudyExportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("book", "İstanbul", "Deniz", BookFormat.EPUB, "book.epub", "book.epub", 1, "hash", 1)
    @After fun restoreFont() { RuntimeEnvironment.setFontScale(1f) }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun narrowNativeFont200ChoiceIsExplicitAndChangedMetadataCannotUseCapturedAction() = verify("narrow", true)

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun shortLandscapeNativeFont200ChoiceAndManualImportHelpScrollWithReachableCancel() = verify("landscape", false)

    private fun verify(name: String, changeBook: Boolean) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        var currentBook by mutableStateOf(book)
        val selections = mutableListOf<Pair<Book, StudyExportFormat>>()
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(currentBook)), false, SnackbarHostState(), {}, {}, {}, "", {},
                layout = LibraryLayout.LIST, onStudyExportFormat = { selected, format -> selections += selected to format })
        } }
        fun open(title: String = book.title) {
            val description = "$title, Deniz, EPUB, Belirlenmedi"
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(description))
            compose.onNodeWithContentDescription(description).performClick()
            compose.onNodeWithText("Çalışma kayıtlarını dışa aktar").performScrollTo().performClick()
        }
        open(); assertTrue(selections.isEmpty())
        val label = compose.activity.getString(R.string.study_export_format_notion)
        val help = compose.activity.getString(R.string.study_export_format_notion_help)
        val tag = "study-export-format-NOTION_CSV"
        compose.onNodeWithText(help).performScrollTo().assertIsDisplayed()
        assertCompleteText(help)
        compose.onNodeWithTag("study-export-format-cancel").assertIsDisplayed().assertIsEnabled()
        capture("$name-help")
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertCompleteText(label)
        val labelBounds = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            assertTrue(labelBounds.left >= 0 && labelBounds.top >= 0)
            assertTrue(labelBounds.right <= view.width && labelBounds.bottom <= view.height)
        }
        capture(name)
        if (changeBook) {
            val oldAction = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle { currentBook = book.copy(title = "Güncel İstanbul") }
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { oldAction() }; assertTrue(selections.isEmpty())
            compose.onNodeWithTag("study-export-format-cancel").performClick()
            open(currentBook.title)
            compose.onNodeWithTag(tag).performScrollTo()
        }
        compose.onNodeWithTag(tag).performClick()
        assertEquals(listOf(currentBook to StudyExportFormat.NOTION_CSV), selections)
        compose.onNodeWithText("Aktarım biçimini seç").assertDoesNotExist()
    }

    private fun assertCompleteText(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val result = results.single()
        assertFalse(result.hasVisualOverflow); assertTrue(result.lineCount > 0)
        assertFalse((0 until result.lineCount).any(result::isLineEllipsized))
        assertEquals(text.length, result.getLineEnd(result.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            assertEquals(2f, view.resources.configuration.fontScale, 0f)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File(System.getProperty("yaprak.screenshotDir"), "notion-export-0.132.0").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

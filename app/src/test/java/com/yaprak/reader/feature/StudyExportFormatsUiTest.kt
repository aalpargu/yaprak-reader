package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.library.StudyExportResult
import com.yaprak.reader.feature.library.LibraryLayout
import com.yaprak.reader.feature.library.LibraryScreen
import com.yaprak.reader.feature.library.LibraryState
import com.yaprak.reader.feature.library.StudyExportFormatDialog
import com.yaprak.reader.feature.library.message
import com.yaprak.reader.ui.theme.YaprakTheme
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
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudyExportFormatsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("book", "İstanbul", "Deniz", BookFormat.EPUB, "book.epub", "book.epub", 1, "hash", 1)
    private val action = "Çalışma kayıtlarını dışa aktar"
    private val card = "İstanbul, Deniz, EPUB, Belirlenmedi"

    @Test fun `each explicit format exports selected book without default or ordinary file export`() {
        val selections = mutableListOf<Pair<Book, StudyExportFormat>>()
        var fileExports = 0
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(), {}, {}, {}, "", {},
                layout = LibraryLayout.LIST, onExport = { fileExports++ },
                onStudyExportFormat = { selected, format -> selections += selected to format })
        } }
        StudyExportFormat.entries.forEach { format ->
            openChooser()
            assertEquals(StudyExportFormat.entries.indexOf(format), selections.size)
            compose.onNodeWithText("Kitap bilgileri").assertDoesNotExist()
            compose.onNodeWithTag("study-export-format-${format.name}").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("Aktarım biçimini seç").assertDoesNotExist()
            assertEquals(book to format, selections.last())
        }
        assertEquals(0, fileExports)
    }

    @Test fun `cancel closes chooser without export and library remains usable`() {
        var exports = 0
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(), {}, {}, {}, "", {},
                layout = LibraryLayout.LIST, onStudyExportFormat = { _, _ -> exports++ })
        } }
        openChooser()
        compose.onNodeWithTag("study-export-format-cancel").assertIsDisplayed().performClick()
        compose.onNodeWithText("Aktarım biçimini seç").assertDoesNotExist()
        assertEquals(0, exports)
        compose.onNodeWithText("Kitap ekle", useUnmergedTree = true).assertIsEnabled()
        openChooser()
        compose.onNodeWithTag("study-export-format-MARKDOWN").performScrollTo().performClick()
        assertEquals(1, exports)
    }

    @Test fun `callback captured before cancel cannot export reopened same book session`() {
        val selections = mutableListOf<StudyExportFormat>()
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(), {}, {}, {}, "", {},
                layout = LibraryLayout.LIST, onStudyExportFormat = { _, format -> selections += format })
        } }
        StudyExportFormat.entries.forEachIndexed { index, format ->
            openChooser()
            val tag = "study-export-format-${format.name}"
            compose.onNodeWithTag(tag).performScrollTo()
            val oldAction = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.onNodeWithTag("study-export-format-cancel").performClick()
            openChooser()
            compose.runOnIdle { oldAction() }
            assertEquals(index, selections.size)
            compose.onNodeWithText("Aktarım biçimini seç").assertExists()
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            assertEquals(StudyExportFormat.entries.take(index + 1), selections)
        }
    }

    @Test fun `changed full book revision and old captured choice cannot export stale metadata`() {
        var currentBook by mutableStateOf(book)
        val selections = mutableListOf<Pair<Book, StudyExportFormat>>()
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(currentBook)), false, SnackbarHostState(), {}, {}, {}, "", {},
                layout = LibraryLayout.LIST, onStudyExportFormat = { selected, format -> selections += selected to format })
        } }
        openChooser()
        compose.onNodeWithTag("study-export-format-PDF").performScrollTo()
        val oldAction = compose.onNodeWithTag("study-export-format-PDF").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { currentBook = book.copy(title = "Güncel İstanbul") }
        compose.onNodeWithTag("study-export-format-PDF").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { oldAction() }
        assertTrue(selections.isEmpty())
        compose.onNodeWithText(compose.activity.getString(R.string.study_export_error_changed_book))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("study-export-format-cancel").performClick()
        openChooser("Güncel İstanbul, Deniz, EPUB, Belirlenmedi")
        compose.onNodeWithTag("study-export-format-PDF").performScrollTo().performClick()
        assertEquals(listOf(currentBook to StudyExportFormat.PDF), selections)
    }

    @Test fun `active mutation disables format choice including previously captured event`() {
        var importing by mutableStateOf(false)
        var exports = 0
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), importing, SnackbarHostState(), {}, {}, {}, "", {},
                layout = LibraryLayout.LIST, onStudyExportFormat = { _, _ -> exports++ })
        } }
        openChooser()
        compose.onNodeWithTag("study-export-format-PDF").performScrollTo()
        val oldAction = compose.onNodeWithTag("study-export-format-PDF").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { importing = true }
        compose.onNodeWithTag("study-export-format-PDF").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { oldAction() }
        assertEquals(0, exports)
        compose.onNodeWithTag("study-export-format-cancel").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { importing = false }
        compose.onNodeWithTag("study-export-format-PDF").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, exports)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `narrow two times font scrolls each format fully while cancel remains reachable`() {
        fontTwo()
        var chosen: StudyExportFormat? = null
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { YaprakTheme {
            StudyExportFormatDialog(book.copy(title = "Bir öğrencinin çok uzun kitap başlığı 日本語 😀"), true, false,
                onSelect = { chosen = it }, onDismiss = {})
        } } }
        assertChoicesReadable()
        compose.onNodeWithTag("study-export-format-ANKI_STUDY_TSV").performScrollTo()
        screenshot("anki-study-narrow-font2")
        compose.onNodeWithTag("study-export-format-ANKI_STUDY_TSV").performClick()
        assertEquals(StudyExportFormat.ANKI_STUDY_TSV, chosen)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short landscape two times font scrolls each format with fixed cancel`() {
        fontTwo()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { YaprakTheme {
            StudyExportFormatDialog(book, true, false, {}, {})
        } } }
        assertChoicesReadable()
        compose.onNodeWithText(compose.activity.getString(R.string.study_export_format_anki_study_help)).performScrollTo()
        screenshot("anki-study-landscape-font2")
    }

    @Test fun `Anki success reports import rows without double counting attached notes or changing old notices`() {
        val result=StudyExportResult.Exported(3,0,2,3)
        assertEquals(compose.activity.getString(R.string.study_export_anki_study_success,3),result.message(compose.activity))
        assertEquals(compose.activity.getString(R.string.study_export_success,3,0,2),
            StudyExportResult.Exported(3,0,2).message(compose.activity))
    }

    private fun fontTwo() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
    }

    private fun screenshot(name:String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view=ShadowDialog.getLatestDialog().window!!.decorView
            assertEquals(2f,view.resources.configuration.fontScale,0f)
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir=File(System.getProperty("yaprak.screenshotDir"),"anki-study-0.99.0").apply{mkdirs()}
            File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }

    private fun openChooser(description: String = card) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).performClick()
        compose.onNodeWithText(action).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Aktarım biçimini seç").assertExists()
    }

    private fun assertChoicesReadable() {
        val strings = listOf(R.string.study_export_format_title, R.string.export_study_formats_body,
            R.string.study_export_format_text, R.string.study_export_format_text_help,
            R.string.study_export_format_markdown, R.string.study_export_format_markdown_help,
            R.string.study_export_format_anki, R.string.study_export_format_anki_help,
            R.string.study_export_format_pdf, R.string.study_export_format_pdf_help,
            R.string.study_export_format_anki_study, R.string.study_export_format_anki_study_help)
        strings.forEach { resource ->
            val text = compose.activity.getString(resource)
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue("No text layout for $text", layout.lineCount > 0 && layout.size.width > 0)
            assertFalse("Ellipsized text: $text", (0 until layout.lineCount).any(layout::isLineEllipsized))
            assertFalse("Text overflow: $text", layout.hasVisualOverflow)
            assertEquals("Incomplete text: $text", layout.layoutInput.text.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            compose.onNodeWithTag("study-export-format-cancel").assertIsDisplayed().assertIsEnabled()
            val cancelLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(compose.activity.getString(R.string.study_export_format_cancel), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(cancelLayouts) }
            assertFalse("Cancel overflow", cancelLayouts.single().hasVisualOverflow)
        }
    }
}

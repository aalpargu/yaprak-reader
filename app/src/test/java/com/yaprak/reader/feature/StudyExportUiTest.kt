package com.yaprak.reader.feature

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportResult
import com.yaprak.reader.feature.library.CreateLocalBook
import com.yaprak.reader.feature.library.LibraryLayout
import com.yaprak.reader.feature.library.LibraryScreen
import com.yaprak.reader.feature.library.LibraryState
import com.yaprak.reader.feature.library.message
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class StudyExportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("saved-id", "Ardıç Günlüğü", "Deniz Yazar", BookFormat.EPUB,
        "ardic.epub", "saved-id.epub", 100, "abcd", 1, lastReadAt = 200)
    private val action = "Çalışma kayıtlarını dışa aktar"
    private val description = "Bu kitaba ait kaydedilmiş notlar, kelimeler ve pasajlar .txt metin dosyasına yazılır. Kitap dosyası ve okuma konumu dahil değildir."

    @Test fun `study action sends the selected persisted book and closes its details`() {
        val other = book.copy(id = "other-id", authors = "Ada Yazar", sha256 = "other")
        var selected: Book? = null
        var fileExports = 0
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(other, book)), false, SnackbarHostState(),
                {}, {}, {}, "", {}, layout = LibraryLayout.LIST,
                onExport = { fileExports++ }, onStudyExport = { selected = it })
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi"))
        compose.onNodeWithContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi").performClick()
        compose.onNodeWithText(description).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(action).performScrollTo().assertIsEnabled().performClick()
        assertEquals(book, selected)
        assertEquals(0, fileExports)
        compose.onNodeWithText("Kitap bilgileri").assertDoesNotExist()
    }

    @Test fun `absent study dependency keeps its action hidden and file export available`() {
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(),
                {}, {}, {}, "", {}, layout = LibraryLayout.LIST)
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi"))
        compose.onNodeWithContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi").performClick()
        compose.onNodeWithText(action).assertDoesNotExist()
        compose.onNodeWithText(description).assertDoesNotExist()
        compose.onNodeWithText("Dosyayı dışa aktar").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Oku").assertIsEnabled()
    }

    @Test fun `study selection and writing lock an open book dialog and continuing reading`() {
        var studyBusy by mutableStateOf(false)
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(),
                {}, {}, { error("Busy reading must be disabled") }, "", {},
                layout = LibraryLayout.LIST, studyExporting = studyBusy,
                onStudyExport = { error("Busy export must be disabled") })
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi"))
        compose.onNodeWithContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi").performClick()
        compose.runOnIdle { studyBusy = true }
        compose.onNodeWithText(action).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Dosyayı dışa aktar").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Başlığı ve yazarı düzenle").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Kitabı kaldır").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Oku").assertIsNotEnabled()
        compose.onNodeWithText("Kapat").performClick()
        compose.onNodeWithText("Çalışma kayıtları dışa aktarılıyor…")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Okumaya devam et").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Kitap ekle").assertDoesNotExist()
        compose.runOnIdle { studyBusy = false }
        compose.onNodeWithText("Okumaya devam et").assertIsEnabled()
        compose.onNodeWithText("Kitap ekle", useUnmergedTree = true).assertIsEnabled()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `study action and scope remain readable at large font on narrow screen`() {
        var selected: Book? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.8f)) {
                YaprakTheme {
                    LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(),
                        {}, {}, {}, "", {}, layout = LibraryLayout.LIST,
                        onStudyExport = { selected = it })
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi"))
        compose.onNodeWithContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi").performClick()
        compose.onNodeWithText(description).performScrollTo().assertIsDisplayed()
        readable(description)
        compose.onNodeWithText(action).performScrollTo().assertIsDisplayed()
        readable(action)
        compose.onNodeWithText(action).performClick()
        assertEquals(book, selected)
    }

    @Test fun `study picker creates a plain text local document and cancellation returns no target`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val contract = CreateLocalBook("text/plain")
        val intent = contract.createIntent(context, "Ardıç-çalışma.txt")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Ardıç-çalışma.txt", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
        assertNull(contract.parseResult(android.app.Activity.RESULT_CANCELED, null))
    }

    @Test fun `study notices report record counts and a possible partial target after failure`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Çalışma kayıtları dışa aktarıldı. Not: 2 · Kelime: 3 · Pasaj: 1.",
            StudyExportResult.Exported(2, 3, 1).message(context))
        assertEquals("Seçilen aktarım biçimi için bu kitapta uygun kayıt yok.",
            StudyExportResult.Failed(StudyExportFailure.NO_RECORDS).message(context))
        assertEquals("Çalışma kayıtları dışa aktarılamadı. Boş alanı ve erişimi kontrol et. " +
            "Seçilen konumda eksik bir dosya kalmış olabilir. Kullanma; oradan silip yeniden dene.",
            StudyExportResult.Failed(StudyExportFailure.IO, partialFileMayRemain = true).message(context))
    }

    private fun readable(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("No text layout for $label", layout.lineCount > 0 && layout.size.width > 0)
        assertFalse("Ellipsized text: $label", (0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals("Incomplete text: $label", layout.layoutInput.text.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
}

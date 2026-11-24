package com.yaprak.reader.formats.pdf.ink

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.pdfink.*
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfInkToolbarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("b", "Kitap", null, BookFormat.PDF, "a.pdf", "b.pdf", 10, "a".repeat(64), 1, pageCount = 5)
    private val store = object : PdfInkRepository {
        override fun observe(bookId: String, contentSha256: String) = MutableStateFlow(emptyList<PdfInkStroke>())
        override suspend fun add(bookId: String, contentSha256: String, stroke: PdfInkStroke) = Unit
        override suspend fun remove(bookId: String, contentSha256: String, strokeId: String) = Unit
    }

    private fun show(text: PdfInkTextLookup?): Pair<PdfInkController, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pen = PdfInkController(book, store, text)
        compose.runOnUiThread { pen.attach(scope); assertTrue(pen.enter()) }
        compose.setContent { YaprakTheme { PdfInkToolbar(pen, atTop = false, onMove = {}) } }
        return pen to scope
    }

    @Test fun choosingColorModeEraserAndLeavingThePen() {
        val (pen, scope) = show(PdfInkTextLookup { _, _, _ -> null })
        try {
            compose.onNodeWithTag("pdf-ink-aligned").assertIsSelected()
            compose.onNodeWithText("Satırın üstünden sürükle · iki parmakla kaydır").assertExists()
            compose.onNodeWithTag("pdf-ink-undo").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Yeşil kalem").performClick()
            assertEquals(PdfHighlightColor.GREEN, pen.tool.value.color)
            compose.onNodeWithContentDescription("Yeşil kalem").assertIsSelected()
            compose.onNodeWithTag("pdf-ink-freehand").performClick()
            assertEquals(PdfInkMode.FREEHAND, pen.tool.value.mode)
            compose.onNodeWithTag("pdf-ink-eraser").performClick()
            assertTrue(pen.tool.value.eraser)
            compose.onNodeWithText("Silmek için çizime dokun").assertExists()
            compose.onNodeWithText("Bitti").performClick()
            assertFalse(pen.active.value); assertFalse(pen.tool.value.eraser)
        } finally { scope.cancel() }
    }

    @Test fun withoutPdfTextOnlyFreehandIsOffered() {
        val (pen, scope) = show(null)
        try {
            compose.onNodeWithTag("pdf-ink-aligned").assertDoesNotExist()
            compose.onNodeWithTag("pdf-ink-freehand").assertDoesNotExist()
            assertEquals(PdfInkMode.FREEHAND, pen.tool.value.mode)
            compose.onNodeWithText("Serbestçe boya · iki parmakla kaydır").assertExists()
            compose.onNodeWithContentDescription("Araç çubuğunu üste taşı").assertExists()
        } finally { scope.cancel() }
    }
}

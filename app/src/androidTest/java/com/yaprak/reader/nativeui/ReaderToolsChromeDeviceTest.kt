package com.yaprak.reader.nativeui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.settings.BookComicViewPreferences
import com.yaprak.reader.domain.settings.PdfNavigationMode
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Compose component checks with owned DTOs; no engine, file, Room or provider acceptance. */
@RunWith(AndroidJUnit4::class)
class ReaderToolsChromeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun primaryToolsStayDirectWhileAdditionalToolsRequireExplicitExpansion() {
        val reader = ready(BookFormat.EPUB)
        var brightness = 0
        compose.setContent { YaprakTheme { Chrome(reader, onBrightness = { brightness++ }) } }
        for (label in listOf("İçindekiler", "Yer imleri", "Notlar", "Görünüm", "Kitapta ara")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Parlaklık").assertDoesNotExist()
        toggle().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            text(R.string.reader_additional_tools_collapsed)))
        toggle().performScrollTo().performClick()
        compose.onNodeWithText("Parlaklık").performScrollTo().performClick()
        assertEquals(1, brightness)
        toggle().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            text(R.string.reader_additional_tools_expanded)))
        toggle().performScrollTo().performClick()
        compose.onNodeWithText("Parlaklık").assertDoesNotExist()
        compose.onNodeWithText("Notlar").performScrollTo().assertIsDisplayed()
    }

    @Test fun collapsedAbaHiddenNewSessionAndDisposedActionsCannotWrite() {
        var reader by mutableStateOf<ReaderState>(ready(BookFormat.PDF))
        var visible by mutableStateOf(true)
        var rendered by mutableStateOf(true)
        val events = mutableListOf<String>()
        val firstBrightness: () -> Unit = { events += "brightness" }
        var brightness by mutableStateOf<(() -> Unit)?>(firstBrightness)
        val navigation: (PdfNavigationMode) -> Unit = { events += it.name }
        compose.setContent {
            YaprakTheme {
                if (rendered) Chrome(reader, visible, onHide = { visible = false },
                    onBrightness = brightness, onNavigation = navigation)
            }
        }
        toggle().performScrollTo().performClick()
        val oldBrightness = clickAction(compose.onNodeWithText("Parlaklık").performScrollTo())
        val oldNavigation = clickAction(compose.onNodeWithTag("pdf-navigation-HORIZONTAL_SCROLL").performScrollTo())
        assertExpanded()
        toggle().performScrollTo().performClick()
        compose.runOnIdle { oldBrightness(); oldNavigation() }
        assertTrue(events.isEmpty())
        assertCollapsed()
        toggle().performScrollTo().performClick()
        compose.runOnIdle { oldBrightness(); oldNavigation() }
        assertTrue(events.isEmpty())
        assertExpanded()
        compose.onNodeWithText("Parlaklık").performScrollTo().performClick()
        compose.onNodeWithTag("pdf-navigation-HORIZONTAL_SCROLL").performScrollTo().performClick()
        assertEquals(listOf("brightness", "HORIZONTAL_SCROLL"), events)

        val beforeNull = clickAction(compose.onNodeWithText("Parlaklık").performScrollTo())
        compose.runOnIdle { brightness = null }
        compose.onNodeWithText("Parlaklık").assertDoesNotExist()
        assertExpanded()
        compose.runOnIdle { brightness = firstBrightness }
        compose.runOnIdle { beforeNull() }
        assertEquals(2, events.size)
        compose.onNodeWithText("Parlaklık").performScrollTo().performClick()
        assertEquals(3, events.size)

        val beforeSession = clickAction(compose.onNodeWithText("Parlaklık"))
        compose.runOnIdle { reader = ready(BookFormat.PDF) }
        assertCollapsed()
        compose.runOnIdle { beforeSession() }
        assertEquals(3, events.size)
        toggle().performScrollTo().performClick()
        val beforeHide = clickAction(compose.onNodeWithText("Parlaklık").performScrollTo())
        compose.onNodeWithText("Gizle").performClick()
        compose.runOnIdle { beforeHide() }
        assertEquals(3, events.size)
        toggle().assertDoesNotExist()
        compose.runOnIdle { visible = true }
        assertCollapsed()
        toggle().performScrollTo().performClick()
        val beforeDispose = clickAction(compose.onNodeWithText("Parlaklık").performScrollTo())
        compose.runOnIdle { rendered = false }
        compose.runOnIdle { beforeDispose() }
        assertEquals(3, events.size)
    }

    @Test fun legacyCropAndComicPreferenceRecoveryRemainAvailableWithAdditionalToolsClosed() {
        var reader by mutableStateOf(ready(BookFormat.PDF))
        var visible by mutableStateOf(false)
        var cropFailed by mutableStateOf(true)
        var crop by mutableStateOf<(() -> Unit)?>(null)
        var comicState by mutableStateOf<ComicBookViewPreferencesState?>(null)
        var crops = 0
        var retries = 0
        crop = { crops++; cropFailed = false }
        compose.setContent {
            YaprakTheme {
                Chrome(reader, visible, onScreenAwake = null, onCrop = crop, cropFailed = cropFailed,
                    comicState = comicState, onRetryComic = { retries++ })
            }
        }
        toggle().assertDoesNotExist()
        compose.onNodeWithText(text(R.string.pdf_crop_restore_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.pdf_crop_title)).performScrollTo().performClick()
        assertEquals(1, crops)
        compose.onNodeWithText(text(R.string.pdf_crop_title)).assertDoesNotExist()
        val comic = ready(BookFormat.CBZ)
        val book = comic.session.book
        compose.runOnIdle {
            reader = comic; visible = true
            crop = { error("Wrong-format PDF control must remain unavailable") }
            comicState = ComicBookViewPreferencesState(ComicBookViewOwner(book.id, book.sha256, 1),
                BookComicViewPreferences(true), BookComicViewPreferences(true), ComicBookViewStatus.LOAD_FAILED)
        }
        toggle().assertDoesNotExist()
        compose.onNodeWithText(text(R.string.pdf_crop_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.cbz_spread_load_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cbz_spread_retry)).performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test fun narrowDoubleTypeKeepsDisclosurePrimaryToolsAndOcrConsentReachable() {
        val reader = ready(BookFormat.PDF)
        var ocr = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                YaprakTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        Chrome(reader, onBrightness = {}, onCrop = {}, onOcr = { ocr++ })
                    }
                }
            }
        }
        for (label in listOf("Yer imleri", "Notlar", "Kitapta ara")) readable(label)
        compose.onNodeWithText(ocrLabel).assertDoesNotExist()
        readable(text(R.string.reader_additional_tools_open))
        toggle().performScrollTo().assertIsDisplayed()
        capture("default-font2-320dp")
        toggle().performClick()
        readable(text(R.string.reader_additional_tools_close))
        readable("Parlaklık")
        readable(text(R.string.pdf_crop_title))
        readable(ocrDisclosure)
        readable(ocrLabel)
        compose.onNodeWithText(ocrLabel).performScrollTo().assertIsDisplayed()
        capture("expanded-font2-320dp")
        compose.onNodeWithText(ocrLabel).performClick()
        assertEquals(1, ocr)
        toggle().performScrollTo().performClick()
        compose.onNodeWithText(ocrLabel).assertDoesNotExist()
        compose.onNodeWithText("Notlar").performScrollTo().assertIsDisplayed()
    }

    @Composable private fun Chrome(reader: ReaderState, visible: Boolean = true, onHide: () -> Unit = {},
        onScreenAwake: (() -> Unit)? = {}, onBrightness: (() -> Unit)? = null,
        onNavigation: ((PdfNavigationMode) -> Unit)? = null, onCrop: (() -> Unit)? = null,
        cropFailed: Boolean = false, onOcr: (() -> Unit)? = null,
        comicState: ComicBookViewPreferencesState? = null, onRetryComic: (() -> Unit)? = null) {
        ReaderChrome(reader, visible, false, false, {}, {}, {}, {}, onHide, {}, {},
            onContents = if ((reader as? ReaderState.Ready)?.session?.book?.format == BookFormat.EPUB) ({}) else null,
            onBookmarks = {}, onBookNotes = {}, onSettings = {}, onBookSearch = {}, onBookAssistant = {},
            onScreenAwake = onScreenAwake, onBrightness = onBrightness, onPdfNavigation = onNavigation,
            onPdfCrop = onCrop, pdfCropApplyFailed = cropFailed, onPdfOcr = onOcr,
            comicBookViewState = comicState, onRetryComicBookView = onRetryComic)
    }

    private fun ready(format: BookFormat) = ReaderState.Ready(object : ReaderSession {
        override val book = Book(UUID.randomUUID().toString(), "Araç denemesi", null, format,
            "owned-ui", "owned-ui", 1, "a".repeat(64), 1, 3)
        override val resume: ReadingLocation? = null
        override fun close() = Unit
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit,
            onError: () -> Unit): ReaderSurface = error("Controlled component only; never an engine fixture")
    })
    private fun toggle() = compose.onNodeWithTag("reader-additional-tools")
    private fun clickAction(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun assertExpanded() = toggle().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
        text(R.string.reader_additional_tools_expanded)))
    private fun assertCollapsed() = toggle().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
        text(R.string.reader_additional_tools_collapsed)))
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun readable(value: String) {
        val node = compose.onNodeWithText(value, useUnmergedTree = true).performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        assertEquals(value, layout.layoutInput.text.text)
        val glyphs = value.indices.filterNot { value[it].isWhitespace() }.map(layout::getBoundingBox)
        val overflow = glyphs.maxOf { maxOf(-it.left, it.right - layout.size.width,
            -it.top, it.bottom - layout.size.height, 0f) }
        // Natural paragraph widths are floating point; the layout box is an IntSize.
        // Check actual character cells with at most one physical pixel of rounding.
        textMetrics += JSONObject().put("text", value).put("fontScale", layout.layoutInput.density.fontScale)
            .put("width", layout.size.width).put("height", layout.size.height)
            .put("paragraphWidth", layout.multiParagraph.width).put("paragraphHeight", layout.multiParagraph.height)
            .put("maxGlyphOutsidePixels", overflow).put("reportedVisualOverflow", layout.hasVisualOverflow)
            .put("constraints", layout.layoutInput.constraints.toString())
        val directory = File(checkNotNull(compose.activity.getExternalFilesDir(null)),
            "yaprak-reader-tools-0.178").apply { assertTrue(isDirectory || mkdirs()) }
        File(directory, "text-layout-metrics.json").writeText(JSONArray(textMetrics).toString(2))
        if (!overflow.isFinite() || overflow > 1f) capture("overflow-diagnostic")
        assertTrue("$value: actual glyph overflow=$overflow px; size=${layout.size}; paragraph=${layout.multiParagraph.width}",
            overflow.isFinite() && overflow <= 1f)
        for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
        assertEquals(value.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String) {
        val directory = File(checkNotNull(compose.activity.getExternalFilesDir(null)),
            "yaprak-reader-tools-0.178").apply { assertTrue(isDirectory || mkdirs()) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private val ocrLabel = "Bu sayfada OCR · isteğe bağlı"
    private val textMetrics = mutableListOf<JSONObject>()
    private val ocrDisclosure = "OCR, bu sayfadaki Latin harfli yazıyı tanımayı dener. Türkçe ve İngilizce desteklenir; sonuçları kontrol et. Görsel ve metin cihazda işlenir. Google ML Kit kullanım ve performans ölçümlerini Google ile paylaşabilir."
}

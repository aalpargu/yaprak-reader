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
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.reader.ReaderChrome
import com.yaprak.reader.feature.reader.ReaderState
import com.yaprak.reader.feature.reader.PdfNightModeStatus
import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.EpubHighlightStatus
import com.yaprak.reader.reader.EpubHighlightFailure
import com.yaprak.reader.ui.theme.YaprakTheme
import com.yaprak.reader.R
import java.io.File
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
class ReaderChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val events = mutableListOf<String>()

    private val ocrLabel = "Bu sayfada OCR · isteğe bağlı"
    private val ocrDisclosure = "OCR, bu sayfadaki Latin harfli yazıyı tanımayı dener. Türkçe ve İngilizce desteklenir; sonuçları kontrol et. Görsel ve metin cihazda işlenir. Google ML Kit kullanım ve performans ölçümlerini Google ile paylaşabilir."
    @Test fun optionalOcrDisclosureAndActionWrapAtActualDoubleTypeAndOldCallbackCannotRetarget() {
        largeFont()
        var callback by mutableStateOf<(() -> Unit)?>(null)
        compose.setContent { YaprakTheme { chrome(BookFormat.PDF, onPdfOcr = callback) } }
        compose.readerToolNodeWithText(ocrLabel).assertDoesNotExist()
        compose.runOnIdle { callback = { events += "first" } }
        readable(ocrDisclosure); readable(ocrLabel)
        compose.readerToolNodeWithText(ocrLabel).performScrollTo().assertIsDisplayed()
        compose.readerToolNodeWithText(ocrLabel, useUnmergedTree = true).assertFontScale(2f)
        capture("ocr-chrome-narrow-font2")
        val old = compose.readerToolNodeWithText(ocrLabel).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { callback = { events += "next" } }
        compose.waitForIdle(); compose.runOnIdle { old() }; assertTrue(events.isEmpty())
        compose.readerToolNodeWithText(ocrLabel).performScrollTo().performClick(); assertEquals(listOf("next"), events)
        compose.runOnIdle { callback = null }
        compose.runOnIdle { old() }; assertEquals(listOf("next"), events)
        compose.readerToolNodeWithText(ocrDisclosure).assertDoesNotExist()
    }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun optionalOcrDisclosureAndButtonRemainReadableInShortLandscape() {
        largeFont()
        compose.setContent { YaprakTheme { chrome(BookFormat.PDF, onPdfOcr = { events += "ocr" }) } }
        readable(ocrDisclosure); readable(ocrLabel)
        compose.readerToolNodeWithText(ocrLabel).performScrollTo().assertIsDisplayed()
        capture("ocr-chrome-landscape-font2")
        compose.readerToolNodeWithText(ocrLabel).performClick(); assertEquals(listOf("ocr"), events)
    }
    @Test fun ocrRequiresVisibleReadyPdfAndDisposedActionCannotSurviveHiddenControls() {
        var format by mutableStateOf(BookFormat.PDF); var visible by mutableStateOf(true)
        var loadFailed by mutableStateOf(false); var saveFailed by mutableStateOf(false)
        compose.setContent { YaprakTheme { chrome(format, visible = visible, loadFailed = loadFailed,
            saveFailed = saveFailed, onPdfOcr = { events += "ocr" }) } }
        val old = compose.readerToolNodeWithText(ocrLabel).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { visible = false }; compose.runOnIdle { old() }; assertTrue(events.isEmpty())
        compose.readerToolNodeWithText(ocrLabel).assertDoesNotExist()
        compose.runOnIdle { visible = true; format = BookFormat.EPUB }
        compose.readerToolNodeWithText(ocrLabel).assertDoesNotExist()
        compose.runOnIdle { format = BookFormat.PDF; loadFailed = true }; compose.readerToolNodeWithText(ocrLabel).assertDoesNotExist()
        compose.runOnIdle { loadFailed = false; saveFailed = true }; compose.readerToolNodeWithText(ocrLabel).assertDoesNotExist()
        compose.runOnIdle { saveFailed = false }; compose.readerToolNodeWithText(ocrLabel).assertExists()
    }

    @Test fun `PDF spread commands wrap at double type and retained actions cannot act after mode ABA or hidden controls`() {
        largeFont()
        val ready = ready(BookFormat.PDF)
        var preferred by mutableStateOf(false)
        var visible by mutableStateOf(true)
        var toggles = 0
        val doubleLabel = RuntimeEnvironment.getApplication().getString(R.string.pdf_spread_double)
        val singleLabel = RuntimeEnvironment.getApplication().getString(R.string.pdf_spread_single)
        compose.setContent { YaprakTheme {
            chrome(BookFormat.PDF, state = ready, visible = visible, pdfDoublePagePreferred = preferred,
                onPdfSpread = { preferred = !preferred; toggles++ })
        } }
        compose.readerToolNodeWithText(doubleLabel).performScrollTo().assertIsDisplayed()
        readable(doubleLabel)
        val old = compose.readerToolNodeWithText(doubleLabel).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.readerToolNodeWithText(doubleLabel).performClick()
        compose.readerToolNodeWithText(singleLabel).performScrollTo().assertIsDisplayed()
        readable(singleLabel)
        readable(RuntimeEnvironment.getApplication().getString(R.string.pdf_spread_help))
        compose.runOnIdle { old() }; assertEquals(1, toggles)
        compose.readerToolNodeWithText(singleLabel).performClick()
        compose.runOnIdle { old() }; assertEquals(2, toggles)
        val fresh = compose.readerToolNodeWithText(doubleLabel).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { visible = false }
        compose.runOnIdle { fresh() }; assertEquals(2, toggles)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `landscape spread action and explanation remain readable and EPUB never gets the PDF control`() {
        largeFont()
        var format by mutableStateOf(BookFormat.PDF)
        val single = RuntimeEnvironment.getApplication().getString(R.string.pdf_spread_single)
        compose.setContent { YaprakTheme {
            chrome(format, pdfDoublePagePreferred = true, onPdfSpread = { events += "spread" })
        } }
        compose.readerToolNodeWithText(single).performScrollTo().assertIsDisplayed()
        readable(single)
        readable(RuntimeEnvironment.getApplication().getString(R.string.pdf_spread_help))
        compose.readerToolNodeWithText(single).performClick(); assertEquals(listOf("spread"), events)
        compose.runOnIdle { format = BookFormat.EPUB }
        compose.readerToolNodeWithText(single).assertDoesNotExist()
    }

    @Test fun `narrow EPUB keeps navigation readable at double font scale`() {
        largeFont()
        show(BookFormat.EPUB)
        capture("reader-narrow-epub")
        compose.readerToolNodeWithText("Önceki", useUnmergedTree = true).assertFontScale(2f)
        listOf("Önceki", "Görünüm", "Kitap görünümü", "Seçili metni göster", "Kelimeler", "Notlar", "Vurgular ve notlar", "Kitapta ara", "Sonraki", "İçindekiler", "Yer imleri").forEach { action ->
            readable(action)
            compose.readerToolNodeWithText(action).performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(listOf("previous", "settings", "bookAppearance", "selection", "words", "notes", "passages", "search", "next", "contents", "bookmarks"), events)
        capture("reader-narrow-epub")
    }

    @Test fun `narrow PDF preserves page label and format tools`() {
        largeFont()
        show(BookFormat.PDF)
        capture("reader-narrow-pdf")
        readable("9999 / 10000")
        compose.onNodeWithContentDescription("PDF sayfası 9999 / 10000").assertExists()
        listOf("Önceki", "Sonraki", "Yer imleri", "Sayfaya git", "Kelimeler", "Notlar").forEach { action ->
            readable(action)
            compose.readerToolNodeWithText(action).performScrollTo().assertIsDisplayed().performClick()
        }
        compose.readerToolNodeWithText("Görünüm").assertDoesNotExist()
        compose.readerToolNodeWithText("Kitap görünümü").assertDoesNotExist()
        compose.readerToolNodeWithText("Seçili metni göster").assertDoesNotExist()
        compose.readerToolNodeWithText("Vurgular ve notlar").assertDoesNotExist()
        compose.readerToolNodeWithText("İçindekiler").assertDoesNotExist()
        compose.readerToolNodeWithText("Kitapta ara").assertDoesNotExist()
        assertEquals(listOf("previous", "next", "bookmarks", "page", "words", "notes"), events)
        capture("reader-narrow-pdf")
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short landscape allows recovery and navigation without overlap`() {
        largeFont()
        show(BookFormat.PDF, saveFailed = true, loadFailed = true)
        listOf("Tekrar kaydet", "Kaydetmeden çık", "Kitaplık", "Sonraki", "Sayfaya git").forEach { action ->
            compose.readerToolNodeWithText(action).performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(listOf("save", "discard", "back", "next", "page"), events)
        capture("reader-landscape-errors")
    }

    @Test @Config(qualifiers = "w640dp-h240dp-land-mdpi")
    fun `open failure retains retry and library exit at large font`() {
        largeFont()
        show(BookFormat.EPUB, state = ReaderState.Error(ReaderFailure.CHANGED))
        compose.readerToolNodeWithText("Yeniden dene").performScrollTo().assertIsDisplayed().performClick()
        compose.readerToolNodeWithText("Kitaplığa dön").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("retry", "back"), events)
    }

    @Test fun `restore recovery failure explains why reader is unavailable`() {
        show(BookFormat.EPUB, state = ReaderState.Error(ReaderFailure.RECOVERY))
        compose.readerToolNodeWithText("Kitaplık kurtarma işlemi tamamlanamadı. Kitaplığa dönüp yeniden dene.")
            .assertExists()
        compose.readerToolNodeWithText("Kitaplığa dön").assertExists()
    }

    @Test fun `hidden controls reappear for unsaved progress recovery`() {
        var failed by mutableStateOf(false)
        compose.setContent { YaprakTheme { chrome(BookFormat.EPUB, visible = false, saveFailed = failed) } }
        compose.readerToolNodeWithText("Kitaplık").assertDoesNotExist()
        compose.readerToolNodeWithText("Sonraki").assertDoesNotExist()
        compose.runOnIdle { failed = true }
        compose.readerToolNodeWithText("Tekrar kaydet").performClick()
        compose.readerToolNodeWithText("Kaydetmeden çık").performClick()
        assertEquals(listOf("save", "discard"), events)
    }

    @Test fun `EPUB position is formatted as an approximate location for accessibility`() {
        var percent by mutableStateOf<Int?>(0)
        compose.setContent { YaprakTheme { chrome(BookFormat.EPUB, bookPositionPercent = percent) } }
        listOf(0, 50, 100).forEach { value ->
            compose.runOnIdle { percent = value }
            compose.readerToolNodeWithText("Yaklaşık kitap konumu: %$value").assertExists()
            compose.onNodeWithContentDescription("Kitaptaki yaklaşık konum yüzde $value").assertExists()
        }
        compose.runOnIdle { percent = null }
        compose.onAllNodesWithText("Yaklaşık kitap konumu:", substring = true).assertCountEquals(0)
    }

    @Test fun `PDF has no EPUB position label`() {
        show(BookFormat.PDF, bookPositionPercent = 50)
        compose.onAllNodesWithText("Yaklaşık kitap konumu:", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Kitaptaki yaklaşık konum yüzde 50").assertDoesNotExist()
    }

    @Test fun `out of range display value has no EPUB position label`() {
        show(BookFormat.EPUB, bookPositionPercent = 101)
        compose.onAllNodesWithText("Yaklaşık kitap konumu:", substring = true).assertCountEquals(0)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `jump back appears only with callback and returns to previous location`() {
        val label = "Atlamadan önceki konuma dön"
        var visible by mutableStateOf(true)
        var jumpBack by mutableStateOf<(() -> Unit)?>(null)
        compose.setContent { YaprakTheme { chrome(BookFormat.EPUB, visible = visible, onJumpBack = jumpBack) } }
        compose.onNodeWithContentDescription(label).assertDoesNotExist()

        compose.runOnIdle { jumpBack = { events += "jumpBack" } }
        compose.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("jumpBack"), events)

        compose.runOnIdle { visible = false }
        compose.onNodeWithContentDescription(label).assertDoesNotExist()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `screen control is optional and works for EPUB and PDF`() {
        var format by mutableStateOf(BookFormat.EPUB)
        var visible by mutableStateOf(true)
        var screenAwake by mutableStateOf<(() -> Unit)?>(null)
        compose.setContent { YaprakTheme { chrome(format, visible = visible, onScreenAwake = screenAwake) } }
        compose.readerToolNodeWithText("Ekran").assertDoesNotExist()

        compose.runOnIdle { screenAwake = { events += "screenAwake" } }
        compose.readerToolNodeWithText("Ekran").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { format = BookFormat.PDF }
        compose.readerToolNodeWithText("Ekran").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("screenAwake", "screenAwake"), events)

        compose.runOnIdle { visible = false }
        compose.readerToolNodeWithText("Ekran").assertDoesNotExist()
    }

    @Test fun `PDF night control switches labels and keeps its notice readable at double font scale`() {
        largeFont()
        var nightMode by mutableStateOf(false)
        compose.setContent { YaprakTheme {
            chrome(BookFormat.PDF, onPdfNightMode = {
                events += "pdfNight"
                nightMode = !nightMode
            }, pdfNightMode = nightMode)
        } }
        val notice = "Gece görünümünde görsel ve diyagram renkleri de ters çevrilir."
        compose.readerToolNodeWithText("Gece görünümü", useUnmergedTree = true).assertFontScale(2f)
        readable("Gece görünümü")
        compose.readerToolNodeWithText("Özgün renkler").assertDoesNotExist()
        compose.readerToolNodeWithText(notice).assertDoesNotExist()
        compose.readerToolNodeWithText("Gece görünümü").performScrollTo().assertIsDisplayed().performClick()

        readable("Özgün renkler")
        compose.readerToolNodeWithText("Gece görünümü").assertDoesNotExist()
        compose.readerToolNodeWithText(notice).performScrollTo().assertIsDisplayed()
        readable(notice)
        compose.readerToolNodeWithText("Özgün renkler").performScrollTo().assertIsDisplayed().performClick()
        compose.readerToolNodeWithText("Gece görünümü").assertExists()
        compose.readerToolNodeWithText("Özgün renkler").assertDoesNotExist()
        compose.readerToolNodeWithText(notice).assertDoesNotExist()
        assertEquals(listOf("pdfNight", "pdfNight"), events)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `PDF night control requires its optional capability`() {
        var capability by mutableStateOf<(() -> Unit)?>(null)
        compose.setContent { YaprakTheme {
            chrome(BookFormat.PDF, onPdfNightMode = capability, pdfNightMode = true)
        } }
        val notice = "Gece görünümünde görsel ve diyagram renkleri de ters çevrilir."
        compose.readerToolNodeWithText("Gece görünümü").assertDoesNotExist()
        compose.readerToolNodeWithText("Özgün renkler").assertDoesNotExist()
        compose.readerToolNodeWithText(notice).assertDoesNotExist()
        compose.runOnIdle { capability = { events += "pdfNight" } }
        compose.readerToolNodeWithText("Özgün renkler").performScrollTo().assertIsDisplayed().performClick()
        compose.readerToolNodeWithText(notice).assertExists()
        assertEquals(listOf("pdfNight"), events)
        compose.runOnIdle { capability = null }
        compose.readerToolNodeWithText("Özgün renkler").assertDoesNotExist()
        compose.readerToolNodeWithText(notice).assertDoesNotExist()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `PDF night control is absent for EPUB loading and failed reader`() {
        var format by mutableStateOf(BookFormat.EPUB)
        var state by mutableStateOf<ReaderState>(ready(format))
        compose.setContent { YaprakTheme {
            chrome(format, state = state, onPdfNightMode = { events += "pdfNight" }, pdfNightMode = true)
        } }
        val notice = "Gece görünümünde görsel ve diyagram renkleri de ters çevrilir."
        listOf("Gece görünümü", "Özgün renkler", notice).forEach {
            compose.readerToolNodeWithText(it).assertDoesNotExist()
        }
        compose.runOnIdle { format = BookFormat.PDF; state = ReaderState.Loading }
        compose.readerToolNodeWithText("Kitap açılıyor…").assertExists()
        compose.readerToolNodeWithText("Özgün renkler").assertDoesNotExist()
        compose.readerToolNodeWithText(notice).assertDoesNotExist()
        compose.runOnIdle { state = ReaderState.Error(ReaderFailure.INVALID) }
        compose.readerToolNodeWithText("Yeniden dene").assertExists()
        compose.readerToolNodeWithText("Özgün renkler").assertDoesNotExist()
        compose.readerToolNodeWithText(notice).assertDoesNotExist()
        compose.runOnIdle { state = ready(BookFormat.PDF) }
        compose.readerToolNodeWithText("Özgün renkler").assertExists()
        compose.readerToolNodeWithText(notice).assertExists()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `hidden PDF tools also hide the night control and notice`() {
        var visible by mutableStateOf(true)
        compose.setContent { YaprakTheme {
            chrome(BookFormat.PDF, visible = visible, onPdfNightMode = { events += "pdfNight" }, pdfNightMode = true)
        } }
        val notice = "Gece görünümünde görsel ve diyagram renkleri de ters çevrilir."
        compose.readerToolNodeWithText("Özgün renkler").assertExists()
        compose.readerToolNodeWithText(notice).assertExists()
        compose.runOnIdle { visible = false }
        compose.readerToolNodeWithText("Gece görünümü").assertDoesNotExist()
        compose.readerToolNodeWithText("Özgün renkler").assertDoesNotExist()
        compose.readerToolNodeWithText(notice).assertDoesNotExist()
        assertTrue(events.isEmpty())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `PDF preference loading disables only its toggle and does not claim restored night colors`() {
        compose.setContent { YaprakTheme {
            chrome(BookFormat.PDF, onPdfNightMode = { events += "pdfNight" }, pdfNightMode = true,
                pdfNightModeStatus = PdfNightModeStatus.Loading)
        } }
        compose.readerToolNodeWithText("PDF gece görünümü tercihi yükleniyor…").assertExists()
        compose.readerToolNodeWithText("Özgün renkler").assertIsNotEnabled()
        compose.readerToolNodeWithText("Gece görünümünde görsel ve diyagram renkleri de ters çevrilir.").assertDoesNotExist()
        compose.readerToolNodeWithText("Tercihi yeniden dene").assertDoesNotExist()
        compose.readerToolNodeWithText("Sonraki").performScrollTo().assertIsEnabled().performClick()
        compose.readerToolNodeWithText("Sayfaya git").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("next", "page"), events)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `PDF preference read failure offers its retry while navigation stays usable`() {
        compose.setContent { YaprakTheme {
            chrome(BookFormat.PDF, onPdfNightMode = { events += "pdfNight" },
                pdfNightModeStatus = PdfNightModeStatus.LoadFailed,
                onRetryPdfNightMode = { events += "pdfRetry" })
        } }
        compose.readerToolNodeWithText("PDF gece görünümü tercihi okunamadı.").assertExists()
        compose.readerToolNodeWithText("Gece görünümü").assertIsNotEnabled()
        compose.readerToolNodeWithText("Tercihi yeniden dene").performScrollTo().assertIsDisplayed().performClick()
        compose.readerToolNodeWithText("Önceki").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("pdfRetry", "previous"), events)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `saving PDF preference allows a newer choice without blocking reading`() {
        var enabled by mutableStateOf(true)
        compose.setContent { YaprakTheme {
            chrome(BookFormat.PDF, onPdfNightMode = { enabled = !enabled; events += "pdfNight" },
                pdfNightMode = enabled, pdfNightModeStatus = PdfNightModeStatus.Saving)
        } }
        compose.readerToolNodeWithText("PDF gece görünümü tercihi kaydediliyor…").assertExists()
        compose.readerToolNodeWithText("Özgün renkler").performScrollTo().assertIsEnabled().performClick()
        compose.readerToolNodeWithText("Gece görünümü").assertIsEnabled()
        compose.readerToolNodeWithText("Gece görünümünde görsel ve diyagram renkleri de ters çevrilir.").assertDoesNotExist()
        compose.readerToolNodeWithText("Tercihi yeniden dene").assertDoesNotExist()
        compose.readerToolNodeWithText("Sonraki").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("pdfNight", "next"), events)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `PDF preference write failure preserves latest displayed choice and allows toggle and retry`() {
        var enabled by mutableStateOf(true)
        compose.setContent { YaprakTheme {
            chrome(BookFormat.PDF, onPdfNightMode = { enabled = !enabled; events += "pdfNight" },
                pdfNightMode = enabled, pdfNightModeStatus = PdfNightModeStatus.SaveFailed,
                onRetryPdfNightMode = { events += "pdfRetry" })
        } }
        compose.readerToolNodeWithText("PDF gece görünümü tercihi kaydedilemedi. Seçim bu oturumda sürüyor.").assertExists()
        compose.readerToolNodeWithText("Gece görünümünde görsel ve diyagram renkleri de ters çevrilir.").assertExists()
        compose.readerToolNodeWithText("Özgün renkler").performScrollTo().assertIsEnabled().performClick()
        compose.readerToolNodeWithText("Gece görünümü").assertIsEnabled()
        compose.readerToolNodeWithText("Gece görünümünde görsel ve diyagram renkleri de ters çevrilir.").assertDoesNotExist()
        compose.readerToolNodeWithText("Tercihi yeniden dene").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("pdfNight", "pdfRetry"), events)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `PDF preference failure is hidden without capability and in EPUB`() {
        var format by mutableStateOf(BookFormat.PDF)
        var capability by mutableStateOf<(() -> Unit)?>(null)
        compose.setContent { YaprakTheme {
            chrome(format, onPdfNightMode = capability, pdfNightModeStatus = PdfNightModeStatus.SaveFailed,
                onRetryPdfNightMode = { events += "pdfRetry" })
        } }
        compose.readerToolNodeWithText("PDF gece görünümü tercihi kaydedilemedi. Seçim bu oturumda sürüyor.").assertDoesNotExist()
        compose.readerToolNodeWithText("Tercihi yeniden dene").assertDoesNotExist()
        compose.runOnIdle { capability = { events += "pdfNight" } }
        compose.readerToolNodeWithText("Tercihi yeniden dene").assertExists()
        compose.runOnIdle { format = BookFormat.EPUB }
        compose.readerToolNodeWithText("Gece görünümü").assertDoesNotExist()
        compose.readerToolNodeWithText("PDF gece görünümü tercihi kaydedilemedi. Seçim bu oturumda sürüyor.").assertDoesNotExist()
        compose.readerToolNodeWithText("Tercihi yeniden dene").assertDoesNotExist()
        assertTrue(events.isEmpty())
    }

    @Test fun `EPUB percentage command remains readable and invokes capability at double font scale`() {
        largeFont()
        compose.setContent { YaprakTheme {
            chrome(BookFormat.EPUB, onBookPositionJump = { events += "bookPosition" })
        } }
        compose.readerToolNodeWithText("Kitap yüzdesine git", useUnmergedTree = true).assertFontScale(2f)
        readable("Kitap yüzdesine git")
        compose.readerToolNodeWithText("Kitap yüzdesine git").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("bookPosition"), events)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `percentage command requires an EPUB capability and stays absent for PDF`() {
        var format by mutableStateOf(BookFormat.EPUB)
        var capability by mutableStateOf<(() -> Unit)?>(null)
        compose.setContent { YaprakTheme { chrome(format, onBookPositionJump = capability) } }
        compose.readerToolNodeWithText("Kitap yüzdesine git").assertDoesNotExist()
        compose.runOnIdle { capability = { events += "bookPosition" }; format = BookFormat.PDF }
        compose.readerToolNodeWithText("Kitap yüzdesine git").assertDoesNotExist()
        compose.runOnIdle { format = BookFormat.EPUB }
        compose.readerToolNodeWithText("Kitap yüzdesine git").assertExists()
        compose.runOnIdle { capability = null }
        compose.readerToolNodeWithText("Kitap yüzdesine git").assertDoesNotExist()
        assertTrue(events.isEmpty())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `loading failure and hidden controls do not expose percentage command`() {
        var state by mutableStateOf<ReaderState>(ReaderState.Loading)
        var visible by mutableStateOf(false)
        compose.setContent { YaprakTheme {
            chrome(BookFormat.EPUB, state = state, visible = visible,
                onBookPositionJump = { events += "bookPosition" })
        } }
        compose.readerToolNodeWithText("Kitap yüzdesine git").assertDoesNotExist()
        compose.runOnIdle { state = ReaderState.Error(ReaderFailure.INVALID) }
        compose.readerToolNodeWithText("Kitap yüzdesine git").assertDoesNotExist()
        compose.runOnIdle { state = ready(BookFormat.EPUB) }
        compose.readerToolNodeWithText("Kitap yüzdesine git").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.readerToolNodeWithText("Kitap yüzdesine git").assertExists()
        assertTrue(events.isEmpty())
    }

    @Test fun `PDF fit commands remain readable at double font scale and repeat the selected capability`() {
        largeFont()
        compose.setContent { YaprakTheme {
            chrome(BookFormat.PDF, onPdfFitPage = { events += "fitPage" },
                onPdfFitWidth = { events += "fitWidth" })
        } }
        listOf("Sayfaya sığdır", "Genişliğe sığdır").forEach { label ->
            compose.readerToolNodeWithText(label, useUnmergedTree = true).assertFontScale(2f)
            readable(label)
            compose.readerToolNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        }
        compose.readerToolNodeWithText("Sayfaya sığdır").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("PDF sayfası 9999 / 10000").assertExists()
        assertEquals(listOf("fitPage", "fitWidth", "fitPage"), events)
    }

    @Test fun `fit commands require their PDF capabilities and remain absent for EPUB loading and hidden tools`() {
        var format by mutableStateOf(BookFormat.PDF)
        var state by mutableStateOf<ReaderState>(ready(BookFormat.PDF))
        var visible by mutableStateOf(true)
        var fitPage by mutableStateOf<(() -> Unit)?>(null)
        var fitWidth by mutableStateOf<(() -> Unit)?>(null)
        compose.setContent { YaprakTheme {
            chrome(format, state = state, visible = visible, onPdfFitPage = fitPage, onPdfFitWidth = fitWidth)
        } }
        compose.readerToolNodeWithText("Sayfaya sığdır").assertDoesNotExist()
        compose.readerToolNodeWithText("Genişliğe sığdır").assertDoesNotExist()
        compose.runOnIdle { fitPage = { events += "fitPage" } }
        compose.readerToolNodeWithText("Sayfaya sığdır").assertExists()
        compose.readerToolNodeWithText("Genişliğe sığdır").assertDoesNotExist()
        compose.runOnIdle {
            fitWidth = { events += "fitWidth" }
            format = BookFormat.EPUB
            state = ready(format)
        }
        compose.readerToolNodeWithText("Sayfaya sığdır").assertDoesNotExist()
        compose.readerToolNodeWithText("Genişliğe sığdır").assertDoesNotExist()
        compose.runOnIdle { format = BookFormat.PDF; state = ReaderState.Loading }
        compose.readerToolNodeWithText("Sayfaya sığdır").assertDoesNotExist()
        compose.readerToolNodeWithText("Genişliğe sığdır").assertDoesNotExist()
        compose.runOnIdle { state = ReaderState.Error(ReaderFailure.INVALID) }
        compose.readerToolNodeWithText("Sayfaya sığdır").assertDoesNotExist()
        compose.readerToolNodeWithText("Genişliğe sığdır").assertDoesNotExist()
        compose.runOnIdle { state = ready(BookFormat.PDF); visible = false }
        compose.readerToolNodeWithText("Sayfaya sığdır").assertDoesNotExist()
        compose.readerToolNodeWithText("Genişliğe sığdır").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.readerToolNodeWithText("Sayfaya sığdır").assertExists()
        compose.readerToolNodeWithText("Genişliğe sığdır").assertExists()
        assertTrue(events.isEmpty())
    }

    @Test fun `brightness command remains readable and separate from screen settings for both formats`() {
        largeFont()
        var format by mutableStateOf(BookFormat.EPUB)
        compose.setContent { YaprakTheme {
            chrome(format, onScreenAwake = { events += "screen" }, onBrightness = { events += "brightness" })
        } }
        listOf("Ekran", "Parlaklık").forEach { label ->
            compose.readerToolNodeWithText(label, useUnmergedTree = true).assertFontScale(2f)
            readable(label)
            compose.readerToolNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        }
        compose.runOnIdle { format = BookFormat.PDF }
        compose.readerToolNodeWithText("Parlaklık").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("screen", "brightness", "brightness"), events)
    }

    @Test fun `brightness command requires visible ready tools without load or save errors`() {
        var state by mutableStateOf<ReaderState>(ready(BookFormat.PDF))
        var visible by mutableStateOf(true)
        var saveFailed by mutableStateOf(false)
        var loadFailed by mutableStateOf(false)
        var brightness by mutableStateOf<(() -> Unit)?>(null)
        compose.setContent { YaprakTheme {
            chrome(BookFormat.PDF, state = state, visible = visible, saveFailed = saveFailed,
                loadFailed = loadFailed, onBrightness = brightness)
        } }
        compose.readerToolNodeWithText("Parlaklık").assertDoesNotExist()
        compose.runOnIdle { brightness = { events += "brightness" } }
        compose.readerToolNodeWithText("Parlaklık").assertExists()
        compose.runOnIdle { visible = false }
        compose.readerToolNodeWithText("Parlaklık").assertDoesNotExist()
        compose.runOnIdle { saveFailed = true }
        compose.readerToolNodeWithText("Tekrar kaydet").assertExists()
        compose.readerToolNodeWithText("Parlaklık").assertDoesNotExist()
        compose.runOnIdle { visible = true; saveFailed = false; loadFailed = true }
        compose.readerToolNodeWithText("Parlaklık").assertDoesNotExist()
        compose.runOnIdle { loadFailed = false; state = ReaderState.Loading }
        compose.readerToolNodeWithText("Parlaklık").assertDoesNotExist()
        compose.runOnIdle { state = ReaderState.Error(ReaderFailure.INVALID) }
        compose.readerToolNodeWithText("Parlaklık").assertDoesNotExist()
        compose.runOnIdle { state = ready(BookFormat.PDF) }
        compose.readerToolNodeWithText("Parlaklık").assertExists()
        assertTrue(events.isEmpty())
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `EPUB image command is separate and readable on a short screen at double font scale`() {
        largeFont()
        compose.setContent { YaprakTheme {
            chrome(BookFormat.EPUB, onScreenAwake = { events += "screen" },
                onBrightness = { events += "brightness" }, onEpubImages = { events += "images" })
        } }
        listOf("Görünüm", "Ekran", "Parlaklık", "Koyu tema görselleri").forEach { label ->
            compose.readerToolNodeWithText(label, useUnmergedTree = true).assertFontScale(2f)
            readable(label)
            compose.readerToolNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(listOf("settings", "screen", "brightness", "images"), events)
    }

    @Test fun `EPUB image command requires a callback and visible ready EPUB without errors`() {
        var format by mutableStateOf(BookFormat.EPUB)
        var state by mutableStateOf<ReaderState>(ready(format))
        var visible by mutableStateOf(true)
        var saveFailed by mutableStateOf(false)
        var loadFailed by mutableStateOf(false)
        var images by mutableStateOf<(() -> Unit)?>(null)
        compose.setContent { YaprakTheme {
            chrome(format, state = state, visible = visible, saveFailed = saveFailed,
                loadFailed = loadFailed, onEpubImages = images)
        } }
        compose.readerToolNodeWithText("Koyu tema görselleri").assertDoesNotExist()
        compose.runOnIdle { images = { events += "images" } }
        compose.readerToolNodeWithText("Koyu tema görselleri").performScrollTo().performClick()
        compose.runOnIdle { visible = false }
        compose.readerToolNodeWithText("Koyu tema görselleri").assertDoesNotExist()
        compose.runOnIdle { saveFailed = true }
        compose.readerToolNodeWithText("Tekrar kaydet").assertExists()
        compose.readerToolNodeWithText("Koyu tema görselleri").assertDoesNotExist()
        compose.runOnIdle { visible = true; saveFailed = false; loadFailed = true }
        compose.readerToolNodeWithText("Koyu tema görselleri").assertDoesNotExist()
        compose.runOnIdle { loadFailed = false; state = ReaderState.Loading }
        compose.readerToolNodeWithText("Koyu tema görselleri").assertDoesNotExist()
        compose.runOnIdle { state = ReaderState.Error(ReaderFailure.INVALID) }
        compose.readerToolNodeWithText("Koyu tema görselleri").assertDoesNotExist()
        compose.runOnIdle { format = BookFormat.PDF; state = ready(format) }
        compose.readerToolNodeWithText("Koyu tema görselleri").assertDoesNotExist()
        compose.runOnIdle { format = BookFormat.EPUB; state = ready(format) }
        compose.readerToolNodeWithText("Koyu tema görselleri").assertExists()
        assertEquals(listOf("images"), events)
    }

    @Test fun `orientation action is available for ready EPUB and PDF and hidden on loading errors or hidden tools`() {
        var format by mutableStateOf(BookFormat.EPUB)
        var state by mutableStateOf<ReaderState>(ready(format))
        var visible by mutableStateOf(true)
        var saveFailed by mutableStateOf(false)
        var loadFailed by mutableStateOf(false)
        compose.setContent { YaprakTheme { chrome(format, state = state, visible = visible,
            saveFailed = saveFailed, loadFailed = loadFailed, onOrientation = { events += "orientation" }) } }
        compose.readerToolNodeWithText("Ekran yönü").performScrollTo().performClick()
        compose.runOnIdle { format = BookFormat.PDF; state = ready(format) }
        compose.readerToolNodeWithText("Ekran yönü").performScrollTo().performClick()
        compose.runOnIdle { visible = false }
        compose.readerToolNodeWithText("Ekran yönü").assertDoesNotExist()
        compose.runOnIdle { visible = true; saveFailed = true }
        compose.readerToolNodeWithText("Ekran yönü").assertDoesNotExist()
        compose.runOnIdle { saveFailed = false; loadFailed = true }
        compose.readerToolNodeWithText("Ekran yönü").assertDoesNotExist()
        compose.runOnIdle { loadFailed = false; state = ReaderState.Loading }
        compose.readerToolNodeWithText("Ekran yönü").assertDoesNotExist()
        compose.runOnIdle { state = ReaderState.Error(ReaderFailure.INVALID) }
        compose.readerToolNodeWithText("Ekran yönü").assertDoesNotExist()
        assertEquals(listOf("orientation", "orientation"), events)
    }

    @Test fun `orientation request failure has explicit retry without claiming physical rotation`() {
        compose.setContent { YaprakTheme { chrome(BookFormat.PDF, orientationApplyFailed = true,
            onRetryOrientation = { events += "orientationRetry" }) } }
        compose.readerToolNodeWithText("Kayıtlı ekran yönü uygulanamadı.").assertExists()
        compose.readerToolNodeWithText("Ekran yönünü yeniden dene").performScrollTo().performClick()
        assertEquals(listOf("orientationRetry"), events)
    }

    @Test fun `highlight status distinguishes unsupported partial and verified paint with retry busy gate`() {
        var status by mutableStateOf<EpubHighlightStatus>(EpubHighlightStatus.Unsupported)
        var busy by mutableStateOf(false)
        var format by mutableStateOf(BookFormat.EPUB)
        compose.setContent { YaprakTheme { chrome(format, highlightStatus = status,
            onRetryHighlights = { events += "highlights" }, highlightRetryBusy = busy) } }
        compose.readerToolNodeWithText("Bu Android System WebView sürümü gerçek metin vurgusunu desteklemiyor. Vurgu ve not kayıtların korunuyor; metnin üzerinde renk gösterilemiyor.").assertExists()
        compose.readerToolNodeWithText("Vurguları yeniden doğrula").performScrollTo().performClick()
        compose.runOnIdle { status = EpubHighlightStatus.Partial(2, 3); busy = true }
        compose.readerToolNodeWithText("Açık bölümde 3 vurgunun 2 tanesi tam olarak doğrulanamadı ve gösterilmiyor. Kayıtlar korunuyor.").assertExists()
        compose.readerToolNodeWithText("Vurguları yeniden doğrula").assertIsNotEnabled()
        compose.runOnIdle { status = EpubHighlightStatus.Ready(1); busy = false }
        compose.readerToolNodeWithText("Açık bölümde 1 kayıtlı vurgu gösteriliyor.").assertExists()
        compose.readerToolNodeWithText("Vurguları yeniden doğrula").assertDoesNotExist()
        compose.runOnIdle { format = BookFormat.PDF }
        compose.readerToolNodeWithText("Açık bölümde 1 kayıtlı vurgu gösteriliyor.").assertDoesNotExist()
        assertEquals(listOf("highlights"), events)
    }

    @Test fun `highlight failure retry is fully readable at double font scale`() {
        largeFont()
        compose.setContent { YaprakTheme { chrome(BookFormat.EPUB,
            highlightStatus = EpubHighlightStatus.Failed(EpubHighlightFailure.SOURCE_LIMIT),
            onRetryHighlights = { events += "highlights" }) } }
        compose.readerToolNodeWithText("Bu bölüm kaynak doğrulama sınırını aşıyor. Metin üzerinde vurgu gösterilemiyor; kayıtların korunuyor.").performScrollTo().assertIsDisplayed()
        compose.readerToolNodeWithText("Vurguları yeniden doğrula").performScrollTo().assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        compose.readerToolNodeWithText("Vurguları yeniden doğrula", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertFalse(results.single().hasVisualOverflow)
    }

    @Test fun `PDF thumbnails require the visible ready capability without load or save errors`() {
        var format by mutableStateOf(BookFormat.PDF)
        var state by mutableStateOf<ReaderState>(ready(format))
        var visible by mutableStateOf(true)
        var saveFailed by mutableStateOf(false)
        var loadFailed by mutableStateOf(false)
        var thumbnails by mutableStateOf<(() -> Unit)?>(null)
        compose.setContent { YaprakTheme { chrome(format, state = state, visible = visible,
            saveFailed = saveFailed, loadFailed = loadFailed, onPdfThumbnails = thumbnails) } }
        compose.readerToolNodeWithText("Sayfa küçük resimleri").assertDoesNotExist()
        compose.runOnIdle { thumbnails = { events += "thumbnails" } }
        compose.readerToolNodeWithText("Sayfa küçük resimleri").performScrollTo().performClick()
        compose.runOnIdle { visible = false }
        compose.readerToolNodeWithText("Sayfa küçük resimleri").assertDoesNotExist()
        compose.runOnIdle { visible = true; loadFailed = true }
        compose.readerToolNodeWithText("Sayfa küçük resimleri").assertDoesNotExist()
        compose.runOnIdle { loadFailed = false; saveFailed = true }
        compose.readerToolNodeWithText("Sayfa küçük resimleri").assertDoesNotExist()
        compose.runOnIdle { saveFailed = false; state = ReaderState.Loading }
        compose.readerToolNodeWithText("Sayfa küçük resimleri").assertDoesNotExist()
        compose.runOnIdle { state = ReaderState.Error(ReaderFailure.INVALID) }
        compose.readerToolNodeWithText("Sayfa küçük resimleri").assertDoesNotExist()
        compose.runOnIdle { format = BookFormat.EPUB; state = ready(format) }
        compose.readerToolNodeWithText("Sayfa küçük resimleri").assertDoesNotExist()
        compose.runOnIdle { format = BookFormat.PDF; state = ready(format) }
        compose.readerToolNodeWithText("Sayfa küçük resimleri").assertExists()
        compose.runOnIdle { thumbnails = null }
        compose.readerToolNodeWithText("Sayfa küçük resimleri").assertDoesNotExist()
        assertEquals(listOf("thumbnails"), events)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `PDF thumbnails remain a readable separate command in short landscape at double type`() {
        largeFont()
        compose.setContent { YaprakTheme { chrome(BookFormat.PDF, onPdfThumbnails = { events += "thumbnails" },
            onPdfFitPage = { events += "fitPage" }, onPdfFitWidth = { events += "fitWidth" }) } }
        listOf("Sayfaya git", "Sayfa küçük resimleri", "Sayfaya sığdır", "Genişliğe sığdır").forEach { label ->
            readable(label)
            compose.readerToolNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(listOf("page", "thumbnails", "fitPage", "fitWidth"), events)
    }

    @Test fun `PDF crop requires its capability and ready visible PDF without load or save errors`() {
        var format by mutableStateOf(BookFormat.PDF)
        var state by mutableStateOf<ReaderState>(ready(format))
        var visible by mutableStateOf(true)
        var saveFailed by mutableStateOf(false)
        var loadFailed by mutableStateOf(false)
        var crop by mutableStateOf<(() -> Unit)?>(null)
        val label = RuntimeEnvironment.getApplication().getString(R.string.pdf_crop_title)
        compose.setContent { YaprakTheme { chrome(format, state = state, visible = visible,
            saveFailed = saveFailed, loadFailed = loadFailed, onPdfCrop = crop) } }
        compose.readerToolNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { crop = { events += "crop" } }
        compose.readerToolNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { visible = false }
        compose.readerToolNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { visible = true; saveFailed = true }
        compose.readerToolNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { saveFailed = false; loadFailed = true }
        compose.readerToolNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { loadFailed = false; state = ReaderState.Loading }
        compose.readerToolNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { state = ReaderState.Error(ReaderFailure.INVALID) }
        compose.readerToolNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { format = BookFormat.EPUB; state = ready(format) }
        compose.readerToolNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { format = BookFormat.PDF; state = ready(format) }
        compose.readerToolNodeWithText(label).assertExists()
        compose.runOnIdle { crop = null }
        compose.readerToolNodeWithText(label).assertDoesNotExist()
        assertEquals(listOf("crop"), events)
    }

    @Test fun `PDF crop restore failure shows readable recovery despite hidden tools at double type`() {
        largeFont()
        var format by mutableStateOf(BookFormat.PDF)
        var failed by mutableStateOf(true)
        val label = RuntimeEnvironment.getApplication().getString(R.string.pdf_crop_title)
        val error = RuntimeEnvironment.getApplication().getString(R.string.pdf_crop_restore_error)
        compose.setContent { YaprakTheme { chrome(format, visible = false, pdfCropApplyFailed = failed,
            onPdfCrop = { events += "crop" }) } }
        compose.readerToolNodeWithText(error).performScrollTo().assertIsDisplayed()
        readable(error)
        compose.readerToolNodeWithText(label).performScrollTo().assertIsDisplayed()
        readable(label)
        compose.readerToolNodeWithText(label).performClick()
        assertEquals(listOf("crop"), events)
        compose.runOnIdle { failed = false }
        compose.readerToolNodeWithText(label).assertDoesNotExist()
        compose.readerToolNodeWithText(error).assertDoesNotExist()
        compose.runOnIdle { failed = true; format = BookFormat.EPUB }
        compose.readerToolNodeWithText(label).assertDoesNotExist()
        compose.readerToolNodeWithText(error).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `PDF crop thumbnails and fit remain distinct readable commands in short landscape double type`() {
        largeFont()
        val crop = RuntimeEnvironment.getApplication().getString(R.string.pdf_crop_title)
        compose.setContent { YaprakTheme { chrome(BookFormat.PDF, onPdfCrop = { events += "crop" },
            onPdfThumbnails = { events += "thumbnails" }, onPdfFitPage = { events += "fitPage" },
            onPdfFitWidth = { events += "fitWidth" }) } }
        listOf(crop, "Sayfa küçük resimleri", "Sayfaya sığdır", "Genişliğe sığdır").forEach { label ->
            readable(label)
            compose.readerToolNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(listOf("crop", "thumbnails", "fitPage", "fitWidth"), events)
    }

    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
    }

    private fun show(format: BookFormat, saveFailed: Boolean = false, loadFailed: Boolean = false,
        state: ReaderState = ready(format), bookPositionPercent: Int? = null) {
        compose.setContent { YaprakTheme { chrome(format, saveFailed = saveFailed, loadFailed = loadFailed,
            state = state, bookPositionPercent = bookPositionPercent) } }
    }

    @Composable private fun chrome(format: BookFormat, visible: Boolean = true, saveFailed: Boolean = false,
        loadFailed: Boolean = false, state: ReaderState = ready(format), bookPositionPercent: Int? = null,
        onJumpBack: (() -> Unit)? = null, onScreenAwake: (() -> Unit)? = null,
        onPdfNightMode: (() -> Unit)? = null, pdfNightMode: Boolean = false,
        pdfNightModeStatus: PdfNightModeStatus = PdfNightModeStatus.Ready,
        onRetryPdfNightMode: (() -> Unit)? = null,
        onBookPositionJump: (() -> Unit)? = null,
        onPdfFitPage: (() -> Unit)? = null, onPdfFitWidth: (() -> Unit)? = null,
        onBrightness: (() -> Unit)? = null, onEpubImages: (() -> Unit)? = null,
        onOrientation: (() -> Unit)? = null, orientationApplyFailed: Boolean = false,
        onRetryOrientation: (() -> Unit)? = null, highlightStatus: EpubHighlightStatus? = null,
        onRetryHighlights: (() -> Unit)? = null, highlightRetryBusy: Boolean = false,
        onPdfThumbnails: (() -> Unit)? = null, onPdfCrop: (() -> Unit)? = null,
        pdfCropApplyFailed: Boolean = false, pdfDoublePagePreferred: Boolean = false,
        onPdfSpread: (() -> Unit)? = null, onPdfOcr: (() -> Unit)? = null, onReaderControls: (() -> Unit)? = null) {
        ReaderChrome(state, visible, saveFailed, loadFailed, { events += "back" }, { events += "retry" },
            { events += "save" }, { events += "discard" }, { events += "hide" },
            { events += "previous" }, { events += "next" },
            if (format == BookFormat.PDF) "9999 / 10000" else null,
            if (format == BookFormat.EPUB) ({ events += "settings" }) else null,
            if (format == BookFormat.EPUB) ({ events += "contents" }) else null,
            { events += "bookmarks" }, if (format == BookFormat.PDF) ({ events += "page" }) else null,
            if (format == BookFormat.EPUB) ({ events += "bookAppearance" }) else null,
            if (format == BookFormat.EPUB) ({ events += "selection" }) else null,
            { events += "words" }, { events += "notes" },
            if (format == BookFormat.EPUB) ({ events += "passages" }) else null,
            if (format == BookFormat.EPUB) ({ events += "search" }) else null,
            bookPositionPercent, onJumpBack, onScreenAwake, onPdfNightMode, pdfNightMode,
            pdfNightModeStatus, onRetryPdfNightMode, onBookPositionJump, onPdfFitPage, onPdfFitWidth, onBrightness,
            onEpubImages, onOrientation, orientationApplyFailed, onRetryOrientation,
            highlightStatus, onRetryHighlights, highlightRetryBusy, onPdfThumbnails, onPdfCrop, pdfCropApplyFailed,
            pdfDoublePagePreferred = pdfDoublePagePreferred, onPdfSpread = onPdfSpread, onPdfOcr = onPdfOcr,
            onReaderControls = onReaderControls)
    }

    @Test fun readerControlsAvailableForBothFormatsAndHiddenDuringErrorsOrReading() {
        var format by mutableStateOf(BookFormat.EPUB)
        var visible by mutableStateOf(true)
        var failed by mutableStateOf(false)
        var opens = 0
        compose.setContent { YaprakTheme {
            chrome(format, visible = visible, saveFailed = failed, onReaderControls = { opens++ })
        } }
        compose.readerToolNodeWithText("Okuma kontrolleri").performScrollTo().performClick()
        assertEquals(1, opens)
        compose.runOnIdle { format = BookFormat.PDF }
        compose.readerToolNodeWithText("Okuma kontrolleri").performScrollTo().performClick()
        assertEquals(2, opens)
        compose.runOnIdle { visible = false }
        compose.readerToolNodeWithText("Okuma kontrolleri").assertDoesNotExist()
        compose.runOnIdle { visible = true; failed = true }
        compose.readerToolNodeWithText("Okuma kontrolleri").assertDoesNotExist()
    }
    @Test fun tapZoneEditorRequiresCapabilityAndVisibleReadyReader() {
        var format by mutableStateOf(BookFormat.EPUB)
        var visible by mutableStateOf(true)
        var failed by mutableStateOf(false)
        var supported by mutableStateOf(true)
        var opens = 0
        compose.setContent { YaprakTheme {
            ReaderChrome(ready(format), visible, failed, false,
                onBack = {}, onRetry = {}, onRetrySave = {}, onLeaveUnsaved = {}, onHide = {},
                onPrevious = {}, onNext = {}, onTapZones = if (supported) ({ opens++ }) else null)
        } }
        compose.readerToolNodeWithText("Dokunma bölgeleri").performScrollTo().performClick()
        compose.runOnIdle { format = BookFormat.PDF }
        compose.readerToolNodeWithText("Dokunma bölgeleri").performScrollTo().performClick()
        assertEquals(2, opens)
        compose.runOnIdle { supported = false }
        compose.readerToolNodeWithText("Dokunma bölgeleri").assertDoesNotExist()
        compose.runOnIdle { supported = true; visible = false }
        compose.readerToolNodeWithText("Dokunma bölgeleri").assertDoesNotExist()
        compose.runOnIdle { visible = true; failed = true }
        compose.readerToolNodeWithText("Dokunma bölgeleri").assertDoesNotExist()
    }
    @Test fun comicThumbnailActionAndPageLabelAreGenericAndPdfToolsStayAbsent() {
        largeFont()
        var visible by mutableStateOf(true)
        var failed by mutableStateOf(false)
        var previews by mutableStateOf<(() -> Unit)?>({ events += "comicPreview" })
        compose.setContent { YaprakTheme {
            ReaderChrome(ready(BookFormat.CBZ), visible, failed, false,
                onBack = {}, onRetry = {}, onRetrySave = {}, onLeaveUnsaved = {}, onHide = {},
                onPrevious = {}, onNext = {}, pageLabel = "1 / 2", onPdfThumbnails = previews,
                onPdfCrop = { events += "wrongCrop" }, onPdfOcr = { events += "wrongOcr" })
        } }
        val title = RuntimeEnvironment.getApplication().getString(R.string.pdf_thumbnails_title)
        readable(title); compose.readerToolNodeWithText(title).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Sayfa 1 / 2").assertExists()
        compose.onNodeWithContentDescription("PDF sayfası 1 / 2").assertDoesNotExist()
        compose.readerToolNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.pdf_crop_title)).assertDoesNotExist()
        compose.readerToolNodeWithText(ocrLabel).assertDoesNotExist(); assertEquals(listOf("comicPreview"), events)
        compose.runOnIdle { previews = null }; compose.readerToolNodeWithText(title).assertDoesNotExist()
        compose.runOnIdle { previews = { events += "comicPreview" }; visible = false }
        compose.readerToolNodeWithText(title).assertDoesNotExist()
        compose.runOnIdle { visible = true; failed = true }; compose.readerToolNodeWithText(title).assertDoesNotExist()
    }

    private fun ready(format: BookFormat) = ReaderState.Ready(object : ReaderSession {
        override val book = Book("chrome-test", "Uzun bir kitap başlığı ile okuma denemesi", "Yazar", format,
            "book", "book", 100, "hash", 1)
        override val resume: ReadingLocation? = null
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit) =
            error("Chrome must not create a rendering engine")
        override fun close() = Unit
    })

    private fun readable(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.readerToolNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("No text layout for $label", layout.lineCount > 0 && layout.size.width > 0)
        assertFalse("Ellipsized action: $label", (0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals("Incomplete action text: $label", layout.layoutInput.text.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

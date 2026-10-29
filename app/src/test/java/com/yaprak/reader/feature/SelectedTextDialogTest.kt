package com.yaprak.reader.feature

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yaprak.reader.feature.reader.SelectedTextDialog
import com.yaprak.reader.feature.reader.SelectedTextPreview
import com.yaprak.reader.feature.reader.CopyStatus
import com.yaprak.reader.feature.reader.copyPlainSelectedText
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectedTextDialogTest {
    @Test @Config(qualifiers = "w320dp-h800dp-mdpi")
    fun optionalOcrLoadingAtActualTwoHundredPercentCanCloseWithoutShowingCopyOrTools() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var preview by mutableStateOf<SelectedTextPreview?>(SelectedTextPreview.Loading(pdfOcr = true))
        var closed = 0
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, { closed++; preview = null },
            { error("No loading copy") }, onTransferNote = { error("No loading note") }) } }
        compose.onNodeWithText("Sayfa metni tanınıyor…").assertIsDisplayed()
        compose.onNodeWithText("Panoya kopyala").assertDoesNotExist()
        compose.onNodeWithText("Not taslağına aktar").assertDoesNotExist()
        compose.onNodeWithText("Kapat").assertIsDisplayed()
        screenshot("ocr-loading-narrow-font2")
        compose.onNodeWithText("Kapat").performClick(); assertEquals(1, closed)
    }
    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun ocrResultKeepsSourceCaptionAndCopyReachableAndCannotBorrowAnyAnnotationOrAiAnchor() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var copies = 0; var notes = 0
        // Deliberately forged EPUB anchor: OCR must not acquire native/AI capabilities from it.
        compose.setContent { YaprakTheme {
            SelectedTextDialog(SelectedTextPreview.Ready("Leaf", sourceAnchor = SourceTextAnchor("chapter.xhtml", 0, 4, "a".repeat(64)),
                pdfPageIndex = 1, pdfOcr = true), {}, { copies++ }, onTransferNote = { notes++ }, onAnnotate = {},
                onTransferPassage = {}, showAnnotationHint = true, pdfAnnotationHint = true,
                assistantContent = { error("OCR cannot borrow an EPUB AI anchor") })
        } }
        compose.onNodeWithText("OCR metni").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("PDF sayfa 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("OCR sonucu hatalı olabilir; kullanmadan önce kontrol et. Bu metin özgün PDF metin seçimi veya kesin vurgu kaynağı değildir.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Vurgu ve bağlı not ekle").assertDoesNotExist()
        compose.onNodeWithText("Pasaj taslağına aktar").assertDoesNotExist()
        compose.onNodeWithText("Bu seçimin PDF metin sınırları bulunamadı. Vurgu kaydetmek için metni yeniden seçebilirsin; diğer araçları kullanabilirsin.")
            .assertDoesNotExist()
        compose.onNodeWithText("Not taslağına aktar").performScrollTo().performClick(); assertEquals(1, notes)
        compose.onNodeWithText("Panoya kopyala").assertIsDisplayed().performClick(); assertEquals(1, copies)
        compose.onNodeWithText("Kapat").assertIsDisplayed(); screenshot("ocr-ready-landscape-font2")
    }
    @Test @Config(qualifiers = "w320dp-h800dp-mdpi")
    fun pendingNativeSelectionAtTwoHundredPercentHasOnlyAnAccessibleCancelAction() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        var preview by mutableStateOf<SelectedTextPreview?>(SelectedTextPreview.Loading())
        var closed = 0
        compose.setContent { YaprakTheme {
            SelectedTextDialog(preview, { closed++; preview = null }, { error("Loading cannot copy") },
                onTransferWord = { error("Loading cannot transfer") }, onDictionary = { error("Loading cannot query") })
        } }
        compose.onNodeWithText("Seçim alınıyor…").assertIsDisplayed()
        compose.onNodeWithText("Özgün metin alınıyor. Kapatmak işlemi iptal eder.").assertIsDisplayed()
        compose.onNodeWithText("Kapat").assertIsDisplayed()
        compose.onNodeWithText("Panoya kopyala").assertDoesNotExist()
        compose.onNodeWithText("Kelime formuna aktar").assertDoesNotExist()
        screenshot("pdf-selection-loading-narrow-font2")
        compose.onNodeWithText("Kapat").performClick(); assertEquals(1, closed)
        compose.onNodeWithText("Seçim alınıyor…").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun nativeRightPageCaptionAndTextToolsScrollAtTwoHundredPercentWithCopyAndCloseFixed() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        var copies = 0; var notes = 0
        compose.setContent { YaprakTheme {
            SelectedTextDialog(SelectedTextPreview.Ready("alpha 🌿", pdfPageIndex = 3), {}, { copies++ },
                onTransferWord = {}, onTransferNote = { notes++ }, onAnnotate = {}, onTransferPassage = {},
                assistantContent = { error("No EPUB anchor may be invented for a PDF") })
        } }
        compose.onNodeWithText("PDF sayfa 4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("alpha 🌿").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Vurgu ve bağlı not ekle").assertDoesNotExist()
        compose.onNodeWithText("Pasaj taslağına aktar").assertDoesNotExist()
        compose.onNodeWithText("Not taslağına aktar").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, notes)
        compose.onNodeWithText("Panoya kopyala").assertIsDisplayed().performClick(); assertEquals(1, copies)
        compose.onNodeWithText("Kapat").assertIsDisplayed()
        screenshot("pdf-selection-ready-landscape-font2")
    }

    @Test fun loadingDismissCallbackCannotDismissAReadyResultOrAReplacementLoadingSession() {
        val loading = SelectedTextPreview.Loading()
        var preview by mutableStateOf<SelectedTextPreview?>(loading)
        var closed = 0
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, { closed++ }, {}) } }
        val old = compose.onNodeWithText("Kapat").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { preview = SelectedTextPreview.Ready("alpha", sessionId = loading.sessionId, pdfPageIndex = 3) }
        compose.waitForIdle(); compose.runOnIdle { old() }; assertEquals(0, closed)
        compose.runOnIdle { preview = SelectedTextPreview.Loading() }
        compose.waitForIdle(); compose.runOnIdle { old() }; assertEquals(0, closed)
        compose.onNodeWithText("Kapat").performClick(); assertEquals(1, closed)
    }

    private fun screenshot(name: String) {
        val path = System.getProperty("yaprak.screenshotDir") ?: return
        compose.runOnIdle {
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            if (name.startsWith("ocr-")) assertEquals(2f, view.resources.configuration.fontScale)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val dir = File(path, if (name.startsWith("ocr-")) "pdf-ocr-0.100.0" else "pdf-selection-0.97.0").apply { mkdirs() }
                File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun paragraphRangeToolbarRetainsNativePreviewAndRejectsPreviousSelectionAction() {
        val range = com.yaprak.reader.reader.SourceRangeAnchor("chapter.xhtml", listOf(
            com.yaprak.reader.reader.SourceRangeSegment("0/0", 0, 4, "a".repeat(64), "Leaf"),
            com.yaprak.reader.reader.SourceRangeSegment("1/0", 0, 6, "b".repeat(64), "Across")))
        val initial = SelectedTextPreview.Ready("Leaf\nAcross", sourceAnchor = range)
        var preview by mutableStateOf<SelectedTextPreview?>(initial)
        val accepted = mutableListOf<SelectedTextPreview>()
        compose.setContent { YaprakTheme {
            val expected = preview
            SelectedTextDialog(expected, { preview = null }, {},
                onTransferPassage = {}, onAnnotate = { accepted += requireNotNull(expected) })
        } }
        compose.onNodeWithText(initial.text).assertExists()
        compose.onNodeWithText("Pasaj taslağına aktar").performScrollTo().assertIsDisplayed()
        val old = compose.onNodeWithText("Vurgu ve bağlı not ekle").performScrollTo()
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        val replacement = initial.copy(sessionId = "replacement-selection")
        compose.runOnIdle { preview = replacement }
        compose.onNodeWithText("Vurgu ve bağlı not ekle").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { old() }
        assertTrue(accepted.isEmpty())
        compose.onNodeWithText("Vurgu ve bağlı not ekle").performClick()
        assertEquals(listOf(replacement), accepted)
        val malformed = range.copy(segments = range.segments.reversed())
        compose.runOnIdle { preview = initial.copy(sourceAnchor = malformed, sessionId = "invalid-selection") }
        compose.onNodeWithText("Vurgu ve bağlı not ekle").assertDoesNotExist()
        compose.onNodeWithText("Pasaj taslağına aktar").assertDoesNotExist()
        compose.onNodeWithText("Panoya kopyala").assertIsDisplayed()
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large type in narrow landscape keeps read only preview and close reachable`() {
        val selected = "Yalnızca seçilen cümle gösterilir. Bu örnek metin sadece test kaynağındadır. " +
            "Uzun bir seçim de pencerede kaydırılabilir ve kapatma düğmesi erişilebilir kalır."
        var preview by mutableStateOf<SelectedTextPreview?>(SelectedTextPreview.Ready(selected))
        var dismissals = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme {
                SelectedTextDialog(preview, {
                    dismissals++
                    preview = null
                }, {})
            }
        }
        compose.onNodeWithText("Seçili metin").assertIsDisplayed()
        compose.onNodeWithText(selected).assertExists()
        compose.onNodeWithText("Panoya kopyala").assertIsDisplayed()
        compose.onNodeWithText("Kelime formuna aktar").assertDoesNotExist()
        compose.onNodeWithText("Not taslağına aktar").assertDoesNotExist()
        compose.onNodeWithText("Kaydet").assertDoesNotExist()
        compose.onNodeWithText("Not ekle").assertDoesNotExist()
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertEquals(1, dismissals)
        compose.onNodeWithText("Seçili metin").assertDoesNotExist()
    }

    @Test fun `error replaces selected text and null hides dialog`() {
        val selected = "Yalnızca test için seçilen metin."
        var preview by mutableStateOf<SelectedTextPreview?>(SelectedTextPreview.Ready(selected))
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, { preview = null }, {},
            { error("No word transfer for error preview") },
            { error("No note transfer for error preview") }) } }
        compose.onNodeWithText(selected).assertIsDisplayed()
        compose.runOnIdle { preview = SelectedTextPreview.Ready(selected, CopyStatus.FAILED) }
        compose.onNodeWithText("Panoya kopyalanamadı. Yeniden deneyin.").assertIsDisplayed()
        compose.runOnIdle { preview = SelectedTextPreview.Error("Metin alınamadı. Yeniden seçip deneyin.") }
        compose.onNodeWithText("Metin gösterilemedi").assertIsDisplayed()
        compose.onNodeWithText("Metin alınamadı. Yeniden seçip deneyin.").assertIsDisplayed()
        compose.onNodeWithText(selected).assertDoesNotExist()
        compose.onNodeWithText("Panoya kopyala").assertDoesNotExist()
        compose.onNodeWithText("Kelime formuna aktar").assertDoesNotExist()
        compose.onNodeWithText("Not taslağına aktar").assertDoesNotExist()
        compose.onNodeWithText("Kapat").performClick()
        compose.onNodeWithText("Metin gösterilemedi").assertDoesNotExist()
    }

    @Test fun `clipboard receives exact text only after explicit copy action`() {
        val selected = " Seçilen metin, olduğu gibi. "
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)!!
        clipboard.clearPrimaryClip()
        var preview by mutableStateOf<SelectedTextPreview?>(SelectedTextPreview.Ready(selected))
        compose.setContent {
            YaprakTheme {
                SelectedTextDialog(preview, { preview = null }, {
                    copyPlainSelectedText(compose.activity, (preview as SelectedTextPreview.Ready).text)
                    preview = (preview as SelectedTextPreview.Ready).copy(copyStatus = CopyStatus.COPIED)
                })
            }
        }
        assertFalse(clipboard.hasPrimaryClip())
        compose.onNodeWithText("Panoya kopyala").performClick()
        compose.onNodeWithText("Panoya kopyalandı.").assertIsDisplayed()
        assertEquals(selected, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("Kapat").performClick()
        compose.onNodeWithText("Panoya kopyala").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `optional transfer actions scroll into view and leave copy and close accessible`() {
        val selected = "leaf " + "Uzun seçili metin. ".repeat(30)
        var transferredWord = 0
        var transferredNote = 0
        var copied = 0
        var closed = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme {
            SelectedTextDialog(SelectedTextPreview.Ready(selected), { closed++ }, { copied++ },
                { transferredWord++ }, { transferredNote++ })
        } }
        compose.onNodeWithText("Kelime formuna aktar").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, transferredWord)
        compose.onNodeWithText("Not taslağına aktar").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, transferredNote)
        assertEquals(0, copied)
        compose.onNodeWithText("Panoya kopyala").assertIsDisplayed()
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertEquals(1, closed)
    }

    @Test fun `note transfer is available only for valid ready text and does not copy`() {
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)!!
        clipboard.clearPrimaryClip()
        var preview by mutableStateOf<SelectedTextPreview?>(SelectedTextPreview.Ready("  A line.\nAnother line!  "))
        var transfers = 0
        compose.setContent { YaprakTheme {
            SelectedTextDialog(preview, { preview = null }, {}, onTransferNote = { transfers++ })
        } }
        compose.onNodeWithText("Not taslağına aktar").assertIsDisplayed().performClick()
        assertEquals(1, transfers)
        assertFalse(clipboard.hasPrimaryClip())
        compose.runOnIdle { preview = SelectedTextPreview.Ready("  \n ") }
        compose.onNodeWithText("Not taslağına aktar").assertDoesNotExist()
        compose.runOnIdle { preview = SelectedTextPreview.Ready("a".repeat(2001)) }
        compose.onNodeWithText("Not taslağına aktar").assertDoesNotExist()
    }

    @Test fun `passage transfer requires matched source and never saves or copies by itself`() {
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)!!
        clipboard.clearPrimaryClip()
        val anchor = SourceTextAnchor("chapter.xhtml", 0, 4, "a".repeat(64))
        var preview by mutableStateOf<SelectedTextPreview?>(SelectedTextPreview.Ready("Leaf"))
        var transfers = 0
        compose.setContent { YaprakTheme {
            SelectedTextDialog(preview, { preview = null }, {}, onTransferPassage = { transfers++ })
        } }
        compose.onNodeWithText("Pasaj taslağına aktar").assertDoesNotExist()
        compose.runOnIdle { preview = SelectedTextPreview.Ready("Leaf", sourceAnchor = anchor) }
        compose.onNodeWithText("Pasaj taslağına aktar").assertIsDisplayed().performClick()
        assertEquals(1, transfers)
        assertFalse(clipboard.hasPrimaryClip())
        compose.runOnIdle { preview = SelectedTextPreview.Ready("Leaf", sourceAnchor = anchor.copy(end = 5)) }
        compose.onNodeWithText("Pasaj taslağına aktar").assertDoesNotExist()
        compose.runOnIdle { preview = SelectedTextPreview.Ready("L".repeat(4097),
            sourceAnchor = anchor.copy(end = 4097)) }
        compose.onNodeWithText("Pasaj taslağına aktar").assertDoesNotExist()
        compose.runOnIdle { preview = SelectedTextPreview.Error("Seçim alınamadı") }
        compose.onNodeWithText("Pasaj taslağına aktar").assertDoesNotExist()
    }
}

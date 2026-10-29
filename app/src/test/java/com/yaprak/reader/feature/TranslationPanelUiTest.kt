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
import com.yaprak.reader.domain.translation.*
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.feature.translation.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
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
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationPanelUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val preview = SelectedTextPreview.Ready("English paragraph 🌿")
    private val ready = TranslationState(preview.sessionId, "attempt", preview.text, TranslationPhase.READY,
        modelsReady = true, translatedText = "  İ/ı e\u0301 🌿 <b>literal</b>\nTürkçe çeviri.")
    @Test fun translatedWordTransferIsExplicitAndCannotUseThePreviousAttemptOrParagraphResult() {
        var state by mutableStateOf(ready.copy(sourceText = "Leaf", translatedText = "yaprak"), referentialEqualityPolicy())
        val transfers = mutableListOf<TranslationState>(); var shown by mutableStateOf(true)
        compose.setContent { YaprakTheme { if (shown) SelectedTextDialog(preview, {}, {}, translationContent = {
            TranslationPanel(state, { true }, {}, {}, {}, {}, {}, {}, {}, transfers::add)
        }) } }
        assertTrue(transfers.isEmpty())
        val old = action("translation-word"); compose.onNodeWithTag("translation-word").performClick()
        assertSame(state, transfers.single())
        compose.runOnIdle { state = state.copy(attemptId = "new-attempt") }; compose.runOnIdle { old() }
        assertEquals(1, transfers.size)
        val fresh = action("translation-word")
        compose.runOnIdle { state = state.copy(sourceText = "two words") }; compose.runOnIdle { fresh() }
        compose.onNodeWithTag("translation-word").assertDoesNotExist(); assertEquals(1, transfers.size)
        compose.runOnIdle { shown = false }; compose.runOnIdle { old(); fresh() }; assertEquals(1, transfers.size)
    }
    @Test fun translatedWordHelpAndActionAreReadableAtDoubleTypeWithoutHidingOriginalCopyAndClose() {
        largeFont()
        val state = ready.copy(sourceText = "Leaf", translatedText = "yaprak")
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, {}, {}, translationContent = {
            TranslationPanel(state, { true }, {}, {}, {}, {}, {}, {}, {}, {})
        }) } }
        readable("Kelime ve çeviri kayıt formuna taslak olarak aktarılır. Anlamı kontrol edip ayrıca kaydet.")
        readable("Kelime ve çeviriyi taslağa aktar"); footer(); capture("translation-word-font2")
    }
    @Test fun openingDownloadTranslateCopyAndDeleteAreExplicitAndStaleCallbacksCannotRetarget() {
        var state by mutableStateOf<TranslationState?>(null, referentialEqualityPolicy()); var shown by mutableStateOf(true); var owner by mutableStateOf(true)
        val events = mutableListOf<String>()
        compose.setContent { YaprakTheme { if (shown) SelectedTextDialog(preview, {}, {}, translationContent = {
            TranslationPanel(state, { owner }, { events += "open" }, { events += "check" }, { events += "download" },
                { events += "translate" }, { events += "delete" }, { events += "copy" }, { events += "close" })
        }) } }
        assertTrue(events.isEmpty())
        val oldOpen = action("translation-open"); compose.onNodeWithTag("translation-open").performClick()
        assertEquals(listOf("open"), events)
        compose.runOnIdle { state = ready.copy(phase = TranslationPhase.MISSING, modelsReady = false, translatedText = null) }
        val oldDownload = action("translation-download")
        compose.runOnIdle { oldOpen() }; assertEquals(listOf("open"), events)
        compose.onNodeWithTag("translation-download").performClick(); assertEquals("download", events.last())
        compose.runOnIdle { state = ready.copy(phase = TranslationPhase.DOWNLOADING, working = true, modelsReady = false, translatedText = null) }
        compose.runOnIdle { oldDownload() }; assertEquals(listOf("open", "download"), events)
        compose.onNodeWithTag("translation-run").assertDoesNotExist(); compose.onNodeWithTag("translation-check").assertDoesNotExist()
        compose.onNodeWithTag("translation-close").performScrollTo().performClick(); assertEquals("close", events.last())
        compose.runOnIdle { state = ready }
        compose.onNodeWithTag("translation-run").performScrollTo().performClick(); assertEquals("translate", events.last())
        val oldCopy = action("translation-copy")
        compose.onNodeWithTag("translation-copy").performClick(); assertEquals("copy", events.last())
        compose.onNodeWithTag("translation-delete").performScrollTo().performClick(); assertEquals("delete", events.last())
        val count = events.size
        compose.runOnIdle { state = ready.copy() }; compose.runOnIdle { oldCopy() }; assertEquals(count, events.size)
        val freshCopy = action("translation-copy")
        compose.runOnIdle { owner = false }; compose.runOnIdle { freshCopy() }; assertEquals(count, events.size)
        compose.runOnIdle { owner = true; shown = false }; compose.runOnIdle { freshCopy(); oldDownload(); oldOpen() }
        assertEquals(count, events.size)
    }
    @Test fun waitingDownloadAndFailedCheckHaveNoInventedTranslationAndCanBeRechecked() {
        var state by mutableStateOf(ready.copy(phase = TranslationPhase.DOWNLOADING, modelsReady = false, translatedText = null))
        var checks = 0
        compose.setContent { YaprakTheme { SelectedTextDialog(preview, {}, {}, translationContent = {
            TranslationPanel(state, { true }, {}, { checks++ }, {}, {}, {}, {}, {})
        }) } }
        compose.onNodeWithTag("translation-run").assertDoesNotExist(); compose.onNodeWithTag("translation-copy").assertDoesNotExist()
        compose.onNodeWithTag("translation-check").performScrollTo().performClick(); assertEquals(1, checks)
        compose.runOnIdle { state = state.copy(phase = TranslationPhase.ERROR, failure = TranslationFailure.MODELS) }
        compose.onNodeWithText("Cihazdaki çeviri modeli kontrol edilemedi.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("translation-check").performScrollTo().performClick(); assertEquals(2, checks)
        compose.onNodeWithTag("translation-copy").assertDoesNotExist()
    }
    @Test fun narrowDoubleTypeLiteralTranslationAndModelRemovalScrollAboveTheFixedFooter() {
        largeFont()
        show(ready)
        readable("Türkçe · makine çevirisi"); readable(ready.translatedText!!)
        readable("Çeviriyi panoya kopyala"); readable("Türkçe çeviri modelini kaldır")
        footer(); capture("translation-narrow-font2")
    }
    @Test @Config(qualifiers = "w640dp-h320dp-mdpi")
    fun shortLandscapeDoubleTypeDownloadDisclosureAndRetryRemainAccessible() {
        largeFont()
        show(ready.copy(phase = TranslationPhase.MISSING, modelsReady = false, translatedText = null))
        readable("Türkçe çeviri modeli bu cihazda hazır değil. Dil modeli yaklaşık 30 MB yer kullanabilir. İndirme Google'dan, yalnız Wi-Fi ile yapılır. Bu düğme metni çevirmez; paneli kapatmak indirmeyi durdurmayabilir.")
        readable("Modeli Wi-Fi ile indir"); readable("Çeviri panelini kapat")
        footer(); capture("translation-landscape-font2")
    }
    private fun show(state: TranslationState) = compose.setContent { YaprakTheme { SelectedTextDialog(preview, {}, {}, translationContent = {
        TranslationPanel(state, { true }, {}, {}, {}, {}, {}, {}, {})
    }) } }
    private fun action(tag: String): () -> Boolean = compose.onNodeWithTag(tag).performScrollTo()
        .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun largeFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate() }
    private fun readable(value: String) {
        val node = compose.onNodeWithText(value, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>(); node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale); assertFalse(layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun footer() {
        compose.onNodeWithText("Panoya kopyala").assertIsDisplayed(); compose.onNodeWithText("Kapat").assertIsDisplayed()
        val body = compose.onNodeWithTag("selected-text-body").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithText("Kapat").fetchSemanticsNode().boundsInRoot.top >= body.bottom)
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

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
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.feature.settings.*
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
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VolumeKeyDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }

    @Test fun explicitOptInSaveAndCancelAreReadableAtDoubleFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var state by mutableStateOf(VolumeKeyState(loading = false, open = true))
        var saves = 0
        compose.setContent { YaprakTheme { VolumeKeyDialog(state,
            { state = state.copy(draft = it) },
            { saves++; state = state.copy(saved = state.draft, open = false) },
            { state = state.copy(open = false, draft = state.saved) }, {}) } }
        readable("Okuma kontrolleri")
        compose.onNodeWithText("Ses tuşlarıyla sayfa çevir").performScrollTo().assertIsOff().performClick()
        assertTrue(state.draft); assertFalse(state.saved); assertEquals(0, saves)
        readable("Ses tuşlarıyla sayfa çevir"); readable("Kaydet"); readable("Vazgeç")
        capture("volume-keys-opt-in-font2")
        compose.onNodeWithText("Vazgeç").performClick()
        assertFalse(state.saved || state.draft)
        compose.runOnIdle { state = state.copy(open = true) }
        compose.onNodeWithText("Ses tuşlarıyla sayfa çevir").performScrollTo().performClick()
        compose.onNodeWithText("Kaydet").performClick()
        assertEquals(1, saves); assertTrue(state.saved)
    }

    @Test fun loadingReadAndWriteFailuresStayReachableWithoutDiscardingSavedChoice() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var state by mutableStateOf(VolumeKeyState(saved = true, draft = false, loading = true, open = true))
        var retries = 0; var saves = 0
        compose.setContent { YaprakTheme { VolumeKeyDialog(state, {}, { saves++ }, {}, { retries++ }) } }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Ses tuşlarıyla sayfa çevir").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, loadFailed = true) }
        compose.onNodeWithText("Yeniden yükle").performScrollTo().performClick()
        readable("Yeniden yükle"); assertEquals(1, retries); assertEquals(0, saves)
        capture("volume-keys-read-failure-font2")
        compose.runOnIdle { state = state.copy(loadFailed = false, saveFailed = true) }
        compose.onNodeWithText("Okuma kontrolleri kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.")
            .performScrollTo().assertIsDisplayed()
        readable("Kaydet")
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
        assertTrue(state.saved); assertFalse(state.draft); assertEquals(0, saves)
    }
    private fun readable(text: String) = checkLayout(compose.onNodeWithText(text, useUnmergedTree = true))
    private fun checkLayout(node: SemanticsNodeInteraction) {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse(layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineLeft(line) >= -0.5f && layout.getLineRight(line) <= layout.size.width + 0.5f)
        }
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

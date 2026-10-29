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
class TapZoneDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }

    @Test fun draftMappingsCancelSaveAndMenuReachabilityAtDoubleFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var state by mutableStateOf(TapZoneState(loading = false, open = true))
        var saves = 0
        compose.setContent { YaprakTheme { TapZoneDialog(state,
            { state = state.copy(draft = it) },
            { saves++; state = state.copy(saved = state.draft, open = false) },
            { state = state.copy(open = false, draft = state.saved) }, {}) } }
        readable("Dokunma bölgeleri")
        compose.onNodeWithText("Özel dokunma bölgeleri").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Orta bölge: İşlem yapma").performScrollTo().performClick()
        assertFalse(state.draft.valid)
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("En az bir bölge araçları göstermeli.").performScrollTo().assertIsDisplayed()
        readable("En az bir bölge araçları göstermeli.")
        compose.onNodeWithContentDescription("Sol bölge: Araçları göster").performScrollTo().performClick()
        assertTrue(state.draft.valid); assertFalse(state.saved.enabled)
        readable("Kaydet"); readable("Vazgeç")
        capture("tap-zones-assignments-font2")
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(TapZoneSettings(), state.saved); assertEquals(0, saves)
        compose.runOnIdle { state = state.copy(open = true) }
        compose.onNodeWithText("Özel dokunma bölgeleri").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Sağ bölge: Önceki sayfa").performScrollTo().performClick()
        compose.onNodeWithText("Kaydet").performClick()
        assertEquals(1, saves); assertTrue(state.saved.enabled)
        assertEquals(TapZoneAction.PREVIOUS, state.saved.right)
    }
    @Test fun failedReadAndSavingCannotOverwritePreferenceAndRetryIsReadable() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var state by mutableStateOf(TapZoneState(loading = true, open = true))
        var retries = 0
        compose.setContent { YaprakTheme { TapZoneDialog(state, {}, {}, {}, { retries++ }) } }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(loading = false, loadFailed = true) }
        compose.onNodeWithText("Yeniden yükle").performScrollTo().performClick()
        readable("Yeniden yükle"); capture("tap-zones-read-failure-font2")
        assertEquals(1, retries)
        compose.runOnIdle { state = state.copy(loadFailed = false, saveFailed = true) }
        compose.onNodeWithText("Dokunma bölgeleri kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
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

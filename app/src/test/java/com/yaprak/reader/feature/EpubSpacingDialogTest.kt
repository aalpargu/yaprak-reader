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
class EpubSpacingDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }

    @Test fun customSpacingSlidersAreReadableAtDoubleFontAndOnlyExplicitSaveCommitsTheDraft() {
        largeFont()
        var state by mutableStateOf(EpubSettingsState(loading = false, open = true)); var saves = 0
        compose.setContent { YaprakTheme { EpubSettingsDialog(state, { state = state.copy(draft = it) }, {
            saves++; state = state.copy(saved = state.draft, open = false)
        }, {}, {}, {}) } }
        compose.onNodeWithText("Yayıncı paragraf aralığı").performScrollTo().performClick().assertIsNotSelected()
        readable("Yayıncı paragraf aralığı")
        compose.onNodeWithContentDescription("Paragraf boşluğu").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(200f) }
        readable("Paragraf boşluğu · %200")
        compose.onNodeWithText("Yayıncı kelime aralığı").performScrollTo().performClick().assertIsNotSelected()
        readable("Yayıncı kelime aralığı")
        compose.onNodeWithContentDescription("Ek kelime boşluğu").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        readable("Ek kelime boşluğu · %0")
        assertEquals(200, state.draft.paragraphSpacingPercent); assertEquals(0, state.draft.wordSpacingPercent)
        assertNull(state.saved.paragraphSpacingPercent); assertNull(state.saved.wordSpacingPercent); assertEquals(0, saves)
        compose.onNodeWithText("Kaydet").assertIsDisplayed(); readable("Kaydet")
        compose.onNodeWithText("Vazgeç").assertIsDisplayed(); readable("Vazgeç"); capture("epub-custom-spacing-font2")
        compose.onNodeWithText("Kaydet").performClick()
        assertEquals(1, saves); assertEquals(200, state.saved.paragraphSpacingPercent); assertEquals(0, state.saved.wordSpacingPercent)
    }

    @Test fun publisherSpacingAndResetStayDraftAndCancelKeepsTheSavedValuesAtDoubleFont() {
        largeFont()
        val saved = EpubSettings(paragraphSpacingPercent = 150, wordSpacingPercent = 50)
        var state by mutableStateOf(EpubSettingsState(saved = saved, draft = saved, loading = false, open = true))
        var saves = 0
        compose.setContent { YaprakTheme { EpubSettingsDialog(state, { state = state.copy(draft = it) }, { saves++ }, {
            state = state.copy(open = false, draft = state.saved)
        }, { state = state.copy(draft = EpubSettings()) }, {}) } }
        compose.onNodeWithText("Yayıncı paragraf aralığı").performScrollTo().performClick().assertIsSelected()
        readable("Yayıncı paragraf aralığı")
        compose.onNodeWithText("Yayıncı kelime aralığı").performScrollTo().performClick().assertIsSelected()
        readable("Yayıncı kelime aralığı")
        compose.onNodeWithContentDescription("Ek kelime boşluğu").assertDoesNotExist()
        compose.onNodeWithContentDescription("Paragraf boşluğu").assertDoesNotExist()
        assertNull(state.draft.paragraphSpacingPercent); assertNull(state.draft.wordSpacingPercent)
        assertEquals(saved, state.saved); assertEquals(0, saves); capture("epub-publisher-spacing-font2")
        compose.onNodeWithText("Varsayılanlara dön").performScrollTo().performClick()
        assertEquals(EpubSettings(), state.draft); assertEquals(saved, state.saved)
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        assertEquals(0, saves); assertEquals(saved, state.draft)
    }

    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
    }
    private fun readable(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse(layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineLeft(line) >= -0.5f && layout.getLineRight(line) <= layout.size.width + 0.5f)
        }
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
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

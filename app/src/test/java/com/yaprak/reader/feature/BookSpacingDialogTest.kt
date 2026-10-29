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
class BookSpacingDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }

    @Test fun independentChoicesAndExactZeroAreReachableAtDoubleFontWithExplicitSave() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var state by mutableStateOf(BookSpacingState(loading = false, open = true)); var saves = 0
        val global = EpubSettings(paragraphSpacingPercent = 150, wordSpacingPercent = 20)
        compose.setContent { YaprakTheme { BookSpacingDialog(state, { state = state.copy(draft = it) }, {
            saves++; state = state.copy(saved = state.draft, open = false)
        }, {}, {}, global) } }
        compose.onNodeWithContentDescription("Paragraf aralığı: Genel ayarı kullan").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Paragraf aralığı: Özel boşluk").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Paragraf boşluğu").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(200f) }
        readable("Paragraf boşluğu · %200")
        compose.onNodeWithContentDescription("Kelime aralığı: Özel boşluk").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Ek kelime boşluğu").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        readable("Ek kelime boşluğu · %0")
        assertEquals(BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Custom(0)), state.draft)
        assertEquals(BookSpacingSettings(), state.saved); assertEquals(0, saves)
        for (label in listOf("Genel ayarı kullan", "Yayıncı düzeni", "Özel boşluk")) {
            val nodes = compose.onAllNodesWithText(label, useUnmergedTree = true)
            assertEquals(2, nodes.fetchSemanticsNodes().size)
            for (i in 0..1) checkLayout(nodes[i])
        }
        compose.onNodeWithText("Kaydet").assertIsDisplayed(); readable("Kaydet"); capture("book-custom-spacing-font2")
        compose.onNodeWithText("Kaydet").performClick()
        assertEquals(1, saves); assertEquals(state.draft, state.saved)
    }

    @Test fun publisherAndInheritanceCancelLateLoadFailuresAndSavingStaySafe() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        val saved = BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Custom(0))
        var state by mutableStateOf(BookSpacingState(open = true)); var saves = 0; var retries = 0
        compose.setContent { YaprakTheme { BookSpacingDialog(state, { state = state.copy(draft = it) }, { saves++ }, {
            state = state.copy(open = false, draft = state.saved)
        }, { retries++ }, EpubSettings()) } }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(loading = false, saved = saved, draft = saved) }
        compose.onNodeWithContentDescription("Paragraf aralığı: Yayıncı düzeni").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Kelime aralığı: Genel ayarı kullan").performScrollTo().performClick()
        assertEquals(BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Inherit), state.draft)
        compose.onNodeWithContentDescription("Kelime aralığı: Yayıncı düzeni").performScrollTo().performClick()
        assertEquals(BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Publisher), state.draft)
        compose.onNodeWithContentDescription("Paragraf boşluğu").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ek kelime boşluğu").assertDoesNotExist()
        capture("book-publisher-spacing-font2")
        compose.runOnIdle { state = state.copy(loadFailed = true, saveFailed = true) }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Yeniden yükle").performScrollTo().performClick()
        assertEquals(1, retries); assertEquals(0, saves)
        compose.runOnIdle { state = state.copy(loadFailed = false, saving = true) }
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled(); compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(saving = false) }
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(saved, state.saved); assertEquals(saved, state.draft); assertEquals(0, saves)
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

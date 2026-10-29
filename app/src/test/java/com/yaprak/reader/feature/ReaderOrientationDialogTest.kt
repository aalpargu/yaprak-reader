package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.domain.settings.ReaderOrientation
import com.yaprak.reader.feature.settings.ReaderOrientationDialog
import com.yaprak.reader.feature.settings.ReaderOrientationState
import com.yaprak.reader.ui.theme.YaprakTheme
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
class ReaderOrientationDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `choices expose selected semantics and change only the draft before save`() {
        var state by mutableStateOf(ReaderOrientationState(loading = false, open = false))
        var saves = 0; var dismissed = 0
        compose.setContent { YaprakTheme { ReaderOrientationDialog(state,
            { state = state.copy(draft = it, revision = state.revision + 1) }, { saves++ }, { dismissed++ }, {}) } }
        compose.onNodeWithText("Ekran yönü").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(open = true, revision = 1) }
        compose.onNodeWithText("Otomatik").assertIsSelected()
        compose.onNodeWithText("Dikey").assertIsNotSelected().performClick().assertIsSelected()
        assertEquals(ReaderOrientation.AUTO, state.saved)
        assertEquals(0, saves)
        compose.onNodeWithText("Kaydet").performClick(); compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(1, saves); assertEquals(1, dismissed)
    }

    @Test fun `loading failure busy and write failure preserve their independent action gates`() {
        var state by mutableStateOf(ReaderOrientationState(open = true))
        var retries = 0
        compose.setContent { YaprakTheme { ReaderOrientationDialog(state, {}, {}, {}, { retries++ }) } }
        compose.onNodeWithText("Ekran yönü okunuyor…").assertExists()
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(loading = false, loadFailed = true, revision = 1) }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Yeniden yükle").performScrollTo().performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state = state.copy(loadFailed = false, draft = ReaderOrientation.LANDSCAPE,
            saveFailed = true, revision = 2) }
        compose.onNodeWithText("Yatay").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Ekran yönü kaydedilemedi. Taslağın korunuyor; tekrar deneyebilirsin.").assertExists()
        compose.onNodeWithText("Kaydet").assertIsEnabled()
        compose.runOnIdle { state = state.copy(saving = true, revision = 3) }
        compose.onNodeWithText("Yatay").assertIsNotEnabled()
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
    }

    @Test fun `old Material callbacks cannot save edit or dismiss a replacement dialog revision`() {
        var state by mutableStateOf(ReaderOrientationState(loading = false, open = true))
        var edits = 0; var saves = 0; var dismisses = 0
        compose.setContent { YaprakTheme { ReaderOrientationDialog(state, { edits++ }, { saves++ }, { dismisses++ }, {}) } }
        val edit = compose.onNodeWithText("Dikey").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val save = compose.onNodeWithText("Kaydet").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val dismiss = compose.onNodeWithText("Vazgeç").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(open = false, revision = 1) }
        compose.runOnIdle { state = state.copy(open = true, revision = 2) }
        compose.runOnIdle { edit(); save(); dismiss() }
        assertEquals(0, edits); assertEquals(0, saves); assertEquals(0, dismisses)
        compose.onNodeWithText("Dikey").performClick(); compose.onNodeWithText("Kaydet").performClick()
        assertEquals(1, edits); assertEquals(1, saves)
    }

    @Test fun `narrow dialog at double font scale keeps fixed actions and every choice reachable`() {
        largeFont(); showLargeDialog()
        listOf("Otomatik", "Dikey", "Yatay").forEach { choice ->
            compose.onNodeWithText(choice).performScrollTo().assertIsDisplayed(); readable(choice)
        }
        listOf("Vazgeç", "Kaydet").forEach { action -> compose.onNodeWithText(action).assertIsDisplayed(); readable(action) }
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short landscape at double font scale keeps scrolling body separate from fixed actions`() {
        largeFont(); showLargeDialog()
        compose.onNodeWithText("Yatay").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Vazgeç").assertIsDisplayed(); readable("Vazgeç")
        compose.onNodeWithText("Kaydet").assertIsDisplayed(); readable("Kaydet")
    }

    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
    }

    private fun showLargeDialog() {
        var state by mutableStateOf(ReaderOrientationState(loading = false, open = true))
        compose.setContent { YaprakTheme { ReaderOrientationDialog(state,
            { state = state.copy(draft = it, revision = state.revision + 1) }, {}, {}, {}) } }
    }

    private fun readable(label: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue(results.isNotEmpty())
        assertFalse("Text overflow: $label", results.single().hasVisualOverflow)
    }
}

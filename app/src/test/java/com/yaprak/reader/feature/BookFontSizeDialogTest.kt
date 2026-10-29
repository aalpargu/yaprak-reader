package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.yaprak.reader.feature.settings.BookFontSizeDialog
import com.yaprak.reader.feature.settings.BookFontSizeState
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
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookFontSizeDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `large type keeps inherited and explicit size controls reachable`() {
        var state by mutableStateOf(BookFontSizeState(loading = false, open = true))
        var saves = 0
        var dismisses = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme {
                BookFontSizeDialog(state, { state = state.copy(draft = it) }, { saves++ }, { dismisses++ }, {}, 155)
            }
        }
        compose.onNodeWithText("Kitap yazı boyutu").assertIsDisplayed()
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Genel yazı boyutu · %155").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Kitap yazı boyutu").assertIsNotEnabled()
        compose.onNodeWithText("Bu kitap için").performScrollTo().performClick()
        assertEquals(155, state.draft)
        assertNull(state.saved)
        compose.onNodeWithContentDescription("Kitap yazı boyutu")
            .performScrollTo().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(152f) }
        assertEquals(150, state.draft)
        compose.onNodeWithText("Yazı boyutu · %150").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().performClick()
        assertNull(state.draft)
        compose.onNodeWithText("Kaydet").assertIsDisplayed().performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        assertEquals(1, dismisses)
    }

    @Test fun `read error allows retry while failed save keeps choice and busy state blocks actions`() {
        var state by mutableStateOf(BookFontSizeState(loading = false, loadFailed = true, open = true))
        var retries = 0
        var saves = 0
        compose.setContent {
            YaprakTheme { BookFontSizeDialog(state, { state = state.copy(draft = it) }, { saves++ }, {}, { retries++ }) }
        }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Yeniden yükle").performScrollTo().performClick()
        assertEquals(1, retries)
        assertEquals(0, saves)
        compose.runOnIdle {
            state = state.copy(loading = false, loadFailed = false, saved = 125,
                draft = 150, saveFailed = true)
        }
        compose.onNodeWithText("Kitap yazı boyutu kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bu kitap için").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Kaydet").assertIsEnabled()
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithContentDescription("Kitap yazı boyutu").assertIsNotEnabled()
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
    }
}

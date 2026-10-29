package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yaprak.reader.feature.settings.ScreenAwakeDialog
import com.yaprak.reader.feature.settings.ScreenAwakeState
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ScreenAwakeDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `closed dialog has no controls and draft changes wait for save`() {
        var state by mutableStateOf(ScreenAwakeState(loading = false, open = false))
        var saves = 0
        var dismisses = 0
        compose.setContent {
            YaprakTheme {
                ScreenAwakeDialog(state, { state = state.copy(draft = it) }, { saves++ }, { dismisses++ }, {})
            }
        }
        compose.onNodeWithText("Ekran ayarı").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(open = true) }
        compose.onNodeWithText("Okurken ekranı açık tut").assertIsOff().performClick().assertIsOn()
        assertFalse(state.saved)
        assertEquals(0, saves)
        compose.onNodeWithText("Kaydet").performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(1, dismisses)
    }

    @Test fun `loading and read failure prevent saving and failed write retains draft`() {
        var state by mutableStateOf(ScreenAwakeState(loading = true, open = true))
        var retries = 0
        var saves = 0
        compose.setContent {
            YaprakTheme {
                ScreenAwakeDialog(state, { state = state.copy(draft = it) }, { saves++ }, {}, { retries++ })
            }
        }
        compose.onNodeWithText("Ayar okunuyor…").assertIsDisplayed()
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(loading = false, loadFailed = true) }
        compose.onNodeWithText("Ekran ayarı okunamadı. Yeniden yükleyip tekrar dene.").assertIsDisplayed()
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Yeniden yükle").performClick()
        assertEquals(1, retries)
        assertEquals(0, saves)

        compose.runOnIdle { state = state.copy(loadFailed = false, draft = true, saveFailed = true) }
        compose.onNodeWithText("Ekran ayarı kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Okurken ekranı açık tut").performScrollTo().assertIsOn()
        compose.onNodeWithText("Kaydet").assertIsEnabled()
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithText("Okurken ekranı açık tut").assertIsNotEnabled()
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short screen with large type keeps switch and final actions reachable`() {
        var state by mutableStateOf(ScreenAwakeState(loading = false, open = true))
        var saves = 0
        var dismisses = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme {
                ScreenAwakeDialog(state, { state = state.copy(draft = it) }, { saves++ }, { dismisses++ }, {})
            }
        }
        compose.onNodeWithText("Okurken ekranı açık tut").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Kaydet").assertIsDisplayed().performClick()
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        assertEquals(1, saves)
        assertEquals(1, dismisses)
    }
}

package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.feature.settings.BookFontDialog
import com.yaprak.reader.feature.settings.BookFontState
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
class BookFontDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `large type offers inherited and explicit book fonts with reachable save and cancel`() {
        var state by mutableStateOf(BookFontState(loading = false, open = true))
        var saves = 0
        var dismisses = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme {
                BookFontDialog(state, { state = state.copy(draft = it) }, { saves++ }, { dismisses++ }, {})
            }
        }
        compose.onNodeWithText("Kitap yazı ailesi").assertIsDisplayed()
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Kitabın yazısı").performScrollTo().performClick()
        assertEquals(ReadingFont.ORIGINAL, state.draft)
        assertNull(state.saved)
        assertEquals(0, saves)
        compose.onNodeWithText("Serif").performScrollTo().performClick()
        assertEquals(ReadingFont.SERIF, state.draft)
        compose.onNodeWithText("Sans serif").performScrollTo().performClick()
        assertEquals(ReadingFont.SANS_SERIF, state.draft)
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().performClick()
        assertNull(state.draft)
        compose.onNodeWithText("Kaydet").assertIsDisplayed().performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        assertEquals(1, dismisses)
    }

    @Test fun `read error has retry and save failure keeps draft while busy state blocks actions`() {
        var state by mutableStateOf(BookFontState(loading = false, loadFailed = true, open = true))
        var retries = 0
        var saves = 0
        compose.setContent {
            YaprakTheme { BookFontDialog(state, { state = state.copy(draft = it) }, { saves++ }, {}, { retries++ }) }
        }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Yeniden yükle").performScrollTo().performClick()
        assertEquals(1, retries)
        assertEquals(0, saves)
        compose.runOnIdle {
            state = state.copy(loading = false, loadFailed = false, saved = ReadingFont.SANS_SERIF,
                draft = ReadingFont.SERIF, saveFailed = true)
        }
        compose.onNodeWithText("Kitap yazı ailesi kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Serif").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Kaydet").assertIsEnabled()
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithText("Serif").assertIsNotEnabled()
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
    }
}


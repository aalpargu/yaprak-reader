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
import com.yaprak.reader.feature.settings.BookMarginsDialog
import com.yaprak.reader.feature.settings.BookMarginsState
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
class BookMarginsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `large type keeps inherited and explicit margins controls reachable`() {
        var state by mutableStateOf(BookMarginsState(loading = false, open = true))
        var saves = 0
        var dismisses = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme {
                BookMarginsDialog(state, { state = state.copy(draft = it) }, { saves++ }, { dismisses++ }, {}, 155)
            }
        }
        compose.onNodeWithText("Kitap kenar boşluğu").assertIsDisplayed()
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Genel kenar boşluğu · %155").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Kitap kenar boşluğu").assertIsNotEnabled()
        compose.onNodeWithText("Bu kitap için").performScrollTo().performClick()
        assertEquals(160, state.draft)
        assertNull(state.saved)
        compose.onNodeWithContentDescription("Kitap kenar boşluğu")
            .performScrollTo().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(142f) }
        assertEquals(140, state.draft)
        compose.onNodeWithText("Kenar boşluğu · %140").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().performClick()
        assertNull(state.draft)
        compose.onNodeWithText("Kaydet").assertIsDisplayed().performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        assertEquals(1, dismisses)
    }

    @Test fun `read error allows retry while failed save preserves choice and busy blocks controls`() {
        var state by mutableStateOf(BookMarginsState(loading = false, loadFailed = true, open = true))
        var retries = 0
        var saves = 0
        compose.setContent {
            YaprakTheme { BookMarginsDialog(state, { state = state.copy(draft = it) }, { saves++ }, {}, { retries++ }) }
        }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Yeniden yükle").performScrollTo().performClick()
        assertEquals(1, retries)
        assertEquals(0, saves)
        compose.runOnIdle {
            state = state.copy(loading = false, loadFailed = false, saved = 120,
                draft = 150, saveFailed = true)
        }
        compose.onNodeWithText("Kitap kenar boşluğu kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bu kitap için").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Kaydet").assertIsEnabled()
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithContentDescription("Kitap kenar boşluğu").assertIsNotEnabled()
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
    }
}

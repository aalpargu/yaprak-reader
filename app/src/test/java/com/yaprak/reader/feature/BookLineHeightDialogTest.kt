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
import com.yaprak.reader.domain.settings.BookLineHeightChoice
import com.yaprak.reader.feature.settings.BookLineHeightDialog
import com.yaprak.reader.feature.settings.BookLineHeightState
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
class BookLineHeightDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `large type keeps all three choices and custom slider reachable`() {
        var state by mutableStateOf(BookLineHeightState(loading = false, open = true))
        var saves = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme {
                BookLineHeightDialog(state, { state = state.copy(draft = it) }, { saves++ }, {}, {}, null)
            }
        }
        compose.onNodeWithText("Kitap satır aralığı").assertIsDisplayed()
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Genel satır aralığı · Yayıncı düzeni").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Kitap satır aralığı").assertIsNotEnabled()
        compose.onNodeWithText("Yayıncı düzeni").performScrollTo().performClick()
        assertEquals(BookLineHeightChoice.Publisher, state.draft)
        compose.onNodeWithText("Bu kitap için yüzde").performScrollTo().performClick()
        assertEquals(BookLineHeightChoice.Custom(140), state.draft)
        compose.onNodeWithContentDescription("Kitap satır aralığı")
            .performScrollTo().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(176f) }
        assertEquals(BookLineHeightChoice.Custom(180), state.draft)
        compose.onNodeWithText("Yayıncı düzeni").performScrollTo().performClick()
        compose.onNodeWithText("Bu kitap için yüzde").performScrollTo().performClick()
        assertEquals(BookLineHeightChoice.Custom(180), state.draft)
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().performClick()
        assertEquals(BookLineHeightChoice.Inherit, state.draft)
        compose.onNodeWithText("Kaydet").assertIsDisplayed().performClick()
        assertEquals(1, saves)
    }

    @Test fun `late load seeds saved custom value and failures keep actions safe`() {
        var state by mutableStateOf(BookLineHeightState(open = true))
        var retries = 0
        var saves = 0
        compose.setContent {
            YaprakTheme {
                BookLineHeightDialog(state, { state = state.copy(draft = it) }, { saves++ }, {}, { retries++ }, 150)
            }
        }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.runOnIdle {
            state = state.copy(loading = false, saved = BookLineHeightChoice.Custom(170),
                draft = BookLineHeightChoice.Custom(170))
        }
        compose.onNodeWithText("Yayıncı düzeni").performScrollTo().performClick()
        compose.onNodeWithText("Bu kitap için yüzde").performScrollTo().performClick()
        assertEquals(BookLineHeightChoice.Custom(170), state.draft)
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().performClick()
        compose.onNodeWithText("Genel satır aralığı · %150").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loadFailed = true, saveFailed = true) }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Yeniden yükle").performScrollTo().performClick()
        assertEquals(1, retries)
        assertEquals(0, saves)
        compose.runOnIdle { state = state.copy(loadFailed = false, saving = true) }
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
    }
}

package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yaprak.reader.feature.settings.BookAppearanceResetDialog
import com.yaprak.reader.feature.settings.BookAppearanceResetState
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.assertEquals
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
class BookAppearanceResetDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `large type in short landscape keeps explanation and actions reachable`() {
        var state by mutableStateOf(BookAppearanceResetState(open = true))
        var confirmations = 0
        var dismissals = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme {
                BookAppearanceResetDialog(state, { confirmations++ }, { dismissals++ })
            }
        }
        compose.onNodeWithText("Kitap görünümünü sıfırla").assertIsDisplayed()
        compose.onNodeWithText("Bu kitabın tema, yazı boyutu, yazı ailesi, kenar boşluğu, satır, paragraf ve kelime aralığı ile metin hizalaması ve okuma modu için kayıtlı özel seçimleri kaldırılacak.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bu dokuz alan güncel genel görünüm ayarlarını kullanacak.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Genel görünüm ayarları, okuma konumu ve yer imleri değişmeyecek.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        assertEquals(1, dismissals)
        assertEquals(0, confirmations)
        compose.onNodeWithText("Sıfırla").assertIsDisplayed().performClick()
        assertEquals(1, confirmations)
    }

    @Test fun `saving blocks actions and failure offers retry`() {
        var state by mutableStateOf(BookAppearanceResetState(open = true))
        var confirmations = 0
        var dismissals = 0
        compose.setContent {
            YaprakTheme {
                BookAppearanceResetDialog(state, { confirmations++ }, { dismissals++ })
            }
        }
        compose.onNodeWithText("Sıfırla").assertIsEnabled()
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithText("Sıfırlanıyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(saving = false, saveFailed = true) }
        compose.onNodeWithText("Kitap görünümü sıfırlanamadı. Tekrar deneyebilir veya vazgeçebilirsin.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tekrar dene").assertIsEnabled().performClick()
        assertEquals(1, confirmations)
        assertEquals(0, dismissals)
    }
}

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
import com.yaprak.reader.feature.settings.BookAppearanceResetRefreshDialog
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
class BookAppearanceResetRefreshDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `large type in short landscape keeps recovery explanation and actions reachable`() {
        var retrying by mutableStateOf(false)
        var retries = 0
        var leaves = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme {
                BookAppearanceResetRefreshDialog(true, retrying, { retries++ }, { leaves++ })
            }
        }
        compose.onNodeWithText("Görünüm yüklenemedi").assertIsDisplayed()
        compose.onNodeWithText("Sıfırlama kaydedildi, ancak görünüm yeniden yüklenemedi.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Kitabın özel görünüm seçimleri kaldırıldı. Genel ayarların okuyucuya uygulanması tamamlanmadı.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Yeniden yüklemeyi dene veya kitaplığa dön.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Yeniden yükle").assertIsDisplayed().performClick()
        assertEquals(1, retries)
        compose.runOnIdle { retrying = true }
        compose.onNodeWithText("Yeniden yükleniyor…").assertIsNotEnabled()
        compose.onNodeWithText("Kitaplığa dön").assertIsEnabled().performClick()
        assertEquals(1, leaves)
    }

    @Test fun `leave action closes only when caller changes open state`() {
        var open by mutableStateOf(true)
        var leaves = 0
        compose.setContent {
            YaprakTheme {
                BookAppearanceResetRefreshDialog(open, false, {}, {
                    leaves++
                    open = false
                })
            }
        }
        compose.onNodeWithText("Kitaplığa dön").performClick()
        assertEquals(1, leaves)
        compose.onNodeWithText("Görünüm yüklenemedi").assertDoesNotExist()
    }
}

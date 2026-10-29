package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yaprak.reader.feature.settings.BookAppearanceDialog
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
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h320dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookAppearanceDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `large type reaches each independent book preference and closes hub`() {
        var open by mutableStateOf(true)
        val selected = mutableListOf<String>()
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme {
                BookAppearanceDialog(open,
                    { selected += "theme"; open = false },
                    { selected += "size"; open = false },
                    { selected += "font"; open = false },
                    { selected += "margins"; open = false },
                    { selected += "line-height"; open = false },
                    { selected += "spacing"; open = false },
                    { selected += "reset"; open = false },
                    { selected += "close"; open = false },
                    onTextAlignment = { selected += "alignment"; open = false },
                    onReadingMode = { selected += "mode"; open = false })
            }
        }
        listOf("Kitap teması", "Kitap yazı boyutu", "Kitap yazı ailesi", "Kitap kenar boşluğu", "Kitap satır aralığı", "Kitap paragraf / kelime aralığı", "Kitap metin hizalaması", "Kitap okuma modu", "Bu kitabın görünümünü genel ayara döndür").forEach { label ->
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("Kitap görünümü").assertDoesNotExist()
            compose.runOnIdle { open = true }
        }
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertEquals(listOf("theme", "size", "font", "margins", "line-height", "spacing", "alignment", "mode", "reset", "close"), selected)
    }
}

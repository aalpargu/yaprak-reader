package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.feature.reader.PageJumpDialog
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
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
class PageJumpDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `invalid input never navigates and keyboard action uses zero based valid page`() {
        var selected: Int? = null
        var closed = 0
        compose.setContent { YaprakTheme { PageJumpDialog(20, 0, { selected = it; true }, { closed++ }) } }
        compose.onNodeWithText("Sayfa numarası").assertTextContains("1")
        compose.onNodeWithText("Git").assertIsEnabled()
        listOf("0", "21", "1.5", "-2", "abc", "1" + " ".repeat(40) + "abc").forEach { invalid ->
            compose.onNodeWithText("Sayfa numarası").performTextReplacement(invalid)
            compose.onNodeWithText("Git").assertIsNotEnabled()
            compose.onNodeWithText("Sayfa numarası").performImeAction()
            assertNull(selected)
            assertEquals(0, closed)
        }
        compose.onNodeWithText("Sayfa numarası").performTextReplacement("20")
        compose.onNodeWithText("Sayfa numarası").performImeAction()
        assertEquals(19, selected)
        assertEquals(1, closed)
    }

    @Test fun `slider updates page field and typing updates accessible slider without navigation`() {
        var selected: Int? = null
        var closed = 0
        compose.setContent { YaprakTheme { PageJumpDialog(21, 2, { selected = it; true }, { closed++ }) } }
        val slider = compose.onNodeWithContentDescription("Hızlı sayfa seçimi")
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3. sayfa, toplam 21 sayfa"))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.onNodeWithText("Sayfa numarası").assertTextContains("11")
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "11. sayfa, toplam 21 sayfa"))
        assertNull(selected)
        assertEquals(0, closed)
        compose.onNodeWithText("Sayfa numarası").performTextReplacement("17")
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "17. sayfa, toplam 21 sayfa"))
        assertNull(selected)
        compose.onNodeWithText("Git").performClick()
        assertEquals(16, selected)
        assertEquals(1, closed)
    }

    @Test fun `one page disables slider and valid input still confirms page zero`() {
        var selected: Int? = null
        compose.setContent { YaprakTheme { PageJumpDialog(1, 0, { selected = it; true }, {}) } }
        compose.onNodeWithContentDescription("Hızlı sayfa seçimi").assertIsNotEnabled()
        compose.onNodeWithText("Sayfa numarası").assertTextContains("1")
        compose.onNodeWithText("Sayfa numarası").performTextReplacement("2")
        compose.onNodeWithText("Git").assertIsNotEnabled()
        compose.onNodeWithText("Sayfa numarası").performTextReplacement("1")
        compose.onNodeWithText("Git").performClick()
        assertEquals(0, selected)
    }

    @Test fun `a background render status change does not discard the page draft`() {
        val currentPage = mutableStateOf<Int?>(2)
        compose.setContent { YaprakTheme { PageJumpDialog(21, currentPage.value, { true }, {}) } }
        compose.onNodeWithText("Sayfa numarası").performTextReplacement("17")
        compose.runOnIdle { currentPage.value = null }
        compose.onNodeWithText("Sayfa numarası").assertTextContains("17")
        compose.onNodeWithContentDescription("Hızlı sayfa seçimi")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "17. sayfa, toplam 21 sayfa"))
    }

    @Test fun `rejected navigation stays open and cancel never navigates`() {
        var calls = 0
        var closed = 0
        compose.setContent { YaprakTheme { PageJumpDialog(20, 9, { calls++; false }, { closed++ }) } }
        compose.onNodeWithText("Sayfa numarası").performTextReplacement("3")
        compose.onNodeWithText("Git").performClick()
        compose.onNodeWithText("Bu sayfaya gidilemedi. Yeniden deneyebilirsin.").assertIsDisplayed()
        assertEquals(0, closed)
        compose.onNodeWithText("Git").performClick()
        assertEquals(2, calls)
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(1, closed)
        assertEquals(2, calls)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-mdpi")
    fun `landscape large type retains field and confirmation access`() {
        var selected: Int? = null
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { PageJumpDialog(500, 1, { selected = it; true }, {}) } }
        compose.onNodeWithText("Sayfaya git").assertFontScale(1.5f)
        compose.onNodeWithText("Sayfa numarası").performScrollTo().performTextReplacement("500")
        compose.onNodeWithText("Git").assertIsDisplayed()
        compose.onNodeWithText("Vazgeç").assertIsDisplayed()
        compose.runOnIdle {
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "page-jump-landscape.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("Git").performClick()
        assertEquals(499, selected)
    }
}

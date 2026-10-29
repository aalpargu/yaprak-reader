package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.feature.reader.ReaderBrightnessDialog
import com.yaprak.reader.ui.theme.YaprakTheme
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
class ReaderBrightnessDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `closed dialog has no actions and system selection applies an explicit null`() {
        var open by mutableStateOf(false)
        val applied = mutableListOf<Int?>()
        compose.setContent { YaprakTheme {
            ReaderBrightnessDialog(open, null, {}, { applied += it; open = false }, { open = false })
        } }
        compose.onNodeWithText("Okuma parlaklığı").assertDoesNotExist()
        compose.onNodeWithText("Uygula").assertDoesNotExist()
        compose.runOnIdle { open = true }
        compose.onNodeWithText("Sistem parlaklığı").assertIsSelected()
        compose.onNodeWithText("Cihazın sistem parlaklığı kullanılır.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Okuma parlaklığı yüzdesi").assertDoesNotExist()
        compose.onNodeWithText("Uygula").performClick()
        assertEquals(listOf<Int?>(null), applied)
        compose.onNodeWithText("Okuma parlaklığı").assertDoesNotExist()
    }

    @Test fun `manual starts at fifty and slider edits one percent steps without applying early`() {
        var open by mutableStateOf(true)
        var draft by mutableStateOf<Int?>(null)
        val applied = mutableListOf<Int?>()
        compose.setContent { YaprakTheme {
            ReaderBrightnessDialog(open, draft, { draft = it }, { applied += it; open = false }, { open = false })
        } }
        compose.onNodeWithText("Elle ayarla").performClick().assertIsSelected()
        assertEquals(50, draft)
        compose.onNodeWithText("Parlaklık: %50").assertIsDisplayed()
        val slider = compose.onNodeWithContentDescription("Okuma parlaklığı yüzdesi")
        assertEquals(89, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(62.4f) }
        assertEquals(62, draft)
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Yüzde 62"))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        assertEquals(10, draft)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        assertEquals(100, draft)
        assertTrue(applied.isEmpty())
        compose.onNodeWithText("Uygula").performClick()
        assertEquals(listOf<Int?>(100), applied)
        compose.onNodeWithText("Uygula").assertDoesNotExist()
    }

    @Test fun `cancel discards the edited draft and reopening shows the applied percentage`() {
        var open by mutableStateOf(true)
        var selected by mutableStateOf<Int?>(70)
        var draft by mutableStateOf<Int?>(selected)
        var applications = 0
        compose.setContent { YaprakTheme {
            ReaderBrightnessDialog(open, draft, { draft = it }, {
                selected = it; applications++; open = false
            }, { draft = selected; open = false })
        } }
        compose.onNodeWithContentDescription("Okuma parlaklığı yüzdesi")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(15f) }
        assertEquals(15, draft)
        assertEquals(70, selected)
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(0, applications)
        assertEquals(70, selected)
        compose.runOnIdle { open = true }
        compose.onNodeWithText("Parlaklık: %70").assertIsDisplayed()
    }

    @Test fun `returning to system changes only the draft until apply`() {
        var draft by mutableStateOf<Int?>(85)
        var selected: Int? = 85
        var applications = 0
        compose.setContent { YaprakTheme {
            ReaderBrightnessDialog(true, draft, { draft = it }, { selected = it; applications++ }, {})
        } }
        compose.onNodeWithText("Sisteme dön").performScrollTo().performClick()
        compose.onNodeWithText("Sistem parlaklığı").assertIsSelected()
        compose.onNodeWithContentDescription("Okuma parlaklığı yüzdesi").assertDoesNotExist()
        assertNull(draft)
        assertEquals(85, selected)
        assertEquals(0, applications)
        compose.onNodeWithText("Uygula").performClick()
        assertNull(selected)
        assertEquals(1, applications)
    }

    @Test fun `Android back invokes dismiss without applying the edited brightness`() {
        var open by mutableStateOf(true)
        var draft by mutableStateOf<Int?>(60)
        var dismisses = 0
        var applications = 0
        compose.setContent { YaprakTheme {
            ReaderBrightnessDialog(open, draft, { draft = it }, { applications++ }, {
                dismisses++; open = false
            })
        } }
        compose.onNodeWithContentDescription("Okuma parlaklığı yüzdesi")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }
        compose.runOnUiThread { requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed() }
        compose.onNodeWithText("Okuma parlaklığı").assertDoesNotExist()
        assertEquals(1, dismisses)
        assertEquals(0, applications)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short screen at double font scale keeps choices slider reset and final actions readable`() {
        var open by mutableStateOf(true)
        var draft by mutableStateOf<Int?>(null)
        val applied = mutableListOf<Int?>()
        var dismisses = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme {
            ReaderBrightnessDialog(open, draft, { draft = it }, { applied += it; open = false }, {
                dismisses++; open = false
            })
        } }
        listOf("Sistem parlaklığı", "Elle ayarla", "Sisteme dön").forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertFontScale(2f)
            readable(label)
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        listOf("Uygula", "Vazgeç").forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertFontScale(2f)
            readable(label)
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithText("Elle ayarla").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Okuma parlaklığı yüzdesi")
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(73f) }
        assertEquals(73, draft)
        compose.onNodeWithText("Parlaklık: %73").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sisteme dön").performScrollTo().performClick()
        compose.onNodeWithText("Sistem parlaklığı").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Uygula").assertIsDisplayed().performClick()
        assertEquals(listOf<Int?>(null), applied)
        compose.runOnIdle { open = true }
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        assertEquals(1, dismisses)
    }

    @Test fun `gesture switch requires manual draft and apply is explicit while cancelled old editor cannot act`() {
        var open by mutableStateOf(true); var draft by mutableStateOf<Int?>(null)
        var gesture by mutableStateOf(false); var request by mutableStateOf("editor-one")
        val applied = mutableListOf<Pair<Int?, Boolean>>()
        compose.setContent { YaprakTheme { ReaderBrightnessDialog(open, draft, { draft = it; if (it == null) gesture = false },
            { applied += it to gesture; open = false }, { open = false }, gesture, { gesture = it }, request) } }
        compose.onNodeWithText("Kaydırarak parlaklık").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Elle ayarla").performScrollTo().performClick()
        val toggle = compose.onNodeWithText("Kaydırarak parlaklık").performScrollTo()
        toggle.assertIsEnabled().performClick(); assertTrue(gesture); assertTrue(applied.isEmpty())
        val action = compose.onNodeWithText("Uygula").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithText("Vazgeç").performClick()
        compose.runOnIdle { request = "editor-two"; open = true; gesture = false; draft = 70 }
        compose.runOnIdle { action() }; assertTrue(applied.isEmpty())
        compose.onNodeWithText("Kaydırarak parlaklık").performScrollTo().performClick()
        compose.onNodeWithText("Uygula").performClick(); assertEquals(listOf(70 to true), applied)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `gesture editor at narrow double type keeps toggle help and apply cancel readable`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { ReaderBrightnessDialog(true, 50, {}, {}, {}, true, {}) } }
        compose.onNodeWithText("Kaydırarak parlaklık").performScrollTo().assertIsDisplayed(); readable("Kaydırarak parlaklık")
        listOf("Uygula", "Vazgeç").forEach { compose.onNodeWithText(it).assertIsDisplayed(); readable(it) }
        capture("brightness-gesture-narrow-double-type")
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `gesture editor short landscape double type keeps switch and fixed final actions reachable`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { ReaderBrightnessDialog(true, 50, {}, {}, {}, true, {}) } }
        compose.onNodeWithText("Kaydırarak parlaklık").performScrollTo().assertIsDisplayed(); readable("Kaydırarak parlaklık")
        listOf("Uygula", "Vazgeç").forEach { compose.onNodeWithText(it).assertIsDisplayed(); readable(it) }
        capture("brightness-gesture-landscape-double-type")
    }

    private fun capture(name: String) = compose.runOnIdle {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            view.draw(android.graphics.Canvas(bitmap))
            val directory = java.io.File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun readable(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("No text layout for $label", layout.lineCount > 0 && layout.size.width > 0)
        assertFalse("Ellipsized action: $label", (0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals("Incomplete action text: $label", layout.layoutInput.text.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
}

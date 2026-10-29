@file:Suppress("DEPRECATION")

package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.feature.reader.EpubImageFilterDialog
import com.yaprak.reader.reader.EpubImageFilterMode
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
class EpubImageFilterDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `closed dialog has no controls and original mode applies explicitly when opened`() {
        var open by mutableStateOf(false)
        val applied = mutableListOf<EpubImageFilterMode>()
        compose.setContent { YaprakTheme {
            EpubImageFilterDialog(open, EpubImageFilterMode.ORIGINAL, {},
                { applied += it; open = false }, { open = false })
        } }
        listOf("Koyu tema görselleri", "Özgün görseller", "Karart", "Renkleri ters çevir",
            "Uygula", "Vazgeç").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.runOnIdle { open = true }
        compose.onNodeWithText("Özgün görseller").assertIsSelected()
        compose.onNodeWithText("Uygula").performClick()
        assertEquals(listOf(EpubImageFilterMode.ORIGINAL), applied)
        compose.onNodeWithText("Koyu tema görselleri").assertDoesNotExist()
    }

    @Test fun `all choices edit a single selected draft and apply only on explicit confirmation`() {
        var open by mutableStateOf(true)
        var draft by mutableStateOf(EpubImageFilterMode.ORIGINAL)
        val edits = mutableListOf<EpubImageFilterMode>()
        val applied = mutableListOf<EpubImageFilterMode>()
        var dismisses = 0
        compose.setContent { YaprakTheme {
            EpubImageFilterDialog(open, draft, { draft = it; edits += it },
                { applied += it; open = false }, { dismisses++; open = false })
        } }
        choices.forEach { (mode, label) ->
            compose.onNodeWithText(label).performScrollTo().performClick().assertIsSelected()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            assertEquals(mode, draft)
            choices.filter { it.first != mode }.forEach { (_, other) ->
                compose.onNodeWithText(other).assertIsNotSelected()
            }
            assertTrue(applied.isEmpty())
        }
        assertEquals(choices.map { it.first }, edits)
        compose.onNodeWithText("Uygula").performClick()
        assertEquals(listOf(EpubImageFilterMode.INVERT), applied)
        assertEquals(0, dismisses)
        compose.onNodeWithText("Uygula").assertDoesNotExist()
    }

    @Test fun `cancel discards the draft and reopening restores the applied mode`() {
        var open by mutableStateOf(true)
        var selected by mutableStateOf(EpubImageFilterMode.DARKEN)
        var draft by mutableStateOf(selected)
        var applications = 0
        var dismisses = 0
        compose.setContent { YaprakTheme {
            EpubImageFilterDialog(open, draft, { draft = it }, {
                selected = it; applications++; open = false
            }, { draft = selected; dismisses++; open = false })
        } }
        compose.onNodeWithText("Renkleri ters çevir").performScrollTo().performClick()
        assertEquals(EpubImageFilterMode.INVERT, draft)
        assertEquals(EpubImageFilterMode.DARKEN, selected)
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(0, applications)
        assertEquals(1, dismisses)
        assertEquals(EpubImageFilterMode.DARKEN, selected)
        compose.onNodeWithText("Koyu tema görselleri").assertDoesNotExist()
        compose.runOnIdle { open = true }
        compose.onNodeWithText("Karart").performScrollTo().assertIsSelected()
    }

    @Test fun `returning to original images stays a draft until apply`() {
        var draft by mutableStateOf(EpubImageFilterMode.INVERT)
        var selected = EpubImageFilterMode.INVERT
        var applications = 0
        compose.setContent { YaprakTheme {
            EpubImageFilterDialog(true, draft, { draft = it }, { selected = it; applications++ }, {})
        } }
        compose.onNodeWithText("Özgün görseller").performScrollTo().performClick().assertIsSelected()
        assertEquals(EpubImageFilterMode.ORIGINAL, draft)
        assertEquals(EpubImageFilterMode.INVERT, selected)
        assertEquals(0, applications)
        compose.onNodeWithText("Uygula").performClick()
        assertEquals(EpubImageFilterMode.ORIGINAL, selected)
        assertEquals(1, applications)
    }

    @Test fun `Android back dismisses without applying the edited filter`() {
        var open by mutableStateOf(true)
        var draft by mutableStateOf(EpubImageFilterMode.ORIGINAL)
        var dismisses = 0
        var applications = 0
        compose.setContent { YaprakTheme {
            EpubImageFilterDialog(open, draft, { draft = it }, { applications++ }, {
                dismisses++; open = false
            })
        } }
        compose.onNodeWithText("Karart").performScrollTo().performClick()
        compose.runOnUiThread { requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed() }
        compose.onNodeWithText("Koyu tema görselleri").assertDoesNotExist()
        assertEquals(1, dismisses)
        assertEquals(0, applications)
    }

    @Test fun `failed apply keeps the chosen draft visible and allows explicit retry`() {
        var open by mutableStateOf(true)
        var draft by mutableStateOf(EpubImageFilterMode.ORIGINAL)
        var selected = EpubImageFilterMode.ORIGINAL
        var failed by mutableStateOf(false)
        val attempts = mutableListOf<EpubImageFilterMode>()
        compose.setContent { YaprakTheme {
            EpubImageFilterDialog(open, draft, { draft = it }, {
                attempts += it
                if (attempts.size == 1) failed = true else { selected = it; open = false }
            }, { open = false }, failed)
        } }
        compose.onNodeWithText("Renkleri ters çevir").performScrollTo().performClick()
        compose.onNodeWithText("Uygula").performClick()
        compose.onNodeWithText(failureNotice).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Renkleri ters çevir").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Uygula").assertIsEnabled()
        assertEquals(EpubImageFilterMode.INVERT, draft)
        assertEquals(EpubImageFilterMode.ORIGINAL, selected)
        compose.onNodeWithText("Uygula").performClick()
        assertEquals(listOf(EpubImageFilterMode.INVERT, EpubImageFilterMode.INVERT), attempts)
        assertEquals(EpubImageFilterMode.INVERT, selected)
        compose.onNodeWithText("Koyu tema görselleri").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short screen at double font scale keeps choices notices and actions readable`() {
        var open by mutableStateOf(true)
        var draft by mutableStateOf(EpubImageFilterMode.ORIGINAL)
        val applied = mutableListOf<EpubImageFilterMode>()
        var dismisses = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme {
            EpubImageFilterDialog(open, draft, { draft = it }, { applied += it; open = false }, {
                dismisses++; open = false
            }, failed = true)
        } }
        compose.onNodeWithText("Koyu tema görselleri", useUnmergedTree = true).assertFontScale(2f)
        readable("Koyu tema görselleri")
        val notices = listOf(
            "Seçim Uygula'dan sonra yalnız bu okuyucu oturumunda geçerli olur. Kitabı yeniden açtığında özgün görseller kullanılır.",
            "Filtre yalnız etkin Koyu temada uygulanır. Bu tema genel ayardan, kitaba özel temadan veya koyu Sistem temasından gelebilir.",
            "Karart ve Renkleri ters çevir, görsel ve diyagram renklerini değiştirir.",
            failureNotice,
        )
        notices.forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertFontScale(2f)
            readable(label)
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        choices.forEach { (mode, label) ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertFontScale(2f)
            readable(label)
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
            assertEquals(mode, draft)
        }
        listOf("Uygula", "Vazgeç").forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertFontScale(2f)
            readable(label)
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        assertTrue(applied.isEmpty())
        compose.onNodeWithText("Uygula").performClick()
        assertEquals(listOf(EpubImageFilterMode.INVERT), applied)
        compose.runOnIdle { open = true }
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(1, dismisses)
    }

    private fun readable(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("No text layout for $label", layout.lineCount > 0 && layout.size.width > 0)
        assertFalse("Ellipsized text: $label", (0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals("Incomplete text: $label", layout.layoutInput.text.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    private companion object {
        val choices = listOf(
            EpubImageFilterMode.ORIGINAL to "Özgün görseller",
            EpubImageFilterMode.DARKEN to "Karart",
            EpubImageFilterMode.INVERT to "Renkleri ters çevir",
        )
        const val failureNotice = "Görsel filtresi uygulanamadı. Seçimin korunuyor; Uygula ile yeniden deneyebilir veya vazgeçebilirsin."
    }
}

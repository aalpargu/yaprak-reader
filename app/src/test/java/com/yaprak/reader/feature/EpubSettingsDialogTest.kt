package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.feature.settings.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import org.junit.After
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
class EpubSettingsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun restoreFontScale() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun `large type dialog allows theme editing scrolling reset and explicit save`() {
        var state by mutableStateOf(EpubSettingsState(loading = false, open = true))
        var saves = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme {
                EpubSettingsDialog(state, { state = state.copy(draft = it) }, { saves++ }, {},
                    { state = state.copy(draft = EpubSettings()) }, {})
            }
        }
        compose.onNodeWithText("Okuma görünümü").assertFontScale(1.5f)
        compose.onNodeWithText("Sepya").performScrollTo().performClick()
        assertEquals(ReadingTheme.SEPIA, state.draft.theme)
        assertEquals(0, saves)
        compose.onNodeWithText("Varsayılanlara dön").performScrollTo().performClick()
        assertEquals(EpubSettings(), state.draft)
        compose.onNodeWithText("Kaydet").assertIsDisplayed().performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("Vazgeç").assertIsDisplayed()
        compose.runOnIdle {
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "epub-settings-large-type.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun `read errors expose retry and prevent writing unknown settings`() {
        var retries = 0
        compose.setContent { YaprakTheme {
            EpubSettingsDialog(EpubSettingsState(loading = false, loadFailed = true, open = true), {}, {}, {}, {}, { retries++ })
        } }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Sayfalı").assertDoesNotExist()
        compose.onNodeWithText("Dikey kaydırma").assertDoesNotExist()
        ReadingTextAlignment.entries.forEach { alignmentNode(it).assertDoesNotExist() }
        compose.onNodeWithText("Yeniden yükle").performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test fun `reading mode edits stay draft until save and cancel restores saved mode`() {
        var state by mutableStateOf(EpubSettingsState(loading = false, open = true))
        var saves = 0
        var dismisses = 0
        compose.setContent { YaprakTheme {
            EpubSettingsDialog(state, { state = state.copy(draft = it) }, {
                saves++; state = state.copy(saved = state.draft, open = false)
            }, {
                dismisses++; state = state.copy(draft = state.saved, open = false)
            }, { state = state.copy(draft = EpubSettings()) }, {})
        } }
        compose.onNodeWithText("Sayfalı").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Dikey kaydırma").performScrollTo().performClick().assertIsSelected()
        assertTrue(state.draft.scroll)
        assertFalse(state.saved.scroll)
        assertEquals(0, saves)
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(1, dismisses)
        assertFalse(state.saved.scroll)
        compose.runOnIdle { state = state.copy(open = true, draft = state.saved) }
        compose.onNodeWithText("Sayfalı").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Dikey kaydırma").performScrollTo().performClick()
        compose.onNodeWithText("Kaydet").performClick()
        assertEquals(1, saves)
        assertTrue(state.saved.scroll)
        compose.onNodeWithText("Okuma görünümü").assertDoesNotExist()
    }

    @Test fun `accessible alignment choices edit only the draft until save and cancel restores saved choice`() {
        val initial = EpubSettings(fontSizePercent = 125, font = ReadingFont.SERIF,
            lineHeightPercent = 150, marginsPercent = 120, theme = ReadingTheme.SEPIA, scroll = true)
        var state by mutableStateOf(EpubSettingsState(saved = initial, draft = initial, loading = false, open = true))
        var saves = 0
        var dismisses = 0
        compose.setContent { YaprakTheme {
            EpubSettingsDialog(state, { state = state.copy(draft = it) }, {
                saves++; state = state.copy(saved = state.draft, open = false)
            }, {
                dismisses++; state = state.copy(draft = state.saved, open = false)
            }, { state = state.copy(draft = EpubSettings()) }, {})
        } }
        alignmentNode(ReadingTextAlignment.PUBLISHER).performScrollTo().assertIsSelected()
        alignmentNode(ReadingTextAlignment.START).performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
        alignmentNode(ReadingTextAlignment.PUBLISHER).assertIsNotSelected()
        alignmentNode(ReadingTextAlignment.JUSTIFY).assertIsNotSelected()
        assertEquals(initial.copy(textAlignment = ReadingTextAlignment.START), state.draft)
        assertEquals(initial, state.saved)
        assertEquals(0, saves)
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(1, dismisses)
        assertEquals(initial, state.draft)
        assertEquals(initial, state.saved)
        compose.runOnIdle { state = state.copy(open = true, draft = state.saved) }
        alignmentNode(ReadingTextAlignment.PUBLISHER).performScrollTo().assertIsSelected()
        alignmentNode(ReadingTextAlignment.JUSTIFY).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Kaydet").performClick()
        assertEquals(1, saves)
        assertEquals(initial.copy(textAlignment = ReadingTextAlignment.JUSTIFY), state.saved)
        compose.onNodeWithText("Okuma görünümü").assertDoesNotExist()
    }

    @Test fun `alignment reset is staged and can be cancelled or saved as publisher layout`() {
        val initial = EpubSettings(textAlignment = ReadingTextAlignment.JUSTIFY)
        var state by mutableStateOf(EpubSettingsState(saved = initial, draft = initial, loading = false, open = true))
        var saves = 0
        compose.setContent { YaprakTheme {
            EpubSettingsDialog(state, { state = state.copy(draft = it) }, {
                saves++; state = state.copy(saved = state.draft, open = false)
            }, { state = state.copy(draft = state.saved, open = false) },
                { state = state.copy(draft = EpubSettings()) }, {})
        } }
        alignmentNode(ReadingTextAlignment.JUSTIFY).performScrollTo().assertIsSelected()
        alignmentNode(ReadingTextAlignment.START).performScrollTo().performClick()
        compose.onNodeWithText("Varsayılanlara dön").performScrollTo().performClick()
        alignmentNode(ReadingTextAlignment.PUBLISHER).performScrollTo().assertIsSelected()
        assertEquals(EpubSettings(), state.draft)
        assertEquals(initial, state.saved)
        assertEquals(0, saves)
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(initial, state.draft)
        compose.runOnIdle { state = state.copy(open = true, draft = state.saved) }
        alignmentNode(ReadingTextAlignment.JUSTIFY).performScrollTo().assertIsSelected()
        compose.onNodeWithText("Varsayılanlara dön").performScrollTo().performClick()
        compose.onNodeWithText("Kaydet").performClick()
        assertEquals(1, saves)
        assertEquals(ReadingTextAlignment.PUBLISHER, state.saved.textAlignment)
    }

    @Test fun `failed alignment save retains selected draft and saving prevents edits until retry succeeds`() {
        var state by mutableStateOf(EpubSettingsState(loading = false, open = true))
        var attempts = 0
        var edits = 0
        compose.setContent { YaprakTheme {
            EpubSettingsDialog(state, { edits++; state = state.copy(draft = it, saveFailed = false) }, {
                attempts++
                state = if (attempts == 1) state.copy(saveFailed = true)
                    else state.copy(saved = state.draft, open = false, saveFailed = false)
            }, {}, {}, {})
        } }
        alignmentNode(ReadingTextAlignment.JUSTIFY).performScrollTo().performClick()
        compose.onNodeWithText("Kaydet").performClick()
        compose.onNodeWithText("Ayarlar kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.")
            .performScrollTo().assertIsDisplayed()
        alignmentNode(ReadingTextAlignment.JUSTIFY).performScrollTo().assertIsSelected().assertIsEnabled()
        assertTrue(state.open)
        assertEquals(ReadingTextAlignment.PUBLISHER, state.saved.textAlignment)
        assertEquals(ReadingTextAlignment.JUSTIFY, state.draft.textAlignment)
        compose.runOnIdle { state = state.copy(saving = true) }
        ReadingTextAlignment.entries.forEach { alignmentNode(it).performScrollTo().assertIsNotEnabled() }
        alignmentNode(ReadingTextAlignment.START).performClick()
        assertEquals(1, edits)
        assertEquals(ReadingTextAlignment.JUSTIFY, state.draft.textAlignment)
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(saving = false) }
        compose.onNodeWithText("Kaydet").assertIsEnabled().performClick()
        assertEquals(2, attempts)
        assertEquals(ReadingTextAlignment.JUSTIFY, state.saved.textAlignment)
        compose.onNodeWithText("Okuma görünümü").assertDoesNotExist()
    }

    @Test fun `loading hides reading modes and saving disables both choices while failed save retains draft`() {
        var state by mutableStateOf(EpubSettingsState(loading = true, open = true))
        var edits = 0
        compose.setContent { YaprakTheme {
            EpubSettingsDialog(state, { edits++; state = state.copy(draft = it, saveFailed = false) }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Sayfalı").assertDoesNotExist()
        compose.onNodeWithText("Dikey kaydırma").assertDoesNotExist()
        ReadingTextAlignment.entries.forEach { alignmentNode(it).assertDoesNotExist() }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.runOnIdle {
            state = state.copy(loading = false, draft = EpubSettings(scroll = true), saveFailed = true)
        }
        compose.onNodeWithText("Dikey kaydırma").performScrollTo().assertIsSelected().assertIsEnabled()
        compose.onNodeWithText("Ayarlar kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.")
            .performScrollTo().assertIsDisplayed()
        assertFalse(state.saved.scroll)
        compose.onNodeWithText("Sayfalı").performScrollTo().performClick().assertIsSelected()
        assertFalse(state.draft.scroll)
        assertFalse(state.saveFailed)
        assertEquals(1, edits)
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithText("Sayfalı").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Dikey kaydırma").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Varsayılanlara dön").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
        assertEquals(1, edits)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `double font short screen keeps modes alignment help last controls save and cancel reachable`() {
        var state by mutableStateOf(EpubSettingsState(loading = false, open = true))
        var saves = 0
        var dismisses = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme {
            EpubSettingsDialog(state, { state = state.copy(draft = it) }, { saves++ }, { dismisses++ },
                { state = state.copy(draft = EpubSettings()) }, {})
        } }
        compose.onNodeWithText("Okuma biçimi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Metin hizalaması").performScrollTo().assertIsDisplayed().assertFontScale(2f)
        ReadingTextAlignment.entries.forEach { alignment ->
            alignmentNode(alignment).performScrollTo().assertIsDisplayed()
            readable(alignmentTextNode(alignment).assertFontScale(2f))
        }
        listOf(directionHelp, alignmentHelp).forEach { label ->
            val node = compose.onNodeWithText(label)
            node.performScrollTo().assertIsDisplayed().assertFontScale(2f)
            readable(node)
        }
        alignmentNode(ReadingTextAlignment.START).performScrollTo().performClick().assertIsSelected()
        assertEquals(ReadingTextAlignment.START, state.draft.textAlignment)
        assertEquals(ReadingTextAlignment.PUBLISHER, state.saved.textAlignment)
        compose.onNodeWithText("Kenar boşluğu · %100").performScrollTo().assertIsDisplayed().assertFontScale(2f)
        readable("Kenar boşluğu · %100")
        listOf("Sayfalı", "Dikey kaydırma", "Varsayılanlara dön").forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertFontScale(2f)
            readable(label)
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Dikey kaydırma").performScrollTo().performClick().assertIsSelected()
        assertTrue(state.draft.scroll)
        assertFalse(state.saved.scroll)
        compose.onNodeWithText("Varsayılanlara dön").performScrollTo().performClick()
        assertFalse(state.draft.scroll)
        assertEquals(ReadingTextAlignment.PUBLISHER, state.draft.textAlignment)
        alignmentNode(ReadingTextAlignment.PUBLISHER).performScrollTo().assertIsSelected()
        compose.onNodeWithText("Sayfalı").performScrollTo().assertIsSelected()
        listOf("Kaydet", "Vazgeç").forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertFontScale(2f)
            readable(label)
            compose.onNodeWithText(label).assertIsDisplayed().performClick()
        }
        assertEquals(1, saves)
        assertEquals(1, dismisses)
    }

    private fun readable(label: String) {
        readable(compose.onNodeWithText(label, useUnmergedTree = true))
    }

    private fun readable(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("No text layout", layout.lineCount > 0 && layout.size.width > 0)
        assertFalse("Ellipsized text", (0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals("Incomplete text", layout.layoutInput.text.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    private fun alignmentNode(alignment: ReadingTextAlignment) =
        compose.onNodeWithContentDescription("Metin hizalaması: ${alignmentLabel(alignment)}")

    private fun alignmentTextNode(alignment: ReadingTextAlignment) = compose.onNode(
        hasText(alignmentLabel(alignment)) and hasAnyAncestor(
            hasContentDescription("Metin hizalaması: ${alignmentLabel(alignment)}")), useUnmergedTree = true)

    private fun alignmentLabel(alignment: ReadingTextAlignment) = when (alignment) {
        ReadingTextAlignment.PUBLISHER -> "Yayıncı düzeni"
        ReadingTextAlignment.START -> "Metin yönüne göre"
        ReadingTextAlignment.JUSTIFY -> "İki yana yasla"
    }

    private companion object {
        const val directionHelp = "Metin yönüne göre, kitabın yazı yönünü izler: soldan sağa metinde sola, sağdan sola metinde sağa hizalar."
        const val alignmentHelp = "Özel hizalama bazı paragraf ve başlık stillerini değiştirir; iki yana yaslama sözcükleri heceleyebilir. Seçim yalnız desteklenen yatay metin düzenlerinde uygulanır."
    }
}

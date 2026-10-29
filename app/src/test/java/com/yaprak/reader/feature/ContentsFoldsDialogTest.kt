package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.domain.reader.BookContents
import com.yaprak.reader.domain.reader.ContentsEntry
import com.yaprak.reader.feature.reader.ContentsDialog
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
class ContentsFoldsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `fold button hides its subtree without navigating and title navigation keeps original identity`() {
        val contents = contents(parentNavigable = true)
        val selected = mutableListOf<String>()
        var dismissed = 0
        compose.setContent { YaprakTheme { ContentsDialog(contents,
            { selected += it; it == "opaque-child" }, { dismissed++ }) } }
        revealTag("contents-fold-parent").performClick()
        assertTrue(selected.isEmpty())
        listOf("first", "sub", "opaque-child").forEach {
            compose.onNodeWithTag("contents-entry-$it").assertDoesNotExist()
        }
        revealTag("contents-entry-last").assertIsDisplayed()
        revealTag("contents-entry-parent").performClick()
        assertEquals(listOf("parent"), selected)
        assertEquals(0, dismissed)
        revealText(ERROR).assertIsDisplayed()
        revealTag("contents-fold-parent").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Daraltılmış"))
        compose.onNodeWithTag("contents-entry-opaque-child").assertDoesNotExist()
        revealTag("contents-fold-parent").performClick()
        revealTag("contents-entry-opaque-child").performClick()
        assertEquals(listOf("parent", "opaque-child"), selected)
        assertEquals(1, dismissed)
    }

    @Test fun `non navigable branch can fold with accessible state while disabled leaf has no fold action`() {
        val contents = BookContents(listOf(
            ContentsEntry("parent", "Kısım", 0, false),
            ContentsEntry("child", "Çocuk", 1, true),
            ContentsEntry("leaf", "Bağlantısız başlık", 0, false),
        ))
        val selected = mutableListOf<String>()
        compose.setContent { YaprakTheme { ContentsDialog(contents, { selected += it; false }, {}) } }
        revealTag("contents-entry-parent").assertIsNotEnabled().performClick()
        revealTag("contents-fold-parent").assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Açık"))
        compose.onNodeWithContentDescription("Kısım: alt başlıkları daralt").performClick()
        compose.onNodeWithTag("contents-entry-child").assertDoesNotExist()
        compose.onNodeWithContentDescription("Kısım: alt başlıkları aç").assertIsEnabled()
        revealTag("contents-entry-leaf").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("contents-fold-leaf").assertDoesNotExist()
        assertTrue(selected.isEmpty())
    }

    @Test fun `search reveals collapsed descendants hides folding and clearing restores the stored fold`() {
        val contents = contents()
        val selected = mutableListOf<String>()
        var dismissed = 0
        compose.setContent { YaprakTheme { ContentsDialog(contents,
            { selected += it; false }, { dismissed++ }) } }
        revealTag("contents-fold-parent").performClick()
        revealTag("contents-search").performTextReplacement("derin")
        revealText("1 / 5 başlık").assertIsDisplayed()
        revealTag("contents-entry-opaque-child").performClick()
        assertEquals(listOf("opaque-child"), selected)
        assertEquals(0, dismissed)
        revealText(ERROR).assertIsDisplayed()
        revealTag("contents-search").assertTextContains("derin").performTextReplacement("kısım")
        revealTag("contents-entry-parent").assertIsNotEnabled()
        compose.onNodeWithTag("contents-fold-parent").assertDoesNotExist()
        compose.onNodeWithText("Tümünü daralt").assertDoesNotExist()
        compose.onNodeWithText("Tümünü aç").assertDoesNotExist()
        revealText("Temizle").performClick()
        revealTag("contents-fold-parent").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Daraltılmış"))
        compose.onNodeWithTag("contents-entry-opaque-child").assertDoesNotExist()
        compose.onNodeWithText(ERROR).assertDoesNotExist()
    }

    @Test fun `bulk folding retains nested folds and closing then reopening starts fully expanded`() {
        val contents = contents()
        var open by mutableStateOf(true)
        var navigations = 0
        compose.setContent { YaprakTheme {
            if (open) ContentsDialog(contents, { navigations++; false }, { open = false })
        } }
        revealText("Tümünü daralt").performClick()
        compose.onNodeWithTag("contents-entry-first").assertDoesNotExist()
        revealTag("contents-fold-parent").performClick()
        revealTag("contents-fold-sub").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Daraltılmış"))
        compose.onNodeWithTag("contents-entry-opaque-child").assertDoesNotExist()
        revealText("Tümünü aç").performClick()
        revealTag("contents-entry-opaque-child").assertIsDisplayed()
        revealText("Tümünü daralt").performClick()
        compose.onNodeWithText("Kapat").performClick()
        compose.onNodeWithText("İçindekiler").assertDoesNotExist()
        compose.runOnIdle { open = true }
        revealTag("contents-entry-opaque-child").assertIsDisplayed()
        assertEquals(0, navigations)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `large font landscape reaches fold controls end navigation and rejection feedback`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        val entries = listOf(ContentsEntry("parent", "Uzun bir kitabın ilk kısmı", 0, false)) +
            (1..30).map { ContentsEntry("chapter-$it", "Bölüm $it", 1, true) } +
            ContentsEntry("last", "Son bölüm", 0, true)
        var selected: String? = null
        var closed = false
        compose.setContent { YaprakTheme { ContentsDialog(BookContents(entries, truncated = true),
            { selected = it; false }, { closed = true }) } }
        compose.onNodeWithText("İçindekiler").assertFontScale(1.5f)
        revealTag("contents-search").assertIsDisplayed()
        revealText("Tümünü daralt").assertIsDisplayed().performClick()
        revealTag("contents-fold-parent").assertIsDisplayed().assertIsEnabled().performClick()
        revealTag("contents-entry-chapter-30").assertIsDisplayed().performClick()
        assertEquals("chapter-30", selected)
        revealText(ERROR).assertIsDisplayed()
        revealText("Liste çok uzun; ilk 5.000 başlık gösteriliyor.").assertIsDisplayed()
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertTrue(closed)
    }

    private fun revealTag(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("contents-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun revealText(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("contents-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    private fun contents(parentNavigable: Boolean = false) = BookContents(listOf(
        ContentsEntry("parent", "Kısım", 0, parentNavigable),
        ContentsEntry("first", "Başlangıç", 1, true),
        ContentsEntry("sub", "Alt bölüm", 1, true),
        ContentsEntry("opaque-child", "Derin hedef", 2, true),
        ContentsEntry("last", "Son bölüm", 0, true),
    ))

    private companion object {
        const val ERROR = "Bu başlığa geçilemedi. Yeniden deneyebilir veya başka bir başlık seçebilirsin."
    }
}

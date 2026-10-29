package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.yaprak.reader.domain.reader.BookContents
import com.yaprak.reader.domain.reader.ContentsEntry
import com.yaprak.reader.feature.reader.ContentsDialog
import com.yaprak.reader.feature.reader.ContentsDialogBody
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
class ContentsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `accepted selection uses opaque identity and dismisses while rejected selection shows error`() {
        val contents = BookContents(listOf(ContentsEntry("group", "Kısım", 0, false),
            ContentsEntry("first", "Başlangıç", 1, true), ContentsEntry("last", "Son bölüm", 1, true)))
        var selected: String? = null
        var dismissed = 0
        compose.setContent { YaprakTheme { ContentsDialog(contents, { selected = it; it == "last" }, { dismissed++ }) } }
        compose.onNodeWithText("Kısım").assertIsNotEnabled()
        compose.onNodeWithText("Başlangıç").performClick()
        assertEquals("first", selected)
        assertEquals(0, dismissed)
        compose.onNodeWithText("Bu başlığa geçilemedi. Yeniden deneyebilir veya başka bir başlık seçebilirsin.").assertIsDisplayed()
        compose.onNodeWithText("Son bölüm").performScrollTo().performClick()
        assertEquals("last", selected)
        assertEquals(1, dismissed)
    }

    @Test
    fun `missing TOC is explicit and dismiss does not trigger navigation`() {
        var dismissed = 0
        compose.setContent { YaprakTheme { ContentsDialog(BookContents(emptyList()), { error("Empty contents cannot navigate") }, { dismissed++ }) } }
        compose.onNodeWithText("Bu kitap içindekiler listesi sunmuyor. Okuma ekranında sayfalar arasında ilerleyebilirsin.").assertIsDisplayed()
        compose.onNodeWithText("Kapat").performClick()
        assertEquals(1, dismissed)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `TOC search keeps original identities and distinguishes empty results from missing TOC`() {
        val contents = BookContents(listOf(
            ContentsEntry("group", "Kısım", 0, false),
            ContentsEntry("first", "Giriş", 1, true),
            ContentsEntry("middle", "İstanbul Defteri", 1, true),
            ContentsEntry("last", "Son bölüm", 0, true),
        ), truncated = true)
        var selected: String? = null
        var dismissed = 0
        compose.setContent { YaprakTheme { ContentsDialogBody(contents,
            { selected = it; it == "last" }, { dismissed++ }) } }

        compose.onNodeWithTag("contents-search").performTextReplacement("istanbul")
        compose.onNodeWithText("1 / 4 başlık").assertIsDisplayed()
        compose.onNodeWithText("İstanbul Defteri").performClick()
        assertEquals("middle", selected)
        assertEquals(0, dismissed)
        compose.onNodeWithText("Bu başlığa geçilemedi. Yeniden deneyebilir veya başka bir başlık seçebilirsin.").assertIsDisplayed()
        compose.onNodeWithText("Liste çok uzun; ilk 5.000 başlık gösteriliyor.").assertExists()

        compose.onNodeWithTag("contents-search").performTextReplacement("bulunmayan")
        compose.onNodeWithText("Eşleşen başlık bulunamadı.").assertIsDisplayed()
        compose.onNodeWithText("0 / 4 başlık").assertIsDisplayed()
        compose.onNodeWithText("Bu başlığa geçilemedi. Yeniden deneyebilir veya başka bir başlık seçebilirsin.").assertDoesNotExist()

        compose.onNodeWithTag("contents-search").performTextReplacement("x".repeat(201))
        compose.onNodeWithTag("contents-search").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("x".repeat(200))))
        compose.onNodeWithText("Temizle").performClick()
        compose.onNodeWithText("Kısım").assertIsNotEnabled()
        compose.onNodeWithTag("contents-search").performTextReplacement("son bölüm")
        compose.onNodeWithText("Son bölüm").performClick()
        assertEquals("last", selected)
        assertEquals(1, dismissed)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-mdpi")
    fun `landscape large type can reach last chapter and rejection message`() {
        val contents = BookContents((1..30).map { ContentsEntry("$it", "Bölüm $it", 0, true) }, truncated = true)
        var selected: String? = null
        var closed = false
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { ContentsDialog(contents, { selected = it; false }, { closed = true }) } }
        compose.onNodeWithText("İçindekiler").assertFontScale(1.5f)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Bölüm 30"))
        compose.onNodeWithText("Bölüm 30").performClick()
        assertEquals("30", selected)
        compose.onNodeWithText("Bu başlığa geçilemedi. Yeniden deneyebilir veya başka bir başlık seçebilirsin.")
            .assertIsDisplayed()
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertTrue(closed)
    }
}

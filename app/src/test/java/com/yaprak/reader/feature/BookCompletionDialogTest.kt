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
class BookCompletionDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }

    @Test fun sourceDateZoneAndCurrentTitleAreReadableAtDoubleFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        val event = com.yaprak.reader.domain.statistics.BookCompletionEvent(
            "11111111-1111-4111-8111-111111111111", "book", "a".repeat(64),
            java.time.ZonedDateTime.parse("2026-10-08T12:30:00+03:00[Europe/Istanbul]").toInstant().toEpochMilli(),
            "2026-10-08", "Europe/Istanbul")
        val snapshot = com.yaprak.reader.domain.statistics.BookCompletionSnapshot(1, listOf(
            com.yaprak.reader.domain.statistics.BookCompletionEntry(event, "Kitap başlığı", "Yazar")))
        var closed = 0
        compose.setContent { YaprakTheme { com.yaprak.reader.feature.statistics.BookCompletionDialog(
            com.yaprak.reader.feature.statistics.BookCompletionState(loading = false, snapshot = snapshot), {},
            { closed++ }) } }
        readable("Kitap bitirme geçmişi")
        compose.onNodeWithTag("completion-history").performScrollToNode(hasText("Kitap başlığı"))
        readable("Kitap başlığı")
        compose.onNodeWithTag("completion-history").performScrollToNode(hasText("8 Ekim 2026, 12:30"))
        readable("8 Ekim 2026, 12:30"); readable("Europe/Istanbul"); readable("Kapat")
        capture("completion-history-font2")
        compose.onNodeWithText("Kapat").performClick(); assertEquals(1, closed)
    }
    @Test fun loadingErrorRetryAndEmptyHistoryStayDistinctAndPausedActionsDoNothing() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var state by mutableStateOf(com.yaprak.reader.feature.statistics.BookCompletionState())
        var active by mutableStateOf(true)
        var retry = 0; var closed = 0
        compose.setContent { YaprakTheme { com.yaprak.reader.feature.statistics.BookCompletionDialog(
            state, { retry++ }, { closed++ }, { active }) } }
        compose.onNodeWithTag("completion-history").performScrollToNode(hasText("Geçmiş yükleniyor…"))
        compose.onNodeWithText("Henüz tarihli bitirme kaydı yok.").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false,
            failure = com.yaprak.reader.domain.statistics.BookCompletionFailure.SOURCE) }
        compose.onNodeWithTag("completion-history").performScrollToNode(hasText("Yeniden yükle"))
        compose.onNodeWithText("Kitap bitirme geçmişi").assertIsDisplayed()
        compose.onNodeWithText("Kapat").assertIsDisplayed()
        readable("Kitap bitirme geçmişi"); readable("Kapat")
        readable("Yeniden yükle")
        compose.runOnIdle { active = false }
        compose.onNodeWithText("Yeniden yükle").performClick(); compose.onNodeWithText("Kapat").performClick()
        assertEquals(0, retry); assertEquals(0, closed)
        compose.runOnIdle { active = true }
        compose.onNodeWithText("Yeniden yükle").performClick(); assertEquals(1, retry)
        compose.runOnIdle { state = state.copy(failure = null,
            snapshot = com.yaprak.reader.domain.statistics.BookCompletionSnapshot(0, emptyList())) }
        compose.onNodeWithTag("completion-history").performScrollToNode(hasText("Henüz tarihli bitirme kaydı yok."))
        readable("Henüz tarihli bitirme kaydı yok.")
    }
    @Test fun initialErrorWindowHasReadableTitleRetryAndCloseAtDoubleFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { com.yaprak.reader.feature.statistics.BookCompletionDialog(
            com.yaprak.reader.feature.statistics.BookCompletionState(loading = false,
                failure = com.yaprak.reader.domain.statistics.BookCompletionFailure.SOURCE), {}, {}) } }
        compose.onNodeWithTag("completion-history").performScrollToNode(hasText("Yeniden yükle"))
        for (text in listOf("Kitap bitirme geçmişi", "Kitap bitirme geçmişi okunamadı.", "Yeniden yükle", "Kapat")) {
            compose.onNodeWithText(text).assertIsDisplayed(); readable(text)
        }
        capture("completion-history-failure-font2")
    }

    private fun readable(text: String) = checkLayout(compose.onNodeWithText(text, useUnmergedTree = true))
    private fun checkLayout(node: SemanticsNodeInteraction) {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse(layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineLeft(line) >= -0.5f && layout.getLineRight(line) <= layout.size.width + 0.5f)
        }
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

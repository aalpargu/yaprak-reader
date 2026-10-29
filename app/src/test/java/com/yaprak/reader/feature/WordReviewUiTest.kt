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
import com.yaprak.reader.domain.vocabulary.*
import com.yaprak.reader.feature.wordreview.*
import com.yaprak.reader.feature.librarywords.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val entry = LibraryWord(SavedWord("word", "leaf", "yaprak", 1), "book", "a".repeat(64), "Kitap", null)
    private val card = WordReviewCard(entry, null)
    private val now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
    private val event = planWordReview(WordReviewCommand(UUID.randomUUID().toString(), card, WordRating.GOOD), now, ZoneOffset.UTC)
    private val snapshot = WordReviewSnapshot(listOf(card), 1, 1, 1, listOf(WordReviewHistoryEntry(event, "Kitap", true)), 1)
    private val ready = WordReviewState(active = true, loading = false, snapshot = snapshot, practicing = true, revision = 1)
    @Test fun revealIsExplicitAndDisposedRatingCannotCreditANewCardOrAba() {
        var state by mutableStateOf(ready); var commands = 0; var ratings = 0
        compose.setContent { YaprakTheme { WordReviewScreen(state, { _, _ -> commands++ }, { _, _ -> ratings++ }, {}, {}, {}) } }
        compose.onNodeWithTag("review-answer").assertDoesNotExist()
        compose.onNodeWithText("yaprak").assertDoesNotExist() // History is hidden during practice.
        scroll("review-reveal").performClick(); assertEquals(1, commands); assertEquals(0, ratings)
        compose.runOnIdle { state = state.copy(revealed = true, revision = 2) }
        scroll("review-rate-GOOD")
        val stale = compose.onNodeWithTag("review-rate-GOOD").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(revision = 3) }
        compose.runOnIdle { stale() }; assertEquals(0, ratings)
        compose.runOnIdle { state = state.copy(revision = 4) }
        compose.runOnIdle { stale() }; assertEquals(0, ratings)
        scroll("review-rate-GOOD").performClick(); assertEquals(1, ratings)
        compose.runOnIdle { state = state.copy(busy = true, revision = 5) }
        scroll("review-rate-EASY").assertIsNotEnabled()
    }
    @Test fun narrowDoubleFontReachesAllRatingsAndFullReceiptAndPreviousContentHistory() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var state by mutableStateOf(ready.copy(revealed = true))
        compose.setContent { YaprakTheme { WordReviewScreen(state, { _, _ -> }, { _, _ -> }, {}, {}, {}) } }
        for ((tag, text) in listOf("AGAIN" to "Tekrar · 10 dakika sonra", "HARD" to "Zor · bir alt basamak",
            "GOOD" to "Hatırladım · bir üst basamak", "EASY" to "Kolay · iki üst basamak")) {
            scroll("review-rate-$tag").assertIsDisplayed(); readable(text)
        }
        readable("Kelimelere dön"); capture("word-review-ratings-font2")
        compose.runOnIdle { state = state.copy(practicing = false, receipt = event, revision = 2) }
        scroll("review-receipt").assertTextContains("Kaydedildi. Sonraki tekrar:", substring = true)
        compose.onNodeWithTag("review-list").performScrollToNode(hasText("Bu kayıt, kelimenin önceki içeriğine ait."))
        readable("Bu kayıt, kelimenin önceki içeriğine ait."); readable("Kelimelere dön")
        capture("word-review-history-font2")
    }
    @Test fun libraryEntryOpensReviewOnlyForActiveUndisposedActions() {
        var state by mutableStateOf(LibraryWordsState(active = true, loading = false, entries = listOf(entry), totalCount = 1, revision = 1))
        var opens = 0
        compose.setContent { YaprakTheme { LibraryWordsScreen(state, {}, {}, { _, _ -> }, { _, _ -> }, {}, { opens++ }) } }
        compose.onNodeWithTag("library-words-list").performScrollToNode(hasTestTag("word-review"))
        val stale = compose.onNodeWithTag("word-review").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(active = false, revision = 2) }
        compose.runOnIdle { stale() }; assertEquals(0, opens)
        compose.runOnIdle { state = state.copy(active = true, revision = 3) }
        compose.onNodeWithTag("library-words-list").performScrollToNode(hasTestTag("word-review"))
        compose.onNodeWithTag("word-review").performClick(); assertEquals(1, opens)
    }
    private fun scroll(tag: String) = compose.onNodeWithTag("review-list").performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }
    private fun readable(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, true))
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView; val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap)); val file = File(System.getProperty("yaprak.screenshotDir"), "$name.png")
            file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

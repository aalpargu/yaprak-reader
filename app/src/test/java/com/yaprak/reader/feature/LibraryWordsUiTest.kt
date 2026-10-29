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
import com.yaprak.reader.feature.librarywords.*
import com.yaprak.reader.feature.vocabulary.SavedWordPractice
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryWordsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val word = LibraryWord(SavedWord("one", "tree", "ağaç", 1), "book", "a".repeat(64), "Uzun kitap başlığı", "Deniz")
    private val ready = LibraryWordsState(entries = listOf(word), totalCount = 1, active = true, loading = false, revision = 1)

    @Test fun `practice answer is hidden and old rendered reveal cannot target a new card revision or ABA`() {
        var state by mutableStateOf(ready.copy(practice = SavedWordPractice.start(listOf(word.word))))
        var calls = 0
        compose.setContent { YaprakTheme { LibraryWordsScreen(state, {}, {}, { _, _ -> calls++ }, { _, _ -> }, {}) } }
        compose.onNodeWithTag("library-word-answer").assertDoesNotExist()
        scroll("word-reveal")
        val old = compose.onNodeWithTag("word-reveal").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(revision = 2) }
        compose.runOnIdle { old() }; assertEquals(0, calls)
        compose.runOnIdle { state = state.copy(revision = 3) }
        compose.runOnIdle { old() }; assertEquals(0, calls)
        scroll("word-reveal").performClick(); assertEquals(1, calls)
        compose.runOnIdle { state = state.copy(practice = state.practice!!.reveal(listOf(word.word)), revision = 4) }
        scroll("library-word-answer").assertTextEquals("ağaç")
        compose.onNodeWithTag("word-reveal").assertDoesNotExist()
    }

    @Test fun `narrow large text keeps full action label and fixed return visible`() {
        largeFont()
        compose.setContent { YaprakTheme { LibraryWordsScreen(ready, {}, {}, { _, _ -> }, { _, _ -> }, {}) } }
        val label = "Gösterilen kelimelerle flashcard pratiği"
        scroll("word-start").assertIsDisplayed()
        readable(label); readable("Kitaplığa dön")
        capture("library-words-narrow-double-type")
        scroll("word-open-one").assertIsDisplayed(); readable("Kelimenin kitabını aç")
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `landscape large type reaches answer navigation shuffle and fixed return without exposing loading records`() {
        largeFont()
        var state by mutableStateOf(ready.copy(practice = SavedWordPractice.start(listOf(word.word))))
        compose.setContent { YaprakTheme { LibraryWordsScreen(state, {}, {}, { _, _ -> }, { _, _ -> }, {}) } }
        listOf("word-reveal", "word-next", "word-shuffle", "word-stop").forEach { scroll(it).assertIsDisplayed() }
        readable("Türkçe anlamı göster"); readable("Kartları karıştır"); readable("Kitaplığa dön")
        capture("library-words-landscape-double-type")
        compose.runOnIdle { state = ready.copy(loading = true, revision = 2) }
        compose.onNodeWithTag("word-start").assertDoesNotExist()
        compose.onNodeWithTag("library-word-question").assertDoesNotExist()
    }

    private fun scroll(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag("library-words-list")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }
    private fun largeFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate() }
    private fun readable(label: String) {
        if (label != "Kitaplığa dön") compose.onNodeWithTag("library-words-list").performScrollToNode(hasText(label))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, true))
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            val file = File(System.getProperty("yaprak.screenshotDir"), "$name.png")
            file.parentFile!!.mkdirs(); file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}

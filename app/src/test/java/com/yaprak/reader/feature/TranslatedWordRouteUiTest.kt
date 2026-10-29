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
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.vocabulary.*
import com.yaprak.reader.feature.vocabulary.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
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
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslatedWordRouteUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("translated-book", "Title", null, BookFormat.EPUB, "book.epub", "book.epub", 1, "a".repeat(64), 1)
    private class Repository : SavedWordRepository {
        val entries = MutableStateFlow<List<SavedWord>>(emptyList())
        val added = mutableListOf<Pair<String, String>>()
        override fun observe(bookId: String, contentSha256: String) = entries
        override suspend fun add(bookId: String, contentSha256: String, english: String, turkish: String) {
            added += english to turkish; entries.value += SavedWord("one", english, turkish, 1)
        }
        override suspend fun update(bookId: String, contentSha256: String, id: String, english: String, turkish: String) { fail("no edit expected") }
        override suspend fun remove(bookId: String, contentSha256: String, id: String) { fail("no delete expected") }
    }
    @Test fun emptyFormGetsBothFieldsAndOnlyExplicitReviewedSaveWritesToTheRepository() {
        largeFont()
        val repo = Repository(); val draft = TranslatedWordDraft("Leaf", "yaprak")
        compose.setContent { YaprakTheme { SavedWordsRoute(book, repo, {}, prefillTranslation = draft) } }
        compose.waitForIdle()
        field("saved-word-english").assertTextContains("Leaf")
        field("saved-word-turkish").assertTextContains("yaprak")
        val help = "Bu taslak makine çevirisinden alındı. Türkçe karşılığını kontrol et ve gerekirse düzelt. Yalnız Kelimeyi kaydet ile kaydedilir."
        readable(help); capture("translated-word-form-font2")
        assertTrue(repo.added.isEmpty())
        field("saved-word-turkish").performTextReplacement("bitki yaprağı")
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasText("Kelimeyi kaydet"))
        compose.onNodeWithText("Kelimeyi kaydet").performScrollTo().performClick()
        compose.waitForIdle(); assertEquals(listOf("Leaf" to "bitki yaprağı"), repo.added)
    }
    @Test @Config(qualifiers = "w640dp-h320dp-mdpi")
    fun conflictingDraftIsPreservedAndObsoleteChoiceCannotReplaceTheNewIncomingDraftOrWrite() {
        largeFont()
        val repo = Repository(); lateinit var model: SavedWordsViewModel
        compose.runOnUiThread {
            model = SavedWordsViewModel(book.id, book.sha256, repo)
            compose.activity.viewModelStore.put("saved-words-${book.id}-${book.sha256}", model)
            model.editEnglish("Earlier"); model.editTurkish("önceki")
        }
        var draft by mutableStateOf(TranslatedWordDraft("Leaf", "yaprak"))
        compose.setContent { YaprakTheme { SavedWordsRoute(book, repo, {}, prefillTranslation = draft) } }
        compose.waitForIdle()
        assertEquals("Earlier", model.state.value.englishDraft); assertEquals("önceki", model.state.value.turkishDraft)
        val old = compose.onNodeWithTag("translation-draft-use").performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        readable("Çeviri taslağını kullan"); readable("Önceki taslağımı koru"); capture("translated-word-conflict-landscape-font2")
        compose.runOnIdle { draft = TranslatedWordDraft("Book", "kitap") }; compose.waitForIdle()
        compose.runOnIdle { old() }
        assertEquals("Earlier", model.state.value.englishDraft); assertEquals("önceki", model.state.value.turkishDraft)
        assertEquals("Book", model.state.value.pendingTranslationDraft!!.english); assertTrue(repo.added.isEmpty())
        compose.onNodeWithTag("translation-draft-keep").performScrollTo().performClick(); compose.waitForIdle()
        assertNull(model.state.value.pendingTranslationDraft); assertEquals("Earlier", model.state.value.englishDraft)
        assertFalse(model.state.value.translationDraft); assertTrue(repo.added.isEmpty())
    }
    private fun largeFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate() }
    private fun field(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun readable(value: String) {
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasText(value))
        val node = compose.onNodeWithText(value, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>(); node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale); assertFalse(layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.vocabulary.SavedWord
import com.yaprak.reader.domain.vocabulary.SavedWordRepository
import com.yaprak.reader.feature.vocabulary.SavedWordsDialog
import com.yaprak.reader.feature.vocabulary.SavedWordsRoute
import com.yaprak.reader.feature.vocabulary.SavedWordsState
import com.yaprak.reader.feature.vocabulary.SavedWordsViewModel
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
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
class SavedWordCopyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun normalFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) }
        compose.activityRule.scenario.recreate()
    }

    @After fun restoreFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun `route copies both current saved fields after search sort and update without changing drafts`() {
        val ada = SavedWord("ada", "Ada İ/ı 😀 e\u0301", "Ortak Ada karşılığı 🌿", 1)
        val zebra = SavedWord("zebra", "Zebra", "Ortak Zebra karşılığı", 2)
        val repository = Repository(listOf(zebra, ada, SavedWord("other", "Other", "Başka kayıt", 3)))
        var open by mutableStateOf(true)
        val copied = mutableListOf<String>()
        compose.setContent { YaprakTheme { if (open) SavedWordsRoute(book, repository,
            { open = false }, prefillEnglish = "Draftword", onCopy = { copied += it }) } }
        assertTrue(copied.isEmpty())
        scrollTo(hasTestTag("saved-word-turkish"))
        compose.onNodeWithTag("saved-word-turkish").performTextReplacement("Kaydedilmemiş Türkçe taslak")
        scrollTo(hasTestTag("saved-word-search"))
        compose.onNodeWithTag("saved-word-search").performTextReplacement("Ortak")
        scrollTo(hasTestTag("saved-word-sort"))
        compose.onNodeWithTag("saved-word-sort").performClick()
        compose.onNodeWithText("İngilizce A–Z").performClick()
        copyNode(ada.id).assert(hasContentDescription(description(1, ada))).performClick()
        assertEquals(listOf(pair(ada)), copied)
        compose.onNodeWithText(success).assertExists()
        val updated = ada.copy(english = "Ada güncel İ/ı e\u0301", turkish = "Ortak yeni Türkçe 漢字 😀 🌿")
        compose.runOnIdle { repository.entries.value = listOf(zebra, updated) }
        compose.onNodeWithText(success).assertDoesNotExist()
        copyNode(updated.id).assert(hasContentDescription(description(1, updated))).performClick()
        assertEquals(listOf(pair(ada), pair(updated)), copied)
        scrollTo(hasTestTag("saved-word-english"))
        compose.onNodeWithTag("saved-word-english")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Draftword")))
        scrollTo(hasTestTag("saved-word-turkish"))
        compose.onNodeWithTag("saved-word-turkish")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,
                AnnotatedString("Kaydedilmemiş Türkçe taslak")))
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { open = true }
        compose.onNodeWithText(success).assertDoesNotExist()
        assertEquals(0, repository.writes)
        assertEquals(listOf(zebra, updated), repository.entries.value)
        assertTrue(repository.observed.isNotEmpty())
        assertTrue(repository.observed.all { it == (book.id to book.sha256) })
    }

    @Test fun `failed copy retries exact Unicode pair and feedback clears when the record is removed`() {
        val word = SavedWord("unicode", "İ/ı 😀 e\u0301", "ışık 漢字 🌿\tkarşılık", 1)
        val repository = Repository(listOf(word))
        var failCopy = true
        val attempts = mutableListOf<String>()
        compose.setContent { YaprakTheme { SavedWordsRoute(book, repository, {}, onCopy = {
            attempts += it
            check(!failCopy) { "Clipboard failure" }
        }) } }
        copyNode(word.id).performClick()
        compose.onNodeWithText(failure).assertExists()
        compose.onNodeWithText(success).assertDoesNotExist()
        assertEquals(listOf(pair(word)), attempts)
        failCopy = false
        copyNode(word.id).performClick()
        compose.onNodeWithText(success).assertExists()
        compose.onNodeWithText(failure).assertDoesNotExist()
        assertEquals(listOf(pair(word), pair(word)), attempts)
        compose.runOnIdle { repository.entries.value = emptyList() }
        compose.onNodeWithText(success).assertDoesNotExist()
        compose.onNodeWithTag("saved-word-copy-${word.id}").assertDoesNotExist()
        assertEquals(0, repository.writes)
    }

    @Test fun `copy is optional gated by pending actions and absent until a successful load`() {
        val word = SavedWord("one", "leaf", "yaprak", 1)
        val loaded = SavedWordsState(entries = listOf(word), loading = false)
        var state by mutableStateOf(loaded)
        var callback by mutableStateOf<((String) -> Unit)?>(null)
        val copied = mutableListOf<String>()
        compose.setContent { YaprakTheme { CopyDialog(state, callback) } }
        compose.onNodeWithTag("saved-word-copy-one").assertDoesNotExist()
        compose.onNodeWithText(help).assertDoesNotExist()
        compose.runOnIdle { callback = { copied += it } }
        compose.onAllNodesWithText(help).assertCountEquals(1)
        copyNode(word.id).assertIsEnabled()
        listOf(loaded.copy(busy = true),
            loaded.copy(pendingEdit = word, editEnglishDraft = word.english, editTurkishDraft = word.turkish),
            loaded.copy(pendingDelete = word)).forEach { gated ->
            compose.runOnIdle { state = gated }
            compose.onNodeWithTag("saved-word-copy-one").assertIsNotEnabled()
            assertTrue(copied.isEmpty())
        }
        listOf(loaded.copy(loading = true), loaded.copy(loadFailed = true)).forEach { unavailable ->
            compose.runOnIdle { state = unavailable }
            compose.onNodeWithTag("saved-word-copy-one").assertDoesNotExist()
        }
        compose.runOnIdle { state = loaded }
        copyNode(word.id).performClick()
        assertEquals(listOf(word.id), copied)
    }

    @Test fun `stale route click rechecks pending edit and delete and never copies unsaved edit fields`() {
        val word = SavedWord("saved", "saved English İ e\u0301", "kayıtlı Türkçe 😀 🌿", 1)
        val repository = Repository(listOf(word))
        val copied = mutableListOf<String>()
        compose.setContent { YaprakTheme { SavedWordsRoute(book, repository, {}, onCopy = { copied += it }) } }
        val action = copyNode(word.id).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)
                .get("saved-words-${book.id}-${book.sha256}", SavedWordsViewModel::class.java)
            model.requestEdit(word.id)
            model.editSelectedEnglish("Unsaved edit")
            model.editSelectedTurkish("Kaydedilmemiş düzenleme")
            action()
            assertTrue(copied.isEmpty())
            assertEquals("Unsaved edit", model.state.value.editEnglishDraft)
            assertEquals("Kaydedilmemiş düzenleme", model.state.value.editTurkishDraft)
            model.cancelEdit()
            model.requestDelete(word.id)
            action()
            assertTrue(copied.isEmpty())
            model.cancelDelete()
            action()
        }
        assertEquals(listOf(pair(word)), copied)
        assertEquals(listOf(word), repository.entries.value)
        assertEquals(0, repository.writes)
        compose.onNodeWithText(success).assertExists()
    }

    @Test fun `copy rethrows cancellation without a success or failure notice`() {
        val word = SavedWord("cancel", "cancel", "iptal", 1)
        val repository = Repository(listOf(word))
        val cancellation = CancellationException("Cancelled")
        compose.setContent { YaprakTheme { SavedWordsRoute(book, repository, {},
            onCopy = { throw cancellation }) } }
        val action = copyNode(word.id).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            try { action(); fail("Cancellation must propagate") }
            catch (actual: CancellationException) { assertSame(cancellation, actual) }
        }
        compose.onNodeWithText(success).assertDoesNotExist()
        compose.onNodeWithText(failure).assertDoesNotExist()
        assertEquals(0, repository.writes)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `double font short screen reaches last record copy feedback and close`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        val words = (1..30).map { SavedWord("word-$it", "Word $it İ/ı", "Karşılık $it 😀 e\u0301", it.toLong()) }
        val copied = mutableListOf<String>()
        var closed = false
        compose.setContent { YaprakTheme {
            CopyDialog(SavedWordsState(entries = words, loading = false), { copied += it }, { closed = true })
        } }
        copyNode("word-30").assertIsDisplayed()
        val label = hasText("Kopyala") and hasAnyAncestor(hasTestTag("saved-word-copy-word-30"))
        compose.onNode(label, useUnmergedTree = true).assertFontScale(2f)
        readable(compose.onNode(label, useUnmergedTree = true))
        copyNode("word-30").performClick()
        scrollTo(hasText(success))
        compose.onNodeWithText(success).assertIsDisplayed().assertFontScale(2f)
        readable(compose.onNodeWithText(success))
        compose.onNodeWithText("Kapat", useUnmergedTree = true).assertFontScale(2f)
        readable(compose.onNodeWithText("Kapat", useUnmergedTree = true))
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertTrue(closed)
        assertEquals(listOf("word-30"), copied)
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNodeWithTag("saved-words-list").performScrollToNode(matcher)
    }

    private fun copyNode(id: String): SemanticsNodeInteraction {
        val tag = "saved-word-copy-$id"
        scrollTo(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun readable(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.lineCount > 0 && layout.size.width > 0)
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    @Composable
    private fun CopyDialog(state: SavedWordsState, onCopy: ((String) -> Unit)?, onDismiss: () -> Unit = {}) {
        SavedWordsDialog(state = state, onEditEnglish = {}, onEditTurkish = {}, onSave = {}, onReload = {},
            onRequestDelete = {}, onDelete = {}, onCancelDelete = {}, onDismiss = onDismiss,
            onRequestEdit = {}, onEditSelectedEnglish = {}, onEditSelectedTurkish = {},
            onSaveEdit = {}, onCancelEdit = {}, onCopy = onCopy)
    }

    private class Repository(words: List<SavedWord>) : SavedWordRepository {
        val entries = MutableStateFlow(words)
        val observed = mutableListOf<Pair<String, String>>()
        var writes = 0
        override fun observe(bookId: String, contentSha256: String): MutableStateFlow<List<SavedWord>> {
            observed += bookId to contentSha256
            return entries
        }
        override suspend fun add(bookId: String, contentSha256: String, english: String, turkish: String) { writes++ }
        override suspend fun update(bookId: String, contentSha256: String, id: String, english: String, turkish: String) { writes++ }
        override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
    }

    private companion object {
        val book = Book("copy-words", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100, "hash", 1)
        const val success = "Kelime kaydı panoya kopyalandı."
        const val failure = "Kelime kaydı kopyalanamadı. Yeniden deneyebilirsin."
        const val help = "Kopyala İngilizce ve Türkçe kaydını birlikte kopyalar."
        fun pair(word: SavedWord) = word.english + " — " + word.turkish
        fun description(index: Int, word: SavedWord) = "$index. kelime: ${word.english}, kopyala"
    }
}

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.random.Random
import org.junit.After
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
class SavedWordPracticeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }

    @Test fun `real route practices all saved words explicitly and preserves drafts search sort and copying`() {
        val first = SavedWord("first", "Zulu", "İlk karşılık", 1)
        val second = SavedWord("second", "Alpha", "Aranan karşılık", 2)
        val repository = Repository(listOf(first, second))
        var open by mutableStateOf(true)
        val copied = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            if (open) SavedWordsRoute(book, repository, { open = false },
                prefillEnglish = "Draftword", onCopy = { copied += it })
        } }
        node("saved-word-turkish").performTextReplacement("Kaydedilmemiş taslak")
        node("saved-word-search").performTextReplacement("Aranan")
        node("saved-word-sort").performClick()
        compose.onNodeWithText("İngilizce A–Z").performClick()
        start()
        node("saved-word-practice-english").assertTextEquals(first.english)
        node("saved-word-practice-count").assertTextEquals("1 / 2 kelime")
        compose.onNodeWithText("Bu kitaba kaydedilmiş 2 kelime. Arama ve sıralama tekrarı değiştirmez.").assertExists()
        listOf("saved-word-english", "saved-word-turkish", "saved-word-search", "saved-word-sort")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithText(first.turkish).assertDoesNotExist()
        compose.onNodeWithText(second.turkish).assertDoesNotExist()
        node("saved-word-practice-previous").assertIsNotEnabled()
        node("saved-word-practice-reveal").performClick()
        node("saved-word-practice-turkish").assertTextEquals(first.turkish)
        node("saved-word-practice-next").performClick()
        node("saved-word-practice-english").assertTextEquals(second.english)
        node("saved-word-practice-count").assertTextEquals("2 / 2 kelime")
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        node("saved-word-practice-next").assertIsNotEnabled()
        node("saved-word-practice-previous").performClick()
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        node("saved-word-practice-return").performClick()
        node("saved-word-english").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Draftword")))
        node("saved-word-turkish").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Kaydedilmemiş taslak")))
        node("saved-word-search").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Aranan")))
        node("saved-word-sort").assertTextEquals("Sıralama: İngilizce A–Z")
        node("saved-word-copy-second").performClick()
        assertEquals(listOf("Alpha — Aranan karşılık"), copied)
        start()
        node("saved-word-practice-reveal").performClick()
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { open = true }
        start()
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        assertEquals(0, repository.writes)
        assertTrue(repository.observed.all { it == (book.id to book.sha256) })
    }

    @Test fun `real route shuffles the full book and restores the current card while preserving direction and drafts`() {
        val first = SavedWord("first", "Zulu", "İlk karşılık", 1)
        val second = SavedWord("second", "Alpha", "Aranan karşılık", 2)
        val repository = Repository(listOf(first, second))
        var open by mutableStateOf(true)
        val copied = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            if (open) SavedWordsRoute(book, repository, { open = false },
                prefillEnglish = "Draftword", onCopy = { copied += it })
        } }
        node("saved-word-turkish").performTextReplacement("Kaydedilmemiş taslak")
        node("saved-word-search").performTextReplacement("Aranan")
        node("saved-word-sort").performClick()
        compose.onNodeWithText("İngilizce A–Z").performClick()
        start()
        node("saved-word-practice-direction-turkish").performClick()
        node("saved-word-practice-reveal").performClick()
        node("saved-word-practice-english").assertTextEquals(first.english)
        compose.onNodeWithTag("saved-word-practice-restore-order").assertDoesNotExist()
        node("saved-word-practice-shuffle").assertIsEnabled()
            .assert(hasContentDescription("Kelime tekrarını karıştır")).performClick()
        node("saved-word-practice-count").assertTextEquals("1 / 2 kelime")
        node("saved-word-practice-turkish").assertTextEquals(second.turkish)
        node("saved-word-practice-direction-turkish").assertIsSelected()
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        node("saved-word-practice-next").performClick()
        node("saved-word-practice-turkish").assertTextEquals(first.turkish)
        node("saved-word-practice-count").assertTextEquals("2 / 2 kelime")
        node("saved-word-practice-next").assertIsNotEnabled()
        node("saved-word-practice-previous").performClick()
        node("saved-word-practice-reveal").performClick()
        node("saved-word-practice-english").assertTextEquals(second.english)
        node("saved-word-practice-restore-order")
            .assert(hasContentDescription("Kelime tekrarında kayıt sırasına dön")).performClick()
        node("saved-word-practice-turkish").assertTextEquals(second.turkish)
        node("saved-word-practice-count").assertTextEquals("2 / 2 kelime")
        node("saved-word-practice-direction-turkish").assertIsSelected()
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        compose.onNodeWithTag("saved-word-practice-restore-order").assertDoesNotExist()
        node("saved-word-practice-previous").performClick()
        node("saved-word-practice-turkish").assertTextEquals(first.turkish)
        node("saved-word-practice-return").performClick()
        node("saved-word-english").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Draftword")))
        node("saved-word-turkish").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Kaydedilmemiş taslak")))
        node("saved-word-search").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Aranan")))
        node("saved-word-sort").assertTextEquals("Sıralama: İngilizce A–Z")
        node("saved-word-copy-second").performClick()
        assertEquals(listOf("Alpha — Aranan karşılık"), copied)
        start()
        node("saved-word-practice-english").assertTextEquals(first.english)
        node("saved-word-practice-count").assertTextEquals("1 / 2 kelime")
        node("saved-word-practice-direction-english").assertIsSelected()
        compose.onNodeWithTag("saved-word-practice-restore-order").assertDoesNotExist()
        node("saved-word-practice-shuffle").performClick()
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { open = true }
        start()
        node("saved-word-practice-english").assertTextEquals(first.english)
        node("saved-word-practice-direction-english").assertIsSelected()
        compose.onNodeWithTag("saved-word-practice-restore-order").assertDoesNotExist()
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        assertEquals(listOf(first, second), repository.entries.value)
        assertEquals(0, repository.writes)
        assertTrue(repository.observed.all { it == (book.id to book.sha256) })
    }

    @Test fun `shuffled cards use current records append new identities and reconcile removals without revealing answers`() {
        val first = SavedWord("first", "first English", "İlk Türkçe", 1)
        val second = SavedWord("second", "second English", "İkinci Türkçe", 2)
        val third = SavedWord("third", "third English", "Üçüncü Türkçe", 3)
        val fourth = SavedWord("fourth", "fourth English", "Dördüncü Türkçe", 4)
        var displayed by mutableStateOf(SavedWordsState(entries = listOf(first, second, third), loading = false))
        var actual = displayed
        compose.setContent { YaprakTheme {
            Dialog(displayed, currentState = { actual }, practiceRandom = zeroRandom())
        } }
        start()
        node("saved-word-practice-shuffle").performClick()
        node("saved-word-practice-english").assertTextEquals(second.english)
        node("saved-word-practice-count").assertTextEquals("1 / 3 kelime")
        node("saved-word-practice-reveal").performClick()
        val updated = second.copy(english = "updated English", turkish = "Güncel karşılık")
        compose.runOnIdle {
            actual = actual.copy(entries = listOf(third, first, updated))
            displayed = actual
        }
        node("saved-word-practice-english").assertTextEquals(updated.english)
        node("saved-word-practice-count").assertTextEquals("1 / 3 kelime")
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        val revealBeforeAdd = action("saved-word-practice-reveal")
        val nextBeforeAdd = action("saved-word-practice-next")
        compose.runOnIdle {
            actual = actual.copy(entries = listOf(fourth, first, third, updated))
            revealBeforeAdd(); nextBeforeAdd()
            displayed = actual
        }
        node("saved-word-practice-english").assertTextEquals(updated.english)
        node("saved-word-practice-count").assertTextEquals("1 / 4 kelime")
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        node("saved-word-practice-next").performClick()
        node("saved-word-practice-english").assertTextEquals(third.english)
        node("saved-word-practice-count").assertTextEquals("2 / 4 kelime")
        node("saved-word-practice-next").performClick()
        node("saved-word-practice-english").assertTextEquals(first.english)
        node("saved-word-practice-count").assertTextEquals("3 / 4 kelime")
        node("saved-word-practice-next").performClick()
        node("saved-word-practice-english").assertTextEquals(fourth.english)
        node("saved-word-practice-count").assertTextEquals("4 / 4 kelime")
        node("saved-word-practice-next").assertIsNotEnabled()
        compose.runOnIdle {
            actual = actual.copy(entries = listOf(first, third, updated))
            displayed = actual
        }
        node("saved-word-practice-english").assertTextEquals(updated.english)
        node("saved-word-practice-count").assertTextEquals("1 / 3 kelime")
        node("saved-word-practice-next").performClick()
        node("saved-word-practice-english").assertTextEquals(third.english)
        val revealBeforeRemove = action("saved-word-practice-reveal")
        val nextBeforeRemove = action("saved-word-practice-next")
        compose.runOnIdle {
            actual = actual.copy(entries = listOf(first, third))
            revealBeforeRemove(); nextBeforeRemove()
            displayed = actual
        }
        node("saved-word-practice-english").assertTextEquals(third.english)
        node("saved-word-practice-count").assertTextEquals("1 / 2 kelime")
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        node("saved-word-practice-reveal").performClick()
        compose.runOnIdle {
            actual = actual.copy(entries = listOf(first))
            displayed = actual
        }
        node("saved-word-practice-english").assertTextEquals(first.english)
        node("saved-word-practice-count").assertTextEquals("1 / 1 kelime")
        node("saved-word-practice-shuffle").assertIsNotEnabled()
        node("saved-word-practice-previous").assertIsNotEnabled()
        node("saved-word-practice-next").assertIsNotEnabled()
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        node("saved-word-practice-restore-order").performClick()
        node("saved-word-practice-english").assertTextEquals(first.english)
        compose.onNodeWithTag("saved-word-practice-restore-order").assertDoesNotExist()
        compose.runOnIdle {
            actual = actual.copy(entries = emptyList())
            displayed = actual
        }
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        node("saved-word-practice-start").assertIsNotEnabled()
    }

    @Test fun `shuffle and restore reject earlier reveal and enabled next before recomposition even with the same current identity`() {
        val first = SavedWord("first", "first English", "İlk Türkçe", 1)
        val second = SavedWord("second", "second English", "İkinci Türkçe", 2)
        val third = SavedWord("third", "third English", "Üçüncü Türkçe", 3)
        compose.setContent { YaprakTheme {
            Dialog(SavedWordsState(entries = listOf(first, second, third), loading = false), practiceRandom = zeroRandom())
        } }
        start()
        node("saved-word-practice-next").performClick()
        node("saved-word-practice-english").assertTextEquals(second.english)
        node("saved-word-practice-count").assertTextEquals("2 / 3 kelime")
        node("saved-word-practice-next").assertIsEnabled()
        val revealInRepositoryOrder = action("saved-word-practice-reveal")
        val nextInRepositoryOrder = action("saved-word-practice-next")
        val shuffle = action("saved-word-practice-shuffle")
        compose.runOnIdle { shuffle(); revealInRepositoryOrder(); nextInRepositoryOrder() }
        node("saved-word-practice-english").assertTextEquals(second.english)
        node("saved-word-practice-count").assertTextEquals("1 / 3 kelime")
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        node("saved-word-practice-next").assertIsEnabled()
        val revealInShuffledOrder = action("saved-word-practice-reveal")
        val nextInShuffledOrder = action("saved-word-practice-next")
        val restore = action("saved-word-practice-restore-order")
        compose.runOnIdle { restore(); revealInShuffledOrder(); nextInShuffledOrder() }
        node("saved-word-practice-english").assertTextEquals(second.english)
        node("saved-word-practice-count").assertTextEquals("2 / 3 kelime")
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        compose.onNodeWithTag("saved-word-practice-restore-order").assertDoesNotExist()
    }

    @Test fun `restoring explicit order identical to repository order rejects earlier actions before recomposition`() {
        val first = SavedWord("first", "first English", "İlk Türkçe", 1)
        val second = SavedWord("second", "second English", "İkinci Türkçe", 2)
        compose.setContent { YaprakTheme {
            Dialog(SavedWordsState(entries = listOf(first, second), loading = false), practiceRandom = zeroRandom())
        } }
        start()
        node("saved-word-practice-shuffle").performClick()
        node("saved-word-practice-english").assertTextEquals(second.english)
        node("saved-word-practice-shuffle").performClick()
        node("saved-word-practice-english").assertTextEquals(first.english)
        node("saved-word-practice-count").assertTextEquals("1 / 2 kelime")
        node("saved-word-practice-next").assertIsEnabled()
        val earlierReveal = action("saved-word-practice-reveal")
        val earlierNext = action("saved-word-practice-next")
        val earlierShuffle = action("saved-word-practice-shuffle")
        val restore = action("saved-word-practice-restore-order")
        compose.runOnIdle { restore(); earlierReveal(); earlierNext(); earlierShuffle(); restore() }
        node("saved-word-practice-english").assertTextEquals(first.english)
        node("saved-word-practice-count").assertTextEquals("1 / 2 kelime")
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        compose.onNodeWithTag("saved-word-practice-restore-order").assertDoesNotExist()
    }

    @Test fun `same English records retain identity through reorder and updated or removed answers stay concealed`() {
        val first = SavedWord("first", "same", "Birinci karşılık", 1)
        val second = SavedWord("second", "same", "İkinci karşılık", 2)
        var state by mutableStateOf(SavedWordsState(entries = listOf(first, second), loading = false))
        compose.setContent { YaprakTheme { Dialog(state) } }
        start()
        node("saved-word-practice-next").performClick()
        node("saved-word-practice-reveal").performClick()
        node("saved-word-practice-turkish").assertTextEquals(second.turkish)
        compose.runOnIdle { state = state.copy(entries = listOf(second, first)) }
        node("saved-word-practice-count").assertTextEquals("1 / 2 kelime")
        node("saved-word-practice-turkish").assertTextEquals(second.turkish)
        compose.runOnIdle { state = state.copy(entries = listOf(second.copy(turkish = "Güncel karşılık"), first)) }
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        compose.onNodeWithText("Güncel karşılık").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(entries = listOf(second, first)) }
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(entries = listOf(first)) }
        node("saved-word-practice-count").assertTextEquals("1 / 1 kelime")
        node("saved-word-practice-previous").assertIsNotEnabled()
        node("saved-word-practice-next").assertIsNotEnabled()
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        node("saved-word-practice-reveal").performClick()
        node("saved-word-practice-turkish").assertTextEquals(first.turkish)
        node("saved-word-practice-hide").performClick()
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        node("saved-word-practice-reveal").performClick()
        node("saved-word-practice-turkish").assertTextEquals(first.turkish)
        compose.runOnIdle { state = state.copy(entries = emptyList()) }
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        node("saved-word-practice-start").assertIsNotEnabled()
    }

    @Test fun `load operation and confirmation gates end practice and stale actions cannot reopen or reveal`() {
        val word = SavedWord("saved", "leaf", "yaprak", 1)
        val second = SavedWord("second", "book", "kitap", 2)
        val loaded = SavedWordsState(entries = listOf(word, second), loading = false)
        var state by mutableStateOf(loaded)
        compose.setContent { YaprakTheme { Dialog(state) } }
        val startAction = node("saved-word-practice-start").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        listOf(loaded.copy(loading = true), loaded.copy(loadFailed = true), loaded.copy(busy = true),
            loaded.copy(pendingEdit = word, editEnglishDraft = word.english, editTurkishDraft = word.turkish),
            loaded.copy(pendingDelete = word)).forEach { gated ->
            start()
            node("saved-word-practice-shuffle").performClick()
            val reveal = node("saved-word-practice-reveal").fetchSemanticsNode()
                .config[SemanticsActions.OnClick].action!!
            val shuffle = action("saved-word-practice-shuffle")
            val restore = action("saved-word-practice-restore-order")
            compose.runOnIdle { state = gated }
            compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
            compose.onNodeWithTag("saved-word-practice-start").assertIsNotEnabled()
            compose.runOnIdle { startAction(); reveal(); shuffle(); restore() }
            compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
            compose.runOnIdle { state = loaded }
            compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
            node("saved-word-practice-start").assertIsEnabled()
        }
    }

    @Test fun `stale removed card actions cannot reveal or skip its replacement`() {
        val first = SavedWord("first", "first word", "İlk", 1)
        val second = SavedWord("second", "second word", "İkinci", 2)
        val third = SavedWord("third", "third word", "Üçüncü", 3)
        var displayed by mutableStateOf(SavedWordsState(entries = listOf(first, second, third), loading = false))
        var actual = displayed
        compose.setContent { YaprakTheme { Dialog(displayed, currentState = { actual }) } }
        start()
        val reveal = node("saved-word-practice-reveal").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        val next = node("saved-word-practice-next").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            actual = actual.copy(entries = listOf(second, third))
            reveal(); next()
            displayed = actual
        }
        node("saved-word-practice-english").assertTextEquals(second.english)
        node("saved-word-practice-count").assertTextEquals("1 / 2 kelime")
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
    }

    @Test fun `real route checks fresh ViewModel gates before recomposition for stale practice clicks`() {
        val word = SavedWord("saved", "leaf", "yaprak", 1)
        val repository = Repository(listOf(word))
        compose.setContent { YaprakTheme { SavedWordsRoute(book, repository, {}) } }
        val startAction = node("saved-word-practice-start").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        start()
        val reveal = node("saved-word-practice-reveal").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)
                .get("saved-words-${book.id}-${book.sha256}", SavedWordsViewModel::class.java)
            model.requestEdit(word.id)
            model.editSelectedTurkish("Kaydedilmemiş düzenleme")
            reveal()
            startAction()
            assertEquals("Kaydedilmemiş düzenleme", model.state.value.editTurkishDraft)
            model.cancelEdit()
        }
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        start()
        val secondReveal = node("saved-word-practice-reveal").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)
                .get("saved-words-${book.id}-${book.sha256}", SavedWordsViewModel::class.java)
            model.requestDelete(word.id)
            secondReveal()
            startAction()
            model.cancelDelete()
        }
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        assertEquals(listOf(word), repository.entries.value)
        assertEquals(0, repository.writes)
    }

    @Test fun `stale reveal of old text cannot expose an updated answer with the same identity`() {
        val original = SavedWord("saved", "old English", "Eski karşılık", 1)
        val updated = original.copy(english = "updated English", turkish = "Güncel karşılık")
        var displayed by mutableStateOf(SavedWordsState(entries = listOf(original), loading = false))
        var actual = displayed
        compose.setContent { YaprakTheme { Dialog(displayed, currentState = { actual }) } }
        start()
        val staleReveal = node("saved-word-practice-reveal").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            actual = actual.copy(entries = listOf(updated))
            staleReveal()
            displayed = actual
        }
        node("saved-word-practice-english").assertTextEquals(updated.english)
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        compose.onNodeWithText(original.turkish).assertDoesNotExist()
        compose.onNodeWithText(updated.turkish).assertDoesNotExist()
        node("saved-word-practice-reveal").performClick()
        node("saved-word-practice-turkish").assertTextEquals(updated.turkish)
    }

    @Test fun `real route reverses the same saved card conceals answers and defaults after restart without writes`() {
        val first = SavedWord("first", "Alpha", "Birinci karşılık", 1)
        val second = SavedWord("second", "Beta", "İkinci karşılık", 2)
        val repository = Repository(listOf(first, second))
        var open by mutableStateOf(true)
        compose.setContent { YaprakTheme {
            if (open) SavedWordsRoute(book, repository, { open = false }, prefillEnglish = "Draftword")
        } }
        node("saved-word-turkish").performTextReplacement("Kaydedilmemiş taslak")
        start()
        node("saved-word-practice-next").performClick()
        node("saved-word-practice-reveal").performClick()
        node("saved-word-practice-turkish").assertTextEquals(second.turkish)
        node("saved-word-practice-direction-turkish").performClick().assertIsSelected()
            .assert(hasContentDescription("Tekrar yönü: Türkçe → İngilizce"))
        node("saved-word-practice-direction-english").assertIsNotSelected()
        node("saved-word-practice-count").assertTextEquals("2 / 2 kelime")
        node("saved-word-practice-turkish").assertTextEquals(second.turkish)
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        node("saved-word-practice-reveal").assertTextEquals("İngilizce kelimeyi göster").performClick()
        node("saved-word-practice-english").assertTextEquals(second.english)
        node("saved-word-practice-hide").performClick()
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        node("saved-word-practice-return").performClick()
        node("saved-word-english").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Draftword")))
        node("saved-word-turkish").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Kaydedilmemiş taslak")))
        start()
        node("saved-word-practice-direction-english").assertIsSelected()
        node("saved-word-practice-count").assertTextEquals("1 / 2 kelime")
        node("saved-word-practice-english").assertTextEquals(first.english)
        node("saved-word-practice-direction-turkish").performClick()
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { open = true }
        start()
        node("saved-word-practice-direction-english").assertIsSelected()
        node("saved-word-practice-english").assertTextEquals(first.english)
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
        assertEquals(0, repository.writes)
        assertEquals(listOf(first, second), repository.entries.value)
    }

    @Test fun `stale reveal and movement from another practice direction cannot expose or skip a card`() {
        val first = SavedWord("first", "first English", "İlk Türkçe", 1)
        val second = SavedWord("second", "second English", "İkinci Türkçe", 2)
        compose.setContent { YaprakTheme {
            Dialog(SavedWordsState(entries = listOf(first, second), loading = false))
        } }
        start()
        val forwardReveal = node("saved-word-practice-reveal").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        val forwardNext = node("saved-word-practice-next").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        val switchToTurkish = node("saved-word-practice-direction-turkish").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { switchToTurkish(); forwardReveal(); forwardNext() }
        node("saved-word-practice-count").assertTextEquals("1 / 2 kelime")
        node("saved-word-practice-turkish").assertTextEquals(first.turkish)
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        val reverseReveal = node("saved-word-practice-reveal").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        val switchToEnglish = node("saved-word-practice-direction-english").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { switchToEnglish(); reverseReveal() }
        node("saved-word-practice-english").assertTextEquals(first.english)
        compose.onNodeWithTag("saved-word-practice-turkish").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w320dp-h480dp-mdpi")
    fun `double font short narrow screen reaches long Unicode words count reveal navigation and close`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        val word = SavedWord("long", "A".repeat(82) + " İ/ı 😀 e\u0301", "karşılık ".repeat(20) + "İ/ı 😀 e\u0301", 1)
        var open by mutableStateOf(true)
        compose.setContent { YaprakTheme {
            if (open) Dialog(SavedWordsState(entries = listOf(word, SavedWord("next", "next", "sonraki", 2)),
                loading = false)) { open = false }
        } }
        start()
        listOf("saved-word-practice-direction-english" to "İngilizce → Türkçe",
            "saved-word-practice-direction-turkish" to "Türkçe → İngilizce").forEach { (tag, label) ->
            node(tag).assertIsDisplayed()
            readable(compose.onNodeWithText(label, useUnmergedTree = true).assertFontScale(2f))
        }
        readable(node("saved-word-practice-count").assertFontScale(2f))
        readable(node("saved-word-practice-english").assertFontScale(2f))
        node("saved-word-practice-reveal").assertIsDisplayed()
        readable(compose.onNodeWithText("Türkçe karşılığı göster", useUnmergedTree = true).assertFontScale(2f))
        node("saved-word-practice-reveal").performClick()
        readable(node("saved-word-practice-turkish").assertFontScale(2f))
        node("saved-word-practice-direction-turkish").performClick().assertIsSelected()
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        readable(node("saved-word-practice-turkish").assertFontScale(2f))
        node("saved-word-practice-reveal").assertIsDisplayed()
        readable(compose.onNodeWithText("İngilizce kelimeyi göster", useUnmergedTree = true).assertFontScale(2f))
        node("saved-word-practice-reveal").performClick()
        readable(node("saved-word-practice-english").assertFontScale(2f))
        listOf("saved-word-practice-hide" to "Karşılığı gizle",
            "saved-word-practice-previous" to "Önceki kelime", "saved-word-practice-next" to "Sonraki kelime",
            "saved-word-practice-return" to "Listeye dön").forEach { (tag, label) ->
            node(tag).assertIsDisplayed()
            readable(compose.onNodeWithText(label, useUnmergedTree = true).assertFontScale(2f))
        }
        readable(compose.onNodeWithText("Kapat", useUnmergedTree = true).assertFontScale(2f))
        node("saved-word-practice-next").performClick()
        node("saved-word-practice-count").assertTextEquals("2 / 2 kelime")
        node("saved-word-practice-shuffle").assertIsDisplayed().assertIsEnabled()
            .assert(hasContentDescription("Kelime tekrarını karıştır"))
        readable(compose.onNodeWithText("Karıştır", useUnmergedTree = true).assertFontScale(2f))
        node("saved-word-practice-shuffle").performClick()
        node("saved-word-practice-count").assertTextEquals("1 / 2 kelime")
            .assert(hasContentDescription("Kelime tekrarı, 1. kelime, toplam 2"))
        readable(node("saved-word-practice-count").assertFontScale(2f))
        node("saved-word-practice-direction-turkish").assertIsSelected()
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        node("saved-word-practice-restore-order").assertIsDisplayed().assertIsEnabled()
            .assert(hasContentDescription("Kelime tekrarında kayıt sırasına dön"))
        readable(compose.onNodeWithText("Kayıt sırasına dön", useUnmergedTree = true).assertFontScale(2f))
        node("saved-word-practice-restore-order").performClick()
        node("saved-word-practice-count").assertTextEquals("2 / 2 kelime")
        readable(node("saved-word-practice-count").assertFontScale(2f))
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
        compose.onNodeWithTag("saved-word-practice-restore-order").assertDoesNotExist()
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        compose.onNodeWithTag("saved-word-practice-english").assertDoesNotExist()
    }

    private fun start() { node("saved-word-practice-start").assertIsEnabled().performClick() }
    private fun action(tag: String) = node(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun zeroRandom() = object : Random() { override fun nextBits(bitCount: Int) = 0 }
    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag(tag))
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
    @Composable private fun Dialog(state: SavedWordsState,
        currentState: (() -> SavedWordsState)? = null, practiceRandom: Random = Random.Default,
        onDismiss: () -> Unit = {}) {
        SavedWordsDialog(state, onEditEnglish = {}, onEditTurkish = {}, onSave = {}, onReload = {},
            onRequestDelete = {}, onDelete = {}, onCancelDelete = {}, onDismiss = onDismiss,
            onRequestEdit = {}, onEditSelectedEnglish = {}, onEditSelectedTurkish = {}, onSaveEdit = {}, onCancelEdit = {},
            currentState = currentState, practiceRandom = practiceRandom)
    }
    private class Repository(initial: List<SavedWord>) : SavedWordRepository {
        val entries = MutableStateFlow(initial)
        val observed = mutableListOf<Pair<String, String>>()
        var writes = 0
        override fun observe(bookId: String, contentSha256: String) = entries.also { observed += bookId to contentSha256 }
        override suspend fun add(bookId: String, contentSha256: String, english: String, turkish: String) { writes++ }
        override suspend fun update(bookId: String, contentSha256: String, id: String, english: String, turkish: String) { writes++ }
        override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
    }
    private companion object {
        val book = Book("practice", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100, "hash", 1)
    }
}

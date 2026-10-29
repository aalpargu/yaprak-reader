package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.vocabulary.SavedWord
import com.yaprak.reader.domain.vocabulary.SavedWordRepository
import com.yaprak.reader.feature.vocabulary.SavedWordSort
import com.yaprak.reader.feature.vocabulary.SavedWordsViewModel
import com.yaprak.reader.feature.vocabulary.TranslatedWordDraft
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedWordsViewModelTest {
    private val word = SavedWord("word-1", "leaf", "yaprak", 1)

    private suspend fun kotlinx.coroutines.test.TestScope.translationModel(
        block: suspend kotlinx.coroutines.test.TestScope.(SavedWordsViewModel, Repository) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val owner = ViewModelStore()
        val repository = Repository()
        val model = SavedWordsViewModel("book-a", "hash-a", repository).also { owner.put("translation-words", it) }
        try { runCurrent(); block(model, repository) }
        finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun translationFillsBothFieldsWithoutSavingAndTheReviewedEditedMeaningIsSavedExplicitly() = runTest {
        translationModel { model, repository ->
            assertTrue(model.offerTranslationDraft(TranslatedWordDraft("Leaf", "yaprak")))
            assertEquals("Leaf", model.state.value.englishDraft); assertEquals("yaprak", model.state.value.turkishDraft)
            assertTrue(model.state.value.translationDraft); assertTrue(model.state.value.canSave)
            assertEquals(0, repository.addCalls)
            model.editTurkish("bitki yaprağı"); model.add(); advanceUntilIdle()
            assertEquals(1, repository.addCalls); assertEquals("Leaf", repository.lastEnglish)
            assertEquals("bitki yaprağı", repository.lastTurkish); assertEquals("book-a", repository.lastBookId)
            assertFalse(model.state.value.translationDraft); assertEquals("", model.state.value.turkishDraft)
        }
    }
    @Test fun anOccupiedFormIsPreservedAndOnlyTheExactPendingChoiceCanReplaceBothFields() = runTest {
        translationModel { model, repository ->
            repository.entries.value = listOf(word); runCurrent()
            model.editEnglish("Earlier"); model.editTurkish("önceki")
            val draft = TranslatedWordDraft("Leaf", "yaprak")
            assertTrue(model.offerTranslationDraft(draft))
            assertEquals("Earlier", model.state.value.englishDraft); assertEquals("önceki", model.state.value.turkishDraft)
            assertSameDraft(draft, model.state.value.pendingTranslationDraft)
            assertFalse(model.state.value.canSave); model.add(); model.requestEdit(word.id); model.requestDelete(word.id)
            assertEquals(0, repository.addCalls); assertNull(model.state.value.pendingEdit); assertNull(model.state.value.pendingDelete)
            assertFalse(model.useTranslationDraft(draft.copy())); assertFalse(model.keepExistingDraft(draft.copy()))
            assertTrue(model.useTranslationDraft(draft)); assertFalse(model.useTranslationDraft(draft))
            assertEquals("Leaf", model.state.value.englishDraft); assertEquals("yaprak", model.state.value.turkishDraft)
            assertTrue(model.state.value.canSave); assertEquals(0, repository.addCalls)
        }
    }
    @Test fun keepingOrDismissingAConflictPreservesTheOriginalUnsavedFormWhileAnAcceptedNewDraftCanBeDiscarded() = runTest {
        translationModel { model, repository ->
            model.editEnglish("Earlier"); model.editTurkish("önceki")
            val first = TranslatedWordDraft("Leaf", "yaprak")
            model.offerTranslationDraft(first); assertTrue(model.keepExistingDraft(first)); model.dismissTranslationPrefill()
            assertEquals("Earlier", model.state.value.englishDraft); assertEquals("önceki", model.state.value.turkishDraft)
            model.offerTranslationDraft(first); model.dismissTranslationPrefill()
            assertNull(model.state.value.pendingTranslationDraft); assertEquals("Earlier", model.state.value.englishDraft)
            val next = first.copy(); model.offerTranslationDraft(next)
            assertFalse(model.useTranslationDraft(first)); model.useTranslationDraft(next); model.dismissTranslationPrefill()
            assertEquals("", model.state.value.englishDraft); assertEquals("", model.state.value.turkishDraft)
            assertFalse(model.state.value.translationDraft); assertEquals(0, repository.addCalls)
        }
    }
    @Test fun invalidBusyEditAndDeleteStatesRejectTheDraftWithoutChangingFieldsOrRecords() = runTest {
        translationModel { model, repository ->
            val valid = TranslatedWordDraft("Leaf", "yaprak")
            for (draft in listOf(valid.copy(english = "two words"), valid.copy(turkish = "\uD800"),
                valid.copy(turkish = "line\n"), valid.copy(turkish = "a".repeat(201)))) assertFalse(model.offerTranslationDraft(draft))
            repository.entries.value = listOf(word); runCurrent()
            model.requestEdit(word.id); assertFalse(model.offerTranslationDraft(valid)); model.cancelEdit()
            model.requestDelete(word.id); assertFalse(model.offerTranslationDraft(valid)); model.cancelDelete()
            model.editEnglish("existing"); model.editTurkish("mevcut"); model.add(); runCurrent()
            assertTrue(model.state.value.busy); assertFalse(model.offerTranslationDraft(valid))
            assertEquals("existing", model.state.value.englishDraft); advanceUntilIdle()
            assertEquals(1, repository.addCalls)
        }
    }
    @Test fun theLegacyEnglishOnlyTransferStillClearsTheMeaningAndTranslationChoices() = runTest {
        translationModel { model, repository ->
            model.offerTranslationDraft(TranslatedWordDraft("Leaf", "yaprak"))
            model.prefillEnglish(" Book ")
            assertEquals("Book", model.state.value.englishDraft); assertEquals("", model.state.value.turkishDraft)
            assertFalse(model.state.value.translationDraft); assertNull(model.state.value.pendingTranslationDraft)
            assertFalse(model.state.value.canSave); assertEquals(0, repository.addCalls)
        }
    }
    private fun assertSameDraft(expected: TranslatedWordDraft, actual: TranslatedWordDraft?) {
        org.junit.Assert.assertSame(expected, actual)
    }

    private class Repository : SavedWordRepository {
        val entries = MutableStateFlow<List<SavedWord>>(emptyList())
        var failRead = false
        var failWrite = false
        var addCalls = 0
        var removeCalls = 0
        var updateCalls = 0
        var lastUpdatedId: String? = null
        var lastBookId: String? = null
        var lastHash: String? = null
        var lastEnglish: String? = null
        var lastTurkish: String? = null

        override fun observe(bookId: String, contentSha256: String) = flow {
            lastBookId = bookId
            lastHash = contentSha256
            if (failRead) throw IOException()
            emitAll(entries)
        }

        override suspend fun add(bookId: String, contentSha256: String, english: String, turkish: String) {
            addCalls++
            lastBookId = bookId
            lastHash = contentSha256
            lastEnglish = english
            lastTurkish = turkish
            delay(100)
            if (failWrite) throw IOException()
            entries.value = entries.value + SavedWord("new", english, turkish, 2)
        }

        override suspend fun remove(bookId: String, contentSha256: String, id: String) {
            removeCalls++
            lastBookId = bookId
            lastHash = contentSha256
            delay(100)
            if (failWrite) throw IOException()
            entries.value = entries.value.filterNot { it.id == id }
        }

        override suspend fun update(bookId: String, contentSha256: String, id: String, english: String, turkish: String) {
            updateCalls++
            lastBookId = bookId
            lastHash = contentSha256
            lastUpdatedId = id
            lastEnglish = english
            lastTurkish = turkish
            delay(100)
            if (failWrite) throw IOException()
            entries.value = entries.value.map { if (it.id == id) it.copy(english = english, turkish = turkish) else it }
        }
    }

    @Test fun `search filters current book words without writing and clearing restores order`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val first = SavedWord("first", "light", "ışık", 3)
            val second = SavedWord("second", "city", "İstanbul", 2)
            val repository = Repository().apply { entries.value = listOf(first, second) }
            val model = SavedWordsViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.search("CITY istanbul")
            assertEquals(listOf(second), model.state.value.visibleEntries)
            assertEquals(listOf(first, second), model.state.value.entries)
            model.search("bulunmayan")
            assertTrue(model.state.value.visibleEntries.isEmpty())
            model.search("x".repeat(201))
            assertEquals(200, model.state.value.query.length)
            model.clearSearch()
            assertEquals(listOf(first, second), model.state.value.visibleEntries)
            assertEquals("", model.state.value.query)
            assertEquals("book-a", repository.lastBookId)
            assertEquals("hash-a", repository.lastHash)
            assertEquals(0, repository.addCalls + repository.removeCalls + repository.updateCalls)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `sorting combines with search and resets without writing or changing repository order`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val zebra = SavedWord("zebra", "zebra", "elma", 3)
            val apple = SavedWord("apple", "apple", "zeytin", 2)
            val cherry = SavedWord("cherry", "cherry", "armut", 1)
            val repository = Repository().apply { entries.value = listOf(zebra, apple, cherry) }
            val model = SavedWordsViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            assertEquals(SavedWordSort.RECENT, model.state.value.sort)
            assertEquals(listOf(zebra, apple, cherry), model.state.value.visibleEntries)

            model.sort(SavedWordSort.ENGLISH)
            assertEquals(listOf(apple, cherry, zebra), model.state.value.visibleEntries)
            model.search("z")
            assertEquals(listOf(apple, zebra), model.state.value.visibleEntries)
            model.sort(SavedWordSort.TURKISH)
            assertEquals(listOf(zebra, apple), model.state.value.visibleEntries)
            assertEquals(listOf(zebra, apple, cherry), model.state.value.entries)

            val newWord = SavedWord("new", "azure", "yaz", 4)
            repository.entries.value = listOf(newWord, zebra, apple, cherry)
            runCurrent()
            assertEquals(listOf(zebra, newWord, apple), model.state.value.visibleEntries)
            model.clearPresentation()
            assertEquals("", model.state.value.query)
            assertEquals(SavedWordSort.RECENT, model.state.value.sort)
            assertEquals(listOf(newWord, zebra, apple, cherry), model.state.value.visibleEntries)
            assertEquals("book-a", repository.lastBookId)
            assertEquals("hash-a", repository.lastHash)
            assertEquals(0, repository.addCalls + repository.removeCalls + repository.updateCalls)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `manual drafts validate before repository call and failed save stays retryable`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failWrite = true }
            val model = SavedWordsViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.editEnglish(" \nleaf ")
            model.editTurkish("yaprak")
            model.add()
            assertFalse(model.state.value.canSave)
            assertEquals(0, repository.addCalls)
            model.editEnglish("x".repeat(101))
            model.add()
            assertEquals(0, repository.addCalls)
            model.editEnglish("  leaf  ")
            model.editTurkish("  yaprak  ")
            assertTrue(model.state.value.canSave)
            model.add()
            model.add()
            runCurrent()
            assertEquals(1, repository.addCalls)
            assertTrue(model.state.value.busy)
            advanceUntilIdle()
            assertEquals("  leaf  ", model.state.value.englishDraft)
            assertEquals("  yaprak  ", model.state.value.turkishDraft)
            assertTrue(model.state.value.entries.isEmpty())
            assertEquals("Kelime kaydedilemedi. Yeniden deneyebilirsin.", model.state.value.message)
            repository.failWrite = false
            model.add()
            advanceUntilIdle()
            assertEquals("book-a", repository.lastBookId)
            assertEquals("hash-a", repository.lastHash)
            assertEquals("leaf", repository.lastEnglish)
            assertEquals("yaprak", repository.lastTurkish)
            assertEquals(listOf("leaf"), model.state.value.entries.map { it.english })
            assertEquals("", model.state.value.englishDraft)
            assertEquals("", model.state.value.turkishDraft)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `delete requires confirmation and a failed removal preserves entry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(word) }
            val model = SavedWordsViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.requestDelete(word.id)
            model.cancelDelete()
            model.delete()
            assertEquals(0, repository.removeCalls)
            repository.failWrite = true
            model.requestDelete(word.id)
            model.delete()
            model.delete()
            advanceUntilIdle()
            assertEquals(1, repository.removeCalls)
            assertNotNull(model.state.value.pendingDelete)
            assertEquals(listOf(word), model.state.value.entries)
            repository.failWrite = false
            model.delete()
            advanceUntilIdle()
            assertEquals(2, repository.removeCalls)
            assertTrue(model.state.value.entries.isEmpty())
            assertNull(model.state.value.pendingDelete)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `read failure blocks writes until reload succeeds`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failRead = true }
            val model = SavedWordsViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.editEnglish("leaf")
            model.editTurkish("yaprak")
            model.add()
            assertTrue(model.state.value.loadFailed)
            assertEquals(0, repository.addCalls)
            repository.failRead = false
            model.reload()
            runCurrent()
            assertTrue(model.state.value.canSave)
            assertEquals("leaf", model.state.value.englishDraft)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `edit targets one record and failed update preserves original list and retryable draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val second = word.copy(id = "word-2", turkish = "sayfa", createdAt = 2)
            val repository = Repository().apply { entries.value = listOf(word, second); failWrite = true }
            val model = SavedWordsViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.requestEdit(second.id)
            assertEquals(second.id, model.state.value.pendingEdit?.id)
            model.requestDelete(word.id)
            assertNull(model.state.value.pendingDelete)
            assertFalse(model.state.value.canUpdate)
            model.editSelectedEnglish("le\naf")
            model.saveEdit()
            model.editSelectedEnglish("  leaf  ")
            model.editSelectedTurkish("  yeni karşılık  ")
            assertTrue(model.state.value.canUpdate)
            model.saveEdit()
            model.saveEdit()
            runCurrent()
            assertEquals(1, repository.updateCalls)
            assertTrue(model.state.value.busy)
            model.cancelEdit()
            assertEquals(second.id, model.state.value.pendingEdit?.id)
            advanceUntilIdle()
            assertEquals(listOf(word, second), model.state.value.entries)
            assertEquals("  yeni karşılık  ", model.state.value.editTurkishDraft)
            assertEquals("Kelime düzenlenemedi. Yeniden deneyebilirsin.", model.state.value.message)
            repository.failWrite = false
            model.saveEdit()
            advanceUntilIdle()
            assertEquals(2, repository.updateCalls)
            assertEquals("book-a", repository.lastBookId)
            assertEquals("hash-a", repository.lastHash)
            assertEquals(second.id, repository.lastUpdatedId)
            assertEquals("leaf", repository.lastEnglish)
            assertEquals("yeni karşılık", repository.lastTurkish)
            assertEquals(word, model.state.value.entries[0])
            assertEquals(second.copy(turkish = "yeni karşılık"), model.state.value.entries[1])
            assertNull(model.state.value.pendingEdit)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `cancelled edit leaves records untouched and deletion excludes editing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(word) }
            val model = SavedWordsViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.requestEdit(word.id)
            model.editSelectedEnglish("changed")
            model.cancelEdit()
            model.saveEdit()
            assertNull(model.state.value.pendingEdit)
            assertEquals("", model.state.value.editEnglishDraft)
            assertEquals(listOf(word), model.state.value.entries)
            assertEquals(0, repository.updateCalls)
            model.requestDelete(word.id)
            model.requestEdit(word.id)
            assertNull(model.state.value.pendingEdit)
            assertEquals(word.id, model.state.value.pendingDelete?.id)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `prefill leaves Turkish meaning empty and never saves without manual action`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val model = SavedWordsViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.editEnglish("earlier")
            model.editTurkish("önceki")
            model.prefillEnglish(" leaf ")
            assertEquals("leaf", model.state.value.englishDraft)
            assertEquals("", model.state.value.turkishDraft)
            assertEquals(0, repository.addCalls)
            assertFalse(model.state.value.canSave)
            model.prefillEnglish("two words")
            model.prefillEnglish("leaf\n")
            assertEquals("leaf", model.state.value.englishDraft)
            model.editTurkish("yaprak")
            model.add()
            advanceUntilIdle()
            assertEquals(1, repository.addCalls)
            assertEquals("leaf", repository.lastEnglish)
            assertEquals("yaprak", repository.lastTurkish)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}

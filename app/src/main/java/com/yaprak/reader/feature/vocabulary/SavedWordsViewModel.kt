package com.yaprak.reader.feature.vocabulary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.vocabulary.SavedWord
import com.yaprak.reader.domain.vocabulary.SavedWordRepository
import com.yaprak.reader.domain.vocabulary.MAX_ENGLISH_WORD_LENGTH
import com.yaprak.reader.domain.vocabulary.MAX_TURKISH_MEANING_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SavedWordsState(
    val entries: List<SavedWord> = emptyList(),
    val query: String = "",
    val sort: SavedWordSort = SavedWordSort.RECENT,
    val englishDraft: String = "",
    val turkishDraft: String = "",
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val pendingDelete: SavedWord? = null,
    val pendingEdit: SavedWord? = null,
    val editEnglishDraft: String = "",
    val editTurkishDraft: String = "",
    val translationDraft: Boolean = false,
    val pendingTranslationDraft: TranslatedWordDraft? = null,
) {
    val visibleEntries: List<SavedWord> get() = sortSavedWords(searchSavedWords(entries, query), sort)
    val englishValid: Boolean get() = validLine(englishDraft, MAX_ENGLISH_WORD_LENGTH)
    val turkishValid: Boolean get() = validLine(turkishDraft, MAX_TURKISH_MEANING_LENGTH)
    val editEnglishValid: Boolean get() = validLine(editEnglishDraft, MAX_ENGLISH_WORD_LENGTH)
    val editTurkishValid: Boolean get() = validLine(editTurkishDraft, MAX_TURKISH_MEANING_LENGTH)
    val canSave: Boolean get() = !loading && !loadFailed && !busy && pendingDelete == null && pendingEdit == null &&
        pendingTranslationDraft == null && englishValid && turkishValid
    val canUpdate: Boolean get() = !loading && !loadFailed && !busy && pendingDelete == null &&
        pendingTranslationDraft == null && pendingEdit != null && editEnglishValid && editTurkishValid &&
        (editEnglishDraft.trim() != pendingEdit.english || editTurkishDraft.trim() != pendingEdit.turkish)
}

private fun validLine(value: String, maxLength: Int): Boolean =
    value.trim().length in 1..maxLength && '\n' !in value && '\r' !in value

internal class SavedWordsViewModel(
    private val bookId: String,
    private val contentSha256: String,
    private val repository: SavedWordRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(SavedWordsState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null

    init { reload() }

    fun search(query: String) { mutable.update { it.copy(query = query.take(200)) } }

    fun clearSearch() { mutable.update { it.copy(query = "") } }

    fun sort(sort: SavedWordSort) { mutable.update { it.copy(sort = sort) } }

    fun clearPresentation() { mutable.update { it.copy(query = "", sort = SavedWordSort.RECENT) } }

    fun reload() {
        observation?.cancel()
        mutable.update { it.copy(loading = true, loadFailed = false, message = null) }
        observation = viewModelScope.launch {
            try {
                repository.observe(bookId, contentSha256).collect { entries ->
                    mutable.update { it.copy(entries = entries, loading = false, loadFailed = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun editEnglish(value: String) {
        if (!state.value.busy) mutable.update { it.copy(englishDraft = value, message = null) }
    }

    fun prefillEnglish(candidate: String) {
        val word = candidate.trim()
        if (!validLine(candidate, MAX_ENGLISH_WORD_LENGTH) || word.any(Char::isWhitespace)) return
        if (state.value.busy || state.value.pendingEdit != null || state.value.pendingDelete != null) return
        mutable.update { it.copy(englishDraft = word, turkishDraft = "", message = null,
            translationDraft = false, pendingTranslationDraft = null) }
    }

    /** An occupied form is preserved until the user explicitly chooses which draft to use. */
    fun offerTranslationDraft(draft: TranslatedWordDraft): Boolean {
        val state = mutable.value
        if (!draft.isValid || state.busy || state.pendingEdit != null || state.pendingDelete != null) return false
        mutable.value = if ((state.englishDraft.isBlank() && state.turkishDraft.isBlank()) ||
            (state.englishDraft == draft.english && state.turkishDraft == draft.turkish)) {
            state.copy(englishDraft = draft.english, turkishDraft = draft.turkish, translationDraft = true,
                pendingTranslationDraft = null, message = null)
        } else state.copy(pendingTranslationDraft = draft, message = null)
        return true
    }
    fun useTranslationDraft(expected: TranslatedWordDraft): Boolean {
        val state = mutable.value
        if (state.pendingTranslationDraft !== expected || !expected.isValid || state.busy ||
            state.pendingEdit != null || state.pendingDelete != null) return false
        mutable.value = state.copy(englishDraft = expected.english, turkishDraft = expected.turkish,
            translationDraft = true, pendingTranslationDraft = null, message = null)
        return true
    }
    fun keepExistingDraft(expected: TranslatedWordDraft): Boolean {
        val state = mutable.value
        if (state.pendingTranslationDraft !== expected || state.busy) return false
        mutable.value = state.copy(pendingTranslationDraft = null)
        return true
    }
    fun dismissTranslationPrefill() {
        if (state.value.busy) return
        if (state.value.pendingTranslationDraft != null) mutable.update { it.copy(pendingTranslationDraft = null) }
        else if (state.value.translationDraft) clearAddDraft()
    }

    fun clearAddDraft() {
        if (!state.value.busy) mutable.update { it.copy(englishDraft = "", turkishDraft = "", message = null,
            translationDraft = false, pendingTranslationDraft = null) }
    }

    fun editTurkish(value: String) {
        if (!state.value.busy) mutable.update { it.copy(turkishDraft = value, message = null) }
    }

    fun add() {
        val current = state.value
        if (!current.canSave) return
        val english = current.englishDraft.trim()
        val turkish = current.turkishDraft.trim()
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                repository.add(bookId, contentSha256, english, turkish)
                mutable.update { it.copy(busy = false, englishDraft = "", turkishDraft = "", message = "Kelime kaydedildi.",
                    translationDraft = false, pendingTranslationDraft = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, message = "Kelime kaydedilemedi. Yeniden deneyebilirsin.") }
            }
        }
    }

    fun requestDelete(id: String) {
        if (state.value.busy || state.value.loading || state.value.loadFailed ||
            state.value.pendingEdit != null || state.value.pendingDelete != null || state.value.pendingTranslationDraft != null) return
        mutable.update { current ->
            current.copy(pendingDelete = current.entries.firstOrNull { it.id == id }, message = null)
        }
    }

    fun cancelDelete() {
        if (!state.value.busy) mutable.update { it.copy(pendingDelete = null, message = null) }
    }

    fun delete() {
        val current = state.value
        val entry = current.pendingDelete ?: return
        if (current.busy || current.loading || current.loadFailed || current.pendingEdit != null) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                repository.remove(bookId, contentSha256, entry.id)
                mutable.update { it.copy(busy = false, pendingDelete = null, message = "Kelime silindi.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, message = "Kelime silinemedi. Yeniden deneyebilirsin.") }
            }
        }
    }

    fun requestEdit(id: String) {
        if (state.value.busy || state.value.loading || state.value.loadFailed ||
            state.value.pendingDelete != null || state.value.pendingEdit != null || state.value.pendingTranslationDraft != null) return
        mutable.update { current ->
            val entry = current.entries.firstOrNull { it.id == id } ?: return@update current
            current.copy(pendingEdit = entry, editEnglishDraft = entry.english,
                editTurkishDraft = entry.turkish, message = null)
        }
    }

    fun editSelectedEnglish(value: String) {
        if (state.value.pendingEdit != null && !state.value.busy)
            mutable.update { it.copy(editEnglishDraft = value, message = null) }
    }

    fun editSelectedTurkish(value: String) {
        if (state.value.pendingEdit != null && !state.value.busy)
            mutable.update { it.copy(editTurkishDraft = value, message = null) }
    }

    fun cancelEdit() {
        if (!state.value.busy) mutable.update { it.copy(pendingEdit = null,
            editEnglishDraft = "", editTurkishDraft = "", message = null) }
    }

    fun saveEdit() {
        val current = state.value
        if (!current.canUpdate) return
        val entry = current.pendingEdit ?: return
        val english = current.editEnglishDraft.trim()
        val turkish = current.editTurkishDraft.trim()
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                repository.update(bookId, contentSha256, entry.id, english, turkish)
                mutable.update { it.copy(busy = false, pendingEdit = null,
                    editEnglishDraft = "", editTurkishDraft = "", message = "Kelime düzenlendi.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, message = "Kelime düzenlenemedi. Yeniden deneyebilirsin.") }
            }
        }
    }
}

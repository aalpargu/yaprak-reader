package com.yaprak.reader.feature.dictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.dictionary.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DictionaryLookupState(val requestId: String, val attemptId: String, val word: String?,
    val loading: Boolean = false, val result: DictionaryResult? = null, val failure: DictionaryFailure? = null)

/** Ephemeral explicit lookups; no book identity/content, cache, note or progress persistence. */
class DictionaryLookupViewModel(private val dictionary: EnglishDictionary) : ViewModel() {
    private val mutable = MutableStateFlow<DictionaryLookupState?>(null)
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0L

    fun lookup(requestId: String, selectedWord: String): Boolean {
        if (requestId.isBlank() || mutable.value?.let { it.requestId == requestId && it.loading } == true) return false
        val word = try { englishDictionaryWord(selectedWord) }
        catch (failure: DictionaryException) {
            close()
            mutable.value = DictionaryLookupState(requestId, UUID.randomUUID().toString(), null, failure = failure.reason)
            return true
        }
        start(requestId, word)
        return true
    }

    fun retry(expected: DictionaryLookupState): Boolean {
        if (mutable.value !== expected || expected.loading || expected.failure == null || expected.word == null) return false
        start(expected.requestId, expected.word)
        return true
    }

    fun close() {
        generation++
        work?.cancel()
        work = null
        mutable.value = null
    }

    private fun start(requestId: String, word: String) {
        work?.cancel()
        val admitted = ++generation
        val loading = DictionaryLookupState(requestId, UUID.randomUUID().toString(), word, loading = true)
        mutable.value = loading
        work = viewModelScope.launch {
            val result = try {
                val found = dictionary.lookup(word)
                if (found.query != word || found.entries.isEmpty()) throw DictionaryException(DictionaryFailure.PROVIDER)
                loading.copy(loading = false, result = found)
            } catch (cancelled: CancellationException) {
                if (admitted == generation && mutable.value === loading) mutable.value = null
                throw cancelled
            }
            catch (failure: DictionaryException) { loading.copy(loading = false, failure = failure.reason) }
            catch (_: Exception) { loading.copy(loading = false, failure = DictionaryFailure.PROVIDER) }
            if (admitted == generation && mutable.value === loading) mutable.value = result
        }
    }
}

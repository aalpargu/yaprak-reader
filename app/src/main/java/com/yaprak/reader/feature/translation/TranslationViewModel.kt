package com.yaprak.reader.feature.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.translation.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TranslationPhase { CHECKING, MISSING, READY, DOWNLOADING, TRANSLATING, DELETING, ERROR }
enum class TranslationCopyStatus { NONE, COPIED, FAILED }
data class TranslationState(
    val requestId: String, val attemptId: String, val sourceText: String,
    val phase: TranslationPhase, val working: Boolean = false, val modelsReady: Boolean = false,
    val translatedText: String? = null, val failure: TranslationFailure? = null,
    val copyStatus: TranslationCopyStatus = TranslationCopyStatus.NONE,
) {
    override fun toString() = "TranslationState(requestId=$requestId, phase=$phase, working=$working)"
}

/** Ephemeral source-bound state; model management and SDK work remain in the adapter. */
class TranslationViewModel(private val service: EnglishTurkishTranslation) : ViewModel() {
    private val mutable = MutableStateFlow<TranslationState?>(null)
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0L
    private var current: (() -> Boolean)? = null

    fun open(requestId: String, text: String, isCurrent: () -> Boolean): Boolean {
        if (requestId.isBlank() || runCatching { validateTranslationText(text) }.isFailure || !isCurrent()) return false
        if (mutable.value?.let { it.requestId == requestId && it.sourceText == text } == true) return false
        close(); current = isCurrent
        val initial = TranslationState(requestId, UUID.randomUUID().toString(), text, TranslationPhase.CHECKING)
        mutable.value = initial
        return checkModels(initial)
    }
    fun checkModels(expected: TranslationState): Boolean = start(expected, TranslationPhase.CHECKING) { state ->
        when (service.models()) {
            TranslationModels.READY -> state.copy(phase = TranslationPhase.READY, modelsReady = true, working = false)
            TranslationModels.MISSING -> state.copy(phase = TranslationPhase.MISSING, modelsReady = false, working = false)
            TranslationModels.DOWNLOADING -> state.copy(phase = TranslationPhase.DOWNLOADING, modelsReady = false, working = false)
        }
    }
    fun download(expected: TranslationState): Boolean {
        if (expected.phase != TranslationPhase.MISSING) return false
        return start(expected, TranslationPhase.DOWNLOADING) { state ->
            service.downloadOverWifi()
            state.copy(phase = TranslationPhase.READY, modelsReady = true, working = false)
        }
    }
    fun translate(expected: TranslationState): Boolean {
        if (!expected.modelsReady) return false
        return start(expected, TranslationPhase.TRANSLATING) { state ->
            val translated = service.translate(state.sourceText)
            try { validateTranslationText(translated) } catch (error: TranslationException) {
                throw TranslationException(if (error.reason == TranslationFailure.LIMIT) TranslationFailure.LIMIT else TranslationFailure.TRANSLATE)
            }
            state.copy(phase = TranslationPhase.READY, working = false, translatedText = translated)
        }
    }
    fun deleteModel(expected: TranslationState): Boolean {
        if (!expected.modelsReady) return false
        return start(expected, TranslationPhase.DELETING) { state ->
            service.deleteTurkishModel()
            state.copy(phase = TranslationPhase.MISSING, modelsReady = false, working = false)
        }
    }
    fun copy(expected: TranslationState, write: (String) -> Unit): Boolean {
        if (!fresh(expected) || expected.working || expected.translatedText == null || !expected.modelsReady) return false
        val status = try { write(expected.translatedText); TranslationCopyStatus.COPIED }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { TranslationCopyStatus.FAILED }
        if (fresh(expected)) mutable.value = expected.copy(copyStatus = status)
        return true
    }
    fun close() {
        generation++; work?.cancel(); work = null; mutable.value = null; current = null
    }
    override fun onCleared() { close(); super.onCleared() }
    private fun fresh(expected: TranslationState) = mutable.value === expected && current?.invoke() == true
    private fun start(expected: TranslationState, phase: TranslationPhase,
        operation: suspend (TranslationState) -> TranslationState): Boolean {
        if (!fresh(expected) || expected.working) return false
        work?.cancel(); val admitted = ++generation
        val pending = expected.copy(attemptId = UUID.randomUUID().toString(), phase = phase, working = true,
            translatedText = null, failure = null, copyStatus = TranslationCopyStatus.NONE)
        mutable.value = pending
        work = viewModelScope.launch {
            val result = try { operation(pending) }
            catch (cancelled: CancellationException) {
                if (admitted == generation && mutable.value === pending) mutable.value = null
                throw cancelled
            }
            catch (error: TranslationException) {
                pending.copy(phase = if (error.reason == TranslationFailure.MODEL_MISSING) TranslationPhase.MISSING else TranslationPhase.ERROR,
                    working = false, modelsReady = pending.modelsReady && error.reason !in setOf(
                        TranslationFailure.MODEL_MISSING, TranslationFailure.MODELS, TranslationFailure.DELETE), failure = error.reason)
            }
            catch (_: Exception) {
                val failure = when (phase) {
                    TranslationPhase.CHECKING -> TranslationFailure.MODELS
                    TranslationPhase.DOWNLOADING -> TranslationFailure.DOWNLOAD
                    TranslationPhase.DELETING -> TranslationFailure.DELETE
                    else -> TranslationFailure.TRANSLATE
                }
                pending.copy(phase = TranslationPhase.ERROR, working = false, failure = failure,
                    modelsReady = pending.modelsReady && failure !in setOf(TranslationFailure.MODELS, TranslationFailure.DELETE))
            }
            currentCoroutineContext().ensureActive()
            if (admitted == generation && mutable.value === pending) {
                if (current?.invoke() == true) mutable.value = result else close()
            }
        }
        return true
    }
}

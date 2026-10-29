package com.yaprak.reader.feature.passages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubPassageRepository
import com.yaprak.reader.domain.passages.MAX_EPUB_ATTACHED_NOTE_LENGTH
import com.yaprak.reader.domain.passages.isWellFormedEpubAnnotationText
import com.yaprak.reader.reader.EpubSourceAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A UUID identifies this deliberate selection session, including repeated selections of identical text. */
internal data class EpubAnnotationRequest(val sessionId: String, val bookId: String, val contentSha256: String,
    val text: String, val anchor: EpubSourceAnchor)

internal data class EpubAnnotationState(val color: EpubHighlightColor? = EpubHighlightColor.YELLOW,
    val note: String = "", val busy: Boolean = false, val failed: Boolean = false,
    val completed: Boolean = false, val closed: Boolean = false, val revision: Long = 0) {
    val noteValid get() = validAttachedNoteDraft(note)
    val canSave get() = !busy && !completed && !closed && noteValid && (color != null || note.isNotEmpty())
}

internal fun validAttachedNoteDraft(note: String): Boolean = note.isEmpty() ||
    (note.isNotBlank() && note.length <= MAX_EPUB_ATTACHED_NOTE_LENGTH && isWellFormedEpubAnnotationText(note))

/** Keeps exact user text; the Activity proves that this selection session is still usable. */
internal class EpubAnnotationViewModel(private val request: EpubAnnotationRequest,
    private val repository: EpubPassageRepository) : ViewModel() {
    private val mutable = MutableStateFlow(EpubAnnotationState())
    val state = mutable.asStateFlow()

    fun editColor(color: EpubHighlightColor?) {
        if (!state.value.busy && !state.value.closed && !state.value.completed)
            mutable.update { it.copy(color = color, failed = false, revision = it.revision + 1) }
    }
    fun editNote(note: String) {
        if (!state.value.busy && !state.value.closed && !state.value.completed)
            mutable.update { it.copy(note = note, failed = false, revision = it.revision + 1) }
    }
    fun close() {
        mutable.update { it.copy(closed = true, color = EpubHighlightColor.YELLOW, note = "", failed = false,
            revision = it.revision + 1) }
    }
    fun save() {
        val current = state.value
        if (!current.canSave) return
        mutable.update { it.copy(busy = true, failed = false, revision = it.revision + 1) }
        viewModelScope.launch {
            try {
                repository.createAnnotation(request.bookId, request.contentSha256, request.text,
                    request.anchor, current.color, current.note.takeUnless(String::isEmpty))
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(busy = false, completed = !it.closed, revision = it.revision + 1) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(failed = !it.closed, revision = it.revision + 1) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}

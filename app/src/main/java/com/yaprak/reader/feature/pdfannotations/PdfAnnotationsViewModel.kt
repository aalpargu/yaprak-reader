package com.yaprak.reader.feature.pdfannotations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.reader.PdfSourceAnchor
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The Activity cancels the lifetime synchronously on pause, before a pending native proof can commit. */
internal class PdfAnnotationRequest(val bookId: String, val hash: String, val text: String, val anchor: PdfSourceAnchor,
    val expected: PdfAnnotation? = null, parent: Job? = null, val id: String = UUID.randomUUID().toString()) {
    val lifetime = SupervisorJob(parent)
    fun close() = lifetime.cancel()
}
internal class PdfAnnotationsWindow(val bookId: String, val hash: String, val editingSupported: Boolean,
    val id: String = UUID.randomUUID().toString()) {
    val lifetime = SupervisorJob()
    fun close() = lifetime.cancel()
}
internal enum class PdfAnnotationError { SOURCE, CHANGED, LIMIT }
internal data class PdfAnnotationEditorState(val color: PdfHighlightColor?, val note: String,
    val busy: Boolean = false, val completed: Boolean = false, val closed: Boolean = false,
    val error: PdfAnnotationError? = null, val revision: Long = 0) {
    val noteValid get() = note.isEmpty() || runCatching { validatePdfAnnotationText(note, MAX_PDF_ATTACHED_NOTE) }.isSuccess
    val canSave get() = !busy && !completed && !closed && error != PdfAnnotationError.CHANGED && noteValid && (color != null || note.isNotEmpty())
}
internal class PdfAnnotationEditorViewModel(private val request: PdfAnnotationRequest,
    private val repository: PdfAnnotationRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PdfAnnotationEditorState(request.expected?.highlightColor ?: PdfHighlightColor.YELLOW,
        request.expected?.attachedNote ?: ""))
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var write: Job? = null
    init {
        // A note-only existing record keeps its deliberate absence of color.
        if (request.expected != null) mutable.value = mutable.value.copy(color = request.expected.highlightColor)
        request.expected?.let { expected -> observation = viewModelScope.launch(request.lifetime) {
            try { repository.observe(request.bookId, request.hash).collect { records ->
                if (!state.value.busy && !state.value.completed && records.singleOrNull { it.id == expected.id } != expected)
                    mutable.update { it.copy(error = PdfAnnotationError.CHANGED, revision = it.revision + 1) }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); mutable.update { it.copy(error = PdfAnnotationError.SOURCE) } }
        } }
    }
    fun editColor(color: PdfHighlightColor?) { if (editable()) mutable.update { it.copy(color=color,error=null,revision=it.revision+1) } }
    fun editNote(note: String) { if (editable()) mutable.update { it.copy(note=note,error=null,revision=it.revision+1) } }
    private fun editable() = request.lifetime.isActive && !state.value.busy && !state.value.completed && !state.value.closed && state.value.error != PdfAnnotationError.CHANGED
    fun save(canMutate: () -> Boolean) {
        val current = state.value
        if (!current.canSave || !request.lifetime.isActive || !canMutate()) return
        mutable.value = current.copy(busy=true,error=null,revision=current.revision+1)
        val job = viewModelScope.launch(request.lifetime, start=CoroutineStart.LAZY) {
            try {
                ensureActive(); if (!canMutate()) return@launch
                val note = current.note.takeUnless(String::isEmpty)
                if (request.expected == null) repository.create(request.bookId,request.hash,request.text,request.anchor,current.color,note)
                else repository.update(request.bookId,request.hash,request.expected,current.color,note)
                ensureActive()
                if (canMutate() && request.lifetime.isActive) mutable.update { it.copy(completed=true,revision=it.revision+1) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                ensureActive()
                if (canMutate() && request.lifetime.isActive) mutable.update { it.copy(error=pdfAnnotationError(failure),revision=it.revision+1) }
            } finally { mutable.update { it.copy(busy=false) } }
        }
        write=job; job.start()
    }
    fun close() { request.close(); observation?.cancel(); write?.cancel(); mutable.update { it.copy(closed=true,busy=false,note="",revision=it.revision+1) } }
    override fun onCleared() { close(); super.onCleared() }
}

internal data class PdfAnnotationsState(val entries: List<PdfAnnotation> = emptyList(), val query: String = "",
    val loading: Boolean = true, val failed: Boolean = false, val closed: Boolean = false,
    val editor: PdfAnnotationRequest? = null, val deleteCandidate: PdfAnnotation? = null,
    val busy: Boolean = false, val error: PdfAnnotationError? = null, val revision: Long = 0) {
    val visibleEntries get() = if (query.isBlank()) entries else {
        val terms = query.lowercase(java.util.Locale.ROOT).replace('ı','i').replace("i\u0307","i").split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)
        entries.filter { annotation ->
            val text = (annotation.text+"\n"+(annotation.attachedNote?:"")).lowercase(java.util.Locale.ROOT).replace('ı','i').replace("i\u0307","i")
            terms.all(text::contains)
        }
    }
    val canAct get() = !loading && !failed && !closed && !busy && editor == null
}
internal class PdfAnnotationsViewModel(private val request: PdfAnnotationsWindow, private val repository: PdfAnnotationRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PdfAnnotationsState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var action: Job? = null
    init { reload() }
    fun reload() {
        if (!request.lifetime.isActive || state.value.closed || state.value.busy || state.value.editor != null) return
        observation?.cancel(); mutable.update { it.copy(loading=true,failed=false,deleteCandidate=null,error=null,revision=it.revision+1) }
        observation=viewModelScope.launch(request.lifetime) {
            try { repository.observe(request.bookId,request.hash).collect { records ->
                ensureActive(); mutable.update { it.copy(entries=records.toList(),loading=false,failed=false,
                    deleteCandidate=it.deleteCandidate?.takeIf { expected -> records.singleOrNull { row -> row.id==expected.id }==expected },revision=it.revision+1) }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ensureActive(); mutable.update { it.copy(entries=emptyList(),loading=false,failed=true,deleteCandidate=null,revision=it.revision+1) } }
        }
    }
    fun search(query: String) {
        if (state.value.closed || state.value.busy || state.value.editor!=null) return
        mutable.update { it.copy(query=com.yaprak.reader.domain.notes.limitLibraryNoteQuery(query),deleteCandidate=null,revision=it.revision+1) }
    }
    private fun current(expected: PdfAnnotation) = request.lifetime.isActive && state.value.canAct &&
        state.value.visibleEntries.singleOrNull { it.id==expected.id }==expected
    fun edit(expected: PdfAnnotation) {
        if (!current(expected) || !request.editingSupported || expected.revision==Long.MAX_VALUE || state.value.deleteCandidate!=null) return
        val editor=PdfAnnotationRequest(request.bookId,request.hash,expected.text,expected.anchor,expected,request.lifetime)
        mutable.update { it.copy(editor=editor,error=null,revision=it.revision+1) }
    }
    fun closeEditor(expected: PdfAnnotationRequest) { if (state.value.editor===expected) { expected.close(); mutable.update { it.copy(editor=null,revision=it.revision+1) } } }
    fun askDelete(expected: PdfAnnotation) { if (current(expected)) mutable.update { it.copy(deleteCandidate=expected,error=null,revision=it.revision+1) } }
    fun cancelDelete(expected: PdfAnnotation) { if (!state.value.busy && state.value.deleteCandidate==expected) mutable.update { it.copy(deleteCandidate=null,revision=it.revision+1) } }
    fun delete(expected: PdfAnnotation, canMutate: () -> Boolean) {
        if (!current(expected) || state.value.deleteCandidate!=expected || !canMutate()) return
        perform(canMutate) { repository.remove(request.bookId,request.hash,expected) }
    }
    fun openPage(expected: PdfAnnotation, canMutate: () -> Boolean, goTo: (PdfSourceAnchor) -> Boolean) {
        if (!current(expected) || state.value.deleteCandidate!=null || !canMutate()) return
        perform(canMutate) {
            val found=repository.observe(request.bookId,request.hash).first().singleOrNull { it.id==expected.id }
            if (found!=expected) throw PdfAnnotationChangedException()
            // Older Android retains page access without pretending it can re-prove native text geometry.
            if (request.editingSupported) repository.verifyExisting(request.bookId,request.hash,listOf(expected))
            ensureActive()
            if (!canMutate() || !request.lifetime.isActive) return@perform
            val latest=repository.observe(request.bookId,request.hash).first().singleOrNull { it.id==expected.id }
            if (latest!=expected) throw PdfAnnotationChangedException()
            ensureActive()
            if (canMutate() && request.lifetime.isActive && state.value.entries.singleOrNull { it.id==expected.id }==expected)
                check(goTo(expected.anchor))
        }
    }
    private fun perform(allowed: () -> Boolean, block: suspend CoroutineScope.() -> Unit) {
        mutable.update { it.copy(busy=true,error=null,revision=it.revision+1) }
        val job=viewModelScope.launch(request.lifetime,start=CoroutineStart.LAZY) {
            try { ensureActive(); if (allowed()) block(); ensureActive(); if (allowed()) mutable.update { it.copy(deleteCandidate=null) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { ensureActive(); if(allowed()) mutable.update { it.copy(error=pdfAnnotationError(failure),deleteCandidate=null,revision=it.revision+1) } }
            finally { mutable.update { it.copy(busy=false) } }
        }
        action=job; job.start()
    }
    fun close() { request.close(); observation?.cancel(); action?.cancel(); state.value.editor?.close(); mutable.update { it.copy(closed=true,entries=emptyList(),editor=null,deleteCandidate=null,revision=it.revision+1) } }
    override fun onCleared() { close(); super.onCleared() }
}
private fun pdfAnnotationError(failure: Exception) = when(failure) {
    is PdfAnnotationChangedException -> PdfAnnotationError.CHANGED
    is PdfAnnotationLimitException -> PdfAnnotationError.LIMIT
    else -> PdfAnnotationError.SOURCE
}

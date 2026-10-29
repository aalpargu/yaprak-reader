package com.yaprak.reader.formats.pdf

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.pdfannotations.PdfAnnotation
import com.yaprak.reader.domain.pdfannotations.PdfAnnotationRepository
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal sealed interface PdfAnnotationPaintState {
    data object Loading : PdfAnnotationPaintState
    data class Ready(val annotations: List<PdfAnnotation>) : PdfAnnotationPaintState
    data object Unsupported : PdfAnnotationPaintState
    class Failed : PdfAnnotationPaintState
}
internal const val MAX_PDF_PAINT_BOUNDS = 8192

/** Stored geometry reaches the renderer only after fresh original-file/native proof. Main-thread owner. */
internal class PdfAnnotationPaintController(private val book: Book, private val repository: PdfAnnotationRepository,
    private val nativeSupported: Boolean, private val active: () -> Boolean) {
    private val mutable = MutableStateFlow<PdfAnnotationPaintState>(PdfAnnotationPaintState.Loading)
    val state = mutable.asStateFlow()
    private var serial = 0L
    private var job: Job? = null
    private var ownerScope: CoroutineScope? = null
    private var ownerPages: Flow<List<Int>>? = null

    fun start(scope: CoroutineScope, pages: Flow<List<Int>>) {
        stop()
        ownerScope = scope; ownerPages = pages
        val generation = ++serial
        val collector = scope.launch(start = CoroutineStart.LAZY) {
            try {
                combine(repository.observe(book.id, book.sha256), pages) { stored, visible -> stored to visible }
                    .collectLatest { (stored, visible) ->
                        currentCoroutineContext().ensureActive()
                        if (generation != serial || !active()) return@collectLatest
                        mutable.value = PdfAnnotationPaintState.Loading
                        val selected = stored.filter { it.highlightColor != null && it.anchor.pageIndex in visible }
                        if (selected.isEmpty()) {
                            mutable.value = PdfAnnotationPaintState.Ready(emptyList())
                        } else if (selected.sumOf { it.anchor.bounds.size } > MAX_PDF_PAINT_BOUNDS) {
                            // No partial paint success: the bounded list/export paths remain available.
                            mutable.value = PdfAnnotationPaintState.Failed()
                        } else if (!nativeSupported) {
                            mutable.value = PdfAnnotationPaintState.Unsupported
                        } else {
                            try {
                                repository.verifyExisting(book.id, book.sha256, selected)
                                currentCoroutineContext().ensureActive()
                                if (generation == serial && active()) mutable.value = PdfAnnotationPaintState.Ready(
                                    Collections.unmodifiableList(ArrayList(selected)))
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                currentCoroutineContext().ensureActive()
                                if (generation == serial && active()) mutable.value = PdfAnnotationPaintState.Failed()
                            }
                        }
                    }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == serial && active()) mutable.value = PdfAnnotationPaintState.Failed()
            }
        }
        job = collector; collector.start()
    }
    fun retry(expected: PdfAnnotationPaintState) {
        if (state.value !== expected || expected !is PdfAnnotationPaintState.Failed || !active()) return
        val scope = ownerScope?.takeIf { it.isActive } ?: return
        start(scope, ownerPages ?: return)
    }
    fun stop() {
        ++serial; job?.cancel(); job = null
        ownerScope = null; ownerPages = null
        mutable.value = PdfAnnotationPaintState.Loading
    }
}

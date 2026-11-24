package com.yaprak.reader.formats.pdf

import com.yaprak.reader.reader.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PdfSelectionPreview(val page: Int, val start: PdfTextPoint, val stop: PdfTextPoint,
    val selection: PdfSelectedText)

/** One immutable gesture belongs to one still-rendered native page. Nothing is persisted. */
internal class PdfTextSelectionSource(private val document: PdfDocument,
    private val isVisible: (Int) -> Boolean) : TextSelectionSource {
    private data class Request(val page: Int, val start: PdfTextPoint, val stop: PdfTextPoint,
        val id: String = UUID.randomUUID().toString())
    private var pending: Request? = null
    private val notifications = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val requests = notifications.asSharedFlow()
    private val displayed = MutableStateFlow<PdfSelectionPreview?>(null)
    val preview = displayed.asStateFlow()

    /** Bounded native decoding; only the latest still-visible gesture may publish geometry. */
    suspend fun preview(page: Int, start: PdfTextPoint, stop: PdfTextPoint) {
        if (!accept(page, start, stop, notify = false)) return
        val request = pending ?: return
        val result = document.selectText(page, start, stop)
        currentCoroutineContext().ensureActive()
        if (pending === request && isCurrent(request.id)) {
            displayed.value = result?.let {
                val anchor = it.sourceAnchor
                val first = anchor?.bounds?.firstOrNull()
                val last = anchor?.bounds?.lastOrNull()
                // A collapsed native request selects a whole word. Subsequent handle drags
                // must retain its edges, rather than the original finger point mid-word.
                val from = if (start == stop && anchor != null && first != null)
                    PdfTextPoint(((first.left - .5) / anchor.pageWidth).coerceIn(0.0, 1.0), (first.top + first.bottom) / 2 / anchor.pageHeight) else start
                val to = if (start == stop && anchor != null && last != null)
                    PdfTextPoint(((last.right + .5) / anchor.pageWidth).coerceIn(0.0, 1.0), (last.top + last.bottom) / 2 / anchor.pageHeight) else stop
                PdfSelectionPreview(page, from, to, it)
            }
            if (result == null) pending = null
        }
    }

    fun showTools() { if (active && displayed.value != null) notifications.tryEmit(Unit) }
    fun clearPage(page: Int) { if (pending?.page == page) clear() }

    fun accept(page: Int, start: PdfTextPoint, stop: PdfTextPoint, notify: Boolean = true): Boolean {
        if (!document.textAvailable || page !in 0 until document.pageCount || !start.isValid || !stop.isValid || !isVisible(page)) return false
        pending = Request(page, start, stop)
        if (notify) notifications.tryEmit(Unit)
        return true
    }
    override val active: Boolean get() = isCurrent(null)
    override fun clear() { pending = null; displayed.value = null }
    override fun isCurrent(requestId: String?): Boolean {
        val request = pending ?: return false
        return (requestId == null || requestId == request.id) && document.isOpen && isVisible(request.page)
    }
    override suspend fun capture(): SelectedText? {
        val request = pending ?: return null
        fun current() {
            if (pending !== request || !isCurrent(request.id)) throw CancellationException("PDF selection is no longer current")
        }
        currentCoroutineContext().ensureActive(); current()
        val selection = document.selectText(request.page, request.start, request.stop)
        currentCoroutineContext().ensureActive(); current()
        return selection?.let { SelectedText(it.text, pdfPageIndex = it.pageIndex, sourceSelectionId = request.id,
            pdfSourceAnchor = it.sourceAnchor) }
    }
}

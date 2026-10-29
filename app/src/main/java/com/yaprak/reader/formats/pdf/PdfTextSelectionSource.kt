package com.yaprak.reader.formats.pdf

import com.yaprak.reader.reader.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One immutable gesture belongs to one still-rendered native page. Nothing is persisted. */
internal class PdfTextSelectionSource(private val document: PdfDocument,
    private val isVisible: (Int) -> Boolean) : TextSelectionSource {
    private data class Request(val page: Int, val start: PdfTextPoint, val stop: PdfTextPoint,
        val id: String = UUID.randomUUID().toString())
    private var pending: Request? = null
    private val notifications = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val requests = notifications.asSharedFlow()

    fun accept(page: Int, start: PdfTextPoint, stop: PdfTextPoint): Boolean {
        if (!document.textAvailable || page !in 0 until document.pageCount || !start.isValid || !stop.isValid || !isVisible(page)) return false
        pending = Request(page, start, stop)
        notifications.tryEmit(Unit)
        return true
    }
    override val active: Boolean get() = isCurrent(null)
    override fun clear() { pending = null }
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

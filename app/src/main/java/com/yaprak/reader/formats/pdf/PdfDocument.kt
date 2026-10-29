package com.yaprak.reader.formats.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Build
import androidx.annotation.RequiresApi
import com.yaprak.reader.reader.BookSearchException
import com.yaprak.reader.reader.BookSearchFailure
import com.yaprak.reader.reader.PdfTextPoint
import com.yaprak.reader.reader.PdfSelectedText
import com.yaprak.reader.reader.PdfSourceAnchor
import com.yaprak.reader.reader.PdfTextBounds
import com.yaprak.reader.reader.nativePdfPoint
import com.yaprak.reader.reader.TextSelectionException
import com.yaprak.reader.reader.TextSelectionFailure
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes native page rendering and disposal; none of that work runs on the UI thread. */
internal class PdfDocument(
    private val renderer: PdfNativeRenderer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    val pageCount = renderer.pageCount
    private val lock = Mutex()
    private val closed = AtomicBoolean(false)
    private val closeResult = CompletableDeferred<Unit>()
    val textSupported get() = renderer.textAvailable
    val textAvailable get() = renderer.textAvailable && !closed.get()
    val linksSupported get() = renderer.linksAvailable
    val linksAvailable get() = renderer.linksAvailable && !closed.get()
    val isOpen get() = !closed.get()

    init { require(pageCount > 0) }

    suspend fun render(pageIndex: Int, viewportWidth: Int): Bitmap =
        renderSized(pageIndex) { width, height -> pdfBitmapSize(width, height, viewportWidth) }

    /** Original colors, independent of the reader's fit, night mode and requested page. */
    suspend fun renderThumbnail(pageIndex: Int): Bitmap = renderSized(pageIndex, ::pdfThumbnailSize)

    /** Original dimensions only, in the same serialized native lease as rendering. No bitmap allocation. */
    suspend fun pageDimensions(pageIndex: Int): PdfPageDimensions = withContext(io) {
        require(pageIndex in 0 until pageCount)
        lock.withLock {
            ensureActive(); check(!closed.get())
            renderer.openPage(pageIndex).use { page ->
                PdfPageDimensions(page.width, page.height).also { ensureActive(); check(!closed.get()) }
            }
        }
    }

    /** Page-local GoTo destinations exposed by Android; not an exhaustive PDF annotation parser.
     * Android 15's native implementation can omit bare /Dest link annotations.
     * No URL, outline inference or bitmap allocation.
     */
    suspend fun gotoLinks(pageIndex: Int): List<PdfNativeGotoLink> = withContext(io) {
        require(pageIndex in 0 until pageCount)
        lock.withLock {
            ensureActive(); check(!closed.get())
            if (!renderer.linksAvailable) throw com.yaprak.reader.reader.PdfPageLinksException(
                com.yaprak.reader.reader.PdfPageLinksFailure.UNSUPPORTED)
            renderer.openPage(pageIndex).use { page ->
                val links = page.gotoLinks()
                ensureActive(); check(!closed.get())
                validatePdfGotoLinks(links, pageCount)
                links.toList()
            }
        }
    }

    /** Shares the actual native page lease with rendering; no bitmap, OCR or file mutation. */
    suspend fun extractText(pageIndex: Int): PdfExtractedText = withContext(io) {
        require(pageIndex in 0 until pageCount)
        lock.withLock {
            ensureActive()
            check(!closed.get())
            if (!renderer.textAvailable) throw BookSearchException(BookSearchFailure.UNSUPPORTED)
            renderer.openPage(pageIndex).use { page ->
                val blocks = page.textContents()
                ensureActive(); check(!closed.get())
                val text = boundedPdfText(blocks)
                ensureActive(); check(!closed.get())
                text
            }
        }
    }

    /** Native point selection uses original page geometry inside the same live page lease. */
    suspend fun selectText(pageIndex: Int, start: PdfTextPoint, stop: PdfTextPoint): PdfSelectedText? = withContext(io) {
        if (pageIndex !in 0 until pageCount || !start.isValid || !stop.isValid)
            throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
        lock.withLock {
            ensureActive(); check(!closed.get())
            if (!renderer.textAvailable) throw TextSelectionException(TextSelectionFailure.UNSUPPORTED)
            renderer.openPage(pageIndex).use { page ->
                val selected = page.selectText(start, stop)
                ensureActive(); check(!closed.get())
                selected?.also {
                    if (it.pageIndex != pageIndex || !validPdfText(it.text))
                        throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
                    if (it.text.length > 5000) throw TextSelectionException(TextSelectionFailure.TOO_LONG)
                    it.sourceAnchor?.let { anchor ->
                        if (anchor.pageIndex != pageIndex || anchor.pageWidth != page.width || anchor.pageHeight != page.height ||
                            anchor.start != nativePdfPoint(start, page.width, page.height) ||
                            anchor.stop != nativePdfPoint(stop, page.width, page.height))
                            throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
                    }
                }?.takeIf { it.text.isNotBlank() }
            }
        }
    }

    private suspend fun renderSized(pageIndex: Int, size: (Int, Int) -> Pair<Int, Int>): Bitmap {
        require(pageIndex in 0 until pageCount)
        var allocated: Bitmap? = null
        try {
            return withContext(io) {
                lock.withLock {
                    ensureActive()
                    check(!closed.get())
                    renderer.openPage(pageIndex).use { page ->
                        val (width, height) = size(page.width, page.height)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        allocated = bitmap
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap)
                        ensureActive()
                        check(!closed.get())
                        bitmap
                    }
                }
            }
        } catch (error: Throwable) {
            // Includes cancellation while the IO result is being handed back to the UI.
            allocated?.recycle()
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Native render is not interruptible. Close waits for it without blocking Activity teardown.
        CoroutineScope(io).launch {
            try { lock.withLock { renderer.close() }; closeResult.complete(Unit) }
            catch (failure: Throwable) { closeResult.completeExceptionally(failure) }
        }
    }

    /** Index extraction keeps its file lease until native disposal completes, even if cancelled. */
    suspend fun closeAndAwait() = withContext(NonCancellable) { close(); closeResult.await() }

    companion object {
        /** Caller opens on IO and owns the returned document even during cancellation. */
        fun open(file: File): PdfDocument {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) {
                descriptor.close()
                throw error
            }
            return try {
                PdfDocument(AndroidPdfNativeRenderer(renderer))
            } catch (error: Throwable) {
                renderer.close()
                throw error
            }
        }
    }
}

/** Thin native seam for serialization, cancellation and bitmap ownership tests. */
internal interface PdfNativeRenderer : Closeable {
    val pageCount: Int
    val textAvailable: Boolean get() = false
    val linksAvailable: Boolean get() = false
    fun openPage(pageIndex: Int): PdfNativePage
}

internal interface PdfNativePage : Closeable {
    val width: Int
    val height: Int
    fun render(bitmap: Bitmap)
    fun textContents(): List<String> = throw BookSearchException(BookSearchFailure.UNSUPPORTED)
    fun gotoLinks(): List<PdfNativeGotoLink> = throw com.yaprak.reader.reader.PdfPageLinksException(
        com.yaprak.reader.reader.PdfPageLinksFailure.UNSUPPORTED)
    fun selectText(start: PdfTextPoint, stop: PdfTextPoint): PdfSelectedText? =
        throw TextSelectionException(TextSelectionFailure.UNSUPPORTED)
}

private class AndroidPdfNativeRenderer(private val renderer: PdfRenderer) : PdfNativeRenderer {
    override val pageCount get() = renderer.pageCount
    override val textAvailable get() = Build.VERSION.SDK_INT >= 35
    override val linksAvailable get() = Build.VERSION.SDK_INT >= 35
    override fun openPage(pageIndex: Int): PdfNativePage {
        val page = renderer.openPage(pageIndex)
        return object : PdfNativePage {
            override val width get() = page.width
            override val height get() = page.height
            override fun render(bitmap: Bitmap) =
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            override fun textContents(): List<String> {
                if (Build.VERSION.SDK_INT < 35) throw BookSearchException(BookSearchFailure.UNSUPPORTED)
                return nativePdfText(page)
            }
            override fun gotoLinks(): List<PdfNativeGotoLink> {
                if (Build.VERSION.SDK_INT < 35) throw com.yaprak.reader.reader.PdfPageLinksException(
                    com.yaprak.reader.reader.PdfPageLinksFailure.UNSUPPORTED)
                return nativePdfGotoLinks(page)
            }
            override fun selectText(start: PdfTextPoint, stop: PdfTextPoint): PdfSelectedText? {
                if (Build.VERSION.SDK_INT < 35) throw TextSelectionException(TextSelectionFailure.UNSUPPORTED)
                return nativePdfSelection(page, start, stop)
            }
            override fun close() = page.close()
        }
    }
    override fun close() = renderer.close()
}

@RequiresApi(35)
private fun nativePdfGotoLinks(page: PdfRenderer.Page): List<PdfNativeGotoLink> {
    val links = page.gotoLinks
    if (links.size > MAX_PDF_PAGE_LINKS) throw com.yaprak.reader.reader.PdfPageLinksException(
        com.yaprak.reader.reader.PdfPageLinksFailure.LIMIT)
    return links.map { link -> link.destination.let {
        PdfNativeGotoLink(it.pageNumber, it.xCoordinate.toDouble(), it.yCoordinate.toDouble(), it.zoom.toDouble())
    } }
}

/** Kept behind a version gate so old Android can still render PDFs normally. */
@RequiresApi(35)
private fun nativePdfText(page: PdfRenderer.Page): List<String> {
    val contents = page.textContents
    if (contents.size > MAX_PDF_TEXT_BLOCKS) throw BookSearchException(BookSearchFailure.LIMIT)
    var length = 0L
    return contents.map {
        val text = it.text
        length += text.length.toLong() + 1
        if (length > MAX_PDF_PAGE_TEXT) throw BookSearchException(BookSearchFailure.LIMIT)
        text
    }
}

@RequiresApi(35)
private fun nativePdfSelection(page: PdfRenderer.Page, start: PdfTextPoint, stop: PdfTextPoint): PdfSelectedText? {
    if (page.width <= 0 || page.height <= 0) throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
    val nativeStart = nativePdfPoint(start, page.width, page.height)
    val nativeStop = nativePdfPoint(stop, page.width, page.height)
    val selected = page.selectContent(
        android.graphics.pdf.models.selection.SelectionBoundary(android.graphics.Point(nativeStart.x, nativeStart.y)),
        android.graphics.pdf.models.selection.SelectionBoundary(android.graphics.Point(nativeStop.x, nativeStop.y))) ?: return null
    if (selected.page != page.index) throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
    val contents = selected.selectedTextContents
    if (contents.size > 5000) throw TextSelectionException(TextSelectionFailure.TOO_LONG)
    var length = 0L
    val texts = contents.map {
        val text = it.text
        length += text.length.toLong() + 1
        if (length > 5001) throw TextSelectionException(TextSelectionFailure.TOO_LONG)
        text
    }
    // Unbounded/over-budget/invalid native geometry may still be copied as text. It cannot be an annotation anchor.
    val bounds = ArrayList<PdfTextBounds>()
    var bounded = true
    for (content in contents) {
        val rectangles = content.bounds
        if (rectangles.isEmpty() || rectangles.size > PdfSourceAnchor.MAX_BOUNDS - bounds.size) { bounded = false; break }
        rectangles.forEach { bounds += PdfTextBounds(it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble()) }
    }
    val anchor = if (bounded) try {
        PdfSourceAnchor(selected.page, page.width, page.height, nativeStart, nativeStop, bounds)
    } catch (_: IllegalArgumentException) { null } else null
    return PdfSelectedText(selected.page, texts.joinToString("\n"), anchor)
}

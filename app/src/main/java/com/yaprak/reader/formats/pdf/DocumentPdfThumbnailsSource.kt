package com.yaprak.reader.formats.pdf

import android.graphics.Bitmap
import com.yaprak.reader.reader.PdfThumbnailsSource
import java.io.Closeable
import java.util.LinkedHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val PDF_THUMBNAIL_WIDTH = 240
internal const val PDF_THUMBNAIL_HEIGHT = 320
internal const val MAX_PDF_THUMBNAIL_BYTES = PDF_THUMBNAIL_WIDTH * PDF_THUMBNAIL_HEIGHT * 4
internal const val MAX_PDF_THUMBNAIL_CACHE_ENTRIES = 12
internal const val MAX_PDF_THUMBNAIL_CACHE_BYTES = 4L * 1024 * 1024

/** Native page dimensions are points: small vector pages can be safely enlarged. */
internal fun pdfThumbnailSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = minOf(PDF_THUMBNAIL_WIDTH.toDouble() / width, PDF_THUMBNAIL_HEIGHT.toDouble() / height)
    return (width * scale).toInt().coerceIn(1, PDF_THUMBNAIL_WIDTH) to
        (height * scale).toInt().coerceIn(1, PDF_THUMBNAIL_HEIGHT)
}

/** Session-only previews. Source bookkeeping never waits on native render during close. */
internal class DocumentPdfThumbnailsSource(
    override val pageCount: Int,
    private val renderPage: suspend (Int) -> Bitmap,
    private val maxCacheEntries: Int = MAX_PDF_THUMBNAIL_CACHE_ENTRIES,
    private val maxCacheBytes: Long = MAX_PDF_THUMBNAIL_CACHE_BYTES,
) : PdfThumbnailsSource, Closeable {
    constructor(document: PdfDocument) : this(document.pageCount, document::renderThumbnail)

    private data class Entry(val bitmap: Bitmap, val bytes: Long)
    private val requests = Mutex()
    private val cacheLock = Any()
    private val cache = LinkedHashMap<Int, Entry>(16, 0.75f, true)
    private var cachedBytes = 0L
    private var closed = false

    init {
        require(pageCount > 0)
        require(maxCacheEntries in 1..MAX_PDF_THUMBNAIL_CACHE_ENTRIES)
        require(maxCacheBytes in 1..MAX_PDF_THUMBNAIL_CACHE_BYTES)
    }

    override suspend fun render(pageIndex: Int): Bitmap {
        currentCoroutineContext().ensureActive()
        require(pageIndex in 0 until pageCount)
        // No outer withContext: cached images must never be recycled by prompt cancellation.
        return requests.withLock {
            currentCoroutineContext().ensureActive()
            val existing = synchronized(cacheLock) {
                check(!closed)
                cache[pageIndex]?.bitmap
            }
            if (existing != null) {
                currentCoroutineContext().ensureActive()
                return@withLock existing
            }

            // PdfDocument owns the fresh image until its IO result reaches this caller.
            val fresh = renderPage(pageIndex)
            try {
                currentCoroutineContext().ensureActive()
                synchronized(cacheLock) {
                    check(!closed)
                    check(!fresh.isRecycled && fresh.config == Bitmap.Config.ARGB_8888 &&
                        fresh.width in 1..PDF_THUMBNAIL_WIDTH && fresh.height in 1..PDF_THUMBNAIL_HEIGHT)
                    val bytes = fresh.allocationByteCount.toLong()
                    check(bytes in 1..MAX_PDF_THUMBNAIL_BYTES.toLong())
                    cache.put(pageIndex, Entry(fresh, bytes))?.let { cachedBytes -= it.bytes }
                    cachedBytes += bytes
                    val entries = cache.entries.iterator()
                    while (cache.size > maxCacheEntries || cachedBytes > maxCacheBytes) {
                        cachedBytes -= entries.next().value.bytes
                        entries.remove()
                    }
                }
                // This image is now shared. Eviction and session close only drop references.
                fresh
            } catch (failure: Throwable) {
                synchronized(cacheLock) {
                    if (cache[pageIndex]?.bitmap === fresh) {
                        cachedBytes -= checkNotNull(cache.remove(pageIndex)).bytes
                    }
                }
                fresh.recycle()
                throw failure
            }
        }
    }

    override fun close() = synchronized(cacheLock) {
        closed = true
        cache.clear()
        cachedBytes = 0L
    }
}

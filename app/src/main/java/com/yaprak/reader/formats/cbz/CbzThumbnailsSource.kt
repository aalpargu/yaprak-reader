package com.yaprak.reader.formats.cbz

import android.graphics.Bitmap
import com.yaprak.reader.reader.PageThumbnailsSource
import java.io.Closeable
import java.util.LinkedHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val CBZ_THUMBNAIL_WIDTH = 240
internal const val CBZ_THUMBNAIL_HEIGHT = 320
internal const val CBZ_THUMBNAIL_MAX_BYTES = CBZ_THUMBNAIL_WIDTH * CBZ_THUMBNAIL_HEIGHT * 4

/** Only completed previews are shared. Source verification also applies to cache hits. */
internal class CbzThumbnailsSource(
    override val pageCount: Int,
    private val verifySource: suspend () -> Unit,
    private val renderPage: suspend (Int) -> Bitmap,
    private val maxEntries: Int = 12,
    private val maxBytes: Long = 4L * 1024 * 1024,
) : PageThumbnailsSource, Closeable {
    constructor(document: CbzDocument) : this(document.archive.pages.size,
        document::verifySource, document::renderThumbnail)

    private data class Entry(val bitmap: Bitmap, val bytes: Long)
    private val requests = Mutex()
    private val cacheLock = Any()
    private val cache = LinkedHashMap<Int, Entry>(16, 0.75f, true)
    private var cachedBytes = 0L
    private var closed = false

    init {
        require(pageCount in 1..2000)
        require(maxEntries in 1..12)
        require(maxBytes in 1..4L * 1024 * 1024)
    }

    override suspend fun render(pageIndex: Int): Bitmap {
        currentCoroutineContext().ensureActive()
        require(pageIndex in 0 until pageCount)
        return requests.withLock {
            currentCoroutineContext().ensureActive()
            synchronized(cacheLock) { check(!closed) }
            // Cached pixels cannot prove that the current original still exists unchanged.
            verifySource()
            currentCoroutineContext().ensureActive()
            val existing = synchronized(cacheLock) { check(!closed); cache[pageIndex]?.bitmap }
            if (existing != null) return@withLock existing

            val fresh = renderPage(pageIndex)
            try {
                currentCoroutineContext().ensureActive()
                verifySource()
                currentCoroutineContext().ensureActive()
                synchronized(cacheLock) {
                    check(!closed)
                    check(!fresh.isRecycled && fresh.config == Bitmap.Config.ARGB_8888 &&
                        fresh.width in 1..CBZ_THUMBNAIL_WIDTH && fresh.height in 1..CBZ_THUMBNAIL_HEIGHT)
                    val bytes = fresh.allocationByteCount.toLong()
                    check(bytes in 1..CBZ_THUMBNAIL_MAX_BYTES.toLong())
                    cache.put(pageIndex, Entry(fresh, bytes))?.let { cachedBytes -= it.bytes }
                    cachedBytes += bytes
                    val iterator = cache.entries.iterator()
                    while (cache.size > maxEntries || cachedBytes > maxBytes) {
                        cachedBytes -= iterator.next().value.bytes
                        iterator.remove()
                    }
                }
                // Consumers may still draw an evicted image. Only drop shared references.
                fresh
            } catch (failure: Throwable) {
                synchronized(cacheLock) {
                    if (cache[pageIndex]?.bitmap === fresh) {
                        cachedBytes -= checkNotNull(cache.remove(pageIndex)).bytes
                    }
                }
                fresh.takeUnless { it.isRecycled }?.recycle()
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

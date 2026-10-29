package com.yaprak.reader.data.covers

import android.content.Context
import android.graphics.Bitmap
import com.yaprak.reader.covers.BookCoverLoader
import com.yaprak.reader.covers.CoverBitmapReader
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.data.storage.BookOperationCoordinator
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.formats.epub.EpubCoverReader
import com.yaprak.reader.formats.pdf.PdfCoverReader
import com.yaprak.reader.formats.CustomCoverBitmapDecoder
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Lazy local artwork with one active verification/decode and a bounded process-only cache. */
class LocalBookCoverLoader internal constructor(
    private val sourceFiles: VerifiedBookFile,
    private val reader: CoverBitmapReader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxCacheBytes: Long = 12L * 1024 * 1024,
    private val maxCacheEntries: Int = 64,
    private val customFiles: VerifiedCoverFile? = null,
    private val operations: BookOperationCoordinator? = null,
) : BookCoverLoader {
    constructor(context: Context, sourceFiles: VerifiedBookFile, customFiles: VerifiedCoverFile? = null, operations: BookOperationCoordinator? = null,
        cbrCache: com.yaprak.reader.formats.cbr.CbrPublicationCache = com.yaprak.reader.formats.cbr.CbrPublicationCache(context.cacheDir)) : this(
        sourceFiles,
        FormatCoverReader(EpubCoverReader(context.applicationContext.contentResolver), PdfCoverReader(), cbrCache),
        customFiles = customFiles,
        operations = operations,
    )

    private data class CachedCover(val bitmap: Bitmap?, val bytes: Long)
    private val cache = LinkedHashMap<String, CachedCover>(16, 0.75f, true)
    private var cachedBytes = 0L
    private val permit = Semaphore(1)

    init { require(maxCacheBytes > 0 && maxCacheEntries > 0) }

    override suspend fun load(book: Book): Bitmap? = try {
        withContext(io) {
            permit.withPermit {
                val custom = book.customCover
                if (custom != null && customFiles != null) {
                    val customKey = "custom:${book.id}:${book.sha256}:${custom.id}:${custom.sha256}:1"
                    cache[customKey]?.let { return@withPermit it.bitmap }
                    try {
                        val bytes = if (operations == null) customFiles.read(custom)
                            else operations.fileMutex.withLock { customFiles.read(custom) }
                        currentCoroutineContext().ensureActive()
                        val bitmap = CustomCoverBitmapDecoder.decode(bytes)
                        if (bitmap != null) {
                            publish(customKey, bitmap)
                            return@withPermit bitmap
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Failed custom revisions remain retryable. */ }
                    catch (_: OutOfMemoryError) { /* Fall back to the original artwork. */ }
                }
                val key = "${book.sha256}:${book.format.name}:1"
                cache[key]?.let { return@withPermit it.bitmap }
                val file = sourceFiles.resolve(book)
                val bitmap = reader.read(file, book.format)
                if (book.format == BookFormat.CBR) {
                    try { sourceFiles.resolve(book); currentCoroutineContext().ensureActive() }
                    catch (failure: Throwable) { bitmap?.recycle(); throw failure }
                }
                publish(key, bitmap)
                bitmap
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Access/verification failures are retryable and never become cached absence.
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private suspend fun publish(key: String, bitmap: Bitmap?) {
        try { currentCoroutineContext().ensureActive() }
        catch (cancelled: CancellationException) { bitmap?.recycle(); throw cancelled }
        // Eviction never recycles images still owned by UI.
        val entry = CachedCover(bitmap, bitmap?.allocationByteCount?.toLong() ?: 0L)
        cache.put(key, entry)?.let { cachedBytes -= it.bytes }
        cachedBytes += entry.bytes
        val entries = cache.entries.iterator()
        while (cache.size > maxCacheEntries || cachedBytes > maxCacheBytes) {
            cachedBytes -= entries.next().value.bytes
            entries.remove()
        }
    }
}

private class FormatCoverReader(private val epub: EpubCoverReader, private val pdf: PdfCoverReader,
    private val cbrCache: com.yaprak.reader.formats.cbr.CbrPublicationCache) : CoverBitmapReader {
    override suspend fun read(file: java.io.File, format: BookFormat): Bitmap? = when (format) {
        BookFormat.EPUB -> epub.read(file)
        BookFormat.PDF -> pdf.read(file)
        BookFormat.TXT -> null
        BookFormat.DOCX -> com.yaprak.reader.formats.docx.DocxCoverReader().read(file)
        BookFormat.FB2 -> com.yaprak.reader.formats.fb2.Fb2CoverReader().read(file)
        BookFormat.CBZ -> com.yaprak.reader.formats.cbz.CbzCoverReader().read(file)
        BookFormat.CBR -> cbrCache.acquire(file).use { archive ->
            archive.retainFile(archive.sourceHash).use { pin -> com.yaprak.reader.formats.cbz.CbzCoverReader().read(pin.file, archive.archive) }
        }
    }
}

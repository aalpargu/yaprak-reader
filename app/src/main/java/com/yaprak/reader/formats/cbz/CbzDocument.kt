package com.yaprak.reader.formats.cbz

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.ReaderOpenException
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class CbzDocument(val archive: CbzArchive, private val book: Book, private val files: VerifiedBookFile,
    private val library: LibraryRepository, private val fileMutex: Mutex,
    private val decodeImage: suspend (ByteArray, CbzImage, Long) -> Bitmap = { bytes, image, limit -> CbzImages.decode(bytes, image, limit) },
    private val acquireArchive: (File) -> ComicArchiveFileLease = { ComicArchiveFileLease(it) },
    private val releaseArchive: () -> Unit = {},
    private val validateRead: suspend () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    val isOpen get() = !closed.get()
    suspend fun verifySource() = withContext(Dispatchers.IO) {
        fileMutex.withLock { verifyCurrent(); files.resolve(book); verifyCurrent() }
    }
    suspend fun render(index: Int): Bitmap = renderOwned(index, thumbnail = false)
    suspend fun renderThumbnail(index: Int): Bitmap = renderOwned(index, thumbnail = true)
    suspend fun renderFrame(anchor: Int, indices: List<Int>): CbzFrame {
        if (indices.size !in 1..2 || anchor !in indices || indices.any { it !in archive.pages.indices } ||
            indices.zipWithNext().any { (left, right) -> right != left + 1 })
            throw ReaderOpenException(ReaderFailure.INVALID)
        val owned = mutableListOf<CbzRenderedPage>()
        try {
            val result = withContext(Dispatchers.IO) {
                fileMutex.withLock {
                    verifyCurrent()
                    val file = files.resolve(book)
                    acquireArchive(file).use { pin ->
                        ZipFile(pin.file, Charsets.UTF_8).use { zip ->
                        for (index in indices) {
                            verifyCurrent()
                            val page = archive.pages[index]
                            val bytes = CbzArchive.readEntry(zip, page.entry)
                            if (cbzHash(bytes) != page.sha256) throw ReaderOpenException(ReaderFailure.CHANGED)
                            val bitmap = decodeImage(bytes, page.image, CbzImages.MAX_BITMAP_PIXELS / indices.size)
                            owned.add(CbzRenderedPage(index, bitmap))
                        }
                        }
                    }
                    // A pair is one handoff. Reprove the whole original and current owner after both decodes.
                    files.resolve(book); verifyCurrent()
                    CbzFrame(anchor, owned.toList())
                }
            }
            owned.clear()
            return result
        } catch (_: OutOfMemoryError) {
            throw ReaderOpenException(ReaderFailure.INVALID)
        } finally { owned.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() } }
    }
    private suspend fun renderOwned(index: Int, thumbnail: Boolean): Bitmap {
        var allocated: Bitmap? = null
        try {
            val result = withContext(Dispatchers.IO) {
                fileMutex.withLock {
                    verifyCurrent()
                    val page = archive.pages.getOrNull(index) ?: throw ReaderOpenException(ReaderFailure.INVALID)
                    val file = files.resolve(book)
                    val bytes = acquireArchive(file).use { pin ->
                        ZipFile(pin.file, Charsets.UTF_8).use { CbzArchive.readEntry(it, page.entry) }
                    }
                    if (cbzHash(bytes) != page.sha256) throw ReaderOpenException(ReaderFailure.CHANGED)
                    var bitmap = decodeImage(bytes, page.image, CbzImages.MAX_BITMAP_PIXELS).also { allocated = it }
                    if (thumbnail) {
                        val scale = minOf(1.0, CBZ_THUMBNAIL_WIDTH.toDouble() / bitmap.width,
                            CBZ_THUMBNAIL_HEIGHT.toDouble() / bitmap.height)
                        val width = (bitmap.width * scale).toInt().coerceIn(1, CBZ_THUMBNAIL_WIDTH)
                        val height = (bitmap.height * scale).toInt().coerceIn(1, CBZ_THUMBNAIL_HEIGHT)
                        val scaled = bitmap.scale(width, height, true)
                        if (scaled !== bitmap) { bitmap.recycle(); bitmap = scaled; allocated = scaled }
                    }
                    // Do not hand a bitmap to a closed/replaced session or a changed original.
                    files.resolve(book); verifyCurrent()
                    bitmap
                }
            }
            allocated = null
            return result
        } catch (_: OutOfMemoryError) {
            throw ReaderOpenException(ReaderFailure.INVALID)
        } finally { allocated?.takeUnless { it.isRecycled }?.recycle() }
    }
    private suspend fun verifyCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isOpen) throw ReaderOpenException(ReaderFailure.MISSING)
        validateRead()
        val current = library.findById(book.id)
        if (current == null || current.format != book.format || current.sha256 != book.sha256 ||
            current.storageName != book.storageName || current.byteSize != book.byteSize || current.pageCount != book.pageCount)
            throw ReaderOpenException(ReaderFailure.CHANGED)
    }
    override fun close() { if (closed.compareAndSet(false, true)) releaseArchive() }
}

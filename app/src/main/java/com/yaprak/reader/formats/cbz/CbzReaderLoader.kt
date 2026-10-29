package com.yaprak.reader.formats.cbz

import android.content.Context
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.domain.reader.ReadingStateRepository
import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.ReaderLoader
import com.yaprak.reader.reader.ReaderOpenException
import com.yaprak.reader.reader.ReaderSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CbzReaderLoader(context: Context, private val library: LibraryRepository, private val reading: ReadingStateRepository,
    private val fileMutex: Mutex = Mutex(), private val validateRead: suspend () -> Unit = {},
    private val cbrCache: com.yaprak.reader.formats.cbr.CbrPublicationCache = com.yaprak.reader.formats.cbr.CbrPublicationCache(context.cacheDir),
) : ReaderLoader {
    private val files = VerifiedBookFile(File(context.filesDir, "books"))
    override suspend fun open(bookId: String): ReaderSession {
        var opened: CbzDocument? = null
        var provisional: com.yaprak.reader.formats.cbr.CbrPublicationCache.Lease? = null
        try {
            return withContext(Dispatchers.IO) {
                fileMutex.withLock {
                    currentCoroutineContext().ensureActive(); validateRead()
                    val book = library.findById(bookId) ?: throw ReaderOpenException(ReaderFailure.MISSING)
                    if (!book.format.comic) throw ReaderOpenException(ReaderFailure.UNSUPPORTED)
                    val original = files.resolve(book)
                    val derived = if (book.format == BookFormat.CBR) cbrCache.acquire(original).also { provisional = it } else null
                    if (derived != null && derived.sourceHash != book.sha256) throw ReaderOpenException(ReaderFailure.CHANGED)
                    val archive = derived?.archive ?: CbzArchive.read(original)
                    if (book.pageCount != archive.pages.size) throw ReaderOpenException(ReaderFailure.CHANGED)
                    val resume = reading.resume(book.id, book.sha256)
                    currentCoroutineContext().ensureActive(); validateRead(); files.resolve(book)
                    val current = library.findById(bookId)
                    if (current?.format != book.format || current.sha256 != book.sha256 || current.storageName != book.storageName ||
                        current.byteSize != book.byteSize || current.pageCount != book.pageCount) throw ReaderOpenException(ReaderFailure.CHANGED)
                    val document = CbzDocument(archive, book, files, library, fileMutex, validateRead = validateRead,
                        acquireArchive = { file -> derived?.retainFile(book.sha256) ?: ComicArchiveFileLease(file) },
                        releaseArchive = { derived?.close() }).also { opened = it; provisional = null }
                    CbzReaderSession(book, document, resume)
                }
            }
        } catch (error: Throwable) {
            opened?.close(); provisional?.close()
            when (error) {
                is com.yaprak.reader.formats.cbr.CbrException -> throw ReaderOpenException(
                    if (error.reason in setOf(com.yaprak.reader.formats.cbr.CbrFailure.UNSUPPORTED, com.yaprak.reader.formats.cbr.CbrFailure.PROTECTED)) ReaderFailure.UNSUPPORTED else ReaderFailure.INVALID)
                else -> throw error
            }
        }
    }
}

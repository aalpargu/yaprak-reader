package com.yaprak.reader.formats.epub

import android.content.Context
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.domain.reader.ReadingStateRepository
import com.yaprak.reader.domain.passages.EpubPassageRepository
import com.yaprak.reader.reader.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EpubReaderLoader(context: Context, private val library: LibraryRepository, private val reading: ReadingStateRepository,
    private val passages: EpubPassageRepository? = null) : ReaderLoader {
    private val source = EpubPublicationSource(context.contentResolver)
    private val files = VerifiedBookFile(File(context.filesDir, "books"))
    private val visibleScript = context.assets.open("reader/visible-text.js").bufferedReader().use { it.readText() }
    private val selectionRangeScript = context.assets.open("reader/selection-range.js").bufferedReader().use { it.readText() }
    private val selectionRangeV2Script = context.assets.open("reader/selection-range-v2.js").bufferedReader().use { it.readText() }
    private val exactHighlightsScript = context.assets.open("reader/exact-highlights.js").bufferedReader().use { it.readText() }

    override suspend fun open(bookId: String): ReaderSession {
        var opened: OpenEpub? = null
        try {
            return withContext(Dispatchers.IO) {
                val book = library.findById(bookId) ?: throw ReaderOpenException(ReaderFailure.MISSING)
                if (book.format != BookFormat.EPUB) throw ReaderOpenException(ReaderFailure.UNSUPPORTED)
                val file = files.resolve(book)
                val publication = source.open(file).also { opened = it }
                EpubReaderSession(book, publication, reading.resume(book.id, book.sha256), visibleScript, selectionRangeScript,
                    exactHighlightsScript, passages, selectionRangeV2Script)
            }
        } catch (error: Throwable) {
            // Close even if cancellation prevents an already opened IO result reaching the UI.
            opened?.close()
            throw error
        }
    }
}

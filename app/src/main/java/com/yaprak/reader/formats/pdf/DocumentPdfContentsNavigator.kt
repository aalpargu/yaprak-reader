package com.yaprak.reader.formats.pdf

import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.BookContents
import com.yaprak.reader.domain.reader.ContentsEntry
import com.yaprak.reader.reader.*
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One surface's explicit metadata request; original verification and parsing stay on IO. */
internal class DocumentPdfContentsNavigator(
    private val book: Book,
    private val pageCount: Int,
    private val files: VerifiedBookFile,
    private val fileMutex: Mutex,
    private val validateBook: suspend (Book) -> Unit,
    private val read: (java.io.File, Int, () -> Unit) -> ParsedPdfOutline,
    private val active: () -> Boolean,
    private val navigate: (Int) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ContentsNavigator {
    private var generation = 0L
    private var destinations: Map<String, Int> = emptyMap()
    override var contents = BookContents(emptyList())
        private set

    override suspend fun loadContents(): BookContents {
        currentCoroutineContext().ensureActive()
        val owner = ++generation
        destinations = emptyMap(); contents = BookContents(emptyList())
        if (!active()) throw CancellationException("PDF contents owner retired")
        if (book.format != BookFormat.PDF) throw ContentsLoadException(ContentsLoadFailure.UNSUPPORTED)
        if (book.byteSize !in 1..PdfOutlineReader.MAX_SOURCE_BYTES || pageCount !in 1..BoundedPdfOutline.MAX_PAGES)
            throw ContentsLoadException(ContentsLoadFailure.LIMIT)
        val parsed = withContext(io) {
            fileMutex.withLock {
                ensureActive(); validateBook(book)
                val file = files.resolve(book)
                val result = read(file, pageCount) { ensureActive() }
                ensureActive(); validateBook(book); files.resolve(book)
                result
            }
        }
        currentCoroutineContext().ensureActive()
        if (owner != generation || !active()) throw CancellationException("PDF contents request changed")
        val targets = LinkedHashMap<String, Int>()
        val entries = parsed.rows.map { row ->
            val id = UUID.randomUUID().toString()
            val page = row.pageIndex?.takeIf { it in 0 until pageCount }
            page?.let { targets[id] = it }
            ContentsEntry(id, row.title, row.depth, page != null)
        }
        destinations = Collections.unmodifiableMap(targets)
        return BookContents(Collections.unmodifiableList(entries), parsed.truncated).also { contents = it }
    }

    override fun goTo(entryId: String): Boolean {
        val page = destinations[entryId] ?: return false
        return active() && navigate(page)
    }
}

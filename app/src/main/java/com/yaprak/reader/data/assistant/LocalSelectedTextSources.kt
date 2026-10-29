package com.yaprak.reader.data.assistant

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Original file reader lives in formats. The shared file/recovery guard lives at this data boundary. */
fun interface SelectedTextOriginalSource {
    suspend fun read(book: Book, request: SelectedTextSourceRequest): List<SelectedTextReference>
}

class LocalSelectedTextSources(private val library: LibraryRepository,
    private val original: SelectedTextOriginalSource, private val fileMutex: Mutex,
    private val validateAccess: () -> Unit) : SelectedTextSourceProvider {
    override suspend fun prepare(request: SelectedTextSourceRequest): PreparedSelectedTextSources {
        if (!request.isValid) failAnswer(BookAnswerFailure.SOURCE)
        val caller = currentCoroutineContext()
        return withContext(Dispatchers.IO) {
            fileMutex.withLock {
                caller.ensureActive()
                try {
                    validateAccess()
                    val book = library.findById(request.bookId) ?: failAnswer(BookAnswerFailure.SOURCE)
                    if (book.sha256 != request.contentSha256 || book.format != request.sourceFormat) failAnswer(BookAnswerFailure.SOURCE)
                    if (book.format == BookFormat.PDF && (book.pageCount == null ||
                        request.pdfSourceAnchor!!.pageIndex !in 0 until book.pageCount)) failAnswer(BookAnswerFailure.SOURCE)
                    val references = original.read(book, request)
                    caller.ensureActive(); currentCoroutineContext().ensureActive(); validateAccess()
                    val current = library.findById(book.id) ?: failAnswer(BookAnswerFailure.SOURCE)
                    if (current.copy(lastReadAt = null) != book.copy(lastReadAt = null)) failAnswer(BookAnswerFailure.SOURCE)
                    PreparedSelectedTextSources(request, current, references).also {
                        SelectedTextQuestionRequest.create(it, "Kaynak doğrulaması")
                        caller.ensureActive(); currentCoroutineContext().ensureActive()
                    }
                } catch (cancelled: CancellationException) {
                    caller.ensureActive()
                    failAnswer(BookAnswerFailure.SOURCE)
                } catch (failure: BookAnswerException) { throw failure }
                catch (_: Exception) { failAnswer(BookAnswerFailure.SOURCE) }
            }
        }
    }
}

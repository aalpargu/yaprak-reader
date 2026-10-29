package com.yaprak.reader.formats.pdf

import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.reader.IndexedPdfPageNavigator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DocumentIndexedPdfPageNavigator(
    private val book: Book,
    private val document: PdfDocument,
    private val files: VerifiedBookFile,
    private val fileMutex: Mutex,
    private val validateBook: suspend (Book) -> Unit,
    private val active: () -> Boolean,
    private val navigate: (Int) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : IndexedPdfPageNavigator {
    private data class Proof(val target: LibraryTextTarget, val owner: Job)
    private var proof: Proof? = null
    private var generation = 0
    override val supported get() = document.textSupported

    private fun valid(target: LibraryTextTarget) = active() && document.textAvailable &&
        book.format == BookFormat.PDF && target.isValid && target.sourceFormat == BookFormat.PDF &&
        target.bookId == book.id && target.contentSha256 == book.sha256 &&
        target.pdfPageIndex != null && target.pdfPageIndex in 0 until document.pageCount

    override suspend fun verifiedPageIndex(target: LibraryTextTarget): Int? {
        val attempt = ++generation
        proof = null
        if (!valid(target)) return null
        val owner = currentCoroutineContext()[Job] ?: return null
        val page = target.pdfPageIndex ?: return null
        val hash = try {
            withContext(io) {
                fileMutex.withLock {
                    ensureActive()
                    validateBook(book)
                    val file = files.resolve(book)
                    val text = document.extractText(page)
                    ensureActive()
                    val actual = indexedPdfTextHash(text.text)
                    if (files.resolve(book).canonicalFile != file.canonicalFile) return@withLock null
                    validateBook(book)
                    ensureActive()
                    actual
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        currentCoroutineContext().ensureActive()
        if (attempt != generation || !valid(target) || hash != target.resourceSha256) return null
        proof = Proof(target, owner)
        return page
    }

    override fun goToPage(pageIndex: Int): Boolean {
        val verified = proof
        proof = null
        generation++
        if (verified == null || !verified.owner.isActive || !valid(verified.target) ||
            verified.target.pdfPageIndex != pageIndex) return false
        return navigate(pageIndex)
    }
}

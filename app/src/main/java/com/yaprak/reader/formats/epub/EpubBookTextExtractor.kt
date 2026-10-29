@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats.epub

import android.content.Context
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.domain.search.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource

/** Caller holds the shared file mutex throughout extraction. No index/progress writes or network reads. */
class EpubBookTextExtractor(context: Context) : BookTextExtractor {
    private val files = VerifiedBookFile(File(context.filesDir, "books"))
    private val source = EpubPublicationSource(context.contentResolver)

    override suspend fun extract(book: Book): ExtractedBookText = withContext(Dispatchers.IO) {
        if (book.format != BookFormat.EPUB) extractionFail(TextExtractionFailure.UNSUPPORTED)
        if (book.id.isBlank() || book.id.length > 200 || !wellFormedIndexText(book.id) ||
            !book.sha256.matches(Regex("[0-9a-f]{64}")))
            extractionFail(TextExtractionFailure.INVALID)
        try {
            source.open(files.resolve(book)).use { opened -> extractIndexBookText(opened.publication, book) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TextExtractionException) {
            throw failure
        } catch (failure: BookImportException) {
            extractionFail(if (failure.reason == ImportFailure.PROTECTED) TextExtractionFailure.UNSUPPORTED
                else TextExtractionFailure.INVALID)
        } catch (_: Exception) {
            extractionFail(TextExtractionFailure.SOURCE)
        }
    }

    override suspend fun extractChapters(book: Book): ExtractedBookChapters = withContext(Dispatchers.IO) {
        if (book.format != BookFormat.EPUB) extractionFail(TextExtractionFailure.UNSUPPORTED)
        if (!BookTextScope(book.id, book.sha256).isValid) extractionFail(TextExtractionFailure.INVALID)
        try {
            val evidence = source.open(files.resolve(book)).use { opened ->
                extractIndexBookEvidence(opened.publication, book, captureChapters = true)
            }
            // Fresh TOC/anchor evidence remains bound to the exact full EPUB after all resources close.
            currentCoroutineContext().ensureActive()
            files.resolve(book)
            evidence
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: TextExtractionException) { throw failure }
        catch (failure: BookImportException) {
            extractionFail(if (failure.reason == ImportFailure.PROTECTED) TextExtractionFailure.UNSUPPORTED
                else TextExtractionFailure.INVALID)
        } catch (_: Exception) { extractionFail(TextExtractionFailure.SOURCE) }
    }
}

/** Reads complete original source bytes. Navigation uses this same proof, never decoded/re-encoded XHTML. */
internal suspend fun readIndexXhtml(publication: Publication, href: String): ByteArray {
    currentCoroutineContext().ensureActive()
    if (!safeLibraryTextHref(href)) extractionFail(TextExtractionFailure.INVALID)
    val link = publication.readingOrder.filter { !it.href.isTemplated && it.url().toString() == href }.singleOrNull()
        ?: extractionFail(TextExtractionFailure.INVALID)
    if (link.href.isTemplated || link.url() !is RelativeUrl || link.mediaType != MediaType.XHTML)
        extractionFail(TextExtractionFailure.UNSUPPORTED)
    val resource = publication.get(link) ?: extractionFail(TextExtractionFailure.SOURCE)
    return readBoundedIndexXhtml(resource)
}

/** Does not trust a ZIP/provider length hint; rejected and cancelled reads still own resource cleanup. */
internal suspend fun readBoundedIndexXhtml(resource: Resource): ByteArray {
    val caller = currentCoroutineContext()
    var primary: Throwable? = null
    try {
        caller.ensureActive()
        val bytes = resource.read(0L..MAX_INDEX_RESOURCE_BYTES.toLong()).getOrNull()
            ?: extractionFail(TextExtractionFailure.SOURCE)
        caller.ensureActive()
        if (bytes.size > MAX_INDEX_RESOURCE_BYTES) extractionFail(TextExtractionFailure.LIMIT)
        if (bytes.isEmpty()) extractionFail(TextExtractionFailure.INVALID)
        return bytes
    } catch (failure: Throwable) {
        val reported = if (failure !is Exception) failure else try {
            caller.ensureActive()
            if (failure is CancellationException || failure is TextExtractionException) failure
            else TextExtractionException(TextExtractionFailure.SOURCE)
        } catch (cancelled: CancellationException) { cancelled }
        primary = reported
        throw reported
    } finally {
        try { resource.close() }
        catch (cleanup: Throwable) {
            val first = primary
            if (first != null) { if (first !== cleanup) first.addSuppressed(cleanup) }
            else if (cleanup is Exception) extractionFail(TextExtractionFailure.SOURCE)
            else throw cleanup
        }
    }
}

/** Adapter seam is shared by real-publication tests; every supported source must complete before return. */
internal suspend fun extractIndexBookText(publication: Publication, book: Book): ExtractedBookText =
    extractIndexBookEvidence(publication, book, captureChapters = false).document

internal suspend fun extractIndexBookEvidence(publication: Publication, book: Book, captureChapters: Boolean): ExtractedBookChapters {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    if (publication.metadata.presentation.layout == EpubLayout.FIXED)
        extractionFail(TextExtractionFailure.UNSUPPORTED)
    val order = publication.readingOrder
    if (order.isEmpty()) extractionFail(TextExtractionFailure.INVALID)
    if (order.size > MAX_INDEX_BOOK_RESOURCES) extractionFail(TextExtractionFailure.LIMIT)
    val hrefs = order.map { link ->
        if (link.href.isTemplated) extractionFail(TextExtractionFailure.UNSUPPORTED)
        val href = link.url().toString()
        if (link.url() !is RelativeUrl || link.mediaType != MediaType.XHTML)
            extractionFail(TextExtractionFailure.UNSUPPORTED)
        if (!safeLibraryTextHref(href)) extractionFail(TextExtractionFailure.INVALID)
        href
    }
    if (hrefs.toSet().size != hrefs.size) extractionFail(TextExtractionFailure.INVALID)
    val catalog = if (captureChapters) EpubChapterCatalog(publication.tableOfContents, hrefs) { caller.ensureActive() } else null
    val chunks = ArrayList<ExtractedTextChunk>()
    val chunkOffsets = ArrayList<Int>()
    var stringBytes = 0L
    for ((index, link) in order.withIndex()) {
        caller.ensureActive()
        val href = hrefs[index]
        val bytes = readIndexXhtml(publication, href)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val body = BoundedXhtmlTextParser.parseAnchored(bytes, catalog?.wantedIds(index) ?: emptySet()) { caller.ensureActive() }
        catalog?.accept(index, hash, body)
        val text = body.text
        val title = link.title
        if (title != null && (!wellFormedIndexText(title) || title.length > MAX_INDEX_CHAPTER_TITLE))
            extractionFail(TextExtractionFailure.INVALID)
        var start = 0
        while (start < text.length) {
            caller.ensureActive()
            var end = minOf(text.length, start + MAX_INDEX_CHUNK_TEXT)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val chunkText = text.substring(start, end)
            if (chunkText.isNotBlank()) {
                if (chunks.size == MAX_INDEX_BOOK_CHUNKS) extractionFail(TextExtractionFailure.LIMIT)
                val chunk = ExtractedTextChunk(index, chunks.size, href, hash, title, chunkText)
                stringBytes += indexedChunkStringBytes(book.id, book.sha256, chunk)
                if (stringBytes > MAX_INDEX_BOOK_STRING_BYTES) extractionFail(TextExtractionFailure.LIMIT)
                chunks += chunk
                if (captureChapters) chunkOffsets += start
            }
            if (end == text.length) break
            start = end - INDEX_CHUNK_OVERLAP
            if (start > 0 && text[start - 1].isHighSurrogate() && text[start].isLowSurrogate()) start--
        }
    }
    caller.ensureActive()
    val document = ExtractedBookText(book.id, book.sha256, chunks, order.size)
    return catalog?.chapters(document, chunkOffsets) ?: ExtractedBookChapters(document, emptyList(), emptyList())
}

private fun wellFormedIndexText(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char == '\u0000') return false
        if (char.isHighSurrogate()) {
            if (index == value.length || !value[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}

private fun extractionFail(reason: TextExtractionFailure): Nothing = throw TextExtractionException(reason)

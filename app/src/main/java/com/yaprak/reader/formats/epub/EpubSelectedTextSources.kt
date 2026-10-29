@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.yaprak.reader.formats.epub

import android.content.Context
import com.yaprak.reader.data.assistant.SelectedTextOriginalSource
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.reader.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation

/** Caller holds the shared file mutex and runs on IO. Fresh hash/source proof on preview and send. */
class EpubSelectedTextSources(context: Context) : SelectedTextOriginalSource {
    private val files = VerifiedBookFile(File(context.filesDir, "books"))
    private val source = EpubPublicationSource(context.contentResolver)
    override suspend fun read(book: Book, request: SelectedTextSourceRequest): List<SelectedTextReference> {
        if (!request.isValid || request.sourceFormat != BookFormat.EPUB || book.format != BookFormat.EPUB ||
            book.id != request.bookId || book.sha256 != request.contentSha256) failAnswer(BookAnswerFailure.SOURCE)
        val anchor = request.anchor ?: failAnswer(BookAnswerFailure.SOURCE)
        try {
            source.open(files.resolve(book)).use { opened ->
                if (opened.publication.metadata.presentation.layout == EpubLayout.FIXED) failAnswer(BookAnswerFailure.SOURCE)
                val proof = SourceTextAnchorVerifier.verifyBatch(opened.publication, anchor.resourceHref,
                    listOf(SourceAnchorRequest("selection", anchor, request.preview)))
                currentCoroutineContext().ensureActive()
                return selectedTextReferences(request, proof)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: BookAnswerException) { throw failure }
        catch (_: Exception) { failAnswer(BookAnswerFailure.SOURCE) }
    }
}

/** Split only the proved raw selection stream; never copy display separators or truncate a slice. */
internal fun selectedTextReferences(request: SelectedTextSourceRequest, proof: SourceAnchorBatch): List<SelectedTextReference> {
    if (!request.isValid || request.sourceFormat != BookFormat.EPUB) failAnswer(BookAnswerFailure.SOURCE)
    if (proof.failure != null) failAnswer(if (proof.failure == SourceProofFailure.LIMIT) BookAnswerFailure.LIMIT else BookAnswerFailure.SOURCE)
    val raw = when (val anchor = request.anchor) {
        is SourceTextAnchor -> proof.nodeTexts["selection"]?.let { node ->
            if (anchor.end > node.length) failAnswer(BookAnswerFailure.SOURCE)
            node.substring(anchor.start, anchor.end).takeIf { it == request.preview }
        } ?: failAnswer(BookAnswerFailure.SOURCE)
        is SourceRangeAnchor -> {
            val nodes = proof.rangeNodeTexts["selection"] ?: failAnswer(BookAnswerFailure.SOURCE)
            if (nodes.size != anchor.segments.size) failAnswer(BookAnswerFailure.SOURCE)
            anchor.segments.mapIndexed { index, segment ->
                val node = nodes[index]
                if (segment.end > node.length || node.substring(segment.start, segment.end) != segment.selectedText)
                    failAnswer(BookAnswerFailure.SOURCE)
                segment.selectedText
            }.joinToString("")
        }
        null -> failAnswer(BookAnswerFailure.SOURCE)
    }
    return splitSelectedTextReferences(raw)
}

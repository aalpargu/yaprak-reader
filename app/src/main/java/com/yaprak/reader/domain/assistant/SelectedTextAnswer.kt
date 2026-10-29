package com.yaprak.reader.domain.assistant

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.BookTextScope
import com.yaprak.reader.domain.search.safeLibraryTextHref
import com.yaprak.reader.reader.*
import java.util.UUID

/** A current reader selection, never an index hit, reading receipt or persisted intent. */
data class SelectedTextSourceRequest(val bookId: String, val contentSha256: String,
    val selectionId: String, val preview: String, val anchor: EpubSourceAnchor? = null,
    val sourceFormat: BookFormat = BookFormat.EPUB, val pdfSourceAnchor: PdfSourceAnchor? = null,
    val sourceSelectionId: String? = null) {
    val isValid: Boolean get() = BookTextScope(bookId, contentSha256).isValid &&
        canonicalUuid(selectionId) && validAnswerText(preview, 4096) && when (sourceFormat) {
            BookFormat.EPUB -> pdfSourceAnchor == null && sourceSelectionId == null && anchor?.let { source ->
                safeLibraryTextHref(source.resourceHref) && when (source) {
                    is SourceTextAnchor -> source.start >= 0 && source.end > source.start && source.end <= 4096 &&
                        source.end - source.start == preview.length && source.nodeTextSha256.matches(Regex("[0-9a-f]{64}"))
                    is SourceRangeAnchor -> SourceRangeAnchors.isValid(source)
                }
            } == true
            BookFormat.PDF -> anchor == null && pdfSourceAnchor != null && sourceSelectionId != null && canonicalUuid(sourceSelectionId)
            BookFormat.TXT, BookFormat.CBZ, BookFormat.CBR, BookFormat.FB2, BookFormat.DOCX -> false
        }
    override fun toString() = "SelectedTextSourceRequest(selectionId=$selectionId)"
}

data class SelectedTextReference(val label: String, val text: String) {
    override fun toString() = "SelectedTextReference(label=$label)"
}

/** Original selected raw text only. Paragraph separators from a display preview are not invented. */
class PreparedSelectedTextSources(val request: SelectedTextSourceRequest, val book: Book,
    references: List<SelectedTextReference>, val requestId: String = UUID.randomUUID().toString()) {
    val references = immutableAnswerList(references)
    fun sameEvidence(other: PreparedSelectedTextSources) = request == other.request && references == other.references &&
        book.copy(lastReadAt = null) == other.book.copy(lastReadAt = null)
    override fun toString() = "PreparedSelectedTextSources(requestId=$requestId, count=${references.size})"
}

fun interface SelectedTextSourceProvider {
    /** Fresh imported-file and original-source proof, with no network, cache or progress writes. */
    suspend fun prepare(request: SelectedTextSourceRequest): PreparedSelectedTextSources
}

class SelectedTextQuestionRequest private constructor(val sources: PreparedSelectedTextSources, val question: String) {
    companion object {
        fun create(sources: PreparedSelectedTextSources, question: String): SelectedTextQuestionRequest {
            val text = question.trim()
            if (!validAnswerText(text, MAX_BOOK_QUESTION)) failAnswer(BookAnswerFailure.INVALID_INPUT)
            val request = sources.request
            if (!request.isValid || !canonicalUuid(sources.requestId) || sources.book.format != request.sourceFormat ||
                sources.book.id != request.bookId || sources.book.sha256 != request.contentSha256 ||
                (request.sourceFormat == BookFormat.PDF && (sources.book.pageCount == null ||
                    request.pdfSourceAnchor!!.pageIndex !in 0 until sources.book.pageCount)) ||
                sources.references.size !in 1..8) failAnswer(BookAnswerFailure.SOURCE)
            sources.references.forEachIndexed { index, source ->
                if (source.label != "S${index + 1}" || !validAnswerText(source.text, 2000)) failAnswer(BookAnswerFailure.SOURCE)
            }
            val raw = sources.references.joinToString("") { it.text }
            if (raw.length > 4096 || when (val anchor = request.anchor) {
                    is SourceTextAnchor -> raw != request.preview
                    is SourceRangeAnchor -> raw != anchor.segments.joinToString("") { it.selectedText }
                    null -> raw != request.preview
                }) failAnswer(BookAnswerFailure.SOURCE)
            return SelectedTextQuestionRequest(sources, text)
        }
    }
    override fun toString() = "SelectedTextQuestionRequest(requestId=${sources.requestId})"
}

data class SelectedTextCitation(val source: SelectedTextReference, val quote: String)
class SelectedTextStatement(val text: String, citations: List<SelectedTextCitation>) {
    val citations = immutableAnswerList(citations)
}
class SelectedTextAnswer(val requestId: String, val status: BookAnswerStatus,
    statements: List<SelectedTextStatement>, val message: String) {
    val statements = immutableAnswerList(statements)
    override fun toString() = "SelectedTextAnswer(requestId=$requestId, status=$status)"
}
fun interface SelectedTextAnswerService {
    suspend fun answerSelection(request: SelectedTextQuestionRequest, connection: BookAnswerConnection): SelectedTextAnswer
}

fun validateSelectedTextAnswer(request: SelectedTextQuestionRequest, answer: SelectedTextAnswer) {
    fun bad(): Nothing = failAnswer(BookAnswerFailure.INVALID_RESPONSE)
    if (answer.requestId != request.sources.requestId || !validAnswerText(answer.message, 500, allowEmpty = true)) bad()
    if (answer.status == BookAnswerStatus.INSUFFICIENT) {
        if (answer.statements.isNotEmpty() || answer.message.isBlank()) bad()
        return
    }
    if (answer.message.isNotEmpty() || answer.statements.size !in 1..8 ||
        answer.statements.sumOf { it.text.length.toLong() } > 4000) bad()
    answer.statements.forEach { statement ->
        if (!validAnswerText(statement.text, 1000) || statement.citations.size !in 1..4) bad()
        val seen = mutableSetOf<Pair<String, String>>()
        statement.citations.forEach { citation ->
            val source = request.sources.references.singleOrNull { it.label == citation.source.label } ?: bad()
            if (source != citation.source || !validAnswerText(citation.quote, 500) ||
                !seen.add(source.label to citation.quote) || !source.text.contains(citation.quote)) bad()
        }
    }
}

private fun canonicalUuid(value: String) = try { UUID.fromString(value).toString() == value } catch (_: Exception) { false }

/** No normalization or display separators. Shared by freshly proved EPUB and native PDF selections. */
internal fun splitSelectedTextReferences(raw: String): List<SelectedTextReference> {
    if (!validAnswerText(raw, 4096)) failAnswer(BookAnswerFailure.SOURCE)
    val references = ArrayList<SelectedTextReference>()
    var offset = 0
    while (offset < raw.length) {
        var end = minOf(offset + 2000, raw.length)
        if (end < raw.length && raw[end - 1].isHighSurrogate()) end--
        val text = raw.substring(offset, end)
        if (!validAnswerText(text, 2000) || references.size == 8) failAnswer(BookAnswerFailure.LIMIT)
        references += SelectedTextReference("S${references.size + 1}", text)
        offset = end
    }
    return immutableAnswerList(references)
}

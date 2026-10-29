package com.yaprak.reader.domain.assistant

import com.yaprak.reader.domain.search.*
import java.net.URI
import java.util.Collections
import java.util.UUID

const val MAX_BOOK_QUESTION = 1000
const val MAX_BOOK_ANSWER_BYTES = 64 * 1024
const val MAX_BOOK_REQUEST_BYTES = 128 * 1024
const val MAX_COMPLETE_BOOK_REQUEST_BYTES = 1024 * 1024

/** Only an explicit send may hand this detached, bounded snapshot to a service. */
class BookQuestionRequest private constructor(val sources: PreparedBookSources, val question: String) {
    companion object {
        fun create(sources: PreparedBookSources, question: String): BookQuestionRequest {
            val text = question.trim()
            if (!validAnswerText(text, MAX_BOOK_QUESTION)) failAnswer(BookAnswerFailure.INVALID_INPUT)
            val maximumReferences = if (sources.section == null && sources.chapter == null && sources.prefix == null) MAX_BOOK_SOURCE_REFERENCES
                else MAX_COMPLETE_BOOK_SOURCE_REFERENCES
            val canonical = try { UUID.fromString(sources.requestId).toString() } catch (_: Exception) { null }
            if (canonical != sources.requestId || !sources.scope.isValid ||
                sources.references.size !in 1..maximumReferences ||
                sources.totalMatches < sources.references.size || !validCompleteSectionSources(sources) ||
                !validCompleteChapterSources(sources))
                failAnswer(BookAnswerFailure.SOURCE)
            val sourceBook = sources.references.first().hit.book
            if (!supportsBookSourceRetrieval(sourceBook.format) ||
                ((sources.chapter != null || sources.prefix != null || sources.prefixQuery != null) &&
                    sourceBook.format != com.yaprak.reader.domain.library.BookFormat.EPUB))
                failAnswer(BookAnswerFailure.SOURCE)
            val identities = mutableSetOf<Pair<String, Long>>()
            sources.references.forEachIndexed { index, reference ->
                val hit = reference.hit
                if (reference.label != "S${index + 1}" || !hit.target.isValid || !identities.add(hit.generation to hit.id) ||
                    hit.book.copy(lastReadAt = null) != sourceBook.copy(lastReadAt = null) ||
                    hit.target.sourceFormat != sourceBook.format ||
                    hit.book.id != sources.scope.bookId || hit.book.sha256 != sources.scope.contentSha256 ||
                    hit.target.bookId != sources.scope.bookId || hit.target.contentSha256 != sources.scope.contentSha256 ||
                    hit.readingOrderIndex !in 0 until MAX_INDEX_BOOK_RESOURCES || hit.chunkIndex !in 0 until MAX_INDEX_BOOK_CHUNKS ||
                    !validAnswerText(hit.text, MAX_INDEX_CHUNK_TEXT) || !validFormatSource(hit) ||
                    !validAnswerText(reference.text, MAX_INDEX_CHUNK_TEXT))
                    failAnswer(BookAnswerFailure.SOURCE)
            }
            return BookQuestionRequest(sources, text)
        }
    }
}

private fun validFormatSource(hit: LibraryTextHit): Boolean = when (hit.target.sourceFormat) {
    com.yaprak.reader.domain.library.BookFormat.EPUB -> true
    com.yaprak.reader.domain.library.BookFormat.PDF -> hit.chapterTitle == null &&
        hit.target.pdfPageIndex == hit.readingOrderIndex &&
        (hit.book.pageCount == null || hit.readingOrderIndex < hit.book.pageCount)
    com.yaprak.reader.domain.library.BookFormat.TXT ->
        txtLibraryTextPartIndex(hit.target.resourceHref) == hit.readingOrderIndex &&
            hit.chapterTitle == "Metin ${hit.readingOrderIndex + 1}"
    com.yaprak.reader.domain.library.BookFormat.FB2 -> hit.chapterTitle?.let { validAnswerText(it, MAX_INDEX_CHAPTER_TITLE) } == true
    com.yaprak.reader.domain.library.BookFormat.DOCX -> hit.readingOrderIndex == 0 && hit.chapterTitle == "Belge metni"
    com.yaprak.reader.domain.library.BookFormat.CBZ, com.yaprak.reader.domain.library.BookFormat.CBR -> false
}

/** The client resolves source labels itself. A provider never supplies a locator or chapter name. */
data class BookAnswerCitation(val source: BookSourceReference, val quote: String)
class BookAnswerStatement(val text: String, citations: List<BookAnswerCitation>) {
    val citations: List<BookAnswerCitation> = immutableAnswerList(citations)
}
enum class BookAnswerStatus { ANSWERED, INSUFFICIENT }
class BookAnswer(val requestId: String, val status: BookAnswerStatus,
    statements: List<BookAnswerStatement>, val message: String) {
    val statements: List<BookAnswerStatement> = immutableAnswerList(statements)
}

/** Server access credentials are session-only, never a provider API key. Deliberately no toString. */
class BookAnswerConnection private constructor(val endpoint: URI, val accessToken: String) {
    companion object {
        fun create(endpoint: String, accessToken: String): BookAnswerConnection {
            val uri = try { URI(endpoint.trim()) } catch (_: Exception) { failAnswer(BookAnswerFailure.CONFIGURATION) }
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                uri.port !in setOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null ||
                uri.rawPath != "/v1/book-answer" || uri.toASCIIString().length > 2048 ||
                accessToken.length !in 16..256 || !accessToken.matches(Regex("[A-Za-z0-9_-]+")) ||
                accessToken.startsWith("sk-")) failAnswer(BookAnswerFailure.CONFIGURATION)
            return BookAnswerConnection(uri, accessToken)
        }
    }
}

fun interface BookAnswerService {
    suspend fun answer(request: BookQuestionRequest, connection: BookAnswerConnection): BookAnswer
}

fun validateBookAnswer(request: BookQuestionRequest, answer: BookAnswer) {
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
                !seen.add(citation.source.label to citation.quote) || !source.text.contains(citation.quote)) bad()
        }
    }
}

enum class BookAnswerFailure {
    CONFIGURATION, INVALID_INPUT, SOURCE, UNAUTHORIZED, BUSY, UNAVAILABLE, NETWORK,
    TIMEOUT, REFUSED, INCOMPLETE, INVALID_RESPONSE, LIMIT,
}
class BookAnswerException(val reason: BookAnswerFailure) : Exception(reason.name)
fun failAnswer(reason: BookAnswerFailure): Nothing = throw BookAnswerException(reason)
fun <T> immutableAnswerList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
fun validAnswerText(value: String, limit: Int, allowEmpty: Boolean = false): Boolean {
    if (value.length > limit || (!allowEmpty && value.isBlank())) return false
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char == '\u0000' || char.isLowSurrogate()) return false
        if (char.isHighSurrogate() && (index >= value.length || !value[index++].isLowSurrogate())) return false
    }
    return true
}

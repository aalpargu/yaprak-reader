package com.yaprak.reader.domain.passages

import com.yaprak.reader.reader.EpubSourceAnchor
import kotlinx.coroutines.flow.Flow

enum class EpubHighlightColor { YELLOW, GREEN, BLUE, PINK }

const val MAX_EPUB_PASSAGE_LENGTH = 4096
const val MAX_EPUB_ATTACHED_NOTE_LENGTH = 2000
const val MAX_EPUB_ANNOTATIONS_PER_BOOK = 1000

/** Exact annotations must round-trip through SQLite, JSON and UTF-8 without replacement characters. */
fun isWellFormedEpubAnnotationText(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (character.isHighSurrogate()) {
            if (index == value.length || !value[index++].isLowSurrogate()) return false
        } else if (character.isLowSurrogate()) return false
    }
    return true
}

/** A stale editor must not replace or delete a newer source-linked annotation. */
class EpubPassageChangedException : Exception("EPUB passage has changed or is missing")

class EpubAnnotationLimitException : Exception("EPUB annotation limit reached")

/** An explicitly saved selection matched to original EPUB XHTML source slices. */
data class EpubPassage(
    val id: String,
    val text: String,
    val anchor: EpubSourceAnchor,
    val createdAt: Long,
    val highlightColor: EpubHighlightColor? = null,
    val attachedNote: String? = null,
    val revision: Long = 0,
)

interface EpubPassageRepository {
    fun observe(bookId: String, contentSha256: String): Flow<List<EpubPassage>>
    suspend fun add(bookId: String, contentSha256: String, text: String, anchor: EpubSourceAnchor)
    suspend fun remove(bookId: String, contentSha256: String, id: String)

    /** Adds an annotation only when this exact source range is not already saved. */
    suspend fun createAnnotation(bookId: String, contentSha256: String, text: String,
        anchor: EpubSourceAnchor, highlightColor: EpubHighlightColor?, attachedNote: String?): EpubPassage {
        throw UnsupportedOperationException("EPUB annotations are not supported by this repository")
    }

    /** Both nullable fields can be cleared independently. The plain passage remains saved. */
    suspend fun updateAnnotation(bookId: String, contentSha256: String, expected: EpubPassage,
        highlightColor: EpubHighlightColor?, attachedNote: String?): EpubPassage {
        throw UnsupportedOperationException("EPUB annotations are not supported by this repository")
    }

    suspend fun removeExpected(bookId: String, contentSha256: String, expected: EpubPassage) {
        throw UnsupportedOperationException("Revision-safe EPUB passage removal is not supported by this repository")
    }
}

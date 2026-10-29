package com.yaprak.reader.domain.vocabulary

import kotlinx.coroutines.flow.Flow

/** Saved user entries, with their current publication; no learning history is inferred. */
data class LibraryWord(
    val word: SavedWord,
    val bookId: String,
    val contentSha256: String,
    val bookTitle: String,
    val bookAuthors: String?,
)
data class LibraryWordsResult(val entries: List<LibraryWord>, val totalCount: Int)
const val MAX_LIBRARY_WORD_RESULTS = 200
const val MAX_LIBRARY_WORD_QUERY_LENGTH = 200
class LibraryWordsSourceException : Exception("Library vocabulary unavailable or invalid")
interface LibraryWordsRepository {
    fun observe(query: String): Flow<LibraryWordsResult>
    suspend fun find(bookId: String, contentSha256: String, wordId: String): LibraryWord?
}
fun limitLibraryWordQuery(value: String): String {
    if (value.length <= MAX_LIBRARY_WORD_QUERY_LENGTH) return value
    val end = MAX_LIBRARY_WORD_QUERY_LENGTH
    return value.take(if (Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end - 1 else end)
}

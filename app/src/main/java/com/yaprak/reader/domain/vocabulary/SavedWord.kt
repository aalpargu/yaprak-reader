package com.yaprak.reader.domain.vocabulary

import kotlinx.coroutines.flow.Flow

/** User-entered text only. No language detection, translation, or reading anchor is implied. */
data class SavedWord(
    val id: String,
    val english: String,
    val turkish: String,
    val createdAt: Long,
    val revision: Long = 0,
)

const val MAX_ENGLISH_WORD_LENGTH = 100
const val MAX_TURKISH_MEANING_LENGTH = 200

interface SavedWordRepository {
    fun observe(bookId: String, contentSha256: String): Flow<List<SavedWord>>
    suspend fun add(bookId: String, contentSha256: String, english: String, turkish: String)
    suspend fun update(bookId: String, contentSha256: String, id: String, english: String, turkish: String)
    suspend fun remove(bookId: String, contentSha256: String, id: String)
}

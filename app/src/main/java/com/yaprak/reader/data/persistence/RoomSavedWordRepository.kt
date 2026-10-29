package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.vocabulary.MAX_ENGLISH_WORD_LENGTH
import com.yaprak.reader.domain.vocabulary.MAX_TURKISH_MEANING_LENGTH
import com.yaprak.reader.domain.vocabulary.SavedWord
import com.yaprak.reader.domain.vocabulary.SavedWordRepository
import java.util.UUID
import kotlinx.coroutines.flow.map

class RoomSavedWordRepository(private val database: LibraryDatabase) : SavedWordRepository {
    override fun observe(bookId: String, contentSha256: String) =
        database.savedWords().observe(bookId, contentSha256).map { rows ->
            rows.map { SavedWord(it.id, it.english, it.turkish, it.createdAt, it.revision) }
        }

    override suspend fun add(bookId: String, contentSha256: String, english: String, turkish: String) {
        val validated = validateInput(english, turkish)
        database.withTransaction {
            verifyBook(bookId, contentSha256)
            database.savedWords().add(SavedWordEntity(
                UUID.randomUUID().toString(), bookId, contentSha256,
                validated.english, validated.turkish, System.currentTimeMillis(),
            ))
        }
    }

    override suspend fun update(bookId: String, contentSha256: String, id: String, english: String, turkish: String) {
        val validated = validateInput(english, turkish)
        database.withTransaction {
            verifyBook(bookId, contentSha256)
            check(database.savedWords().update(bookId, contentSha256, id,
                validated.english, validated.turkish) == 1) { "Saved word missing" }
        }
    }

    override suspend fun remove(bookId: String, contentSha256: String, id: String) {
        database.withTransaction {
            verifyBook(bookId, contentSha256)
            check(database.savedWords().remove(bookId, contentSha256, id) == 1) { "Saved word missing" }
        }
    }

    private suspend fun verifyBook(bookId: String, contentSha256: String) {
        check(database.books().findById(bookId)?.sha256 == contentSha256) { "Saved word book mismatch" }
    }

    private fun validateInput(english: String, turkish: String): ValidatedInput {
        val trimmedEnglish = english.trim()
        val trimmedTurkish = turkish.trim()
        require(trimmedEnglish.length in 1..MAX_ENGLISH_WORD_LENGTH && english.none { it == '\n' || it == '\r' }) {
            "English word must be one line of 1–$MAX_ENGLISH_WORD_LENGTH characters"
        }
        require(trimmedTurkish.length in 1..MAX_TURKISH_MEANING_LENGTH && turkish.none { it == '\n' || it == '\r' }) {
            "Turkish meaning must be one line of 1–$MAX_TURKISH_MEANING_LENGTH characters"
        }
        return ValidatedInput(trimmedEnglish, trimmedTurkish)
    }

    private data class ValidatedInput(val english: String, val turkish: String)
}

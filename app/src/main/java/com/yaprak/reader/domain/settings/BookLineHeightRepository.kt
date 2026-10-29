package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

/** The absence of a book record inherits the current global choice. */
sealed interface BookLineHeightChoice {
    data object Inherit : BookLineHeightChoice
    /** Explicitly follow the publisher's paragraph spacing, regardless of the global choice. */
    data object Publisher : BookLineHeightChoice
    data class Custom(val percent: Int) : BookLineHeightChoice {
        init {
            require(percent in 100..220 && percent % 10 == 0) {
                "EPUB line height must be 100-220% in steps of ten"
            }
        }
    }
}

interface BookLineHeightRepository {
    fun observe(bookId: String, contentSha256: String): Flow<BookLineHeightChoice>
    suspend fun save(bookId: String, contentSha256: String, choice: BookLineHeightChoice)
}

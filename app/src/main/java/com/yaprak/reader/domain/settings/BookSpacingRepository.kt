package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

sealed interface BookSpacingChoice {
    data object Inherit : BookSpacingChoice
    data object Publisher : BookSpacingChoice
    data class Custom(val percent: Int) : BookSpacingChoice {
        init { require(percent in 0..200 && percent % 10 == 0) }
    }
}

/** Both independently chosen fields belong to one atomic book snapshot. */
data class BookSpacingSettings(
    val paragraphs: BookSpacingChoice = BookSpacingChoice.Inherit,
    val words: BookSpacingChoice = BookSpacingChoice.Inherit,
) {
    init { require(words !is BookSpacingChoice.Custom || words.percent <= 100) }
}

fun EpubSettings.withBookSpacing(choice: BookSpacingSettings): EpubSettings {
    fun BookSpacingChoice.resolve(global: Int?): Int? = when (this) {
        BookSpacingChoice.Inherit -> global
        BookSpacingChoice.Publisher -> null
        is BookSpacingChoice.Custom -> percent
    }
    return copy(paragraphSpacingPercent = choice.paragraphs.resolve(paragraphSpacingPercent),
        wordSpacingPercent = choice.words.resolve(wordSpacingPercent))
}

interface BookSpacingRepository {
    fun observe(bookId: String, contentSha256: String): Flow<BookSpacingSettings>
    suspend fun save(bookId: String, contentSha256: String, choice: BookSpacingSettings)
}

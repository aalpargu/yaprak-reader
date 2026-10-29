package com.yaprak.reader.domain.library

import java.util.Locale

enum class BookReadingStatus { UNSET, TO_READ, READING, COMPLETED }

data class BookOrganization(
    val readingStatus: BookReadingStatus = BookReadingStatus.UNSET,
    val favorite: Boolean = false,
    val series: String? = null,
    val genre: String? = null,
    val tags: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
) {
    /** Normalize editable names while rejecting oversized or multiline metadata. */
    fun normalized(): BookOrganization = copy(
        series = normalizeText(series, MAX_TEXT_LENGTH),
        genre = normalizeText(genre, MAX_TEXT_LENGTH),
        tags = normalizeLabels(tags),
        collections = normalizeLabels(collections),
    )

    companion object {
        const val MAX_TEXT_LENGTH = 200
        const val MAX_LABEL_COUNT = 50
        const val MAX_LABEL_LENGTH = 100

        private fun normalizeText(value: String?, maxLength: Int): String? {
            val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            require(trimmed.length <= maxLength && trimmed.none(Char::isISOControl))
            return trimmed
        }

        private fun normalizeLabels(values: List<String>): List<String> {
            val keys = HashSet<String>()
            val normalized = values.mapNotNull { normalizeText(it, MAX_LABEL_LENGTH) }
                .filter { value ->
                    // The editor uses commas as separators, so one label cannot contain a comma.
                    require(',' !in value)
                    val key = value.replace('I', 'i').replace('İ', 'i').replace('ı', 'i')
                        .lowercase(Locale.ROOT)
                    keys.add(key)
                }
            require(normalized.size <= MAX_LABEL_COUNT)
            return normalized
        }
    }
}

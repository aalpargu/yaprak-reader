package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.library.BookOrganization
import java.util.Locale
import org.readium.r2.shared.publication.Metadata

/** Only declared publication metadata; optional invalid names never invent or block a book. */
internal fun initialEpubOrganization(metadata: Metadata): BookOrganization {
    fun name(value: String, max: Int): String? = value.trim().takeIf {
        it.isNotEmpty() && it.length <= max && it.none(Char::isISOControl)
    }
    fun key(value: String) = value.replace('I', 'i').replace('İ', 'i').replace('ı', 'i').lowercase(Locale.ROOT)
    fun labels(names: Sequence<String>) = names.mapNotNull { name(it, BookOrganization.MAX_LABEL_LENGTH) }
        .filter { ',' !in it }.distinctBy(::key).take(BookOrganization.MAX_LABEL_COUNT).toList()
    // The app has one series field. Multiple distinct declared series are not guessed or joined.
    val series = metadata.belongsToSeries.asSequence().mapNotNull { name(it.name, BookOrganization.MAX_TEXT_LENGTH) }
        .distinctBy(::key).take(2).toList().singleOrNull()
    return BookOrganization(series = series,
        tags = labels(metadata.subjects.asSequence().map { it.name }),
        collections = labels(metadata.belongsToCollections.asSequence().map { it.name })).normalized()
}
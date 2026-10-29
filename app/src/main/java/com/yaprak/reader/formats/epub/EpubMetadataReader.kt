package com.yaprak.reader.formats.epub

import android.content.ContentResolver
import com.yaprak.reader.formats.BookMetadata
import java.io.File

/** Readium types stop at this boundary. */
class EpubMetadataReader(resolver: ContentResolver) {
    private val source = EpubPublicationSource(resolver)
    suspend fun read(file: File): BookMetadata = source.open(file).use {
        val metadata = it.publication.metadata
        BookMetadata(
            initialOrganization = initialEpubOrganization(metadata),
            title = metadata.title?.trim()?.takeIf(String::isNotEmpty)?.take(1000),
            authors = metadata.authors.map { author -> author.name.trim() }.filter(String::isNotEmpty)
                .joinToString(", ").takeIf(String::isNotBlank)?.take(2000),
        )
    }
}

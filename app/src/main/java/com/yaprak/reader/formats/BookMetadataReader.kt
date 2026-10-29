package com.yaprak.reader.formats

import com.yaprak.reader.domain.library.BookFormat
import java.io.File

data class BookMetadata(val title: String? = null, val authors: String? = null, val pageCount: Int? = null,
    val initialOrganization: com.yaprak.reader.domain.library.BookOrganization =
        com.yaprak.reader.domain.library.BookOrganization())

fun interface BookMetadataReader {
    suspend fun read(file: File, format: BookFormat): BookMetadata
}

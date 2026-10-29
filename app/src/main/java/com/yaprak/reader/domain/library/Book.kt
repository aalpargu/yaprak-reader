package com.yaprak.reader.domain.library

enum class BookFormat(val extension: String) {
    EPUB("epub"),
    PDF("pdf"),
    TXT("txt"),
    CBZ("cbz"), CBR("cbr"),
    FB2("fb2"), DOCX("docx");
    val comic: Boolean get() = this == CBZ || this == CBR
    val reflowable: Boolean get() = this == EPUB || this == TXT || this == FB2 || this == DOCX
}

data class Book(
    val id: String,
    val title: String,
    val authors: String?,
    val format: BookFormat,
    val originalName: String,
    val storageName: String,
    val byteSize: Long,
    val sha256: String,
    val importedAt: Long,
    val pageCount: Int? = null,
    val lastReadAt: Long? = null,
    val organization: BookOrganization = BookOrganization(),
    val customCover: BookCover? = null,
) {
    /** Original container extension; the private storage name remains opaque to document providers. */
    val originalExtension: String get() = if (format == BookFormat.FB2 && originalName.endsWith(".fb2.zip", ignoreCase = true))
        "fb2.zip" else format.extension
}

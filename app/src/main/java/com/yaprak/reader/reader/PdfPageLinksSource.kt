package com.yaprak.reader.reader

import com.yaprak.reader.domain.reader.ReadingLocation

/** Session-local native GoTo links on one original page; these are not a document outline. */
data class PdfPageLink(val id: String, val ordinal: Int, val targetPageIndex: Int)

interface PdfPageLinksSource {
    val supported: Boolean
    suspend fun load(pageIndex: Int): List<PdfPageLink>
    /** Only an ID issued by the latest successful load can resolve a destination. */
    suspend fun resolve(pageIndex: Int, linkId: String): ReadingLocation
    /** Accepts only the exact resolved object while its source page is still current. */
    fun goTo(location: ReadingLocation): Boolean
}

enum class PdfPageLinksFailure { UNSUPPORTED, LIMIT, SOURCE, INTERRUPTED, NAVIGATION }
class PdfPageLinksException(val reason: PdfPageLinksFailure) : Exception(reason.name)

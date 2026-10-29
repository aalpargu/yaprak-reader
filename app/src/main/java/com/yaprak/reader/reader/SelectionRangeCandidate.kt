package com.yaprak.reader.reader

/** A transient EPUB DOM position; it is not a verified or persisted highlight anchor. */
data class SelectionRangeCandidate(
    val resourceHref: String,
    val path: String,
    val start: Int,
    val end: Int,
    val nodeTextSha256: String,
)

package com.yaprak.reader.reader

/** A transient match to one text node in the original EPUB reading-order XHTML.
 * It does not establish a stable WebView range or prove that the passage was read.
 */
data class SourceTextAnchor(
    override val resourceHref: String,
    val start: Int,
    val end: Int,
    val nodeTextSha256: String,
) : EpubSourceAnchor

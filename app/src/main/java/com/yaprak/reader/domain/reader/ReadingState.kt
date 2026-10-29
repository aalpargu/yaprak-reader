package com.yaprak.reader.domain.reader

/** Engine JSON is opaque outside its format adapter. Percentages never authorize recap text. */
data class ReadingLocation(val locatorJson: String, val resourceHref: String, val locatorVersion: Int = 1)

/** Conservative evidence of a fully visible DOM text node, not proof of comprehension.
 * UTF-16 offsets and child-node paths use the versioned DOM extraction scheme below.
 * The future content extractor must resolve the path and verify the text hash before using it.
 */
data class ObservedTextSpan(
    val resourceHref: String,
    val nodePath: String,
    val startOffset: Int,
    val endOffset: Int,
    val textSha256: String,
    val extractionVersion: Int = 1,
)

data class ReadingSnapshot(val location: ReadingLocation, val spans: List<ObservedTextSpan> = emptyList())

interface ReadingStateRepository {
    suspend fun resume(bookId: String, contentSha256: String): ReadingLocation?
    suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot)
}

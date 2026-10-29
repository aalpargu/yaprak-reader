package com.yaprak.reader.reader

/** Original-XHTML provenance. These models do not establish a live DOM or read coverage. */
sealed interface EpubSourceAnchor { val resourceHref: String }

/** Ordered source text slices; publication text outside the selected slices is never persisted. */
class SourceRangeAnchor(
    override val resourceHref: String,
    segments: List<SourceRangeSegment>,
) : EpubSourceAnchor {
    val segments: List<SourceRangeSegment> = java.util.Collections.unmodifiableList(ArrayList(segments))

    fun copy(resourceHref: String = this.resourceHref, segments: List<SourceRangeSegment> = this.segments) =
        SourceRangeAnchor(resourceHref, segments)

    override fun equals(other: Any?): Boolean = other is SourceRangeAnchor &&
        resourceHref == other.resourceHref && segments == other.segments
    override fun hashCode(): Int = 31 * resourceHref.hashCode() + segments.hashCode()
    override fun toString(): String = "SourceRangeAnchor(resourceHref=$resourceHref, segments=$segments)"
}

data class SourceRangeSegment(
    val path: String,
    val start: Int,
    val end: Int,
    val nodeTextSha256: String,
    val selectedText: String,
)

/** Structural limits alone are not proof of either source or live-document correspondence. */
object SourceRangeAnchors {
    const val MAX_SEGMENTS = 64
    const val MAX_SELECTED_UNITS = 4096
    const val MAX_NODE_UNITS = 4096
    const val MAX_PATH_DEPTH = 64
    const val MAX_PATH_UNITS = 1024
    private val shaPattern = Regex("[0-9a-f]{64}")
    private val pathPattern = Regex("(?:0|[1-9][0-9]*)(?:/(?:0|[1-9][0-9]*))*")

    fun isValid(anchor: SourceRangeAnchor): Boolean {
        if (anchor.resourceHref.isBlank() || anchor.resourceHref.length > 2000 ||
            !validUtf16(anchor.resourceHref) || anchor.resourceHref.any { it.isISOControl() } || anchor.segments.isEmpty() ||
            anchor.segments.size > MAX_SEGMENTS) return false
        var total = 0
        var previous: List<Int>? = null
        for (segment in anchor.segments) {
            val path = pathIndices(segment.path) ?: return false
            if (previous != null && comparePaths(previous, path) >= 0) return false
            previous = path
            if (segment.start < 0 || segment.end <= segment.start || segment.end > MAX_NODE_UNITS ||
                segment.end - segment.start != segment.selectedText.length ||
                !shaPattern.matches(segment.nodeTextSha256) || !validUtf16(segment.selectedText)) return false
            total += segment.selectedText.length
            if (total > MAX_SELECTED_UNITS) return false
        }
        return true
    }

    fun pathIndices(path: String): List<Int>? {
        if (path.length > MAX_PATH_UNITS || !pathPattern.matches(path)) return null
        val pieces = path.split('/')
        if (pieces.size > MAX_PATH_DEPTH) return null
        return pieces.map { it.toIntOrNull()?.takeIf { value -> value in 0..200_000 } ?: return null }
    }

    fun validUtf16(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char.isHighSurrogate()) {
                if (index == text.length || !text[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) return false
        }
        return true
    }

    private fun comparePaths(first: List<Int>, second: List<Int>): Int {
        for (index in 0 until minOf(first.size, second.size)) {
            val result = first[index].compareTo(second[index])
            if (result != 0) return result
        }
        return first.size.compareTo(second.size)
    }
}

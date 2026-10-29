package com.yaprak.reader.formats.epub

/** SDK-normalized resource extent, not a chapter, text count or read-coverage boundary. */
internal data class EpubPaceRange(val start: Double, val end: Double) {
    fun position(progression: Double?): Double? {
        if (!start.isFinite() || !end.isFinite() || start !in 0.0..1.0 || end !in 0.0..1.0 || end <= start ||
            progression == null || !progression.isFinite() || progression !in 0.0..1.0) return null
        return start + progression * (end - start)
    }
}

/** The supplied starts must come from the actual publication positions service, never equal guesses. */
internal class EpubPaceMap private constructor(private val ranges: Map<String, EpubPaceRange>) {
    fun range(href: String): EpubPaceRange? = ranges[href]

    companion object {
        const val MAX_RESOURCES = 10_000
        const val MAX_POSITIONS = 100_000
        fun create(resources: List<String>, starts: List<Double?>): EpubPaceMap? {
            if (resources.isEmpty() || resources.size > MAX_RESOURCES || resources.size != starts.size ||
                resources.distinct().size != resources.size || resources.any { it.isBlank() || it.length > 4096 || it.any(Char::isISOControl) } ||
                starts.any { it == null || !it.isFinite() || it !in 0.0..<1.0 } || starts.first() != 0.0) return null
            val values = starts.map { checkNotNull(it) }
            if (values.zipWithNext().any { (a, b) -> b <= a }) return null
            val result = LinkedHashMap<String, EpubPaceRange>()
            resources.forEachIndexed { index, href ->
                result[href] = EpubPaceRange(values[index], values.getOrElse(index + 1) { 1.0 })
            }
            return EpubPaceMap(java.util.Collections.unmodifiableMap(result))
        }
    }
}

package com.yaprak.reader.feature.reader

/** A temporary one-word suggestion from selected text, without language or anchor claims. */
private val selectedWordPattern = Regex("""\p{L}+(?:['’\-]\p{L}+)*""")

internal fun selectedWordCandidate(text: String): String? {
    val candidate = text.trim()
    return candidate.takeIf { it.length in 1..100 && selectedWordPattern.matches(it) }
}

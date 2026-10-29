package com.yaprak.reader.feature.reader

/** Temporary plain-text note draft from an explicit EPUB selection; it carries no source anchor. */
internal fun selectedNoteCandidate(text: String): String? {
    val candidate = text.trim()
    return candidate.takeIf { it.length in 1..2000 }
}

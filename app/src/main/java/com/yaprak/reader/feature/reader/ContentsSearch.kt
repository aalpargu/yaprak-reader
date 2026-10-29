package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.reader.ContentsEntry
import java.util.Locale

private val contentsSearchWhitespace = Regex("[\\s\\p{Z}]+")

/** Searches TOC titles only; original entry identities and publication order remain intact. */
internal fun searchContents(entries: List<ContentsEntry>, query: String): List<ContentsEntry> {
    val terms = query.contentsSearchKey().split(contentsSearchWhitespace).filter(String::isNotEmpty)
    if (terms.isEmpty()) return entries
    return entries.filter { entry ->
        val title = entry.title.contentsSearchKey()
        terms.all(title::contains)
    }
}

// Match Turkish İ/I/ı/i independently of the device's default locale.
private fun String.contentsSearchKey() = lowercase(Locale.ROOT).replace("i\u0307", "i").replace('ı', 'i')

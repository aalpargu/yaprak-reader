@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.reader.BookSearchHit
import com.yaprak.reader.reader.BookSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.isSearchable
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse

/** Uses Readium's local search service; no index, query, or excerpt is written to storage. */
internal class EpubBookSearch(private val publication: Publication) {
    private val searchMutex = Mutex()
    val available: Boolean get() = publication.isSearchable

    suspend fun find(query: String): BookSearchResults = withContext(Dispatchers.IO) {
        searchMutex.withLock {
            require(query.isNotBlank() && query.length <= MAX_QUERY_LENGTH)
            val iterator = publication.search(query) ?: throw IllegalStateException("EPUB search is unavailable")
            try {
                val hits = ArrayList<BookSearchHit>()
                var truncated = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val page = iterator.next().getOrElse { throw IllegalStateException("EPUB search failed") } ?: break
                    for (locator in page.locators) {
                        currentCoroutineContext().ensureActive()
                        val hit = toHit(locator) ?: continue
                        if (hits.size == MAX_RESULTS) {
                            truncated = true
                            break
                        }
                        hits += hit
                    }
                    if (truncated) break
                }
                BookSearchResults(hits, truncated)
            } finally {
                // Keep the service locked until its cursor is closed, even after cancellation.
                withContext(NonCancellable) { iterator.close() }
            }
        }
    }

    private fun toHit(locator: Locator): BookSearchHit? {
        val href = locator.href.removeFragment().toString()
        val location = ReadingLocation(locator.toJSON().toString(), href)
        if (decodeEpubLocation(location, publication.readingOrder) == null) return null
        val index = publication.readingOrder.indexOfFirst { it.url().removeFragment() == locator.href.removeFragment() }
        val title = locator.title?.trim()?.take(160)?.takeIf(String::isNotEmpty)
            ?: publication.readingOrder.getOrNull(index)?.title?.trim()?.take(160)?.takeIf(String::isNotEmpty)
            ?: "Bölüm ${index + 1}"
        return BookSearchHit(title, searchSnippet(locator.text), location)
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 200
        const val MAX_RESULTS = 200
    }
}

/** Readium supplies context around a real match. Keep previews short and plain-text only. */
internal fun searchSnippet(text: Locator.Text): String? {
    fun clean(value: String?) = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    val match = clean(text.highlight)
    if (match.isEmpty()) return null
    val before = clean(text.before)
    val after = clean(text.after)
    val prefix = before.takeLast(80)
    val selected = match.take(160)
    val suffix = after.take(80)
    return buildString {
        if (before.length > prefix.length) append('…')
        if (prefix.isNotEmpty()) append(prefix).append(' ')
        append(selected)
        if (match.length > selected.length) append('…')
        if (suffix.isNotEmpty()) append(' ').append(suffix)
        if (after.length > suffix.length) append('…')
    }
}

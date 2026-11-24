package com.yaprak.reader.formats.epub

import com.yaprak.reader.reader.ReadAloudChunk
import com.yaprak.reader.reader.ReadAloudSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content

/**
 * Walks the publication's own text with Readium's content iterator. Each batch restarts the iterator
 * after the last spoken element, so no iterator is kept across pauses or navigation.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class EpubReadAloudSource(
    private val publication: Publication,
    private val current: () -> Locator?,
    private val go: (Locator) -> Boolean,
) : ReadAloudSource {
    override val language: String? get() = publication.metadata.languages.firstOrNull()

    override suspend fun fromCurrent(): List<ReadAloudChunk> = batch(current(), skipFirst = false)

    override suspend fun next(after: ReadAloudChunk): List<ReadAloudChunk> =
        batch(after.position as? Locator, skipFirst = true)

    override fun show(chunk: ReadAloudChunk): Boolean = (chunk.position as? Locator)?.let(go) ?: false

    private suspend fun batch(start: Locator?, skipFirst: Boolean): List<ReadAloudChunk> = withContext(Dispatchers.IO) {
        val iterator = publication.content(start)?.iterator() ?: return@withContext emptyList()
        val chunks = ArrayList<ReadAloudChunk>()
        var skipped = !skipFirst
        while (chunks.size < BATCH && iterator.hasNext()) {
            val element = iterator.next()
            if (!skipped) { skipped = true; continue }
            val text = (element as? Content.TextualElement)?.text?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            // Speech engines cap one utterance (~4000 chars); split long paragraphs at sentence ends.
            splitForSpeech(text).forEach { chunks += ReadAloudChunk(it, element.locator) }
        }
        chunks
    }

    private companion object { const val BATCH = 40 }
}

internal fun splitForSpeech(text: String, limit: Int = 1500): List<String> {
    if (text.length <= limit) return listOf(text)
    val parts = ArrayList<String>()
    var rest = text
    while (rest.length > limit) {
        val window = rest.substring(0, limit)
        val cut = listOf(". ", "! ", "? ", "; ", ", ", " ").firstNotNullOfOrNull { mark ->
            window.lastIndexOf(mark).takeIf { it > limit / 3 }?.let { it + mark.length }
        } ?: limit
        parts += rest.substring(0, cut).trim()
        rest = rest.substring(cut)
    }
    if (rest.isNotBlank()) parts += rest.trim()
    return parts
}

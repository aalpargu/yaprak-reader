package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.search.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.RelativeUrl

/** The original publication TOC defines explicit start/exclusive-end scope, never read-prefix. */
internal class EpubChapterCatalog(links: List<Link>, private val hrefs: List<String>, private val checkActive: () -> Unit) {
    private data class Entry(val title: String, val depth: Int, val href: String, val resource: Int, val anchor: String?)
    private data class ResourceEvidence(val hash: String, val length: Int, val anchors: Map<String, Int?>)
    private val entries = ArrayList<Entry>()
    private val resources = linkedMapOf<Int, ResourceEvidence>()
    private val fingerprint: String

    init {
        val stack = ArrayDeque<Iterator<Link>>()
        stack.addLast(links.iterator())
        while (stack.isNotEmpty()) {
            checkActive()
            if (!stack.last().hasNext()) { stack.removeLast(); continue }
            if (entries.size == MAX_BOOK_TOC_ENTRIES || stack.size > MAX_BOOK_TOC_DEPTH) fail(TextExtractionFailure.LIMIT)
            val link = stack.last().next()
            val url = link.url()
            if (link.href.isTemplated || url !is RelativeUrl) fail(TextExtractionFailure.UNSUPPORTED)
            val href = url.toString()
            val resourceHref = url.removeFragment().toString()
            if (!safeLibraryTextHref(resourceHref)) fail(TextExtractionFailure.UNSUPPORTED)
            val resource = hrefs.indexOf(resourceHref)
            if (resource < 0) fail(TextExtractionFailure.UNSUPPORTED)
            val uri = try { URI(href) } catch (_: Exception) { fail(TextExtractionFailure.UNSUPPORTED) }
            if (uri.rawQuery != null || uri.rawAuthority != null || uri.isAbsolute) fail(TextExtractionFailure.UNSUPPORTED)
            val anchor = uri.rawFragment?.let { decodeFragment(it) }
            val title = link.title?.trim() ?: fail(TextExtractionFailure.UNSUPPORTED)
            if (title.isBlank() || title.length > 500 || !validTitle(title)) fail(TextExtractionFailure.UNSUPPORTED)
            entries += Entry(title, stack.size - 1, href, resource, anchor)
            if (link.children.isNotEmpty()) stack.addLast(link.children.iterator())
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(entries.size)
            for (entry in entries) {
                checkActive()
                for (value in listOf(entry.title, entry.href)) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size); output.write(encoded)
                }
                output.writeInt(entry.depth)
            }
        }
        fingerprint = hash(bytes.toByteArray())
    }

    fun wantedIds(resource: Int): Set<String> = entries.asSequence().filter { it.resource == resource }
        .mapNotNull { it.anchor }.toSet()

    fun accept(resource: Int, hash: String, body: BoundedXhtmlTextParser.AnchoredText) {
        resources[resource] = ResourceEvidence(hash, body.text.length, body.anchors)
    }

    fun chapters(document: ExtractedBookText, chunkOffsets: List<Int>): ExtractedBookChapters {
        val temporary = ExtractedBookChapters(document, emptyList(), chunkOffsets)
        if (entries.isEmpty()) return temporary
        if (resources.size != hrefs.size || hrefs.isEmpty()) fail(TextExtractionFailure.INVALID)
        val starts = entries.map { entry ->
            checkActive()
            val resource = resources.getValue(entry.resource)
            val offset = if (entry.anchor == null) 0 else resource.anchors[entry.anchor]
                ?: fail(TextExtractionFailure.UNSUPPORTED)
            BookChapterBoundary(entry.resource, hrefs[entry.resource], resource.hash, offset)
        }
        for (index in 1 until starts.size) {
            checkActive()
            if (compareChapterBoundaries(starts[index - 1], starts[index]) > 0) fail(TextExtractionFailure.UNSUPPORTED)
        }
        val finalResource = resources.getValue(hrefs.lastIndex)
        val finish = BookChapterBoundary(hrefs.lastIndex, hrefs.last(), finalResource.hash, finalResource.length)
        val result = entries.mapIndexed { index, entry ->
            checkActive()
            var following = index + 1
            while (following < entries.size && entries[following].depth > entry.depth) { checkActive(); following++ }
            val end = starts.getOrNull(following) ?: finish
            val start = starts[index]
            if (compareChapterBoundaries(start, end) >= 0) fail(TextExtractionFailure.UNSUPPORTED)
            val count = extractedChapterPieces(temporary, start, end, checkActive).size
            ExtractedChapter(fingerprint, index, entry.title, entry.depth, start, end, count)
        }
        return ExtractedBookChapters(document, result, chunkOffsets)
    }

    private fun decodeFragment(raw: String): String {
        if (raw.isEmpty() || raw.length > 2048 || raw.startsWith("epubcfi(")) fail(TextExtractionFailure.UNSUPPORTED)
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < raw.length) {
            checkActive()
            if (raw[index] == '%') {
                if (index + 2 >= raw.length) fail(TextExtractionFailure.UNSUPPORTED)
                val value = raw.substring(index + 1, index + 3).toIntOrNull(16) ?: fail(TextExtractionFailure.UNSUPPORTED)
                bytes.write(value); index += 3
            } else {
                val start = index++
                if (raw[start].isHighSurrogate()) {
                    if (index >= raw.length || !raw[index].isLowSurrogate()) fail(TextExtractionFailure.UNSUPPORTED)
                    index++
                } else if (raw[start].isLowSurrogate()) fail(TextExtractionFailure.UNSUPPORTED)
                bytes.write(raw.substring(start, index).toByteArray(Charsets.UTF_8))
            }
        }
        val value = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString() }
            catch (_: Exception) { fail(TextExtractionFailure.UNSUPPORTED) }
        if (value.isBlank() || value.any { it.isISOControl() } || value.length > 2048) fail(TextExtractionFailure.UNSUPPORTED)
        return value
    }

    private fun validTitle(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char == '\u0000' || char.isLowSurrogate()) return false
            if (char.isHighSurrogate() && (index >= value.length || !value[index++].isLowSurrogate())) return false
        }
        return true
    }

    private fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }
    private fun fail(reason: TextExtractionFailure): Nothing = throw TextExtractionException(reason)
}

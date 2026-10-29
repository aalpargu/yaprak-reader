package com.yaprak.reader.domain.passages

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeAnchors
import com.yaprak.reader.reader.SourceRangeSegment
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.security.MessageDigest

/** Canonical storage validation only. A valid payload does not prove a live or original-source range. */
object EpubRangeAnchorCodec {
    const val MAX_JSON_UNITS = 131072
    const val MAX_JSON_BYTES = 131072

    fun encode(anchor: SourceRangeAnchor): String {
        require(SourceRangeAnchors.isValid(anchor) && isSafeEpubPassageHref(anchor.resourceHref)) {
            "Invalid EPUB range anchor"
        }
        val buffer = StringWriter()
        JsonWriter(buffer).use { writer ->
            writer.beginObject().name("resourceHref").value(anchor.resourceHref).name("segments").beginArray()
            anchor.segments.forEach { segment ->
                writer.beginObject().name("path").value(segment.path).name("start").value(segment.start)
                    .name("end").value(segment.end).name("nodeTextSha256").value(segment.nodeTextSha256)
                    .name("selectedText").value(segment.selectedText).endObject()
            }
            writer.endArray().endObject()
        }
        return buffer.toString().also(::bounded)
    }

    fun decode(resourceHref: String, json: String): SourceRangeAnchor {
        bounded(json)
        val anchor = try {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                reader.beginObject()
                val fields = HashSet<String>()
                var href: String? = null
                var segments: List<SourceRangeSegment>? = null
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(fields.add(name)) { "Duplicate EPUB range field" }
                    when (name) {
                        "resourceHref" -> href = reader.string()
                        "segments" -> {
                            reader.beginArray()
                            val slices = ArrayList<SourceRangeSegment>()
                            while (reader.hasNext()) {
                                require(slices.size < SourceRangeAnchors.MAX_SEGMENTS) { "EPUB range is too large" }
                                reader.beginObject()
                                val keys = HashSet<String>()
                                var path: String? = null
                                var start: Int? = null
                                var end: Int? = null
                                var hash: String? = null
                                var selected: String? = null
                                while (reader.hasNext()) {
                                    val key = reader.nextName()
                                    require(keys.add(key)) { "Duplicate EPUB range segment field" }
                                    when (key) {
                                        "path" -> path = reader.string()
                                        "start" -> start = reader.integer()
                                        "end" -> end = reader.integer()
                                        "nodeTextSha256" -> hash = reader.string()
                                        "selectedText" -> selected = reader.string()
                                        else -> error("Unknown EPUB range segment field")
                                    }
                                }
                                reader.endObject()
                                require(keys == setOf("path", "start", "end", "nodeTextSha256", "selectedText"))
                                slices += SourceRangeSegment(checkNotNull(path), checkNotNull(start),
                                    checkNotNull(end), checkNotNull(hash), checkNotNull(selected))
                            }
                            reader.endArray()
                            segments = slices
                        }
                        else -> error("Unknown EPUB range field")
                    }
                }
                reader.endObject()
                require(fields == setOf("resourceHref", "segments") && reader.peek() == JsonToken.END_DOCUMENT)
                require(href == resourceHref) { "EPUB range resource mismatch" }
                SourceRangeAnchor(checkNotNull(href), checkNotNull(segments))
            }
        } catch (invalid: Exception) {
            throw IllegalArgumentException("Invalid EPUB range payload", invalid)
        }
        require(encode(anchor) == json) { "Noncanonical EPUB range payload" }
        return anchor
    }

    fun key(anchor: SourceRangeAnchor): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(anchor).toByteArray(Charsets.UTF_8)).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }

    fun verify(resourceHref: String, json: String, anchorKey: String): SourceRangeAnchor {
        require(Regex("[0-9a-f]{64}").matches(anchorKey)) { "Invalid EPUB range key" }
        return decode(resourceHref, json).also {
            require(key(it) == anchorKey) { "EPUB range key mismatch" }
        }
    }

    private fun bounded(json: String) {
        require(json.length in 1..MAX_JSON_UNITS && SourceRangeAnchors.validUtf16(json) &&
            json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "EPUB range payload exceeds its bound" }
    }

    private fun JsonReader.string(): String {
        require(peek() == JsonToken.STRING)
        return nextString()
    }

    private fun JsonReader.integer(): Int {
        require(peek() == JsonToken.NUMBER)
        val raw = nextString()
        require(Regex("0|[1-9][0-9]*").matches(raw))
        return raw.toIntOrNull() ?: error("EPUB range integer overflow")
    }
}

fun isSafeEpubPassageHref(href: String): Boolean {
    if (href.isEmpty() || href.length > 4096 || href.contains('\\') ||
        Regex("%(?:2[fF]|5[cC])").containsMatchIn(href) || href.any(Char::isISOControl) ||
        !isWellFormedEpubAnnotationText(href)) return false
    return try {
        val uri = URI(href)
        val path = uri.path ?: return false
        !uri.isAbsolute && !uri.isOpaque && uri.rawAuthority == null && uri.rawQuery == null &&
            uri.rawFragment == null && path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) } &&
            uri.rawPath == href
    } catch (_: IllegalArgumentException) { false }
}

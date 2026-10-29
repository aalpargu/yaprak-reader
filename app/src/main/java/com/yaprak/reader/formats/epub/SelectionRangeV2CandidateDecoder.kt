package com.yaprak.reader.formats.epub

import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeAnchors
import com.yaprak.reader.reader.SourceRangeSegment
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.shared.util.Url

/** Full source/live body inventories are short-lived. Nothing returned here is a durable proof. */
internal object SelectionRangeV2CandidateDecoder {
    private val tokenPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}:[0-9]{1,16}:[0-9]{1,16}")

    fun decodeIdentity(raw: String?, resourceHref: String, selectedText: String): SelectionRangeIdentity? = try {
        if (raw == null || raw.length > 65_536 || selectedText.isBlank() || selectedText.length > 5000 ||
            !SourceRangeAnchors.validUtf16(selectedText)) null else {
            val outer = JSONTokener(raw)
            val value = outer.nextValue() as? String
            val inner = value?.let(::JSONTokener)
            val json = inner?.nextValue() as? JSONObject
            if (json == null || outer.nextClean() != '\u0000' || inner?.nextClean() != '\u0000' ||
                !keys(json, setOf("version", "href", "preview", "selectionToken")) || json.opt("version") != 2 ||
                json.opt("preview") != selectedText) null else {
                val actual = json.opt("href") as? String
                val token = json.opt("selectionToken") as? String
                val expected = Url(resourceHref)?.path
                if (actual == null || expected == null || !actual.startsWith("/publication/") ||
                    actual.removePrefix("/publication/") != expected || token == null || !tokenPattern.matches(token)) null
                else SelectionRangeIdentity(resourceHref, selectedText, token).takeIf { validToken(token) }
            }
        }
    } catch (_: Exception) { null }

    fun decode(raw: String?, resourceHref: String, selectedText: String): SelectionRangeV2Candidate? = try {
        if (raw == null || raw.length > SourceTextAnchorVerifier.MAX_RESOURCE_BYTES || selectedText.isBlank() ||
            selectedText.length > SourceRangeAnchors.MAX_SELECTED_UNITS || !SourceRangeAnchors.validUtf16(selectedText)) null
        else {
            val outer = JSONTokener(raw)
            val value = outer.nextValue() as? String
            if (value == null || outer.nextClean() != '\u0000' ||
                value.toByteArray(Charsets.UTF_8).size > SourceTextAnchorVerifier.MAX_RESOURCE_BYTES) null
            else {
                val inner = JSONTokener(value)
                val json = inner.nextValue() as? JSONObject
                if (json == null || inner.nextClean() != '\u0000') null else parse(json, resourceHref, selectedText)
            }
        }
    } catch (_: Exception) { null }

    private fun parse(json: JSONObject, href: String, selected: String): SelectionRangeV2Candidate? {
        if (!keys(json, setOf("version", "href", "preview", "selectionToken", "segments", "bodyTopology")) ||
            json.opt("version") != 2 || json.opt("preview") != selected) return null
        val expected = Url(href)?.path ?: return null
        val actual = json.opt("href") as? String ?: return null
        if (!actual.startsWith("/publication/") || actual.removePrefix("/publication/") != expected) return null
        val token = json.opt("selectionToken") as? String ?: return null
        if (!validToken(token)) return null
        val topology = decodeTopology(json.opt("bodyTopology") as? JSONArray ?: return null) ?: return null
        val array = json.opt("segments") as? JSONArray ?: return null
        if (array.length() !in 1..SourceRangeAnchors.MAX_SEGMENTS) return null
        val segments = (0 until array.length()).map { index ->
            val slice = array.opt(index) as? JSONObject ?: return null
            if (!keys(slice, setOf("path", "start", "end", "nodeText"))) return null
            val path = slice.opt("path") as? String ?: return null
            val start = slice.opt("start") as? Int ?: return null
            val end = slice.opt("end") as? Int ?: return null
            val nodeText = slice.opt("nodeText") as? String ?: return null
            if (nodeText.length > SourceRangeAnchors.MAX_NODE_UNITS || !SourceRangeAnchors.validUtf16(nodeText) ||
                start < 0 || end <= start || end > nodeText.length ||
                !validUtf16Boundary(nodeText, start) || !validUtf16Boundary(nodeText, end)) return null
            SourceRangeSegment(path, start, end, sourceNodeSha256(nodeText), nodeText.substring(start, end))
        }
        val anchor = SourceRangeAnchor(href, segments)
        if (!SourceRangeAnchors.isValid(anchor)) return null
        return SelectionRangeV2Candidate(href, selected, token, anchor.segments, topology)
    }

    private fun decodeTopology(array: JSONArray): List<List<Any>>? {
        if (array.length() !in 1..SourceTextAnchorVerifier.MAX_XML_NODES) return null
        var pending = 1L
        val result = ArrayList<List<Any>>(array.length())
        for (index in 0 until array.length()) {
            if (pending-- <= 0) return null
            val entry = array.opt(index) as? JSONArray ?: return null
            val type = entry.opt(0) as? Int ?: return null
            val decoded: List<Any> = when (type) {
                1 -> {
                    if (entry.length() != 4) return null
                    val namespace = entry.opt(1) as? String ?: return null
                    val name = entry.opt(2) as? String ?: return null
                    val count = entry.opt(3) as? Int ?: return null
                    if (namespace.length > 4096 || name.isEmpty() || name.length > 1024 ||
                        !SourceRangeAnchors.validUtf16(namespace) || !SourceRangeAnchors.validUtf16(name) ||
                        count !in 0..SourceTextAnchorVerifier.MAX_XML_NODES) return null
                    if (index == 0 && (namespace != "http://www.w3.org/1999/xhtml" || name != "body")) return null
                    pending += count
                    if (pending > SourceTextAnchorVerifier.MAX_XML_NODES) return null
                    listOf(1, namespace, name, count)
                }
                3, 8 -> {
                    if (entry.length() != 2 || index == 0) return null
                    val text = entry.opt(1) as? String ?: return null
                    if (!SourceRangeAnchors.validUtf16(text)) return null
                    listOf(type, text)
                }
                else -> return null
            }
            result.add(java.util.Collections.unmodifiableList(decoded))
        }
        return if (pending == 0L) java.util.Collections.unmodifiableList(result) else null
    }

    private fun keys(json: JSONObject, expected: Set<String>): Boolean = json.keys().asSequence().toSet() == expected
    private fun validToken(token: String): Boolean = tokenPattern.matches(token) && token.split(':').drop(1).all {
        (it == "0" || !it.startsWith('0')) && it.toLongOrNull()?.let { value -> value <= 9_007_199_254_740_991L } == true
    }
}

internal data class SelectionRangeIdentity(val resourceHref: String, val preview: String, val selectionToken: String)

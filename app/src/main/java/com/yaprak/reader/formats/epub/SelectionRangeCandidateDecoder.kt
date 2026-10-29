package com.yaprak.reader.formats.epub

import com.yaprak.reader.reader.SelectionRangeCandidate
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.shared.util.Url

/** Decode a WebView result without retaining the source text. All mismatches fail closed. */
internal object SelectionRangeCandidateDecoder {
    private val pathPattern = Regex("[0-9]+(?:/[0-9]+)*")

    fun decode(raw: String?, resourceHref: String, selectedText: String): SelectionRangeCandidate? = try {
        if (raw == null || raw.length > 65_536 || selectedText.isEmpty()) null else {
            val value = JSONTokener(raw).nextValue() as? String
            if (value == null) null else parse(JSONObject(value), resourceHref, selectedText)
        }
    } catch (_: Exception) { null }

    private fun parse(json: JSONObject, resourceHref: String, selectedText: String): SelectionRangeCandidate? {
        val expectedPath = Url(resourceHref)?.path ?: return null
        val actualHref = json.opt("href") as? String ?: return null
        if (!actualHref.startsWith("/publication/") ||
            actualHref.removePrefix("/publication/") != expectedPath) return null

        val path = json.opt("path") as? String ?: return null
        if (path.length > 1024 || !pathPattern.matches(path) || path.count { it == '/' } >= 64) return null
        val start = json.opt("start") as? Int ?: return null
        val end = json.opt("end") as? Int ?: return null
        val nodeText = json.opt("nodeText") as? String ?: return null
        if (nodeText.length > 4096 || start < 0 || end <= start || end > nodeText.length ||
            !validUtf16Boundary(nodeText, start) || !validUtf16Boundary(nodeText, end) ||
            nodeText.substring(start, end) != selectedText) return null

        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(nodeText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return SelectionRangeCandidate(resourceHref, path, start, end, sha256)
    }
}

internal fun validUtf16Boundary(text: String, offset: Int): Boolean =
    offset in 0..text.length && !(offset > 0 && offset < text.length &&
        text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.reader.ObservedTextSpan
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.shared.util.Url

/** Handles WebView's JSON-string return envelope. Never stores passages or future context. */
internal object VisibleTextObservation {
    fun decode(raw: String?, resourceHref: String): List<ObservedTextSpan> = try {
        if (raw == null || raw.length > 2_000_000) emptyList() else {
            val value = JSONTokener(raw).nextValue() as? String
            if (value == null) emptyList() else parse(JSONObject(value), resourceHref)
        }
    } catch (_: Exception) { emptyList() }

    private fun parse(json: JSONObject, href: String): List<ObservedTextSpan> {
        val expectedPath = Url(href)?.path ?: return emptyList()
        val actualPath = json.optString("href").removePrefix("/publication/")
        if (actualPath != expectedPath) return emptyList()
        val nodes = json.optJSONArray("nodes") ?: return emptyList()
        if (nodes.length() > 256) return emptyList()
        return (0 until nodes.length()).mapNotNull { index ->
            val node = nodes.optJSONObject(index) ?: return@mapNotNull null
            val path = node.optString("path")
            val text = node.optString("text")
            if (path.length > 1024 || !path.matches(Regex("[0-9]+(?:/[0-9]+)*")) || text.isBlank() || text.length > 4096) null
            else ObservedTextSpan(href, path, 0, text.length, MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
        }.distinct()
    }
}

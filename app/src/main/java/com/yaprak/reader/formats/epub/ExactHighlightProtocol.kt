package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceTextAnchor
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** JSON is passed as data to one app-owned script; publication/user text is never executable code. */
internal class ExactHighlightProtocol(private val script: String, val owner: String, private val sourceHash: String) {
    fun request(op: String, generation: Long, path: String, documentToken: String? = null,
        documentSeed: String? = null, records: List<EpubPassage> = emptyList(), proof: SourceAnchorBatch? = null): String {
        val request = JSONObject().put("op", op).put("owner", owner).put("sourceHash", sourceHash)
            .put("generation", generation).put("path", path)
        documentToken?.let { request.put("documentToken", it) }
        documentSeed?.let { request.put("documentSeed", it) }
        if (op == "apply") {
            val nodes = mutableListOf<String>()
            val indices = mutableMapOf<String, Int>()
            val ranges = JSONArray()
            val rangeNodes = JSONArray()
            val rangeIndices = mutableMapOf<String, Pair<String, Int>>()
            val groups = JSONArray()
            for (record in records) {
                val color = record.highlightColor ?: continue
                when (val anchor = record.anchor) {
                    is SourceTextAnchor -> {
                        val fullText = proof?.nodeTexts?.get(record.id) ?: continue
                        val index = indices.getOrPut(fullText) { nodes.add(fullText); nodes.lastIndex }
                        ranges.put(JSONObject().put("id", record.id).put("node", index).put("start", anchor.start)
                            .put("end", anchor.end).put("text", record.text).put("color", color.name))
                    }
                    is SourceRangeAnchor -> {
                        // A group is included only with complete original-source proof. Display text may
                        // contain native selection separators; each source slice carries its own text.
                        val fullTexts = proof?.rangeNodeTexts?.get(record.id) ?: continue
                        if (proof.bodyTopology == null || fullTexts.size != anchor.segments.size) continue
                        val segments = JSONArray()
                        for ((ordinal, segment) in anchor.segments.withIndex()) {
                            val fullText = fullTexts[ordinal]
                            val previous = rangeIndices[segment.path]
                            if (previous != null && previous.first != fullText) throw HighlightProofMismatchException()
                            val index = previous?.second ?: rangeNodes.length().also {
                                rangeNodes.put(JSONObject().put("path", segment.path).put("text", fullText))
                                rangeIndices[segment.path] = fullText to it
                            }
                            segments.put(JSONObject().put("node", index).put("start", segment.start)
                                .put("end", segment.end).put("text", segment.selectedText))
                        }
                        groups.put(JSONObject().put("id", record.id).put("revision", record.revision.toString())
                            .put("color", color.name).put("segments", segments))
                    }
                }
            }
            request.put("nodes", JSONArray(nodes)).put("ranges", ranges).put("total", records.size)
            if (groups.length() > 0) {
                request.put("rangeNodes", rangeNodes).put("groups", groups)
                    .put("bodyTopology", JSONArray(proof!!.bodyTopology!!.map { JSONArray(it) }))
            }
        }
        val json = request.toString()
        if (json.toByteArray(Charsets.UTF_8).size > SourceTextAnchorVerifier.MAX_RESOURCE_BYTES)
            throw HighlightPayloadLimitException()
        return script.replace("/*YAPRAK_REQUEST*/", json)
    }

    fun decode(raw: String?, generation: Long, path: String, token: String? = null): HighlightReply? = try {
        if (raw == null || raw.length > 8192) null else {
            val value = JSONTokener(raw).nextValue() as? String
            val json = value?.let(::JSONObject)
            val rawGeneration = json?.opt("generation")
            val exactGeneration = when (rawGeneration) { is Int -> rawGeneration.toLong(); is Long -> rawGeneration; else -> null }
            if (json == null || json.opt("owner") != owner || json.opt("sourceHash") != sourceHash ||
                exactGeneration != generation || json.opt("path") != path) null else {
                val state = json.opt("state") as? String
                val documentToken = json.opt("documentToken") as? String
                if (state == null || (token != null && documentToken != token) ||
                    (documentToken != null && !Regex("[a-z0-9-]{36}").matches(documentToken))) null
                else HighlightReply(state, documentToken, json.opt("total") as? Int, json.opt("resolved") as? Int)
            }
        }
    } catch (_: Exception) { null }
}

internal data class HighlightReply(val state: String, val documentToken: String?, val total: Int?, val resolved: Int?)
internal class HighlightPayloadLimitException : Exception("Exact highlight payload limit exceeded")
internal class HighlightProofMismatchException : Exception("Exact highlight proof is inconsistent")

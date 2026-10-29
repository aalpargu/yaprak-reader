package com.yaprak.reader.formats.epub

import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeAnchors
import com.yaprak.reader.reader.SourceRangeSegment
import java.security.MessageDigest
import java.util.ArrayDeque
import org.json.JSONArray
import org.w3c.dom.Node

/** Exact typed body identity is a transient proof, never a persisted annotation field. */
internal data class SourceBodyTopology(
    val entries: List<List<Any>>,
    val textNodes: List<Pair<String, String>>,
)

internal data class SelectionRangeV2Candidate(
    val resourceHref: String,
    val preview: String,
    val selectionToken: String,
    val segments: List<SourceRangeSegment>,
    val bodyTopology: List<List<Any>>,
)

/** Paths become meaningful only after the original and complete live body inventories match. */
internal object SourceRangeAnchorVerifier {
    fun verifyCandidate(candidate: SelectionRangeV2Candidate, proof: SourceAnchorBatch): SourceRangeAnchor? {
        val anchor = SourceRangeAnchor(candidate.resourceHref, candidate.segments.toList())
        if (!SourceRangeAnchors.isValid(anchor) || proof.failure != null ||
            proof.bodyTopology != candidate.bodyTopology ||
            proof.rangeNodeTexts["selection"]?.size != anchor.segments.size) return null
        val texts = try { topologyTextNodes(candidate.bodyTopology) } catch (_: Exception) { return null }
        val actual = proveRange(anchor, texts) ?: return null
        if (actual != proof.rangeNodeTexts["selection"]) return null
        return anchor
    }

    fun verifyBody(body: Node, href: String, requests: List<SourceAnchorRequest>): SourceAnchorBatch {
        val topology = try { scanBody(body) } catch (limit: SourceBodyLimitException) {
            return SourceAnchorBatch(emptyMap(), SourceProofFailure.LIMIT)
        } catch (_: Exception) { return SourceAnchorBatch(emptyMap(), SourceProofFailure.UNAVAILABLE) }
        val positions = topology.textNodes.mapIndexed { index, pair -> pair.first to index }.toMap()
        val proven = mutableMapOf<String, List<String>>()
        for (request in requests) {
            val anchor = request.anchor as? SourceRangeAnchor ?: continue
            if (request.id.isBlank() || anchor.resourceHref != href || !SourceRangeAnchors.isValid(anchor) ||
                request.text.isBlank() || request.text.length > SourceRangeAnchors.MAX_SELECTED_UNITS ||
                !SourceRangeAnchors.validUtf16(request.text)) continue
            proveRange(anchor, topology.textNodes, positions)?.let { proven[request.id] = it }
        }
        return SourceAnchorBatch(emptyMap(), rangeNodeTexts = proven, bodyTopology = topology.entries)
    }

    private fun proveRange(anchor: SourceRangeAnchor, texts: List<Pair<String, String>>,
        positions: Map<String, Int> = texts.mapIndexed { index, pair -> pair.first to index }.toMap()): List<String>? {
        val indices = anchor.segments.map { positions[it.path] ?: return null }
        if (indices.zipWithNext().any { (first, second) -> second != first + 1 }) return null
        return anchor.segments.mapIndexed { index, segment ->
            val full = texts[indices[index]].second
            if (full.length > SourceRangeAnchors.MAX_NODE_UNITS || segment.end > full.length ||
                !validUtf16Boundary(full, segment.start) || !validUtf16Boundary(full, segment.end) ||
                sourceNodeSha256(full) != segment.nodeTextSha256 ||
                full.substring(segment.start, segment.end) != segment.selectedText ||
                (index > 0 && segment.start != 0) ||
                (index < anchor.segments.lastIndex && segment.end != full.length)) return null
            full
        }
    }

    /** Recover path bindings from the exact proved topology, not from another request's strings. */
    private fun topologyTextNodes(entries: List<List<Any>>): List<Pair<String, String>> {
        if (entries.isEmpty() || entries.size > SourceTextAnchorVerifier.MAX_XML_NODES) throw IllegalArgumentException("Topology")
        data class Pending(val path: String, val depth: Int)
        val pending = ArrayDeque<Pending>()
        pending.addLast(Pending("", 0))
        val texts = mutableListOf<Pair<String, String>>()
        for ((index, entry) in entries.withIndex()) {
            val next = pending.removeLast()
            if (next.depth > SourceRangeAnchors.MAX_PATH_DEPTH || next.path.length > SourceRangeAnchors.MAX_PATH_UNITS)
                throw IllegalArgumentException("Path")
            when (entry.firstOrNull()) {
                1 -> {
                    if (entry.size != 4 || entry[1] !is String || entry[2] !is String || entry[3] !is Int)
                        throw IllegalArgumentException("Element")
                    if (index == 0 && (entry[1] != XHTML_NAMESPACE || entry[2] != "body")) throw IllegalArgumentException("Body")
                    val count = entry[3] as Int
                    if (count !in 0..SourceTextAnchorVerifier.MAX_XML_NODES || pending.size.toLong() + count > SourceTextAnchorVerifier.MAX_XML_NODES)
                        throw IllegalArgumentException("Children")
                    for (child in count - 1 downTo 0) pending.addLast(Pending(
                        if (next.path.isEmpty()) "$child" else "${next.path}/$child", next.depth + 1))
                }
                3, 8 -> {
                    if (entry.size != 2 || entry[1] !is String || index == 0) throw IllegalArgumentException("Text")
                    if (entry[0] == 3) texts.add(next.path to (entry[1] as String))
                }
                else -> throw IllegalArgumentException("Kind")
            }
        }
        if (pending.isNotEmpty()) throw IllegalArgumentException("Incomplete topology")
        return texts
    }

    /** Child node indices include comments. No publisher or Readium nodes are silently omitted. */
    fun scanBody(body: Node): SourceBodyTopology {
        if (body.nodeType != Node.ELEMENT_NODE || body.namespaceURI != XHTML_NAMESPACE || body.localName != "body")
            throw IllegalArgumentException("Unsupported body")
        data class Pending(val node: Node, val path: String, val depth: Int)
        val pending = ArrayDeque<Pending>()
        pending.addLast(Pending(body, "", 0))
        val entries = mutableListOf<List<Any>>()
        val textNodes = mutableListOf<Pair<String, String>>()
        // The byte budget is checked during construction, before a large JSON string is built.
        var budget = 2L
        while (pending.isNotEmpty()) {
            val item = pending.removeLast()
            if (entries.size >= SourceTextAnchorVerifier.MAX_XML_NODES ||
                item.depth > SourceRangeAnchors.MAX_PATH_DEPTH || item.path.length > SourceRangeAnchors.MAX_PATH_UNITS)
                throw SourceBodyLimitException()
            val node = item.node
            val entry: List<Any> = when (node.nodeType) {
                Node.ELEMENT_NODE -> listOf(1, node.namespaceURI ?: "", node.localName ?: throw IllegalArgumentException("Name"), node.childNodes.length)
                Node.TEXT_NODE -> {
                    val text = node.nodeValue ?: throw IllegalArgumentException("Text")
                    if (!SourceRangeAnchors.validUtf16(text)) throw IllegalArgumentException("Unicode")
                    textNodes.add(item.path to text)
                    listOf(3, text)
                }
                Node.COMMENT_NODE -> {
                    val comment = node.nodeValue ?: throw IllegalArgumentException("Comment")
                    if (!SourceRangeAnchors.validUtf16(comment)) throw IllegalArgumentException("Unicode")
                    listOf(8, comment)
                }
                else -> throw IllegalArgumentException("Unsupported node")
            }
            budget += JSONArray(entry).toString().toByteArray(Charsets.UTF_8).size + 1
            if (budget > SourceTextAnchorVerifier.MAX_RESOURCE_BYTES) throw SourceBodyLimitException()
            entries.add(entry)
            for (index in node.childNodes.length - 1 downTo 0) {
                val path = if (item.path.isEmpty()) "$index" else "${item.path}/$index"
                pending.addLast(Pending(node.childNodes.item(index), path, item.depth + 1))
            }
        }
        return SourceBodyTopology(entries, textNodes)
    }

    private const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"
}

internal class SourceBodyLimitException : Exception("Original body proof limit")
internal fun sourceNodeSha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

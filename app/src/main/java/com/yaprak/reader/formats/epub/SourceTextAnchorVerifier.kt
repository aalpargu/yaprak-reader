package com.yaprak.reader.formats.epub

import com.yaprak.reader.reader.SelectionRangeCandidate
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.reader.EpubSourceAnchor
import com.yaprak.reader.reader.SourceRangeAnchor
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.mediatype.MediaType
import org.w3c.dom.Node

internal data class SourceAnchorRequest(val id: String, val anchor: EpubSourceAnchor, val text: String)
/** Full original node texts are transient proof payloads, never durable models or logs. */
internal data class SourceAnchorBatch(
    val nodeTexts: Map<String, String>,
    val failure: SourceProofFailure? = null,
    val rangeNodeTexts: Map<String, List<String>> = emptyMap(),
    val bodyTopology: List<List<Any>>? = null,
)
internal enum class SourceProofFailure { LIMIT, UNAVAILABLE }

/** One complete, bounded original-XHTML scan. A live DOM must independently prove uniqueness. */
internal object SourceTextAnchorVerifier {
    internal const val MAX_RESOURCE_BYTES = 2 * 1024 * 1024
    internal const val MAX_XML_NODES = 200_000
    private const val MAX_REQUESTS = 1000
    private const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"
    private val shaPattern = Regex("[0-9a-f]{64}")

    suspend fun verify(publication: Publication, candidate: SelectionRangeCandidate, selectedText: String): SourceTextAnchor? {
        val anchor = SourceTextAnchor(candidate.resourceHref, candidate.start, candidate.end, candidate.nodeTextSha256)
        return anchor.takeIf { verifySaved(publication, it, selectedText) }
    }

    suspend fun verifySaved(publication: Publication, anchor: EpubSourceAnchor, text: String): Boolean {
        val proof = verifyBatch(publication, anchor.resourceHref, listOf(SourceAnchorRequest("selection", anchor, text)))
        return when (anchor) {
            is SourceTextAnchor -> proof.nodeTexts.containsKey("selection")
            is SourceRangeAnchor -> proof.rangeNodeTexts.containsKey("selection")
        }
    }

    suspend fun verifyRange(publication: Publication, candidate: SelectionRangeV2Candidate): SourceRangeAnchor? {
        val anchor = SourceRangeAnchor(candidate.resourceHref, candidate.segments)
        val proof = verifyBatch(publication, anchor.resourceHref, listOf(SourceAnchorRequest("selection", anchor, candidate.preview)))
        return SourceRangeAnchorVerifier.verifyCandidate(candidate, proof)
    }

    suspend fun verifyBatch(publication: Publication, href: String, requests: List<SourceAnchorRequest>): SourceAnchorBatch =
        withContext(Dispatchers.IO) {
            if (requests.size > MAX_REQUESTS) return@withContext failed(SourceProofFailure.LIMIT)
            val link = publication.readingOrder.filter {
                it.mediaType == MediaType.XHTML && it.url().removeFragment().toString() == href
            }.singleOrNull() ?: return@withContext failed(SourceProofFailure.UNAVAILABLE)
            val resource = publication.get(link) ?: return@withContext failed(SourceProofFailure.UNAVAILABLE)
            try {
                val bytes = resource.read(0L..MAX_RESOURCE_BYTES.toLong()).getOrNull()
                    ?: return@withContext failed(SourceProofFailure.UNAVAILABLE)
                val context = currentCoroutineContext()
                context.ensureActive()
                verifyXhtmlBatch(bytes, href, requests) { context.ensureActive() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed(SourceProofFailure.UNAVAILABLE) }
            finally { resource.close() }
        }

    fun verifyXhtml(bytes: ByteArray, candidate: SelectionRangeCandidate, selectedText: String): SourceTextAnchor? {
        val anchor = SourceTextAnchor(candidate.resourceHref, candidate.start, candidate.end, candidate.nodeTextSha256)
        return anchor.takeIf { verifyXhtmlBatch(bytes, anchor.resourceHref,
            listOf(SourceAnchorRequest("selection", anchor, selectedText))).nodeTexts.containsKey("selection") }
    }

    fun verifyXhtmlBatch(bytes: ByteArray, href: String, requests: List<SourceAnchorRequest>,
        checkActive: () -> Unit = {}): SourceAnchorBatch {
        checkActive()
        if (bytes.size > MAX_RESOURCE_BYTES || requests.size > MAX_REQUESTS) return failed(SourceProofFailure.LIMIT)
        if (bytes.isEmpty() || requests.map { it.id }.toSet().size != requests.size) return failed(SourceProofFailure.UNAVAILABLE)
        val valid = requests.filter { request ->
            val anchor = request.anchor as? SourceTextAnchor ?: return@filter false
            request.id.isNotBlank() && anchor.resourceHref == href && request.text.isNotBlank() &&
                request.text.length <= 4096 && shaPattern.matches(anchor.nodeTextSha256) &&
                anchor.start >= 0 && anchor.end > anchor.start && anchor.end - anchor.start == request.text.length
        }.groupBy { (it.anchor as SourceTextAnchor).nodeTextSha256 }
        return try {
            val document = BoundedSourceXhtmlDocument.parse(bytes, checkActive)
            // Android's getElementsByTagNameNS can recurse. Discovery also scans every original node.
            var body: Node? = null
            val discovery = ArrayDeque<Node>().apply { addLast(document) }
            while (discovery.isNotEmpty()) {
                checkActive()
                val node = discovery.removeLast()
                if (node.nodeType == Node.ELEMENT_NODE && node.namespaceURI == XHTML_NAMESPACE && node.localName == "body") {
                    if (body != null) return failed(SourceProofFailure.UNAVAILABLE)
                    body = node
                }
                var child = node.lastChild
                while (child != null) { discovery.addLast(child); child = child.previousSibling }
            }
            val originalBody = body ?: return failed(SourceProofFailure.UNAVAILABLE)
            val counts = mutableMapOf<String, Int>()
            val texts = mutableMapOf<String, String>()
            var visited = 0
            val pending = ArrayDeque<Node>()
            pending.addLast(originalBody)
            // Complete traversal is required even after a match: later duplicates and limits matter.
            while (pending.isNotEmpty()) {
                checkActive()
                val node = pending.removeLast()
                if (++visited > MAX_XML_NODES) return failed(SourceProofFailure.LIMIT)
                if (node.nodeType == Node.TEXT_NODE) {
                    val value = node.nodeValue
                    if (value != null && value.length <= 4096) {
                        val hash = sha256(value)
                        if (hash in valid) {
                            counts[hash] = (counts[hash] ?: 0) + 1
                            texts[hash] = value
                        }
                    }
                }
                var child = node.lastChild
                while (child != null) { pending.addLast(child); child = child.previousSibling }
            }
            val proven = mutableMapOf<String, String>()
            for ((hash, grouped) in valid) {
                val text = texts[hash] ?: continue
                if (counts[hash] != 1) continue
                for (request in grouped) {
                    val anchor = request.anchor as SourceTextAnchor
                    if (anchor.end <= text.length && validUtf16Boundary(text, anchor.start) &&
                        validUtf16Boundary(text, anchor.end) && text.substring(anchor.start, anchor.end) == request.text)
                        proven[request.id] = text
                }
            }
            if (requests.none { it.anchor is SourceRangeAnchor }) SourceAnchorBatch(proven)
            else {
                val rangeProof = SourceRangeAnchorVerifier.verifyBody(originalBody, href, requests)
                checkActive()
                // A v2 topology limit/mismatch never removes independent, proven legacy records.
                SourceAnchorBatch(proven, rangeProof.failure.takeIf { proven.isEmpty() }, rangeProof.rangeNodeTexts, rangeProof.bodyTopology)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SourceBodyLimitException) { failed(SourceProofFailure.LIMIT) }
        catch (_: Exception) { failed(SourceProofFailure.UNAVAILABLE) }
    }

    private fun failed(reason: SourceProofFailure) = SourceAnchorBatch(emptyMap(), reason)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

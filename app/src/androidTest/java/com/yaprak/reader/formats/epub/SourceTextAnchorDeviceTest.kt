package com.yaprak.reader.formats.epub

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeSegment
import kotlinx.coroutines.CancellationException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android source proof; failure diagnostics contain platform configuration only. */
@RunWith(AndroidJUnit4::class)
class SourceTextAnchorDeviceTest {
    private val namespace = "http://www.w3.org/1999/xhtml"
    private val href = "EPUB/text.xhtml"
    private fun xhtml(body: String) = "<html xmlns='$namespace'><head/><body>$body</body></html>"

    @Test fun actualPlatformProvesEveryUniqueOriginalParagraph() {
        val href = "EPUB/text.xhtml"
        val paragraphs = (1..24).map { "Paragraph $it has its own unique original source text." }
        val xhtml = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head/><body>" +
            paragraphs.joinToString("") { "<p>$it</p>" } + "</body></html>"
        val requests = paragraphs.mapIndexed { index, text ->
            SourceAnchorRequest("paragraph-$index", SourceTextAnchor(href, 0, text.length,
                sourceNodeSha256(text)), text)
        }

        val proof = SourceTextAnchorVerifier.verifyXhtmlBatch(xhtml.toByteArray(Charsets.UTF_8), href, requests)
        if (proof.failure != null || proof.nodeTexts.size != requests.size ||
            requests.any { proof.nodeTexts[it.id] != it.text }) {
            // Do not include source text, parsed XML or exception messages in the failure.
            fail("Original source proof failed: sdk=${Build.VERSION.SDK_INT}, failure=${proof.failure}, " +
                "proven=${proof.nodeTexts.size}/${requests.size}; ${legacyFeatureDiagnostics()}")
        }
    }

    @Test fun platformRangeProofPreservesCommentsNamespacesAndDecodedEntityOffsets() {
        val first = "A&🌿"
        val segments = listOf(
            SourceRangeSegment("0/1", 0, first.length, sourceNodeSha256(first), first),
            SourceRangeSegment("0/2/0", 0, 1, sourceNodeSha256("B"), "B"),
        )
        val anchor = SourceRangeAnchor(href, segments)
        val expectedTopology = listOf(
            listOf(1, namespace, "body", 1), listOf(1, namespace, "p", 3),
            listOf(8, "original"), listOf(3, first),
            listOf(1, namespace, "em", 1), listOf(3, "B"),
        )
        for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16)) {
            val proof = SourceTextAnchorVerifier.verifyXhtmlBatch(
                xhtml("<p><!--original-->A&amp;&#x1F33F;<em>B</em></p>").toByteArray(encoding), href,
                listOf(SourceAnchorRequest("selection", anchor, first + "B")))
            assertNull(proof.failure)
            assertEquals(listOf(first, "B"), proof.rangeNodeTexts["selection"])
            assertEquals(expectedTopology, proof.bodyTopology)
            assertEquals(anchor, SourceRangeAnchorVerifier.verifyCandidate(
                SelectionRangeV2Candidate(href, first + "B", "fixture-token", segments, expectedTopology), proof))
        }
    }

    @Test fun platformRejectsWholeMalformedOrEntityBearingSourceWithoutPartialProof() {
        val request = SourceAnchorRequest("one", SourceTextAnchor(href, 0, 8, sourceNodeSha256("Original")), "Original")
        val invalid = listOf(
            "<!DOCTYPE html>" + xhtml("<p>Original</p>"),
            "<!DOCTYPE html [<!ENTITY injected SYSTEM 'file:///unreadable-fixture'>]>" + xhtml("<p>&injected;</p>"),
            "<!DOCTYPE html [<!ENTITY injected 'Original'>]>" + xhtml("<p>&injected;</p>"),
            xhtml("<p>Original</p><p>&undeclared;</p>"),
            xhtml("<p>Original</p><broken>"),
        ).flatMap { xml -> listOf(xml.toByteArray(Charsets.UTF_8), xml.toByteArray(Charsets.UTF_16)) } +
            listOf(byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0xD8.toByte()))
        for (bytes in invalid) {
            val proof = SourceTextAnchorVerifier.verifyXhtmlBatch(bytes, href, listOf(request))
            assertEquals(SourceProofFailure.UNAVAILABLE, proof.failure)
            assertTrue(proof.nodeTexts.isEmpty())
            assertTrue(proof.rangeNodeTexts.isEmpty())
            assertNull(proof.bodyTopology)
        }
    }

    @Test fun platformSupportsDeepLegacySourceAndDoesNotSwallowCallbackCancellation() {
        val request = SourceAnchorRequest("one", SourceTextAnchor(href, 0, 8, sourceNodeSha256("Original")), "Original")
        val nested = xhtml("<span>".repeat(2_000) + "Original" + "</span>".repeat(2_000)).toByteArray()
        val proof = SourceTextAnchorVerifier.verifyXhtmlBatch(nested, href, listOf(request))
        assertNull(proof.failure)
        assertEquals(mapOf("one" to "Original"), proof.nodeTexts)
        val excess = xhtml("<span>".repeat(BoundedSourceXhtmlDocument.MAX_DEPTH) + "Original" +
            "</span>".repeat(BoundedSourceXhtmlDocument.MAX_DEPTH)).toByteArray()
        assertEquals(SourceProofFailure.LIMIT,
            SourceTextAnchorVerifier.verifyXhtmlBatch(excess, href, listOf(request)).failure)

        val bytes = xhtml("<p>Original</p>").toByteArray()
        var preflightChecks = 0
        BoundedXhtmlTextParser.preflight(bytes) { preflightChecks++ }
        var checks = 0
        val cancelled = CancellationException("Fixture cancellation")
        val failure = runCatching {
            SourceTextAnchorVerifier.verifyXhtmlBatch(bytes, href, listOf(request)) {
                if (++checks == preflightChecks + 8) throw cancelled
            }
        }.exceptionOrNull()
        assertSame(cancelled, failure)
    }

    /** Probe independently so a rejected first flag does not hide the remaining incompatibilities. */
    private fun legacyFeatureDiagnostics(): String {
        val features = listOf(
            XMLConstants.FEATURE_SECURE_PROCESSING to true,
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )
        val factory = DocumentBuilderFactory.newInstance()
        val outcomes = features.joinToString { (feature, enabled) ->
            val failure = runCatching { factory.setFeature(feature, enabled) }.exceptionOrNull()
            "$feature=${failure?.javaClass?.simpleName ?: "supported"}"
        }
        return "factory=${factory.javaClass.name}, features=[$outcomes]"
    }
}

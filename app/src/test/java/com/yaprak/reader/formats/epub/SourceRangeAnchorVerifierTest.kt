package com.yaprak.reader.formats.epub

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeAnchors
import com.yaprak.reader.reader.SourceRangeSegment
import com.yaprak.reader.reader.SourceTextAnchor
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SourceRangeAnchorVerifierTest {
    @get:Rule val temp = TemporaryFolder()
    private val href = "EPUB/chapter.xhtml"

    @Test fun `inline emphasis and strong prove every selected original text slice`() {
        val body = "<p>Bir <em>güzel</em> <strong>gün</strong> başladı.</p>"
        val candidate = candidate(body, start = 1, end = 4, preview = "ir güzel gün baş")
        val proof = proof(body, candidate)
        assertEquals(listOf("Bir ", "güzel", " ", "gün", " başladı."), proof.rangeNodeTexts["selection"])
        assertEquals(SourceRangeAnchor(href, candidate.segments), SourceRangeAnchorVerifier.verifyCandidate(candidate, proof))
        assertEquals(listOf("0/0", "0/1/0", "0/2", "0/3/0", "0/4"), candidate.segments.map { it.path })
    }

    @Test fun `native paragraph preview separators do not become fabricated source text`() {
        val body = "<p>Birinci</p><p>İkinci</p>"
        val candidate = candidate(body, preview = "Birinci\n\nİkinci")
        assertEquals("Birinciİkinci", candidate.segments.joinToString("") { it.selectedText })
        assertEquals(SourceRangeAnchor(href, candidate.segments), SourceRangeAnchorVerifier.verifyCandidate(candidate, proof(body, candidate)))
        assertFalse(candidate.segments.any { it.selectedText.contains('\n') })
    }

    @Test fun `identical nodes can be addressed only after complete exact topology matches`() {
        val body = "<p>Aynı</p><p>Aynı</p><p>Aynı</p>"
        val candidate = candidate(body, first = 2, last = 2)
        assertEquals("2/0", candidate.segments.single().path)
        val proof = proof(body, candidate)
        assertEquals(listOf("Aynı"), proof.rangeNodeTexts["selection"])
        assertEquals(SourceRangeAnchor(href, candidate.segments), SourceRangeAnchorVerifier.verifyCandidate(candidate, proof))
        val legacy = SourceTextAnchor(href, 0, 4, sourceNodeSha256("Aynı"))
        assertTrue(SourceTextAnchorVerifier.verifyXhtmlBatch(xhtml(body), href,
            listOf(SourceAnchorRequest("legacy", legacy, "Aynı"))).nodeTexts.isEmpty())
    }

    @Test fun `hidden clones wrapped nodes split nodes and virtual page additions fail live correspondence`() {
        val original = "<p>Aynı pasaj</p>"
        val source = candidate(original)
        val proof = proof(original, source)
        for (live in listOf("<p>Aynı pasaj</p><p hidden='hidden'>Aynı pasaj</p>",
            "<div><p>Aynı pasaj</p></div>", "<p>Aynı <em>pasaj</em></p>",
            "<p>Aynı pasaj</p><div id='readium-virtual-page'>\u200b</div>")) {
            assertNull(SourceRangeAnchorVerifier.verifyCandidate(source.copy(bodyTopology = topology(live).entries), proof))
        }
    }

    @Test fun `element namespace names ordered child shape and comments participate in identity`() {
        val original = "<p><!--source-->Bir pasaj</p>"
        val source = candidate(original)
        assertEquals("0/1", source.segments.single().path)
        val proof = proof(original, source)
        for (live in listOf("<p><!--other-->Bir pasaj</p>", "<span><!--source-->Bir pasaj</span>",
            "<p xmlns='urn:other'><!--source-->Bir pasaj</p>", "<p>Bir pasaj<!--source--></p>"))
            assertNull(SourceRangeAnchorVerifier.verifyCandidate(source.copy(bodyTopology = topology(live).entries), proof))
    }

    @Test fun `any segment tampering rejects the whole annotation without partial group proof`() {
        val body = "<p>Bir <em>güzel</em> gün</p>"
        val source = candidate(body)
        val middle = source.segments[1]
        val variants = listOf(middle.copy(selectedText = "başka"), middle.copy(nodeTextSha256 = "0".repeat(64)),
            middle.copy(path = "99/0"), middle.copy(start = 1, selectedText = "üzel"))
        for (wrong in variants) {
            val candidate = source.copy(segments = listOf(source.segments[0], wrong, source.segments[2]))
            assertTrue(proof(body, candidate).rangeNodeTexts.isEmpty())
        }
    }

    @Test fun `a proof for another candidate cannot lend its strings to unproved paths hashes or slices`() {
        val body = "<p>Bir</p><p>Başka</p><p>Bir</p>"
        val source = candidate(body, first = 0, last = 0)
        val proof = proof(body, source)
        val segment = source.segments.single()
        for (wrong in listOf(segment.copy(path = "99/0"), segment.copy(path = "1/0"),
            segment.copy(nodeTextSha256 = "0".repeat(64)), segment.copy(start = 1, selectedText = "Bi")))
            assertNull(SourceRangeAnchorVerifier.verifyCandidate(source.copy(segments = listOf(wrong)), proof))
        // A repeated exact node at an actually proved path is a valid source match, not ambiguity.
        assertEquals(SourceRangeAnchor(href, listOf(segment.copy(path = "2/0"))),
            SourceRangeAnchorVerifier.verifyCandidate(source.copy(segments = listOf(segment.copy(path = "2/0"))), proof))
    }

    @Test fun `omitted reordered duplicate overlapping or incomplete middle slices never prove`() {
        val body = "<p>Bir <em>güzel</em> gün</p>"
        val source = candidate(body)
        val variants = listOf(listOf(source.segments.first(), source.segments.last()), source.segments.reversed(),
            listOf(source.segments.first(), source.segments.first()),
            source.segments.toMutableList().apply { this[1] = this[1].copy(end = 4, selectedText = "güze") })
        for (segments in variants) assertTrue(proof(body, source.copy(segments = segments)).rangeNodeTexts.isEmpty())
    }

    @Test fun `UTF16 entities NBSP combining marks and complete emoji offsets preserve exact slices`() {
        val body = "<p>A&amp;🌿<em>&#160;e&#769;</em>B</p>"
        val source = candidate(body, start = 2, end = 1, preview = "🌿\u00a0e\u0301B")
        assertEquals(listOf("🌿", "\u00a0e\u0301", "B"), source.segments.map { it.selectedText })
        assertEquals(SourceRangeAnchor(href, source.segments), SourceRangeAnchorVerifier.verifyCandidate(source, proof(body, source)))
        val broken = source.copy(segments = source.segments.toMutableList().apply {
            this[0] = this[0].copy(end = 3, selectedText = "🌿".substring(0, 1))
        })
        assertTrue(proof(body, broken).rangeNodeTexts.isEmpty())
    }

    @Test fun `DTD unsupported source nodes bytes depth and node limits cannot publish early proof`() {
        val source = candidate("<p>Bir</p>")
        val request = SourceAnchorRequest("selection", SourceRangeAnchor(href, source.segments), source.preview)
        assertEquals(SourceProofFailure.UNAVAILABLE, SourceTextAnchorVerifier.verifyXhtmlBatch(
            "<!DOCTYPE html>".toByteArray() + xhtml("<p>Bir</p>"), href, listOf(request)).failure)
        assertEquals(SourceProofFailure.UNAVAILABLE, SourceTextAnchorVerifier.verifyXhtmlBatch(
            xhtml("<p><![CDATA[Bir]]></p>"), href, listOf(request)).failure)
        assertEquals(SourceProofFailure.LIMIT, SourceTextAnchorVerifier.verifyXhtmlBatch(
            ByteArray(SourceTextAnchorVerifier.MAX_RESOURCE_BYTES + 1), href, listOf(request)).failure)
        assertEquals(SourceProofFailure.LIMIT, SourceTextAnchorVerifier.verifyXhtmlBatch(
            xhtml("<p>Bir</p>" + "<i/>".repeat(200_001)), href, listOf(request)).failure)
        assertEquals(SourceProofFailure.LIMIT, SourceTextAnchorVerifier.verifyXhtmlBatch(
            xhtml("<p>Bir</p>" + "<span>".repeat(65) + "x" + "</span>".repeat(65)), href, listOf(request)).failure)
    }

    @Test fun `legacy proof remains available when new range topology alone is unsupported`() {
        val body = "<p>Bir</p><p><![CDATA[unrelated]]></p>"
        val source = candidate("<p>Bir</p>")
        val legacy = SourceTextAnchor(href, 0, 3, sourceNodeSha256("Bir"))
        val proof = SourceTextAnchorVerifier.verifyXhtmlBatch(xhtml(body), href, listOf(
            SourceAnchorRequest("legacy", legacy, "Bir"),
            SourceAnchorRequest("selection", SourceRangeAnchor(href, source.segments), source.preview)))
        assertEquals(mapOf("legacy" to "Bir"), proof.nodeTexts)
        assertTrue(proof.rangeNodeTexts.isEmpty())
        assertNull(proof.failure)
    }

    @Test fun `range model snapshots caller lists and copy never exposes mutation aliases`() {
        val segment = candidate("<p>Bir</p>").segments.single()
        val mutable = mutableListOf(segment)
        val anchor = SourceRangeAnchor(href, mutable)
        mutable.clear()
        assertEquals(listOf(segment), anchor.segments)
        val copySource = mutableListOf(segment.copy(path = "1/0"))
        val copied = anchor.copy(segments = copySource)
        copySource.clear()
        assertEquals("1/0", copied.segments.single().path)
        var rejected = false
        try { (anchor.segments as MutableList<SourceRangeSegment>).clear() } catch (_: UnsupportedOperationException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun `canonical numeric paths scalar UTF16 and complete selection budgets are structural limits`() {
        val source = candidate("<p>Bir</p>")
        val anchor = SourceRangeAnchor(href, source.segments)
        for (path in listOf("", "01/0", "0/-1", "0//0", "2147483648", "200001", "0/".repeat(64) + "0"))
            assertFalse(SourceRangeAnchors.isValid(anchor.copy(segments = listOf(source.segments.single().copy(path = path)))))
        assertFalse(SourceRangeAnchors.isValid(anchor.copy(segments = emptyList())))
        assertFalse(SourceRangeAnchors.validUtf16("\ud83c"))
        assertFalse(SourceRangeAnchors.validUtf16("\udf3f"))
        val long = SourceRangeSegment("0/0", 0, 4096, sourceNodeSha256("x".repeat(4096)), "x".repeat(4096))
        assertTrue(SourceRangeAnchors.isValid(SourceRangeAnchor(href, listOf(long))))
        assertFalse(SourceRangeAnchors.isValid(SourceRangeAnchor(href, listOf(long, long.copy(path = "1/0", end = 1, selectedText = "x")))))
    }

    @Test fun `actual Readium publication source proof is restricted to current reading order XHTML`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = TestBooks.epub(temp.newFile("range.epub"))
        val bytes = ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry(href)).use { it.readBytes() } }
        val body = document(bytes).getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "body").item(0)
        val topology = SourceRangeAnchorVerifier.scanBody(body)
        val selected = topology.textNodes.single { it.second.contains("Güneş doğdu.") }
        val full = selected.second
        val offset = full.indexOf("Güneş")
        val candidate = SelectionRangeV2Candidate(href, "Güneş", "11111111-1111-4111-8111-111111111111:0:1",
            listOf(SourceRangeSegment(selected.first, offset, offset + 5, sourceNodeSha256(full), "Güneş")), topology.entries)
        EpubPublicationSource(context.contentResolver).open(file).use { opened ->
            assertEquals(SourceRangeAnchor(href, candidate.segments), SourceTextAnchorVerifier.verifyRange(opened.publication, candidate))
            assertTrue(SourceTextAnchorVerifier.verifySaved(opened.publication, SourceRangeAnchor(href, candidate.segments), "Güneş"))
            assertNull(SourceTextAnchorVerifier.verifyRange(opened.publication, candidate.copy(resourceHref = "EPUB/nav.xhtml")))
            assertNull(SourceTextAnchorVerifier.verifyRange(opened.publication, candidate.copy(resourceHref = "../chapter.xhtml")))
        }
    }

    private fun proof(body: String, candidate: SelectionRangeV2Candidate): SourceAnchorBatch =
        SourceTextAnchorVerifier.verifyXhtmlBatch(xhtml(body), href, listOf(
            SourceAnchorRequest("selection", SourceRangeAnchor(candidate.resourceHref, candidate.segments), candidate.preview)))

    private fun candidate(body: String, first: Int = 0, last: Int = topology(body).textNodes.lastIndex,
        start: Int = 0, end: Int? = null, preview: String? = null): SelectionRangeV2Candidate {
        val topology = topology(body)
        val nodes = topology.textNodes.subList(first, last + 1)
        val segments = nodes.mapIndexed { index, (path, text) ->
            val from = if (index == 0) start else 0
            val to = if (index == nodes.lastIndex) end ?: text.length else text.length
            SourceRangeSegment(path, from, to, sourceNodeSha256(text), text.substring(from, to))
        }
        return SelectionRangeV2Candidate(href, preview ?: segments.joinToString("") { it.selectedText },
            "11111111-1111-4111-8111-111111111111:0:1", segments, topology.entries)
    }

    private fun topology(body: String) = SourceRangeAnchorVerifier.scanBody(document(xhtml(body))
        .getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "body").item(0))
    private fun document(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    private fun xhtml(body: String) = "<html xmlns='http://www.w3.org/1999/xhtml'><head/><body>$body</body></html>".toByteArray()
}

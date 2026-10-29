package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.search.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AnchoredXhtmlChapterTest {
    private fun xml(body: String, head: String = "") =
        ("<html xmlns='http://www.w3.org/1999/xhtml'><head>" + head + "</head><body>" + body + "</body></html>").toByteArray()

    @Test fun anchorBoundariesUseTheIdenticalOriginalBodyTextAndBlockSeparators() {
        val bytes = xml("<p>A&amp;🌿</p><h2 id='later'>İkinci</h2><p>B</p>")
        val proof = BoundedXhtmlTextParser.parseAnchored(bytes, setOf("later", "missing"))
        assertEquals(BoundedXhtmlTextParser.parse(bytes), proof.text)
        assertEquals("A&🌿\nİkinci\nB\n", proof.text)
        assertEquals(proof.text.indexOf("İkinci"), proof.anchors["later"])
        assertFalse(proof.anchors.containsKey("missing"))
        try { (proof.anchors as MutableMap).clear(); fail("Mutable anchor proof") } catch (_: UnsupportedOperationException) {}
    }

    @Test fun duplicateHeadHiddenScriptForeignAndRepeatedIdsNeverAcquireAVisibleBoundary() {
        val body = "<h1 id='dupe'>One</h1><h2 id='dupe'>Two</h2><p id='head'>Body</p>" +
            "<div hidden='hidden'><p id='hidden'>SECRET</p></div><script id='script'>SCRIPT</script>" +
            "<svg xmlns='http://www.w3.org/2000/svg'><text id='foreign'>FOREIGN</text></svg>"
        val ids = setOf("dupe", "head", "hidden", "script", "foreign")
        val proof = BoundedXhtmlTextParser.parseAnchored(xml(body, "<title id='head'>HEAD</title>"), ids)
        assertEquals(ids, proof.anchors.keys)
        assertTrue(proof.anchors.values.all { it == null })
        assertEquals("One\nTwo\nBody\n", proof.text)
    }

    @Test fun inlineAndXmlIdsBindWithoutInsertingExtraWhitespace() {
        val proof = BoundedXhtmlTextParser.parseAnchored(
            xml("<p>first<span id='a+b'>middle</span><span xml:id='Ω'>last</span></p>"), setOf("a+b", "Ω"))
        assertEquals("firstmiddlelast\n", proof.text)
        assertEquals(5, proof.anchors["a+b"]); assertEquals(11, proof.anchors["Ω"])
    }

    @Test fun anchorAdmissionKeepsDtdDepthAndCancellationGuards() {
        val expected = CancellationException("chapter caller cancelled")
        val result = runCatching { BoundedXhtmlTextParser.parseAnchored(xml("<p id='one'>text</p>"), setOf("one")) {
            throw expected
        } }.exceptionOrNull()
        assertSame(expected, result)
        val dtd = runCatching { BoundedXhtmlTextParser.parseAnchored("<!DOCTYPE html>".toByteArray() +
            xml("<p id='one'>text</p>"), setOf("one")) }.exceptionOrNull()
        assertEquals(TextExtractionFailure.UNSUPPORTED, (dtd as TextExtractionException).reason)
        val tooMany = runCatching { BoundedXhtmlTextParser.parseAnchored(xml(""), (0..5000).map { it.toString() }.toSet()) }
            .exceptionOrNull()
        assertEquals(TextExtractionFailure.LIMIT, (tooMany as TextExtractionException).reason)
    }
}

package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.search.MAX_INDEX_RESOURCE_BYTES
import com.yaprak.reader.domain.search.TextExtractionException
import com.yaprak.reader.domain.search.TextExtractionFailure
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BoundedXhtmlTextParserTest {
    @Test fun `inline markup entities CDATA and original UTF16 spaces remain literal`() {
        val body = "<p>  İstanbul &amp; ı 🌿 dependency <em>injection</em>\t</p><pre><![CDATA[<not markup>\n 漢字]]></pre>"
        assertEquals("  İstanbul & ı 🌿 dependency injection\t\n<not markup>\n 漢字\n", parse(body))
    }

    @Test fun `block and br boundaries cannot glue unrelated words or create inline separators`() {
        assertEquals("one\ntwo\nthree\ninline word\n", parse("<div><p>one</p><p>two<br/>three</p><p>inline <span>word</span></p></div>"))
        assertEquals("A\n\nB\n", parse("<p>A\n\nB</p>"))
    }

    @Test fun `head comments scripts styles templates hidden and foreign namespaces are excluded`() {
        val bytes = ("<html xmlns='http://www.w3.org/1999/xhtml'><head><title>HEAD_SECRET</title>" +
            "<style>HEAD_STYLE</style></head><body><p>kept</p><!-- COMMENT_SECRET -->" +
            "<script>BODY_SECRET</script><style>BODY_STYLE</style><template><p>TEMPLATE_SECRET</p></template>" +
            "<noscript>NO_SCRIPT</noscript><div hidden='hidden'>HIDDEN_SECRET</div>" +
            "<svg xmlns='http://www.w3.org/2000/svg'><text>SVG_SECRET</text>" +
            "<p xmlns='http://www.w3.org/1999/xhtml'>FOREIGN_CHILD</p></svg><p>tail</p></body></html>").toByteArray()
        assertEquals("kept\ntail\n", BoundedXhtmlTextParser.parse(bytes))
    }

    @Test fun `an empty supported body is distinct from invalid structure`() {
        assertEquals("", parse("")); assertEquals("  \n ", parse("  \n "))
        for (source in listOf("<html/>", "<html><body>wrong namespace</body></html>",
            "<html xmlns='http://www.w3.org/1999/xhtml'><body/><body/></html>",
            "<html xmlns='http://www.w3.org/1999/xhtml'><head><body/></head></html>",
            "<body xmlns='http://www.w3.org/1999/xhtml'>wrong root</body>")) {
            assertFailure(TextExtractionFailure.INVALID, source.toByteArray())
        }
    }

    @Test fun `external internal and ordinary XHTML doctype are rejected before any result`() {
        for (doctype in listOf("<!DOCTYPE html>",
            "<!DOCTYPE html SYSTEM 'https://example.invalid/must-not-read'>",
            "<!DOCTYPE html [<!ENTITY secret SYSTEM 'file:///must-not-read'>]>",
            "<!DOCTYPE html [<!ENTITY a 'expanded'><!ENTITY b '&a;&a;&a;'>]>")) {
            val failure = runCatching { BoundedXhtmlTextParser.parse((doctype + xhtml("<p>&secret;</p>")).toByteArray()) }
                .exceptionOrNull()
            assertTrue(failure is TextExtractionException)
            assertTrue((failure as TextExtractionException).reason in
                setOf(TextExtractionFailure.INVALID, TextExtractionFailure.UNSUPPORTED))
        }
    }

    @Test fun `malformed syntax encodings undefined entities and illegal XML characters fail closed`() {
        for (source in listOf(xhtml("<p>bad</em>"), xhtml("<p>&undefined;</p>"),
            xhtml("<p>bad\u0000</p>"), xhtml("<p>&#xD800;</p>"), xhtml("<p>&#0;</p>"))) {
            assertFailure(TextExtractionFailure.INVALID, source.toByteArray())
        }
        assertFailure(TextExtractionFailure.INVALID, xhtml("<p>").toByteArray() +
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()) + "</p></body></html>".toByteArray())
        assertFailure(TextExtractionFailure.INVALID, byteArrayOf())
    }

    @Test fun `declared UTF16 encoding is decoded by XML without reencoding the text`() {
        val source = "<?xml version='1.0' encoding='UTF-16'?>" + xhtml("<p>A&amp;🌿B</p>")
        assertEquals("A&🌿B\n", BoundedXhtmlTextParser.parse(source.toByteArray(Charsets.UTF_16)))
    }

    @Test fun `preflight distinguishes literal doctype text from a declaration in UTF8 and both UTF16 orders`() {
        val body = "<!-- <!DOCTYPE html> --><?fixture <!DOCTYPE ignored?><p><![CDATA[<!DOCTYPE literal>]]></p>"
        assertEquals("<!DOCTYPE literal>\n", parse(body))
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16, Charsets.UTF_16BE, Charsets.UTF_16LE)) {
            val declaration = "<?xml version='1.0' encoding='${charset.name()}'?>"
            assertEquals("<!DOCTYPE literal>\n", BoundedXhtmlTextParser.parse((declaration + xhtml(body)).toByteArray(charset)))
            assertFailure(TextExtractionFailure.UNSUPPORTED,
                (declaration + "<!DOCTYPE html [<!ENTITY a 'expansion'>]>" + xhtml("<p>&a;</p>")).toByteArray(charset))
        }
    }

    @Test fun `invalid UTF16 and unsupported declared encodings never reach native parsing`() {
        val source = "<?xml version='1.0' encoding='UTF-16'?>" + xhtml("<p>text</p>")
        assertFailure(TextExtractionFailure.INVALID, source.toByteArray(Charsets.UTF_16) + byteArrayOf(0))
        assertFailure(TextExtractionFailure.UNSUPPORTED,
            ("<?xml version='1.0' encoding='ISO-8859-1'?>" + xhtml("<p>text</p>")).toByteArray())
        assertFailure(TextExtractionFailure.UNSUPPORTED, byteArrayOf(0, 0, 0xfe.toByte(), 0xff.toByte(), 0, 0, 0, 60))
    }

    @Test fun `BOM is preserved for SAX while mismatching declarations are explicitly rejected`() {
        val utf8 = "<?xml version='1.0' encoding='UTF-8'?>" + xhtml("<p>İ🌿</p>")
        assertEquals("İ🌿\n", BoundedXhtmlTextParser.parse(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + utf8.toByteArray()))
        val bigEndian = "<?xml version='1.0' encoding='UTF-16BE'?>" + xhtml("<p>İ🌿</p>")
        val littleEndian = "<?xml version='1.0' encoding='UTF-16LE'?>" + xhtml("<p>İ🌿</p>")
        assertEquals("İ🌿\n", BoundedXhtmlTextParser.parse(byteArrayOf(0xfe.toByte(), 0xff.toByte()) + bigEndian.toByteArray(Charsets.UTF_16BE)))
        assertEquals("İ🌿\n", BoundedXhtmlTextParser.parse(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + littleEndian.toByteArray(Charsets.UTF_16LE)))
        assertFailure(TextExtractionFailure.UNSUPPORTED, byteArrayOf(0xfe.toByte(), 0xff.toByte()) + littleEndian.toByteArray(Charsets.UTF_16BE))
        assertFailure(TextExtractionFailure.UNSUPPORTED, byteArrayOf(0xff.toByte(), 0xfe.toByte()) + bigEndian.toByteArray(Charsets.UTF_16LE))
        assertFailure(TextExtractionFailure.UNSUPPORTED,
            ("<?xml version='1.0' encoding='UTF-16'?>" + xhtml("<p>text</p>")).toByteArray(Charsets.UTF_8))
    }

    @Test fun `size limit accepts the exact boundary and rejects one extra byte`() {
        val shell = xhtml("<p></p>")
        val text = "a".repeat(MAX_INDEX_RESOURCE_BYTES - shell.toByteArray().size)
        val exact = xhtml("<p>$text</p>").toByteArray()
        assertEquals(MAX_INDEX_RESOURCE_BYTES, exact.size)
        assertEquals(text + "\n", BoundedXhtmlTextParser.parse(exact))
        assertFailure(TextExtractionFailure.LIMIT, exact + byteArrayOf(32))
    }

    @Test fun `deep XML is bounded before building an unbounded object tree`() {
        val accepted = "<span>".repeat(BoundedXhtmlTextParser.MAX_XML_DEPTH - 2) + "deep" +
            "</span>".repeat(BoundedXhtmlTextParser.MAX_XML_DEPTH - 2)
        assertEquals("deep", parse(accepted))
        assertFailure(TextExtractionFailure.LIMIT, xhtml("<span>$accepted</span>").toByteArray())
    }

    @Test fun `event budget covers empty elements comments and excluded content too`() {
        assertFailure(TextExtractionFailure.LIMIT, xhtml("<i/>".repeat(100_000)).toByteArray())
        assertFailure(TextExtractionFailure.LIMIT, xhtml("<script>" + "<!--x-->".repeat(200_000) + "</script>").toByteArray())
    }

    @Test fun `caller cancellation escapes unchanged during parse instead of an invalid extraction result`() {
        val cancelled = CancellationException("Caller left extraction")
        var checks = 0
        val failure = runCatching { BoundedXhtmlTextParser.parse(xhtml("<p>ok</p>".repeat(100)).toByteArray()) {
            if (++checks == 20) throw cancelled
        } }.exceptionOrNull()
        assertSame(cancelled, failure)
    }

    @Test fun `caller cancellation inside SAX events survives callback exception wrapping`() {
        val cancelled = CancellationException("Caller left native parsing")
        var checks = 0
        // This document has seven '<' tokens: the initial/preflight checks total ten.
        // Check fifteen is inside the body SAX callbacks, after preflight has finished.
        val failure = runCatching { BoundedXhtmlTextParser.parse(xhtml("<p>text</p>").toByteArray()) {
            if (++checks == 15) throw cancelled
        } }.exceptionOrNull()
        assertEquals(15, checks)
        assertSame(cancelled, failure)
    }

    private fun parse(body: String) = BoundedXhtmlTextParser.parse(xhtml(body).toByteArray())
    private fun xhtml(body: String) = "<html xmlns='http://www.w3.org/1999/xhtml'><head/><body>$body</body></html>"
    private fun assertFailure(reason: TextExtractionFailure, bytes: ByteArray) {
        val failure = runCatching { BoundedXhtmlTextParser.parse(bytes) }.exceptionOrNull()
        assertTrue("Expected $reason, got $failure", failure is TextExtractionException)
        assertEquals(reason, (failure as TextExtractionException).reason)
    }
}

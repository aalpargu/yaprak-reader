package com.yaprak.reader.formats.epub

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaprak.reader.domain.search.TextExtractionException
import com.yaprak.reader.domain.search.TextExtractionFailure
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real platform Expat acceptance. Compiling this fixture does not execute native parsing. */
@RunWith(AndroidJUnit4::class)
class BoundedXhtmlTextParserDeviceTest {
    @Test fun actual_platform_parser_preserves_Unicode_entities_and_block_text() {
        val source = xhtml("<p>İstanbul &amp; 🌿 dependency <em>injection</em> 漢字</p>" +
            "<script>SECRET</script><p><![CDATA[<!DOCTYPE literal>]]></p>")
        assertEquals("İstanbul & 🌿 dependency injection 漢字\n<!DOCTYPE literal>\n",
            BoundedXhtmlTextParser.parse(source.toByteArray(Charsets.UTF_8)))
    }

    @Test fun actual_platform_parser_supports_UTF16_byte_orders_without_replacement() {
        for (charset in listOf(Charsets.UTF_16, Charsets.UTF_16BE, Charsets.UTF_16LE)) {
            val source = "<?xml version='1.0' encoding='${charset.name()}'?>" + xhtml("<p>A&amp;🌿漢字B</p>")
            assertEquals("A&🌿漢字B\n", BoundedXhtmlTextParser.parse(source.toByteArray(charset)))
        }
    }

    @Test fun actual_platform_path_rejects_internal_and_external_DTD_before_any_result() {
        for (doctype in listOf("<!DOCTYPE html>",
            "<!DOCTYPE html SYSTEM 'https://example.invalid/must-not-read'>",
            "<!DOCTYPE html [<!ENTITY secret SYSTEM 'file:///must-not-read'>]>",
            "<!DOCTYPE html [<!ENTITY a 'expanded'><!ENTITY b '&a;&a;&a;'>]>")) {
            for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16)) {
                val source = "<?xml version='1.0' encoding='${charset.name()}'?>" + doctype + xhtml("<p>&b;</p>")
                val failure = runCatching { BoundedXhtmlTextParser.parse(source.toByteArray(charset)) }.exceptionOrNull()
                assertEquals(TextExtractionFailure.UNSUPPORTED, (failure as? TextExtractionException)?.reason)
            }
        }
    }

    @Test fun actual_platform_callbacks_preserve_depth_and_event_limit_failures() {
        val depth = "<span>".repeat(BoundedXhtmlTextParser.MAX_XML_DEPTH - 1) + "deep" +
            "</span>".repeat(BoundedXhtmlTextParser.MAX_XML_DEPTH - 1)
        for (body in listOf(depth, "<i/>".repeat(100_000))) {
            val failure = runCatching { BoundedXhtmlTextParser.parse(xhtml(body).toByteArray()) }.exceptionOrNull()
            assertEquals(TextExtractionFailure.LIMIT, (failure as? TextExtractionException)?.reason)
        }
    }

    @Test fun actual_platform_callback_cancellation_escapes_unchanged() {
        val cancelled = CancellationException("Caller left native parsing")
        var checks = 0
        // Seven markup tokens make ten initial/preflight checks; fifteen reaches SAX events.
        val failure = runCatching { BoundedXhtmlTextParser.parse(xhtml("<p>text</p>").toByteArray()) {
            if (++checks == 15) throw cancelled
        } }.exceptionOrNull()
        assertEquals(15, checks)
        assertSame(cancelled, failure)
    }

    private fun xhtml(body: String) = "<html xmlns='http://www.w3.org/1999/xhtml'><head/><body>$body</body></html>"
}

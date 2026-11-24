package com.yaprak.reader.formats.epub

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Node

class BoundedSourceXhtmlDocumentTest {
    private val namespace = "http://www.w3.org/1999/xhtml"
    private fun xhtml(body: String) = "<html xmlns='$namespace'><head/><body>$body</body></html>"

    @Test fun decodedCharactersCommentsAndCdataKeepSeparateOriginalNodes() {
        for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16)) {
            val document = BoundedSourceXhtmlDocument.parse(
                xhtml("<p><!--original-->A&amp;&#x1F33F;<![CDATA[<literal>]]><?source hint?><em>B</em></p>")
                    .toByteArray(encoding))
            val paragraph = document.documentElement.lastChild.firstChild
            assertEquals(namespace, paragraph.namespaceURI)
            assertEquals(5, paragraph.childNodes.length)
            assertEquals(Node.COMMENT_NODE, paragraph.childNodes.item(0).nodeType)
            assertEquals("original", paragraph.childNodes.item(0).nodeValue)
            assertEquals(Node.TEXT_NODE, paragraph.childNodes.item(1).nodeType)
            assertEquals("A&🌿", paragraph.childNodes.item(1).nodeValue)
            assertEquals(Node.CDATA_SECTION_NODE, paragraph.childNodes.item(2).nodeType)
            assertEquals("<literal>", paragraph.childNodes.item(2).nodeValue)
            assertEquals(Node.PROCESSING_INSTRUCTION_NODE, paragraph.childNodes.item(3).nodeType)
            assertEquals("em", paragraph.childNodes.item(4).localName)
        }
    }

    @Test fun forbiddenDeclarationsEntitiesAndMalformedEncodingCannotReturnADocument() {
        val invalid = listOf(
            "<!DOCTYPE html>" + xhtml("<p>Original</p>"),
            "<!DOCTYPE html [<!ENTITY injected SYSTEM 'file:///unreadable-fixture'>]>" + xhtml("<p>&injected;</p>"),
            "<!DOCTYPE html [<!ENTITY injected 'changed'>]>" + xhtml("<p>&injected;</p>"),
            xhtml("<p>Original</p><p>&undeclared;</p>"),
            xhtml("<p>Original</p><broken>"),
        )
        for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16)) {
            for (xml in invalid) assertTrue(runCatching {
                BoundedSourceXhtmlDocument.parse(xml.toByteArray(encoding))
            }.isFailure)
        }
        assertTrue(runCatching { BoundedSourceXhtmlDocument.parse(byteArrayOf(0xC3.toByte(), 0x28)) }.isFailure)
    }

    @Test fun cancellationInsideSaxCallbacksEscapesWithItsOriginalIdentity() {
        val bytes = xhtml("<p>Original</p>").toByteArray()
        var preflightChecks = 0
        BoundedXhtmlTextParser.preflight(bytes) { preflightChecks++ }
        var checks = 0
        val cancelled = CancellationException("Fixture cancellation")
        val failure = runCatching {
            BoundedSourceXhtmlDocument.parse(bytes) {
                // Reach parser callbacks after the complete byte preflight and initial check.
                if (++checks == preflightChecks + 5) throw cancelled
            }
        }.exceptionOrNull()
        assertSame(cancelled, failure)
    }

    @Test fun nestedLegacyNodesRemainSupportedAndExcessDepthHasAnExplicitBudget() {
        val nested = "<span>".repeat(2_000) + "Original" + "</span>".repeat(2_000)
        var node: Node = BoundedSourceXhtmlDocument.parse(xhtml(nested).toByteArray()).documentElement.lastChild
        repeat(2_000) { node = node.firstChild }
        assertEquals("Original", node.firstChild.nodeValue)
        val excessive = "<span>".repeat(BoundedSourceXhtmlDocument.MAX_DEPTH) + "Original" +
            "</span>".repeat(BoundedSourceXhtmlDocument.MAX_DEPTH)
        assertTrue(runCatching { BoundedSourceXhtmlDocument.parse(xhtml(excessive).toByteArray()) }
            .exceptionOrNull() is SourceBodyLimitException)
    }
}

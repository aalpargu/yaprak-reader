package com.yaprak.reader.formats.docx

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DocxXmlTest {
    private suspend fun read(xml: String) = DocxXml.read(xml.toByteArray())
    private suspend fun refused(xml: String, reason: DocxFailure = DocxFailure.INVALID) {
        val failure = runCatching { read(xml) }.exceptionOrNull()
        assertTrue("XML should be refused", failure is DocxException)
        assertEquals(reason, (failure as DocxException).reason)
    }

    @Test fun namespacesAttributeIdentityEntitiesCdataAndOriginalTextArePreserved() = runBlocking {
        val root = read("<w:document xmlns:w='$DOCX_WORD_NS' xmlns:r='$DOCX_REL_NS' id='plain' r:id='linked'><w:t xml:space='preserve'>  İ &amp; &#x1F33F; <![CDATA[<literal>]]> 雪  </w:t></w:document>")
        assertEquals(DocxName(DOCX_WORD_NS, "document"), root.name)
        assertEquals("plain", root.attribute("id")); assertEquals("linked", root.attribute("id", DOCX_REL_NS))
        assertEquals("  İ & 🌿 <literal> 雪  ", root.single("t")!!.text())
        assertEquals("preserve", root.single("t")!!.attribute("space", "http://www.w3.org/XML/1998/namespace"))
        assertNull(root.attribute("id", "urn:foreign"))
    }

    @Test fun utf8BomAndUtf16BothByteOrdersWithAndWithoutBomDecodeExactly() = runBlocking {
        val plain = "<root>İ 🌿 雪</root>"
        assertEquals("İ 🌿 雪", DocxXml.read(byteArrayOf(-17, -69, -65) + plain.toByteArray()).text())
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val xml = ("<?xml version='1.0' encoding='UTF-16'?>" + plain).toByteArray(charset)
            val bom = if (charset == Charsets.UTF_16LE) byteArrayOf(-1, -2) else byteArrayOf(-2, -1)
            assertEquals("İ 🌿 雪", DocxXml.read(bom + xml).text())
            assertEquals("İ 🌿 雪", DocxXml.read(xml).text())
        }
    }

    @Test fun dtdExternalAndCustomEntitiesNeverProduceContent() = runBlocking {
        refused("<!DOCTYPE root [<!ENTITY x 'injected'>]><root>&x;</root>")
        refused("<!DOCTYPE root SYSTEM 'urn:yaprak:no-fetch'><root/>")
        refused("<root>&unresolved;</root>")
        assertEquals("<!DOCTYPE root>", read("<root><![CDATA[<!DOCTYPE root>]]></root>").text())
    }

    @Test fun malformedXmlMultipleRootsUnboundPrefixesAndDuplicateQualifiedAttributesAreRejected() = runBlocking {
        for (xml in listOf("", "<root>", "<root></other>", "<root/><other/>", "text<root/>", "<root/>text",
            "<x:root/>", "<root a='1' a='2'/>", "<root xmlns:a='urn:x' xmlns:b='urn:x' a:id='1' b:id='2'/>",
            "<root>&#0;</root>", "<root>&#xD800;</root>", "<root>&#xFFFF;</root>", "<root>&#x110000;</root>")) refused(xml)
    }

    @Test fun malformedBytesAndMismatchedOrUnsupportedDeclarationsNeverReplaceCharacters() = runBlocking {
        assertTrue(runCatching { DocxXml.read("<root/>".toByteArray() + byteArrayOf(-61, 40)) }.exceptionOrNull() is DocxException)
        refused("<?xml version='1.0' encoding='UTF-7'?><root/>", DocxFailure.UNSUPPORTED)
        refused("<?xml version='1.1'?><root/>", DocxFailure.UNSUPPORTED)
        refused("<?xml version='1.0' encoding='UTF-16'?><root/>")
        val mismatched = byteArrayOf(-1, -2) + "<?xml version='1.0' encoding='UTF-8'?><root/>".toByteArray(Charsets.UTF_16LE)
        assertEquals(DocxFailure.INVALID, (runCatching { DocxXml.read(mismatched) }.exceptionOrNull() as DocxException).reason)
    }

    @Test fun depthAttributeNamespaceNodeAndByteBudgetsRejectWholeParts() = runBlocking {
        refused("<r>".repeat(65) + "</r>".repeat(65), DocxFailure.LIMIT)
        refused("<r " + (1..65).joinToString(" ") { "a$it='x'" } + "/>", DocxFailure.LIMIT)
        refused("<r " + (1..65).joinToString(" ") { "xmlns:n$it='urn:$it'" } + "/>", DocxFailure.LIMIT)
        refused("<r a='" + "x".repeat(8193) + "'/>", DocxFailure.LIMIT)
        refused("<r>" + "<x/>".repeat(100_001) + "</r>", DocxFailure.LIMIT)
        assertEquals(DocxFailure.LIMIT, (runCatching { DocxXml.read(ByteArray(MAX_DOCX_ENTRY_BYTES + 1)) }.exceptionOrNull() as DocxException).reason)
    }

    @Test fun noncharactersAndRawInvalidControlsAreRejectedAlsoInsideCommentsAndAttributes() = runBlocking {
        for (point in listOf(0, 1, 0xb, 0xfdd0, 0xfffe, 0x1ffff)) {
            val value = String(Character.toChars(point))
            refused("<root>$value</root>"); refused("<root a='$value'/>"); refused("<!--$value--><root/>")
        }
    }

    @Test fun alreadyCancelledCallerWinsBeforeXmlValidation() = runBlocking {
        var preserved = false
        val child = launch {
            cancel()
            try { DocxXml.read(byteArrayOf()) } catch (_: CancellationException) { preserved = true }
        }
        child.join(); assertTrue(preserved)
    }
}

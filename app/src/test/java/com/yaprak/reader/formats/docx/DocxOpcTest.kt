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
class DocxOpcTest {
    private suspend fun read(parts: Map<String, String>) = DocxOpc.read(DocxPackages.decode(DocxFixtures.zip(parts)))
    private suspend fun refused(parts: Map<String, String>, expected: DocxFailure = DocxFailure.INVALID) {
        val problem = runCatching { read(parts) }.exceptionOrNull()
        assertTrue("Package semantics should be refused", problem is DocxException)
        assertEquals(expected, (problem as DocxException).reason)
    }

    @Test fun arbitraryMainPartAndCoreMetadataAreFoundByActualRelationships() = runBlocking {
        val parts = DocxFixtures.parts("custom/main.xml")
        parts["_rels/.rels"] = DocxFixtures.relationships(DocxFixtures.relationship("doc", "officeDocument", "/custom/main.xml") +
            "<Relationship Id='props' Type='$DOCX_PACKAGE_REL_NS/metadata/core-properties' Target='meta/core.xml'/>")
        parts["meta/core.xml"] = "<cp:coreProperties xmlns:cp='$DOCX_CORE_NS' xmlns:dc='$DOCX_DC_NS'><dc:title>  Başlık &amp; 🌿 </dc:title><dc:creator>İsim Soyisim</dc:creator><dc:language>tr-TR</dc:language></cp:coreProperties>"
        parts["[Content_Types].xml"] = DocxFixtures.types("custom/main.xml", "<Override PartName='/meta/core.xml' ContentType='application/vnd.openxmlformats-package.core-properties+xml'/>")
        val opc = read(parts)
        assertEquals("custom/main.xml", opc.mainPart); assertEquals(DOCX_WORD_NS, opc.wordNamespace)
        assertEquals(DocxMetadata("Başlık & 🌿", "İsim Soyisim", "tr-TR"), opc.metadata)
        assertTrue(opc.main.single("body")!!.text().contains("Özgün 🌿 belge"))
    }

    @Test fun strictMainRelationshipsRequireMatchingStrictWordVocabulary() = runBlocking {
        val parts = DocxFixtures.parts(body = DocxFixtures.word(strict = true), strict = true)
        val opc = read(parts)
        assertEquals(DOCX_STRICT_WORD_NS, opc.wordNamespace); assertEquals(DOCX_STRICT_REL_NS, opc.relationshipNamespace)
        parts["word/document.xml"] = DocxFixtures.word()
        refused(parts)
    }

    @Test fun absentMetadataIsNotGuessedFromAConvenientFilename() = runBlocking {
        val parts = DocxFixtures.parts()
        parts["docProps/core.xml"] = "<notMetadata/>"
        assertEquals(DocxMetadata(null, null, "und"), read(parts).metadata)
    }

    @Test fun asciiCaseEquivalenceResolvesPartsAndOverridesWithoutMergingUnicode() = runBlocking {
        val parts = DocxFixtures.parts("WORD/Document.xml")
        parts["_rels/.rels"] = DocxFixtures.relationships(DocxFixtures.relationship("doc", "officeDocument", "word/document.XML"))
        parts["[Content_Types].xml"] = DocxFixtures.types("word/DOCUMENT.xml")
        val opc = read(parts)
        assertEquals("WORD/Document.xml", opc.mainPart)
        assertEquals("WORD/Document.xml", opc.resolve("", "word/document.xml"))
        assertNotEquals(docxPartKey("İ.xml"), docxPartKey("i.xml"))
    }

    @Test fun relativeTargetsAreResolvedAgainstTheOwnerWithOnePercentDecode() = runBlocking {
        val parts = DocxFixtures.parts()
        parts["word/_rels/document.xml.rels"] = DocxFixtures.relationships(DocxFixtures.relationship("styles", "styles", "../shared/my%20styles.xml"))
        parts["shared/my%20styles.xml"] = "<w:styles xmlns:w='$DOCX_WORD_NS'/>"
        val opc = read(parts)
        assertEquals("shared/my styles.xml", opc.officePart("styles"))
        assertEquals("word/child.xml", resolveDocxTarget("word/document.xml", "./child.xml"))
        assertEquals("shared/a%2e.xml", resolveDocxTarget("shared/file%2e.xml", "a%252e.xml"))
        for (target in listOf("../../outside.xml", "%2e%2e/outside.xml", "child%2Fbad.xml", "child%5cbad.xml", "child.xml#x", "child.xml?q=x", "//host/child.xml", "file:/child.xml", "child\\bad.xml"))
            assertTrue("Unsafe target $target", runCatching { resolveDocxTarget("word/document.xml", target) }.exceptionOrNull() is DocxException)
    }

    @Test fun missingTargetsDuplicateRelationshipIdsWrongNamespacesAndXmlBaseAreRejected() = runBlocking {
        val base = DocxFixtures.parts()
        for (relations in listOf(
            DocxFixtures.relationships(DocxFixtures.relationship("doc", "officeDocument", "missing.xml")),
            DocxFixtures.relationships(DocxFixtures.relationship("doc", "officeDocument", "word/document.xml").repeat(2)),
            DocxFixtures.relationships(DocxFixtures.relationship("doc1", "officeDocument", "word/document.xml") + DocxFixtures.relationship("doc2", "officeDocument", "word/document.xml")),
            "<Relationships xmlns='urn:foreign'/>",
            DocxFixtures.relationships(DocxFixtures.relationship("doc", "officeDocument", "word/document.xml")).replace("<Relationships ", "<Relationships xml:base='https://example.invalid/' ")
        )) refused(base + ("_rels/.rels" to relations))
        refused(base + ("word/document.xml" to "<w:document xmlns:w='$DOCX_WORD_NS'/ >"))
    }

    @Test fun onlyRealDocxMainMimeIsAcceptedAndActiveContentIsRefusedAnywhere() = runBlocking {
        val base = DocxFixtures.parts()
        for (mime in listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
            "application/vnd.ms-word.document.macroEnabled.main+xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml"))
            refused(base + ("[Content_Types].xml" to DocxFixtures.types().replace(DOCX_MAIN_TYPE, mime)), DocxFailure.UNSUPPORTED)
        refused(base + ("_rels/.rels" to DocxFixtures.relationships(DocxFixtures.relationship("doc", "officeDocument", "https://example.invalid/book.xml", external = true))), DocxFailure.UNSUPPORTED)
        refused(base + ("[Content_Types].xml" to DocxFixtures.types(extra = "<Default Extension='bin' ContentType='application/vnd.ms-office.vbaProject'/>")), DocxFailure.UNSUPPORTED)
    }

    @Test fun contentTypesCannotAliasOrLosePartsAndRelationshipsCannotTargetRelationshipParts() = runBlocking {
        val base = DocxFixtures.parts()
        refused(base + ("[Content_Types].xml" to DocxFixtures.types(extra = "<Override PartName='/WORD/DOCUMENT.xml' ContentType='$DOCX_MAIN_TYPE'/>")))
        refused(base + ("payload.unknown" to "content"))
        refused(base + ("[Content_Types].xml" to DocxFixtures.types(extra = "<Override PartName='/missing.xml' ContentType='application/xml'/>")))
        refused(base + ("_rels/.rels" to DocxFixtures.relationships(DocxFixtures.relationship("doc", "officeDocument", "_rels/.rels"))))
        refused(base + ("word/_rels/missing.xml.rels" to DocxFixtures.relationships("")))
    }

    @Test fun cancelledCallerWinsBeforePackageSemanticValidation() = runBlocking {
        val source = DocxPackages.decode(DocxFixtures.zip(DocxFixtures.parts()))
        var preserved = false
        val child = launch { cancel(); try { DocxOpc.read(source) } catch (_: CancellationException) { preserved = true } }
        child.join(); assertTrue(preserved)
    }
}

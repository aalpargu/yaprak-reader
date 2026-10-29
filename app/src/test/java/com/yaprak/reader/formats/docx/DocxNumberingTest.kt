package com.yaprak.reader.formats.docx

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.formats.epub.EpubBookSearch
import com.yaprak.reader.formats.epub.EpubPublicationSource
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DocxNumberingTest {
    @get:Rule val temp = TemporaryFolder()
    private fun level(index: Int, format: String = "decimal", template: String = "%1.", extra: String = "<w:start w:val='1'/>") =
        "<w:lvl w:ilvl='$index'>$extra<w:numFmt w:val='$format'/><w:lvlText w:val='$template'/><w:suff w:val='space'/></w:lvl>"
    private fun numbering(levels: String, extra: String = "") =
        "<w:numbering xmlns:w='$DOCX_WORD_NS'><w:abstractNum w:abstractNumId='0'>$levels</w:abstractNum><w:num w:numId='1'><w:abstractNumId w:val='0'/></w:num>$extra</w:numbering>"
    private fun paragraph(text: String, number: Int = 1, level: Int = 0, extra: String = "") =
        "<w:p><w:pPr><w:numPr><w:numId w:val='$number'/><w:ilvl w:val='$level'/></w:numPr>$extra</w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"
    private fun parts(body: String, numbering: String): Map<String, String> = DocxFixtures.parts(body = DocxFixtures.word(body)) + mapOf(
        "word/numbering.xml" to numbering,
        "word/_rels/document.xml.rels" to DocxFixtures.relationships(DocxFixtures.relationship("numbering", "numbering", "numbering.xml")),
        "[Content_Types].xml" to DocxFixtures.types(extra = "<Override PartName='/word/numbering.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml'/>"))
    private suspend fun read(body: String, numbering: String) = StrictDocx.decode(DocxFixtures.zip(parts(body, numbering)))
    private suspend fun refused(body: String, numbering: String, reason: DocxFailure = DocxFailure.INVALID) {
        val failure = runCatching { read(body, numbering) }.exceptionOrNull()
        assertTrue(failure is DocxException); assertEquals(reason, (failure as DocxException).reason)
    }
    private fun text(document: DocxDocument) = document.body.replace(Regex("<[^>]+>"), "|")

    @Test fun actualNestedLevelsContinueAndRestartWhenTheirSpecifiedParentIsUsed() = runBlocking {
        val rules = numbering(level(0) + level(1, "lowerLetter", "%1.%2)"))
        val body = paragraph("A") + paragraph("B", level = 1) + paragraph("C", level = 1) + paragraph("D") + paragraph("E", level = 1)
        assertEquals("|1. A||1.a) B||1.b) C||2. D||2.a) E|", text(read(body, rules)))
    }

    @Test fun strictVocabularyAndActualNumberedHeadingShareTheSameSourceLabel() = runBlocking {
        val source = parts(paragraph("Başlık", extra = "<w:outlineLvl w:val='0'/>"), numbering(level(0))).mapValues { (_, value) ->
            value.replace(DOCX_WORD_NS, DOCX_STRICT_WORD_NS).replace(DOCX_REL_NS, DOCX_STRICT_REL_NS)
        }
        val document = StrictDocx.decode(DocxFixtures.zip(source))
        assertEquals("1. Başlık", document.headings.single().title)
        assertTrue(document.body.contains("1. Başlık</h1>"))
    }

    @Test fun numberingInstancesAndOverridesOwnIndependentCounters() = runBlocking {
        val rules = numbering(level(0), "<w:num w:numId='2'><w:abstractNumId w:val='0'/><w:lvlOverride w:ilvl='0'><w:startOverride w:val='7'/></w:lvlOverride></w:num>")
        val body = paragraph("A") + paragraph("B", 2) + paragraph("C") + paragraph("D", 2)
        assertEquals("|1. A||7. B||2. C||8. D|", text(read(body, rules)))
    }

    @Test fun neverRestartAndExplicitEarlierRestartUseActualOneBasedRules() = runBlocking {
        val never = numbering(level(0) + level(1, "lowerLetter", "%2)", "<w:start w:val='1'/><w:lvlRestart w:val='0'/>"))
        val body = paragraph("A") + paragraph("B", level = 1) + paragraph("C") + paragraph("D", level = 1)
        assertEquals("|1. A||a) B||2. C||b) D|", text(read(body, never)))
        val explicit = numbering(level(0) + level(1, template = "%2.") + level(2, template = "%3.", extra = "<w:start w:val='1'/><w:lvlRestart w:val='1'/>"))
        val deeper = paragraph("A") + paragraph("B", level = 2) + paragraph("C", level = 1) + paragraph("D", level = 2) + paragraph("E") + paragraph("F", level = 2)
        assertEquals("|1. A||1. B||1. C||2. D||2. E||1. F|", text(read(deeper, explicit)))
    }

    @Test fun missingStartDefaultsToZeroAndLetterMarkersRepeatTheWordAlphabetPattern() = runBlocking {
        assertEquals("|00. A||01. B|", text(read(paragraph("A") + paragraph("B"), numbering(level(0, "decimalZero", extra = "")))))
        val alphabet = numbering(level(0, "upperLetter", extra = "<w:start w:val='26'/>"))
        assertEquals("|Z. A||AA. B||BB. C|", text(read(paragraph("A") + paragraph("B") + paragraph("C"), alphabet)))
        assertEquals("|mmmcmxcix. A|", text(read(paragraph("A"), numbering(level(0, "lowerRoman", extra = "<w:start w:val='3999'/>")))))
        refused(paragraph("A") + paragraph("B"), numbering(level(0, "lowerRoman", extra = "<w:start w:val='3999'/>")), DocxFailure.UNSUPPORTED)
    }

    @Test fun bulletAndNoneLabelsAreEscapedWithoutInventingListNumbersOrFontGlyphs() = runBlocking {
        val document = read(paragraph("A") + paragraph("B"), numbering(level(0, "bullet", "• &lt;literal&gt;")))
        assertTrue(document.body.contains("• &lt;literal&gt; A")); assertTrue(document.body.contains("• &lt;literal&gt; B"))
        assertEquals("|A|", text(read(paragraph("A"), numbering(level(0, "none", "ignored")))))
        refused(paragraph("A"), numbering(level(0, "bullet", "&#xF0B7;")), DocxFailure.UNSUPPORTED)
    }

    @Test fun inheritedNumberAndDirectLevelMergeAndExplicitZeroDisablesTheList() = runBlocking {
        val first = "<w:p><w:pPr><w:pStyle w:val='List'/><w:numPr><w:ilvl w:val='1'/></w:numPr></w:pPr><w:r><w:t>A</w:t></w:r></w:p>"
        val disabled = first.replace("<w:ilvl w:val='1'/>", "<w:numId w:val='0'/>").replace(">A<", ">B<")
        val rules = numbering(level(0) + level(1, template = "%2."))
        val source = parts(first + disabled, rules).toMutableMap()
        source["word/styles.xml"] = "<w:styles xmlns:w='$DOCX_WORD_NS'><w:style w:type='paragraph' w:styleId='List'><w:pPr><w:numPr><w:numId w:val='1'/></w:numPr></w:pPr></w:style></w:styles>"
        source["word/_rels/document.xml.rels"] = DocxFixtures.relationships(DocxFixtures.relationship("num", "numbering", "numbering.xml") + DocxFixtures.relationship("styles", "styles", "styles.xml"))
        source["[Content_Types].xml"] = source.getValue("[Content_Types].xml").replace("</Types>", "<Override PartName='/word/styles.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml'/></Types>")
        assertEquals("|1. A||B|", text(StrictDocx.decode(DocxFixtures.zip(source))))
    }

    @Test fun actualLevelOverrideChangesTheLabelAndDeletedParagraphDoesNotConsumeANumber() = runBlocking {
        val override = "<w:num w:numId='2'><w:abstractNumId w:val='0'/><w:lvlOverride w:ilvl='0'>" + level(0, "upperRoman", "%1)", "<w:start w:val='4'/>") + "</w:lvlOverride></w:num>"
        val body = paragraph("A", 2) + "<w:del>${paragraph("deleted", 2)}</w:del>" + paragraph("B", 2)
        assertEquals("|IV) A||V) B|", text(read(body, numbering(level(0), override))))
    }

    @Test fun missingDuplicateOrInvalidDefinitionsAndUnimplementedFormatsAreRefused() = runBlocking {
        refused(paragraph("x", 9), numbering(level(0)))
        refused(paragraph("x", level = 1), numbering(level(0)))
        refused(paragraph("x"), numbering(level(0).repeat(2)))
        refused(paragraph("x"), numbering(level(0, "ordinal")), DocxFailure.UNSUPPORTED)
        refused(paragraph("x"), numbering(level(0, extra = "<w:start w:val='-1'/>")))
        refused(paragraph("x"), numbering(level(0, template = "%2.")), DocxFailure.UNSUPPORTED)
    }

    @Test fun independentPythonDocxProducerPreservesMetadataHeadingsTablesAndActualListLabels() = runBlocking {
        for (name in listOf("python-docx-basic.docx", "python-docx-numbered.docx")) {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/docx/$name")).use { it.readBytes() }
            val document = StrictDocx.decode(bytes)
            assertEquals(DocxMetadata("Bağımsız üretici 🌿", "Yaprak Test", "tr-TR"), document.metadata)
            assertEquals("Gerçek Başlık", document.headings.single().title)
            assertTrue(document.body.contains("<strong>kalın</strong>")); assertTrue(document.body.contains("<em>italik</em>"))
            assertTrue(document.body.contains("Sol hücre")); assertTrue(document.body.contains("Sağ hücre"))
            if (name.contains("numbered")) {
                assertTrue(document.body.contains("1.\tİlk numaralı öğe")); assertTrue(document.body.contains("2.\tİkinci numaralı öğe"))
            }
            val file = temp.newFile(name + ".epub")
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            DocxEpub.write(file, hash, document)
            val context = ApplicationProvider.getApplicationContext<Application>()
            EpubPublicationSource(context.contentResolver).open(file).use { opened ->
                assertEquals("Bağımsız üretici 🌿", opened.publication.metadata.title)
                assertEquals("Gerçek Başlık", opened.publication.tableOfContents.single().title)
                assertEquals(1, EpubBookSearch(opened.publication).find("Independent producer unique tail").hits.size)
            }
        }
    }
}

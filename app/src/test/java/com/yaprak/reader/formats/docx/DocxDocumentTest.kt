package com.yaprak.reader.formats.docx

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.formats.epub.EpubBookSearch
import com.yaprak.reader.formats.epub.EpubPublicationSource
import com.yaprak.reader.formats.epub.decodeEpubLocation
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
class DocxDocumentTest {
    @get:Rule val temp = TemporaryFolder()
    private fun paragraph(text: String, properties: String = "", run: String = "") =
        "<w:p>$properties<w:r>$run<w:t xml:space='preserve'>$text</w:t></w:r></w:p>"
    private suspend fun read(body: String, extra: Map<String, String> = emptyMap(), strict: Boolean = false) =
        StrictDocx.decode(DocxFixtures.zip(DocxFixtures.parts(body = DocxFixtures.word(body, strict), strict = strict) + extra))
    private suspend fun refused(body: String, reason: DocxFailure = DocxFailure.UNSUPPORTED, extra: Map<String, String> = emptyMap()) {
        val failure = runCatching { read(body, extra) }.exceptionOrNull()
        assertTrue("Unrenderable main content should be refused", failure is DocxException)
        assertEquals(reason, (failure as DocxException).reason)
    }
    private fun styleParts(content: String): Map<String, String> = mapOf(
        "word/styles.xml" to "<w:styles xmlns:w='$DOCX_WORD_NS'>$content</w:styles>",
        "word/_rels/document.xml.rels" to DocxFixtures.relationships(DocxFixtures.relationship("styles", "styles", "styles.xml")),
        "[Content_Types].xml" to DocxFixtures.types(extra = "<Override PartName='/word/styles.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml'/>"))

    @Test fun originalUnicodeWhitespaceEntitiesAndInlineFormattingBecomeEscapedXhtml() = runBlocking {
        val document = read(paragraph("  İ &amp; &lt;script&gt; 🌿 雪  ", run = "<w:rPr><w:b/><w:i/><w:u/><w:strike/><w:vertAlign w:val='superscript'/></w:rPr>"))
        assertTrue(document.body.contains("<strong><em><u><s><sup>  İ &amp; &lt;script&gt; 🌿 雪  </sup></s></u></em></strong>"))
        assertFalse(document.body.contains("<script>")); assertTrue(document.headings.isEmpty())
        assertEquals(document.body, read(paragraph("  İ &amp; &lt;script&gt; 🌿 雪  ", run = "<w:rPr><w:b/><w:i/><w:u/><w:strike/><w:vertAlign w:val='superscript'/></w:rPr>"), strict = true).body)
    }

    @Test fun tabsLineBreaksAndBothHyphensKeepTheirActualCharacters() = runBlocking {
        val document = read("<w:p><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t><w:cr/><w:noBreakHyphen/><w:softHyphen/></w:r></w:p>")
        assertEquals("<p>A\tB<br/>C<br/>\u2011\u00ad</p>", document.body)
    }

    @Test fun realOutlineLevelsCreateStableHeadingsAndOrdinaryParagraphsDoNotInventChapters() = runBlocking {
        val body = paragraph("Giriş") + paragraph("Başlık 🌿", "<w:pPr><w:outlineLvl w:val='0'/></w:pPr>") +
            paragraph("Alt başlık", "<w:pPr><w:outlineLvl w:val='3'/></w:pPr>") + paragraph("Gövde", "<w:pPr><w:outlineLvl w:val='9'/></w:pPr>")
        val document = read(body)
        assertEquals(listOf(DocxHeading("h1", 0, "Başlık 🌿"), DocxHeading("h2", 3, "Alt başlık")), document.headings)
        assertTrue(document.body.contains("<h1 id=\"h1\">Başlık 🌿</h1>")); assertTrue(document.body.contains("<h4 id=\"h2\">Alt başlık</h4>"))
        assertTrue(document.body.endsWith("<p>Gövde</p>"))
    }

    @Test fun paragraphInheritanceDefaultStylesCharacterTogglesAndDirectOverridesAreApplied() = runBlocking {
        val styles = styleParts("<w:docDefaults><w:rPrDefault><w:rPr><w:i/></w:rPr></w:rPrDefault></w:docDefaults>" +
            "<w:style w:type='paragraph' w:styleId='Base' w:default='1'><w:rPr><w:b/></w:rPr></w:style>" +
            "<w:style w:type='paragraph' w:styleId='Heading'><w:basedOn w:val='Base'/><w:pPr><w:outlineLvl w:val='1'/></w:pPr></w:style>" +
            "<w:style w:type='character' w:styleId='Toggle'><w:rPr><w:b/><w:u w:val='single'/></w:rPr></w:style>")
        val document = read(paragraph("Default") + paragraph("Heading", "<w:pPr><w:pStyle w:val='Heading'/></w:pPr>",
            "<w:rPr><w:rStyle w:val='Toggle'/><w:i w:val='0'/></w:rPr>"), styles)
        assertTrue(document.body.startsWith("<p><strong><em>Default</em></strong></p>"))
        assertTrue(document.body.endsWith("<h2 id=\"h1\"><u>Heading</u></h2>"))
        assertEquals("Heading", document.headings.single().title)
    }

    @Test fun cyclesMissingStylesAndMalformedOutlineValuesCannotGiveMisleadingHeadings() = runBlocking {
        val body = paragraph("x", "<w:pPr><w:pStyle w:val='A'/></w:pPr>")
        refused(body, DocxFailure.INVALID)
        refused(body, DocxFailure.INVALID, styleParts("<w:style w:type='paragraph' w:styleId='A'><w:basedOn w:val='B'/></w:style><w:style w:type='paragraph' w:styleId='B'><w:basedOn w:val='A'/></w:style>"))
        refused(paragraph("x", "<w:pPr><w:outlineLvl w:val='bad'/></w:pPr>"), DocxFailure.INVALID)
    }

    @Test fun bookmarksAndInternalOrHttpsLinksUseGeneratedSafeTargets() = runBlocking {
        val body = "<w:p><w:bookmarkStart w:id='1' w:name='hedef🌿'/><w:r><w:t>Hedef</w:t></w:r><w:bookmarkEnd w:id='1'/>" +
            "<w:hyperlink w:anchor='hedef🌿'><w:r><w:t>İç bağlantı</w:t></w:r></w:hyperlink>" +
            "<w:hyperlink r:id='web'><w:r><w:t>Web</w:t></w:r></w:hyperlink></w:p>"
        val document = read(body, mapOf("word/_rels/document.xml.rels" to DocxFixtures.relationships(
            DocxFixtures.relationship("web", "hyperlink", "https://example.invalid/?a=1&amp;b=2", external = true))))
        assertTrue(document.body.contains("<span id=\"b1\"></span>")); assertTrue(document.body.contains("href=\"#b1\""))
        assertTrue(document.body.contains("href=\"https://example.invalid/?a=1&amp;b=2\""))
    }

    @Test fun unresolvedDuplicateOrUnpairedBookmarksAndUnsafeLinksAreRefused() = runBlocking {
        refused("<w:p><w:hyperlink w:anchor='missing'><w:r><w:t>x</w:t></w:r></w:hyperlink></w:p>", DocxFailure.INVALID)
        refused("<w:p><w:bookmarkStart w:id='1' w:name='x'/><w:r><w:t>x</w:t></w:r></w:p>", DocxFailure.INVALID)
        refused("<w:p><w:bookmarkStart w:id='1' w:name='x'/><w:bookmarkStart w:id='2' w:name='x'/></w:p>", DocxFailure.INVALID)
        for (target in listOf("javascript:alert(1)", "file:/private/file", "mailto:x@example.invalid", "https://user:secret@example.invalid/")) {
            val relations = DocxFixtures.relationships(DocxFixtures.relationship("web", "hyperlink", target, external = true))
            refused("<w:p><w:hyperlink r:id='web'><w:r><w:t>x</w:t></w:r></w:hyperlink></w:p>", extra = mapOf("word/_rels/document.xml.rels" to relations))
        }
    }

    @Test fun finalRevisionAndCachedFieldTextRenderWithoutExecutingInstructionsOrIncludingDeletions() = runBlocking {
        val body = "<w:p><w:del><w:r><w:delText>deleted</w:delText></w:r></w:del>" +
            "<w:ins><w:r><w:t>inserted</w:t></w:r></w:ins><w:moveFrom><w:r><w:t>old</w:t></w:r></w:moveFrom>" +
            "<w:moveTo><w:r><w:t> moved</w:t></w:r></w:moveTo>" +
            "<w:fldSimple w:instr='DDE ignored'><w:r><w:t> cached</w:t></w:r></w:fldSimple>" +
            "<w:r><w:fldChar w:fldCharType='begin'/><w:instrText> INCLUDETEXT ignored </w:instrText><w:fldChar w:fldCharType='separate'/><w:t> result</w:t><w:fldChar w:fldCharType='end'/></w:r></w:p>"
        assertEquals("<p>inserted moved cached result</p>", read(body).body)
    }

    @Test fun nestedContentControlsAndBasicTablesKeepCellTextAndExplicitColumnSpans() = runBlocking {
        val table = "<w:tbl><w:tblGrid><w:gridCol/><w:gridCol/></w:tblGrid><w:tr><w:tc><w:tcPr><w:gridSpan w:val='2'/></w:tcPr>" +
            paragraph("Hücre") + "</w:tc></w:tr></w:tbl>"
        val body = "<w:sdt><w:sdtPr/><w:sdtContent>$table</w:sdtContent></w:sdt>"
        assertEquals("<table><tbody><tr><td colspan=\"2\"><p>Hücre</p></td></tr></tbody></table>", read(body).body)
        refused(table.replace("<w:gridSpan w:val='2'/>", "<w:vMerge w:val='restart'/>"))
        refused(table.replace("<w:gridSpan w:val='2'/>", "<w:gridSpan w:val='0'/>"), DocxFailure.INVALID)
    }

    @Test fun unimplementedVisibleContentNeverSilentlyDisappears() = runBlocking {
        for (inline in listOf("<w:drawing/>", "<w:object/>", "<w:pict/>", "<w:footnoteReference w:id='1' w:customMarkFollows='1'/>", "<w:endnoteReference w:id='1' w:customMarkFollows='1'/>", "<w:sym w:char='F0A7'/>"))
            refused("<w:p><w:r><w:t>Before</w:t>$inline<w:t>After</w:t></w:r></w:p>")
        refused(paragraph("x", "<w:pPr><w:numPr><w:numId w:val='1'/></w:numPr></w:pPr>"), DocxFailure.INVALID)
        refused(paragraph("x") + "<w:altChunk r:id='chunk'/>")
        refused(paragraph("x") + "<w:sectPr><w:headerReference r:id='header'/></w:sectPr>")
        refused("<w:p><m:oMath xmlns:m='http://schemas.openxmlformats.org/officeDocument/2006/math'/></w:p>")
        refused(paragraph("x", run = "<w:rPr><w:vanish/></w:rPr>"))
    }

    @Test fun emptyOrDeletedOnlyMainStoryDoesNotBecomeAFakeReadableBook() = runBlocking {
        refused("", DocxFailure.INVALID)
        refused("<w:p><w:del><w:r><w:delText>deleted</w:delText></w:r></w:del></w:p>", DocxFailure.INVALID)
    }

    @Test fun actualReadiumOpensTheDerivedPackageAndResolvesWholeStorySearchAndHeadingTargets() = runBlocking {
        val bytes = DocxFixtures.zip(DocxFixtures.parts(body = DocxFixtures.word(paragraph("Heading", "<w:pPr><w:outlineLvl w:val='0'/></w:pPr>") +
            paragraph("Unique source tail 🌿"))))
        val original = temp.newFile().apply { writeBytes(bytes) }
        val document = StrictDocx.read(original)
        val view = temp.newFile("docx-view.epub")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        DocxEpub.write(view, hash, document)
        val context = ApplicationProvider.getApplicationContext<Application>()
        EpubPublicationSource(context.contentResolver).open(view).use { opened ->
            assertEquals(listOf("Text/document.xhtml"), opened.publication.readingOrder.map { it.url().toString() })
            val hit = EpubBookSearch(opened.publication).find("Unique source tail").hits.single()
            assertEquals("Text/document.xhtml", hit.location.resourceHref)
            assertNotNull(decodeEpubLocation(hit.location, opened.publication.readingOrder))
            val heading = opened.publication.tableOfContents.single()
            assertEquals("Heading", heading.title); assertEquals("Text/document.xhtml#h1", heading.url().toString())
        }
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test fun epubIsDeterministicEscapedBoundedAndHasStoredFirstMimetypeWithoutExtraFields() = runBlocking {
        val document = read(paragraph("Literal &lt;script&gt; &amp; 🌿"))
        val first = temp.newFile("first.epub"); val second = temp.newFile("second.epub")
        DocxEpub.write(first, "a".repeat(64), document); DocxEpub.write(second, "a".repeat(64), document)
        assertArrayEquals(first.readBytes(), second.readBytes())
        ZipFile(first).use { zip ->
            val entries = zip.entries().toList()
            assertEquals("mimetype", entries.first().name); assertEquals(0, entries.first().method)
            assertTrue(entries.first().extra == null || entries.first().extra.isEmpty())
            assertTrue(zip.getInputStream(zip.getEntry("Text/document.xhtml")).bufferedReader().readText().contains("Literal &lt;script&gt; &amp; 🌿"))
            assertEquals(5, entries.size)
        }
        val fixture = File("build/reports/fixtures/docx-foundation-2026-10-14.epub")
        requireNotNull(fixture.parentFile).mkdirs(); first.copyTo(fixture, overwrite = true)
        val untouched = temp.newFile().apply { writeText("existing") }
        val tooLarge = document.copy(body = "雪".repeat(MAX_DOCX_ENTRY_BYTES / 2))
        assertEquals(DocxFailure.LIMIT, (runCatching { DocxEpub.write(untouched, "a".repeat(64), tooLarge) }.exceptionOrNull() as DocxException).reason)
        assertEquals("existing", untouched.readText())
    }

    @Test fun cancelledConversionLeavesTheOutputUntouched() = runBlocking {
        val document = read(paragraph("x")); val output = temp.newFile().apply { writeText("existing") }
        var preserved = false
        val child = launch { cancel(); try { DocxEpub.write(output, "a".repeat(64), document) } catch (_: CancellationException) { preserved = true } }
        child.join(); assertTrue(preserved); assertEquals("existing", output.readText())
    }
}

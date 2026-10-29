package com.yaprak.reader.formats.docx

import android.app.Application
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class DocxOriginalTextTest {
    @get:Rule val temp=TemporaryFolder()
    private suspend fun source(body: String)=StrictDocx.decode(DocxIndexFixtures.bytes(body)).sourceText

    @Test fun actualDecodedSourceUnicodeAndEntitiesStayTextWhileMarkupIsExcluded()=runBlocking {
        val text="Literal <script> & \"quoted\" 🌿"
        val document=StrictDocx.decode(DocxIndexFixtures.bytes(DocxIndexFixtures.p(text)))
        assertEquals(text,document.sourceText); assertFalse(document.sourceText.contains("&lt;"))
        assertTrue(document.body.contains("&lt;script&gt;"))
    }
    @Test fun paragraphAndTableBoundariesAreExtractionDelimitersAndNotWordOffsets()=runBlocking {
        val body=DocxIndexFixtures.p("  First   words  ")+"<w:tbl><w:tr><w:tc>"+DocxIndexFixtures.p("Left")+"</w:tc><w:tc>"+DocxIndexFixtures.p("Right")+"</w:tc></w:tr></w:tbl>"+DocxIndexFixtures.p("Last")
        assertEquals("First words\nLeft\nRight\nLast",source(body))
    }
    @Test fun formattingAndHyperlinkMetadataDoNotBecomeSourceWords()=runBlocking {
        val parts=DocxFixtures.parts(body=DocxFixtures.word("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>depen</w:t></w:r><w:hyperlink r:id='link'><w:r><w:t>dency</w:t></w:r></w:hyperlink><w:r><w:t> injection</w:t></w:r></w:p>"))
        parts["word/_rels/document.xml.rels"]=DocxFixtures.relationships(DocxFixtures.relationship("link","hyperlink","https://example.invalid/metadata",external=true))
        assertEquals("dependency injection",StrictDocx.decode(DocxFixtures.zip(parts)).sourceText)
    }
    @Test fun finalRevisionAndCachedFieldResultOmitDeletedAndInstructionText()=runBlocking {
        val body="<w:p><w:del><w:r><w:delText>Deleted</w:delText></w:r></w:del><w:r><w:instrText>INCLUDETEXT ignored</w:instrText><w:t>Cached</w:t></w:r><w:ins><w:r><w:t> result</w:t></w:r></w:ins></w:p>"
        assertEquals("Cached result",source(body))
    }
    @Test fun tabsBreaksAndExplicitHyphensRemainCanonicalSourceCharacters()=runBlocking {
        val body="<w:p><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t><w:noBreakHyphen/><w:t>D</w:t><w:softHyphen/><w:t>E</w:t></w:r></w:p>"
        assertEquals("A B\nC\u2011D\u00adE",source(body))
    }
    @Test fun referencedNoteTextIsIncludedOnceAndGeneratedLabelsAndBacklinksAreExcluded()=runBlocking {
        val document=StrictDocx.decode(DocxIndexFixtures.withNote())
        assertEquals("Original body\nActual note tail",document.sourceText)
        assertFalse(document.sourceText.contains("Dipnot")); assertFalse(document.sourceText.contains("Metne dön"))
        assertTrue(document.body.contains("Dipnot 1"))
    }
    @Test fun realGeneratedListLabelsAreNotQuotedAsLiteralOriginalRunText()=runBlocking {
        val bytes=requireNotNull(javaClass.getResourceAsStream("/docx/python-docx-numbered.docx")).use { it.readBytes() }
        val document=StrictDocx.decode(bytes)
        assertTrue(document.sourceText.contains("Independent producer unique tail"))
        assertFalse(document.sourceText.contains("<p>")); assertFalse(document.sourceText.contains("margin-left"))
        // Actual list labels are generated from numbering definitions, separately from source run text.
        assertFalse(document.sourceText.lines().any { it.matches(Regex("[0-9]+\\. .*")) })
    }
    @Test fun imageAltAndPrivateViewPathsDoNotPretendToBeOriginalBodyText()=runBlocking {
        val body="<w:p><w:r><w:t>Before</w:t>"+DocxPictureFixtures.picture(alt="not body text")+"<w:t>After</w:t></w:r></w:p>"
        val document=StrictDocx.decode(DocxPictureFixtures.bytes(body)) { _,_->DocxRaster(6,4) }
        assertEquals("Before After",document.sourceText)
        assertFalse(document.sourceText.contains("not body")); assertFalse(document.sourceText.contains("Images/"))
        val only=StrictDocx.decode(DocxPictureFixtures.bytes()) { _,_->DocxRaster(6,4) }
        assertEquals("",only.sourceText)
    }
    @Test fun canonicalHashDependsOnActualTextAndNotOnRenderingOrMetadata()=runBlocking {
        val first=StrictDocx.decode(DocxIndexFixtures.bytes(DocxIndexFixtures.p("Actual source")))
        val same=first.copy(body="different rendering",metadata=first.metadata.copy(title="Edited metadata"))
        assertEquals(docxOriginalText(first),docxOriginalText(same))
        assertNotEquals(docxOriginalText(first).sha256,docxOriginalText(first.copy(sourceText="Changed")).sha256)
        assertEquals(DocxIndexFixtures.hash("Actual source".toByteArray()),docxOriginalText(first).sha256)
    }
    @Test fun capturingOriginalTextDoesNotAlterTheExistingPrivateEpubOrLocatorResource()=runBlocking {
        val document=StrictDocx.decode(DocxIndexFixtures.bytes())
        val first=temp.newFile("first.epub");val second=temp.newFile("second.epub")
        DocxEpub.write(first,"a".repeat(64),document);DocxEpub.write(second,"a".repeat(64),document.copy(sourceText="irrelevant internal field"))
        assertArrayEquals(first.readBytes(),second.readBytes())
        ZipFile(first).use { assertNotNull(it.getEntry(DOCX_INDEX_HREF)) }
    }
    @Test fun sourceWriterPreservesCancellationAndItsIndependentTextBound() {
        val cancellation=CancellationException("owned")
        assertSame(cancellation,runCatching { DocxSourceTextWriter { throw cancellation }.append("source") }.exceptionOrNull())
        val writer=DocxSourceTextWriter {}
        val failure=runCatching { writer.append("x".repeat(MAX_DOCX_ENTRY_BYTES+1)) }.exceptionOrNull()
        assertEquals(DocxFailure.LIMIT,(failure as DocxException).reason)
    }
}

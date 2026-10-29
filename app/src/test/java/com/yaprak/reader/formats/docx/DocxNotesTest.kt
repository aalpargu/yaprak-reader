package com.yaprak.reader.formats.docx

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.MemoryLibrary
import com.yaprak.reader.data.backup.validBackupBookSignature
import com.yaprak.reader.data.importing.*
import com.yaprak.reader.data.storage.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.formats.BookMetadataReader
import com.yaprak.reader.formats.epub.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocxNotesTest {
    @get:Rule val temp=TemporaryFolder()
    private fun p(text: String)="<w:p><w:r><w:t>"+text+"</w:t></w:r></w:p>"
    private fun ref(id: String="2",kind: String="footnote",extra: String="")="<w:r><w:"+kind+"Reference w:id='"+id+"'"+extra+"/></w:r>"
    private fun note(id: String="2",text: String="Unique note 🌿",kind: String="footnote")="<w:"+kind+" w:id='"+id+"'><w:p><w:r><w:"+kind+"Ref/><w:t>"+text+"</w:t></w:r></w:p></w:"+kind+">"
    private fun parts(body: String="<w:p><w:r><w:t>Source</w:t></w:r>"+ref()+"</w:p>",
        footnotes: String=note(),endnotes: String?=null,strict: Boolean=false,
        main: String="word/document.xml",footPart: String="word/footnotes.xml"): LinkedHashMap<String,String> {
        val ns=if(strict) DOCX_STRICT_WORD_NS else DOCX_WORD_NS
        val source=DocxFixtures.parts(main,DocxFixtures.word(body,strict),strict)
        var types="<Override PartName='/"+footPart+"' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml'/>"
        if(endnotes!=null) types+="<Override PartName='/word/endnotes.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.endnotes+xml'/>"
        source["[Content_Types].xml"]=DocxFixtures.types(main,types)
        val parent=main.substringBeforeLast('/',"")
        val owner=(if(parent.isEmpty()) "" else parent+"/")+"_rels/"+main.substringAfterLast('/')+".rels"
        var links=DocxFixtures.relationship("notes","footnotes","/"+footPart,strict=strict)
        if(endnotes!=null) links+=DocxFixtures.relationship("end","endnotes","/word/endnotes.xml",strict=strict)
        source[owner]=DocxFixtures.relationships(links)
        source[footPart]="<w:footnotes xmlns:w='"+ns+"' xmlns:r='"+(if(strict) DOCX_STRICT_REL_NS else DOCX_REL_NS)+"'>"+footnotes+"</w:footnotes>"
        if(endnotes!=null) source["word/endnotes.xml"]="<w:endnotes xmlns:w='"+ns+"'>"+endnotes+"</w:endnotes>"
        return source
    }
    private suspend fun decode(source: Map<String,String>)=StrictDocx.decode(DocxFixtures.zip(source))
    private suspend fun refused(reason: DocxFailure=DocxFailure.INVALID,block: suspend ()->Unit) {
        val failure=runCatching { block() }.exceptionOrNull()
        assertTrue("Expected DOCX refusal, got "+failure,failure is DocxException)
        assertEquals(reason,(failure as DocxException).reason)
    }

    @Test fun actualIdIsResolvedAndAccessibleReflowLabelIsIndependentOfWordPrintedNumber()=runBlocking {
        val document=decode(parts())
        assertEquals(1,document.noteCount)
        assertTrue(document.body.contains("href=\"#fn1\" id=\"nr1\">[Dipnot 1]</a>"))
        assertTrue(document.body.contains("<aside id=\"fn1\" epub:type=\"footnote\" role=\"doc-footnote\">"))
        assertTrue(document.body.contains("Unique note 🌿")); assertTrue(document.body.contains("href=\"#nr1\""))
    }

    @Test fun repeatedReferencePublishesOneNoteAndASeparateBacklinkForEachActualReference()=runBlocking {
        val document=decode(parts("<w:p>"+ref()+ref()+"</w:p>"))
        assertEquals(1,document.noteCount)
        assertEquals(1,Regex("<aside ").findAll(document.body).count())
        assertTrue(document.body.contains("href=\"#nr1\"")); assertTrue(document.body.contains("href=\"#nr2\""))
        assertTrue(document.body.contains("Metne dön 2"))
    }

    @Test fun footnotesAndEndnotesUseDistinctTargetsEvenWhenTheirSourceIdsMatch()=runBlocking {
        val document=decode(parts("<w:p>"+ref(kind="endnote")+ref()+"</w:p>",endnotes=note(text="End source",kind="endnote")))
        assertEquals(2,document.noteCount)
        assertTrue(document.body.contains("href=\"#en1\"")); assertTrue(document.body.contains("href=\"#fn1\""))
        assertTrue(document.body.indexOf("<aside id=\"en1\"")<document.body.indexOf("<aside id=\"fn1\""))
        assertTrue(document.body.contains("role=\"doc-endnote\""))
    }

    @Test fun plainDocumentKeepsPreviousBodyAndNeedsNoNewEpubNamespace()=runBlocking {
        val document=StrictDocx.decode(DocxFixtures.zip(DocxFixtures.parts(body=DocxFixtures.word(p("Plain")))))
        assertEquals(0,document.noteCount); assertEquals("<p>Plain</p>",document.body)
        val output=temp.newFile(); DocxEpub.write(output,"a".repeat(64),document)
        ZipFile(output).use { zip ->
            val html=zip.getInputStream(zip.getEntry("Text/document.xhtml")).bufferedReader().readText()
            assertFalse(html.contains("xmlns:epub")); assertFalse(html.contains("<aside"))
        }
    }

    @Test fun strictAndAlternatePartRelationshipsResolveTheActualReferencedNoteStory()=runBlocking {
        val document=decode(parts(strict=true,main="custom/main.xml",footPart="assets/notes.xml"))
        assertEquals(1,document.noteCount); assertTrue(document.body.contains("Unique note 🌿"))
    }

    @Test fun noteHyperlinksUseTheirOwnPartRelationshipsRatherThanTheMainStoryId()=runBlocking {
        val source=parts(footnotes="<w:footnote w:id='2'><w:p><w:hyperlink r:id='web'><w:r><w:t>Note link</w:t></w:r></w:hyperlink></w:p></w:footnote>")
        source["word/_rels/footnotes.xml.rels"]=DocxFixtures.relationships(DocxFixtures.relationship("web","hyperlink","https://example.invalid/note",external=true))
        source["word/_rels/document.xml.rels"]=source.getValue("word/_rels/document.xml.rels").replace("</Relationships>",DocxFixtures.relationship("web","hyperlink","https://example.invalid/main",external=true)+"</Relationships>")
        val document=decode(source)
        assertTrue(document.body.contains("href=\"https://example.invalid/note\"")); assertFalse(document.body.contains("https://example.invalid/main"))
    }

    @Test fun scopedBookmarkAndHeadingIdsDoNotCollideOrAddNoteHeadingsToMainToc()=runBlocking {
        val mark="<w:bookmarkStart w:id='1' w:name='same'/><w:r><w:t>Marked</w:t></w:r><w:bookmarkEnd w:id='1'/>"
        val heading="<w:pPr><w:outlineLvl w:val='0'/></w:pPr>"
        val document=decode(parts("<w:p>"+heading+mark+ref()+"</w:p>",footnotes="<w:footnote w:id='2'><w:p>"+heading+mark+"<w:hyperlink w:anchor='same'><w:r><w:t>Jump</w:t></w:r></w:hyperlink></w:p></w:footnote>"))
        assertEquals(1,document.headings.size); assertEquals("h1",document.headings.single().id)
        assertTrue(document.body.contains("id=\"b1\"")); assertTrue(document.body.contains("id=\"n-fn1-b1\""))
        assertTrue(document.body.contains("href=\"#n-fn1-b1\"")); assertTrue(document.body.contains("id=\"n-fn1-h1\""))
    }

    @Test fun finalRevisionSkipsDeletedReferencesAndDeletedNoteText()=runBlocking {
        val plain=StrictDocx.decode(DocxFixtures.zip(DocxFixtures.parts(body=DocxFixtures.word("<w:p><w:del>"+ref()+"</w:del><w:r><w:t>Retained</w:t></w:r></w:p>"))))
        assertEquals(0,plain.noteCount); assertEquals("<p>Retained</p>",plain.body)
        val document=decode(parts(footnotes="<w:footnote w:id='2'><w:p><w:del><w:r><w:delText>Removed</w:delText></w:r></w:del><w:ins><w:r><w:t>Kept</w:t></w:r></w:ins></w:p></w:footnote>"))
        assertFalse(document.body.contains("Removed")); assertTrue(document.body.contains("Kept"))
    }

    @Test fun referencesInsideIgnoredInstructionAndMetadataNodesCannotInventNoteContent()=runBlocking {
        val hidden="<w:r><w:instrText>"+ref()+"</w:instrText><w:annotationRef>"+ref()+"</w:annotationRef><w:t>Retained</w:t></w:r>"
        val document=decode(parts("<w:p>"+hidden+"</w:p>"))
        assertEquals(0,document.noteCount); assertEquals("<p>Retained</p>",document.body)
    }

    @Test fun noteBasicTablesContentControlsAndEmphasisUseTheExistingRenderer()=runBlocking {
        val table="<w:sdt><w:sdtContent><w:tbl><w:tr><w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:sdtContent></w:sdt>"
        val document=decode(parts(footnotes="<w:footnote w:id='2'>"+table+"</w:footnote>"))
        assertTrue(document.body.contains("<td><p><strong>Cell</strong></p></td>"))
    }

    @Test fun missingPartMissingIdAndSeparatorReferencesAreRejectedBeforeReturningContent()=runBlocking {
        refused { StrictDocx.decode(DocxFixtures.zip(DocxFixtures.parts(body=DocxFixtures.word("<w:p>"+ref()+"</w:p>")))) }
        refused { decode(parts(footnotes=note("3"))) }
        refused { decode(parts(footnotes="<w:footnote w:id='2' w:type='separator'><w:p><w:r><w:separator/></w:r></w:p></w:footnote>")) }
    }

    @Test fun specialSeparatorsAndUnreferencedNotesAreNotInventedAsReadableSource()=runBlocking {
        val separators="<w:footnote w:id='-1' w:type='separator'><w:p><w:r><w:separator/></w:r></w:p></w:footnote>"
        val document=decode(parts(footnotes=separators+note()+note("99","Unused")))
        assertEquals(1,document.noteCount); assertFalse(document.body.contains("Unused"))
    }

    @Test fun incorrectRootMimeExternalAndDuplicateRoleRelationsAreRejected()=runBlocking {
        val wrongRoot=parts(); wrongRoot["word/footnotes.xml"]=wrongRoot.getValue("word/footnotes.xml").replace("w:footnotes","w:endnotes")
        refused { decode(wrongRoot) }
        val wrongMime=parts(); wrongMime["[Content_Types].xml"]=wrongMime.getValue("[Content_Types].xml").replace("wordprocessingml.footnotes+xml","wordprocessingml.endnotes+xml")
        refused(DocxFailure.UNSUPPORTED) { decode(wrongMime) }
        val remote=parts(); remote["word/_rels/document.xml.rels"]=DocxFixtures.relationships(DocxFixtures.relationship("notes","footnotes","https://example.invalid/notes.xml",external=true))
        refused(DocxFailure.UNSUPPORTED) { decode(remote) }
        val duplicate=parts(); duplicate["word/_rels/document.xml.rels"]=duplicate.getValue("word/_rels/document.xml.rels").replace("</Relationships>",DocxFixtures.relationship("second","footnotes","footnotes.xml")+"</Relationships>")
        refused { decode(duplicate) }
    }

    @Test fun sourceIdSyntaxAndDuplicateCanonicalIdsAreValidated()=runBlocking {
        for(id in listOf("-1","bad","2147483648")) refused { decode(parts("<w:p>"+ref(id)+"</w:p>")) }
        refused { decode(parts(footnotes=note()+note("02"))) }
        assertEquals(1,decode(parts("<w:p>"+ref("02")+"</w:p>")).noteCount)
    }

    @Test fun nestedReferencesAreRejectedWhileDeletedNestedReferencesAreIgnored()=runBlocking {
        for(kind in listOf("footnote","endnote")) refused(DocxFailure.UNSUPPORTED) { decode(parts(footnotes="<w:footnote w:id='2'><w:p><w:r><w:t>Note</w:t></w:r>"+ref(kind=kind)+"</w:p></w:footnote>")) }
        assertEquals(1,decode(parts(footnotes="<w:footnote w:id='2'><w:p><w:del>"+ref()+"</w:del><w:r><w:t>Kept</w:t></w:r></w:p></w:footnote>")).noteCount)
    }

    @Test fun customMarkersAndMismatchedStoryMarkerAreExplicitlyUnsupported()=runBlocking {
        refused(DocxFailure.UNSUPPORTED) { decode(parts("<w:p>"+ref(extra=" w:customMarkFollows='1'")+"</w:p>")) }
        assertEquals(1,decode(parts("<w:p>"+ref(extra=" w:customMarkFollows='0'")+"</w:p>")).noteCount)
        refused(DocxFailure.UNSUPPORTED) { decode(parts(footnotes=note().replace("w:footnoteRef/>","w:endnoteRef/>"))) }
    }

    @Test fun unsupportedNoteGraphicsListsAndMathCannotDisappearDuringImport()=runBlocking {
        for(content in listOf("<w:r><w:drawing/></w:r>","<m:oMath xmlns:m='http://schemas.openxmlformats.org/officeDocument/2006/math'/>","<w:pPr><w:numPr><w:numId w:val='1'/></w:numPr></w:pPr><w:r><w:t>List</w:t></w:r>"))
            refused(DocxFailure.UNSUPPORTED) { decode(parts(footnotes="<w:footnote w:id='2'><w:p>"+content+"</w:p></w:footnote>")) }
    }

    @Test fun noteAndReferenceBudgetsStopLargeGraphsBeforeReturningPartialContent()=runBlocking {
        val references=(0..1024).joinToString("") { ref(it.toString()) }
        refused(DocxFailure.LIMIT) { decode(parts("<w:p>"+references+"</w:p>")) }
        refused(DocxFailure.LIMIT) { decode(parts("<w:p>"+ref().repeat(4097)+"</w:p>")) }
    }

    @Test fun cancellationDoesNotBecomeFormatFailureOrAnEmptySuccessfulDocument()=runBlocking {
        var preserved=false
        val source=DocxFixtures.zip(parts())
        val child=launch { cancel(); try { StrictDocx.decode(source) } catch(_: CancellationException) { preserved=true } }
        child.join(); assertTrue(preserved)
    }

    @Test fun actualImporterAndBackupSourceValidationKeepTheCompleteOriginalNotePackage()=runBlocking {
        val bytes=DocxFixtures.zip(parts()); val library=MemoryLibrary(); val folder=temp.newFolder()
        val importer=DefaultBookImporter(DocumentProvider { SourceDocument("notes.docx") { bytes.inputStream() } },LocalBookFiles(folder),
            BookMetadataReader { file,_ -> DocxMetadataReader().read(file) },library,Dispatchers.Unconfined)
        assertEquals(ImportResult.Added,importer.import("content://owned/notes"))
        assertEquals(ImportResult.Duplicate,importer.import("content://owned/notes-again"))
        val book=library.books.value.single(); val original=File(folder,book.storageName)
        assertArrayEquals(bytes,original.readBytes()); assertEquals(bytes.size.toLong(),book.byteSize)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },book.sha256)
        assertTrue(validBackupBookSignature(original,"DOCX","notes.docx"))
    }

    @Test fun epubContainsVerifiedBidirectionalAnchorsAndRealReadiumCanSearchTheNoteText()=runBlocking {
        val document=decode(parts()); val view=temp.newFile("notes.epub")
        DocxEpub.write(view,"a".repeat(64),document)
        ZipFile(view).use { zip ->
            val html=zip.getInputStream(zip.getEntry("Text/document.xhtml")).bufferedReader().readText()
            assertTrue(html.contains("xmlns:epub=\"http://www.idpf.org/2007/ops\""))
            assertTrue(html.contains("id=\"fn1\"")); assertTrue(html.contains("href=\"#fn1\""))
        }
        val context=ApplicationProvider.getApplicationContext<Application>()
        EpubPublicationSource(context.contentResolver).open(view).use { opened ->
            assertEquals("Text/document.xhtml",EpubBookSearch(opened.publication).find("Unique note").hits.single().location.resourceHref)
        }
        val owned=File("build/reports/fixtures/docx-notes-0.163.0.epub"); requireNotNull(owned.parentFile).mkdirs()
        view.copyTo(owned,overwrite=true); Unit
    }

    @Test fun combinedEscapedOutputBudgetRejectsMainPlusNotesAsAWhole()=runBlocking {
        val quotes="\"".repeat(1024*1024)
        refused(DocxFailure.LIMIT) { decode(parts("<w:p><w:r><w:t>"+quotes+"</w:t></w:r>"+ref()+"</w:p>",footnotes=note(text=quotes))) }
    }
}

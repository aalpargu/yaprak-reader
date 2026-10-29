package com.yaprak.reader.formats.docx

import android.app.Application
import com.yaprak.reader.data.search.validateDocument
import com.yaprak.reader.data.search.wellFormedIndexText
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class DocxBookTextExtractorTest {
    @get:Rule val temp=TemporaryFolder()
    private suspend fun failure(reason: TextExtractionFailure,block: suspend ()->Unit) {
        assertEquals(reason,(runCatching { block() }.exceptionOrNull() as TextExtractionException).reason)
    }
    @Test fun actualOriginalSourceProducesTypedCompleteChunksWithoutCreatingAViewingFile()=runBlocking {
        val folder=temp.newFolder();val (book,file)=DocxIndexFixtures.book(folder,DocxIndexFixtures.withNote())
        val original=file.readBytes();val extractor=DocxBookTextExtractor(VerifiedBookFile(folder),Dispatchers.Unconfined)
        val document=extractor.extract(book)
        assertTrue(extractor.supports(book));assertEquals(1,document.resourceCount);assertEquals(BookFormat.DOCX,document.sourceFormat)
        val chunk=document.chunks.single()
        assertEquals("Original body\nActual note tail",chunk.text)
        assertEquals(DOCX_INDEX_HREF,chunk.resourceHref);assertEquals(DOCX_INDEX_TITLE,chunk.chapterTitle)
        assertEquals(0,chunk.readingOrderIndex);assertNull(chunk.pdfPageIndex)
        assertEquals(DocxIndexFixtures.hash(chunk.text.toByteArray()),chunk.resourceSha256)
        assertTrue(validateDocument(book,document)>0)
        assertArrayEquals(original,file.readBytes());assertEquals(listOf(file.name),folder.listFiles()!!.map { it.name })
    }
    @Test fun chunkEdgesPreserveSurrogatesOverlapTailAndTheSingleFullSourceHash()=runBlocking {
        val folder=temp.newFolder();val text="x".repeat(1999)+"🌿".repeat(1600)+" final needle"
        val (book,_)=DocxIndexFixtures.book(folder,DocxIndexFixtures.bytes(DocxIndexFixtures.p(text)))
        val document=DocxBookTextExtractor(VerifiedBookFile(folder),Dispatchers.Unconfined).extract(book)
        assertTrue(document.chunks.size>2)
        assertTrue(document.chunks.all { wellFormedIndexText(it.text) && it.text.length<=MAX_INDEX_CHUNK_TEXT })
        assertTrue(document.chunks.last().text.endsWith("final needle"))
        assertEquals(setOf(DocxIndexFixtures.hash(text.toByteArray())),document.chunks.map { it.resourceSha256 }.toSet())
        assertTrue(validateDocument(book,document)<=MAX_INDEX_BOOK_STRING_BYTES)
    }
    @Test fun canonicalResourceByteBudgetRejectsTheWholeTextInsteadOfPublishingAPrefix()=runBlocking {
        val folder=temp.newFolder();val text="雪".repeat(MAX_INDEX_RESOURCE_BYTES/3+1)
        val (book,_)=DocxIndexFixtures.book(folder,DocxIndexFixtures.bytes(DocxIndexFixtures.p(text)))
        failure(TextExtractionFailure.LIMIT) { DocxBookTextExtractor(VerifiedBookFile(folder),Dispatchers.Unconfined).extract(book) }
    }
    @Test fun wrongFormatAndOversizeDeclaredSourceFailBeforeFileHashWork()=runBlocking {
        val folder=temp.newFolder();val (book,_)=DocxIndexFixtures.book(folder)
        val extractor=DocxBookTextExtractor(VerifiedBookFile(folder),Dispatchers.Unconfined)
        assertFalse(extractor.supports(book.copy(format=BookFormat.TXT)))
        failure(TextExtractionFailure.UNSUPPORTED) { extractor.extract(book.copy(format=BookFormat.TXT)) }
        failure(TextExtractionFailure.LIMIT) { extractor.extract(book.copy(byteSize=MAX_DOCX_PACKAGE_BYTES+1L)) }
    }
    @Test fun changedOriginalHashOrSizeCannotReturnSourceText()=runBlocking {
        val folder=temp.newFolder();val (book,file)=DocxIndexFixtures.book(folder)
        val extractor=DocxBookTextExtractor(VerifiedBookFile(folder),Dispatchers.Unconfined)
        failure(TextExtractionFailure.SOURCE) { extractor.extract(book.copy(sha256="b".repeat(64))) }
        file.appendBytes(byteArrayOf(32))
        failure(TextExtractionFailure.SOURCE) { extractor.extract(book) }
    }
    @Test fun unsupportedOriginalGrammarFailsAsInvalidSourceRatherThanAnEmptyReadyBook()=runBlocking {
        val folder=temp.newFolder();val (book,_)=DocxIndexFixtures.book(folder,DocxIndexFixtures.bytes("<w:p><w:r><w:object/></w:r></w:p>"))
        failure(TextExtractionFailure.INVALID) { DocxBookTextExtractor(VerifiedBookFile(folder),Dispatchers.Unconfined).extract(book) }
    }
    @Test fun callerCancellationIsPreservedWithoutAReplacementIndexGeneration()=runBlocking {
        val folder=temp.newFolder();val (book,_)=DocxIndexFixtures.book(folder)
        var preserved=false
        val child=launch { cancel();try { DocxBookTextExtractor(VerifiedBookFile(folder),Dispatchers.Unconfined).extract(book) } catch(_: CancellationException) { preserved=true } }
        child.join();assertTrue(preserved)
    }
}

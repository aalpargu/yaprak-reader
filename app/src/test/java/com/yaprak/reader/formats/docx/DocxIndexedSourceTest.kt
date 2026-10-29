package com.yaprak.reader.formats.docx

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.MemoryLibrary
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.formats.epub.EpubBookPosition
import com.yaprak.reader.formats.epub.EpubPublicationSource
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class DocxIndexedSourceTest {
    @get:Rule val temp=TemporaryFolder()
    private suspend fun fixture(block: suspend (Book,java.io.File,java.io.File,MemoryLibrary,DocxIndexedSource,LibraryTextTarget)->Unit) {
        val folder=temp.newFolder();val (book,original)=DocxIndexFixtures.book(folder)
        val library=MemoryLibrary();library.books.value=listOf(book)
        val artifact=temp.newFile();val document=StrictDocx.read(original);DocxEpub.write(artifact,book.sha256,document)
        val mutex=Mutex()
        val source=DocxIndexedSource(book,library,VerifiedBookFile(folder),artifact,docxViewHash(artifact),mutex,{assertTrue(mutex.isLocked)},Dispatchers.Unconfined)
        val target=LibraryTextTarget(book.id,book.sha256,DOCX_INDEX_HREF,docxOriginalText(document).sha256,sourceFormat=BookFormat.DOCX)
        block(book,original,artifact,library,source,target)
    }
    @Test fun actualOriginalTextHashAndUnchangedArtifactAreBothRequiredUnderTheSharedMutex()=runBlocking {
        fixture { _,original,artifact,_,source,target ->
            val bytes=original.readBytes();val view=artifact.readBytes()
            assertTrue(source.verify(target));assertFalse(source.verify(target.copy(resourceSha256="f".repeat(64))))
            artifact.appendText("changed");assertFalse(source.verify(target));artifact.writeBytes(view)
            original.appendBytes(byteArrayOf(32));assertFalse(source.verify(target));original.writeBytes(bytes)
            assertTrue(source.verify(target));assertArrayEquals(view,artifact.readBytes())
        }
    }
    @Test fun currentBookFormatHashStorageAndSizeAreAuthorityWhileMetadataEditsRemainAllowed()=runBlocking {
        fixture { book,_,_,library,source,target ->
            library.books.value=listOf(book.copy(title="Edited"));assertTrue(source.verify(target))
            for(changed in listOf(book.copy(format=BookFormat.TXT),book.copy(sha256="c".repeat(64)),book.copy(byteSize=book.byteSize+1),book.copy(storageName="other.docx"))) {
                library.books.value=listOf(changed);assertFalse(source.verify(target))
            }
            library.books.value=emptyList();assertFalse(source.verify(target))
        }
    }
    @Test fun foreignTargetCannotBecomeAnOriginalDocxProof()=runBlocking {
        fixture { _,_,_,_,source,target ->
            for(invalid in listOf(target.copy(bookId="other"),target.copy(sourceFormat=BookFormat.EPUB),target.copy(resourceHref="Text/document.xhtml#fn1"),target.copy(pdfPageIndex=0)))
                assertFalse(source.verify(invalid))
        }
    }
    @Test fun sourceValidationCancellationPropagatesAndOrdinaryFailureFailsClosed()=runBlocking {
        fixture { book,original,artifact,library,_,target ->
            val cancelled=CancellationException("owned")
            val source=DocxIndexedSource(book,library,VerifiedBookFile(original.parentFile!!),artifact,docxViewHash(artifact),Mutex(),{throw cancelled},Dispatchers.Unconfined)
            assertSame(cancelled,runCatching { source.verify(target) }.exceptionOrNull())
            val denied=DocxIndexedSource(book,library,VerifiedBookFile(original.parentFile!!),artifact,docxViewHash(artifact),Mutex(),{error("Recovery gate")},Dispatchers.Unconfined)
            assertFalse(denied.verify(target))
        }
    }
    @Test fun actualReadiumResolvesTheDocumentStartBeforeAFirstHeadingWithoutInventingALocator()=runBlocking {
        val document=StrictDocx.decode(DocxIndexFixtures.bytes(DocxIndexFixtures.p("Preface before heading")+"<w:p><w:pPr><w:outlineLvl w:val='0'/></w:pPr><w:r><w:t>Heading</w:t></w:r></w:p>"))
        val view=temp.newFile();DocxEpub.write(view,"a".repeat(64),document)
        val context=ApplicationProvider.getApplicationContext<Application>()
        EpubPublicationSource(context.contentResolver).open(view).use { opened ->
            val position=requireNotNull(EpubBookPosition(opened.publication,Dispatchers.Unconfined).resolvePercent(0))
            assertEquals(DOCX_INDEX_HREF,position.resourceHref)
            assertTrue(position.locatorJson.contains("progression"))
        }
    }
}

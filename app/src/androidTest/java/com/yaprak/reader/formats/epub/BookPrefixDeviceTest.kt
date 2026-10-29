package com.yaprak.reader.formats.epub

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.search.*
import com.yaprak.reader.domain.assistant.BookQuestionRequest
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual original EPUB/Room/FTS, isolated to a private UUID folder; never the user's databases. */
@RunWith(AndroidJUnit4::class)
class BookPrefixDeviceTest {
    private fun epub(firstLength: Int=2500,emptyLeading: Boolean=false): ByteArray {
        val contents=linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' version='1.0'><rootfiles><rootfile full-path='EPUB/package.opf' media-type='application/oebps-package+xml'/></rootfiles></container>",
            "EPUB/package.opf" to "<package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='uid'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='uid'>urn:uuid:owned-prefix</dc:identifier><dc:title>Owned prefix</dc:title><dc:language>en</dc:language><meta property='dcterms:modified'>2026-10-17T00:00:00Z</meta></metadata><manifest><item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/><item id='b' href='b.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>",
            "EPUB/nav.xhtml" to ("<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>Contents</title></head><body><nav epub:type='toc'><ol><li><a href='a.xhtml#first'>First</a></li>"+(if(emptyLeading) "" else "<li><a href='a.xhtml#later'>Later</a></li>")+"<li><a href='b.xhtml#last'>Last</a></li></ol></nav></body></html>"),
            "EPUB/a.xhtml" to ("<html xmlns='http://www.w3.org/1999/xhtml'><head><title>A</title></head><body>"+(if(emptyLeading) "<div id='first'></div>" else "<p>PREFACE</p><h1 id='first'>FIRST</h1><p>"+"a".repeat(firstLength)+"</p><h1 id='later'>LATER</h1><p>LATER CONTENT</p>")+"</body></html>"),
            "EPUB/b.xhtml" to ("<html xmlns='http://www.w3.org/1999/xhtml'><head><title>B</title></head><body>"+(if(emptyLeading) "" else "<p>BRIDGE</p>")+"<h1 id='last'>FUTURE LAST</h1><p>FUTURE CONTENT</p></body></html>"))
        return ByteArrayOutputStream().also { output->ZipOutputStream(output).use { zip->
            for((name,text) in contents) {
                val bytes=text.toByteArray();val entry=ZipEntry(name)
                if(name=="mimetype") { entry.method=ZipEntry.STORED;entry.size=bytes.size.toLong();entry.compressedSize=entry.size;entry.crc=CRC32().apply { update(bytes) }.value }
                zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry()
            }
        } }.toByteArray()
    }
    private inner class Fixture(val context: Context,val root: File,val main: LibraryDatabase,var cache: TextIndexDatabase) {
        val library=RoomLibraryRepository(main.books())
        val extractor=EpubBookTextExtractor(context)
        var service=LocalLibraryTextIndex(cache,library,extractor)
        val files=File(root,"books").apply { check(mkdirs()) }
        suspend fun add(length: Int=2500,emptyLeading: Boolean=false): Book {
            val bytes=epub(length,emptyLeading);val file=File(files,UUID.randomUUID().toString()+".epub").apply { writeBytes(bytes) }
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return Book(UUID.randomUUID().toString(),"Owned prefix",null,BookFormat.EPUB,"owned.epub",file.name,file.length(),hash,1).also { library.add(it) }
        }
        fun reopen() { cache.close();cache=Room.databaseBuilder(context,TextIndexDatabase::class.java,File(root,"index.db").absolutePath).build();service=LocalLibraryTextIndex(cache,library,extractor) }
    }
    private suspend fun fixture(block: suspend Fixture.()->Unit) {
        val parent=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(parent.cacheDir,"prefix-device-"+UUID.randomUUID()).apply { check(mkdirs()) }
        val context=object: ContextWrapper(parent) { override fun getFilesDir()=root }
        val main=Room.databaseBuilder(context,LibraryDatabase::class.java,File(root,"main.db").absolutePath).build()
        val cache=Room.databaseBuilder(context,TextIndexDatabase::class.java,File(root,"index.db").absolutePath).build()
        val owned=Fixture(context,root,main,cache)
        try { withTimeout(90_000) { owned.block() } }
        finally { owned.cache.close();main.close();check(root.canonicalFile.parentFile==parent.cacheDir.canonicalFile && root.name.startsWith("prefix-device-"));check(root.deleteRecursively()) }
    }
    private suspend fun refused(reason: BookSourceFailure,block: suspend ()->Unit) {
        try { block();fail("Expected prefix refusal") } catch(failure: BookSourceException) { assertEquals(reason,failure.reason) }
    }
    @Test fun actualOriginalPrefixCrossesResourcesKeepsPrefaceExcludesFutureAndDoesNotWriteProgressOrIndex()=runBlocking {
        fixture {
            val book=add();val scope=BookTextScope(book.id,book.sha256);service.rebuild {}
            val chosen=service.listChapters(scope)[1];val before=cache.index().books()
            val progress=ReadingProgressEntity(book.id,book.sha256,1,"{}","EPUB/b.xhtml",999)
            main.reading().save(progress)
            val preview=service.preparePrefix(scope,chosen);BookQuestionRequest.create(preview,"Soru")
            val joined=preview.references.joinToString("") { it.text }
            assertEquals("PREFACE\nFIRST\n"+"a".repeat(2500)+"\nLATER\nLATER CONTENT\nBRIDGE\n",joined)
            assertFalse(joined.contains("FUTURE"));assertTrue(preview.references.any { it.hit.text.contains("FUTURE") })
            assertTrue(preview.references.all { service.resolve(it.hit)==it.hit });assertNull(preview.chapter);assertNull(preview.section)
            assertEquals(chosen,preview.prefix!!.throughChapter);assertFalse(preview.truncated)
            reopen();assertEquals(preview.references,service.preparePrefix(scope,chosen).references)
            assertEquals(before,cache.index().books());assertEquals(progress,main.reading().resume(book.id,book.sha256))
        }
    }
    @Test fun staleTocGenerationChangedOriginalCancellationAndOversizedPrefixNeverBecomeEvidence()=runBlocking {
        fixture {
            val book=add();val scope=BookTextScope(book.id,book.sha256);service.rebuild {}
            val chosen=service.listChapters(scope)[1];val ledger=cache.index().books()
            refused(BookSourceFailure.SOURCE) { service.preparePrefix(scope,chosen.copy(tocSha256="d".repeat(64))) }
            refused(BookSourceFailure.SOURCE) { service.preparePrefix(scope,chosen.copy(end=chosen.end.copy(textOffset=chosen.end.textOffset-1))) }
            val entered=CompletableDeferred<Unit>()
            val blocking=object: BookTextExtractor {
                override fun supports(book: Book)=true
                override suspend fun extract(book: Book)=extractor.extract(book)
                override suspend fun extractChapters(book: Book): ExtractedBookChapters { entered.complete(Unit);awaitCancellation() }
            }
            val cancelled=LocalLibraryTextIndex(cache,library,blocking)
            val job=launch { cancelled.preparePrefix(scope,chosen) };entered.await();job.cancelAndJoin();assertTrue(job.isCancelled)
            assertEquals(ledger,cache.index().books())
            service.rebuildBook(scope) {};refused(BookSourceFailure.SOURCE) { service.preparePrefix(scope,chosen) }
            val latest=service.listChapters(scope)[1];File(files,book.storageName).appendText("changed")
            refused(BookSourceFailure.SOURCE) { service.preparePrefix(scope,latest) }
            val large=add(130_000);val largeScope=BookTextScope(large.id,large.sha256);service.rebuildBook(largeScope) {}
            val early=service.listChapters(largeScope).first();refused(BookSourceFailure.LIMIT) { service.preparePrefix(largeScope,early) }
            assertTrue(service.prepare(largeScope,"PREFACE").references.size<=MAX_BOOK_SOURCE_REFERENCES)
            val empty=add(emptyLeading=true);val emptyScope=BookTextScope(empty.id,empty.sha256)
            service.rebuildBook(emptyScope) {};val bounds=service.listChapters(emptyScope)
            assertEquals(0,bounds.first().sourceCount)
            refused(BookSourceFailure.UNSUPPORTED) { service.preparePrefix(emptyScope,bounds.first()) }
            val admitted=service.preparePrefix(emptyScope,bounds.last())
            assertEquals(1,admitted.prefix!!.start.readingOrderIndex);BookQuestionRequest.create(admitted,"Soru")
        }
    }

    @Test fun actualPrefixKeywordSearchClipsBeforeMatchingAndKeepsOnlyEightLargePrefixWindowsWithoutWrites()=runBlocking {
        fixture {
            val book=add();val large=add(130_000);service.rebuild {}
            val scope=BookTextScope(book.id,book.sha256);val largeScope=BookTextScope(large.id,large.sha256)
            val chosen=service.listChapters(scope).first();val largeChosen=service.listChapters(largeScope).first()
            val before=cache.index().books();val original=File(files,book.storageName).readBytes()
            val largeOriginal=File(files,large.storageName).readBytes()
            val progress=ReadingProgressEntity(book.id,book.sha256,1,"{}","EPUB/b.xhtml",999)
            main.reading().save(progress)
            assertTrue(service.prepare(scope,"LATER").references.isNotEmpty())
            val empty=service.preparePrefixQuery(scope,chosen,"LATER")
            assertEquals(0,empty.totalMatches);assertTrue(empty.references.isEmpty());assertTrue(validPrefixQuerySources(empty))
            val matching=service.preparePrefixQuery(scope,chosen,"aaa")
            BookQuestionRequest.create(matching,"Soru")
            assertTrue(matching.references.any {it.hit.text.contains("LATER")})
            assertFalse(matching.references.any {it.text.contains("LATER") || it.text.contains("FUTURE")})
            assertTrue(matching.references.all {service.resolve(it.hit)==it.hit})
            refused(BookSourceFailure.LIMIT) {service.preparePrefix(largeScope,largeChosen)}
            val bounded=service.preparePrefixQuery(largeScope,largeChosen,"aaa")
            assertEquals(8,bounded.references.size);assertTrue(bounded.totalMatches>64);assertTrue(bounded.truncated)
            assertTrue(bounded.prefixQuery!!.candidateCount>64);assertEquals(largeChosen,bounded.prefixQuery!!.throughChapter)
            BookQuestionRequest.create(bounded,"Soru")
            assertNull(bounded.prefix);assertNull(bounded.chapter);assertNull(bounded.section)
            reopen();assertEquals(bounded.references,service.preparePrefixQuery(largeScope,largeChosen,"aaa").references)
            assertEquals(before,cache.index().books());assertEquals(progress,main.reading().resume(book.id,book.sha256))
            assertArrayEquals(original,File(files,book.storageName).readBytes())
            assertArrayEquals(largeOriginal,File(files,large.storageName).readBytes())
        }
    }

    @Test fun prefixKeywordEvidenceRejectsOldTocGenerationChangedOriginalAndCancellation()=runBlocking {
        fixture {
            val book=add();val scope=BookTextScope(book.id,book.sha256);service.rebuild {}
            val chosen=service.listChapters(scope).first();val ledger=cache.index().books()
            refused(BookSourceFailure.INVALID_QUERY) {service.preparePrefixQuery(scope,chosen,"+++")}
            refused(BookSourceFailure.SOURCE) {service.preparePrefixQuery(scope,chosen.copy(tocSha256="d".repeat(64)),"aaa")}
            refused(BookSourceFailure.SOURCE) {service.preparePrefixQuery(scope,chosen.copy(end=chosen.end.copy(textOffset=chosen.end.textOffset-1)),"aaa")}
            val entered=CompletableDeferred<Unit>()
            val blocking=object: BookTextExtractor {
                override fun supports(book: Book)=true
                override suspend fun extract(book: Book)=extractor.extract(book)
                override suspend fun extractChapters(book: Book): ExtractedBookChapters {entered.complete(Unit);awaitCancellation()}
            }
            val cancelled=LocalLibraryTextIndex(cache,library,blocking)
            val job=launch {cancelled.preparePrefixQuery(scope,chosen,"aaa")}
            entered.await();job.cancelAndJoin();assertTrue(job.isCancelled);assertEquals(ledger,cache.index().books())
            service.rebuildBook(scope) {};refused(BookSourceFailure.SOURCE) {service.preparePrefixQuery(scope,chosen,"aaa")}
            val latest=service.listChapters(scope).first();File(files,book.storageName).appendText("changed")
            refused(BookSourceFailure.SOURCE) {service.preparePrefixQuery(scope,latest,"aaa")}
        }
    }
}

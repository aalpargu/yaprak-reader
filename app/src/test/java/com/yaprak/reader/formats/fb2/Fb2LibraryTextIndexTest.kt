package com.yaprak.reader.formats.fb2

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.search.*
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.formats.epub.EpubContents
import com.yaprak.reader.formats.epub.EpubPublicationSource
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fb2LibraryTextIndexTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun original(body: String, extra: String = "") = "<FictionBook xmlns=\"$FB2_NAMESPACE\"><description><title-info><book-title>Original</book-title><lang>tr</lang></title-info></description><body>$body</body>$extra</FictionBook>"
    private inner class Fixture(val main: LibraryDatabase, var cache: TextIndexDatabase, val cacheName: String) {
        val library = RoomLibraryRepository(main.books())
        val files = File(context.filesDir, "books").apply { mkdirs() }
        val owned = mutableListOf<File>()
        val extractor = Fb2BookTextExtractor(VerifiedBookFile(files))
        val index get() = LocalLibraryTextIndex(cache, library, extractor)
        suspend fun add(bytes: ByteArray): Book {
            val id = UUID.randomUUID().toString()
            val file = File(files, "$id.fb2").apply { writeBytes(bytes); owned += this }
            return Book(id, "Book $id", "Writer", BookFormat.FB2, file.name, file.name, file.length(), hash(bytes), 1).also { library.add(it) }
        }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val main = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val name = File(temp.newFolder(), "i.db").absolutePath
        val f = Fixture(main, Room.databaseBuilder(context, TextIndexDatabase::class.java, name).build(), name)
        try { f.block() } finally { f.cache.close(); main.close(); f.owned.forEach { it.delete() } }
    }
    @Test fun wholeOriginalBodiesNestedSectionsAndNotesSearchAcrossBooksAndSurviveRealFtsReopen() = runBlocking {
        fixture {
            val xml = original("<title><p>Önsöz</p></title><section><title><p>Bir</p></title><p>depen<strong>dency</strong> injection 🌿</p><section><title><p>Alt</p></title><p>A &amp; B</p></section></section><section><title><p>Son</p></title><p>last marker</p></section>",
                "<body name=\"notes\"><section id=\"note\"><title><p>Dipnot</p></title><p>footnote needle</p></section></body>")
            val book = add(xml.toByteArray())
            val otherXml = original("<section><p>dependency injection second</p></section>")
            val other = add(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + otherXml.toByteArray(Charsets.UTF_16LE))
            val progress = ReadingProgressEntity(book.id, book.sha256, 1, "real opaque locator", "Text/b1-p1.xhtml", 123L)
            main.reading().save(progress)
            val doc = extractor.extract(book)
            assertEquals(4, doc.resourceCount)
            assertEquals(listOf("Önsöz", "Bir\ndependency injection 🌿\nAlt\nA & B", "Son\nlast marker", "Dipnot\nfootnote needle"), doc.chunks.map { it.text })
            assertEquals(listOf("Text/b1-p1.xhtml", "Text/b1-p2.xhtml", "Text/b1-p3.xhtml", "Text/b2-p1.xhtml"), doc.chunks.map { it.resourceHref })
            assertEquals(hash(doc.chunks[1].text.toByteArray()), doc.chunks[1].resourceSha256)
            index.rebuild {}
            val result = index.observe("DEPENDENCY injection").first()
            assertEquals(setOf(book.id, other.id), result.hits.map { it.book.id }.toSet())
            val hit = result.hits.single { it.book.id == book.id }
            assertEquals(BookFormat.FB2, hit.target.sourceFormat); assertNull(hit.target.pdfPageIndex)
            assertEquals("Text/b1-p2.xhtml", hit.target.resourceHref); assertTrue(hit.snippet.contains("dependency"))
            assertEquals(hit, index.resolve(hit))
            assertEquals("Text/b2-p1.xhtml", index.observeBook("footnote", BookTextScope(book.id, book.sha256)).first().hits.single().target.resourceHref)
            cache.close(); cache = Room.databaseBuilder(context, TextIndexDatabase::class.java, cacheName).build()
            assertEquals(hit, index.observeBook("dependency injection", BookTextScope(book.id, book.sha256)).first().hits.single())
            assertEquals(progress, main.reading().resume(book.id, book.sha256)); assertTrue(main.reading().observed(book.id, book.sha256).isEmpty())
            assertTrue(supportsBookSourceRetrieval(BookFormat.FB2))
            val sources = index.prepare(BookTextScope(book.id, book.sha256), "dependency")
            assertEquals(hit.target, sources.references.single().hit.target); assertNull(sources.section); assertNull(sources.chapter)
        }
    }
    @Test fun blankImageFreePartsKeepReadingOrderAndChunkEdgesNeverSplitUnicodeOrLoseTheTail() = runBlocking {
        fixture {
            val text = "🌿".repeat(1600) + " final needle"
            val book = add(original("<empty-line/><section><p>$text</p></section>").toByteArray())
            val doc = extractor.extract(book)
            assertEquals(2, doc.resourceCount); assertTrue(doc.chunks.size > 1)
            assertTrue(doc.chunks.all { it.readingOrderIndex == 1 && it.resourceHref == "Text/b1-p2.xhtml" && wellFormedIndexText(it.text) })
            assertTrue(doc.chunks.last().text.endsWith("final needle"))
            assertEquals(1, doc.chunks.map { it.resourceSha256 }.toSet().size)
            assertTrue(validateDocument(book, doc) <= MAX_INDEX_BOOK_STRING_BYTES)
        }
    }
    @Test fun oldUnsupportedFb2CacheRequiresExplicitRebuildAndDoesNotChangeTheCacheSchema() = runBlocking {
        fixture {
            val book = add(original("<section><p>upgrade needle</p></section>").toByteArray())
            cache.index().insertBook(TextIndexBookEntity(book.id, book.sha256, 1, UUID.randomUUID().toString(), LibraryTextBookState.UNSUPPORTED.name, 0, 0, BookFormat.FB2.name))
            assertEquals(LibraryTextBookState.NOT_INDEXED, index.observe("").first().books.single().state)
            assertTrue(index.observe("needle").first().hits.isEmpty())
            index.rebuild {}
            assertEquals(LibraryTextBookState.READY, index.observe("needle").first().books.single().state)
            assertEquals(2, cache.openHelper.readableDatabase.version)
        }
    }
    @Test fun unsupportedXmlChangedBytesAndResourceOrPayloadLimitsNeverPublishAPrefix() = runBlocking {
        fixture {
            val changed = add(original("<section><p>changed needle</p></section>").toByteArray())
            File(files, changed.storageName).appendText(" ")
            val bad = add(original("<section><video/></section>").toByteArray())
            val tooMany = add(original("<section><p>needle</p></section>".repeat(1001)).toByteArray())
            val tooLarge = add(original("<section><p>${"漢".repeat(3_000_000)}</p></section>").toByteArray())
            index.rebuild {}
            val states = index.observe("").first().books.associate { it.book.id to it.state }
            assertEquals(LibraryTextBookState.FAILED, states[changed.id]); assertEquals(LibraryTextBookState.FAILED, states[bad.id])
            assertEquals(LibraryTextBookState.LIMIT, states[tooMany.id]); assertEquals(LibraryTextBookState.LIMIT, states[tooLarge.id])
            assertTrue(index.observe("needle").first().hits.isEmpty())
        }
    }
    @Test fun actualSourceProofRejectsChangedOriginalArtifactAndOwnershipWithoutWritingProgress() = runBlocking {
        fixture {
            val bytes = original("<section><p>first</p><section><p>nested</p></section></section><section><p>proof needle</p></section>").toByteArray()
            val book = add(bytes); index.rebuild {}
            val hit = index.observe("needle").first().hits.single()
            val document = StrictFb2.read(File(files, book.storageName))
            val artifact = temp.newFile("owned.epub"); Fb2Epub.write(artifact, book.sha256, document)
            val originalArtifact = artifact.readBytes()
            var admitted = true
            val proof = Fb2IndexedSource(book, library, VerifiedBookFile(files), artifact, fb2ViewHash(artifact), Mutex(), { check(admitted) })
            assertTrue(proof.verify(hit.target))
            EpubPublicationSource(context.contentResolver).open(artifact).use { opened ->
                val contents = EpubContents(opened.publication.tableOfContents, opened.publication.readingOrder)
                assertEquals("2", contents.entryIdForResource(hit.target.resourceHref))
                assertEquals("Text/b1-p2.xhtml", contents.destination("2")!!.url().removeFragment().toString())
                assertNull(contents.entryIdForResource("Text/b1-p2.xhtml#n3"))
                assertNull(contents.entryIdForResource("../Text/b1-p2.xhtml"))
            }
            for (bad in listOf(hit.target.copy(bookId = "other"), hit.target.copy(resourceSha256 = "b".repeat(64)), hit.target.copy(sourceFormat = BookFormat.EPUB))) assertFalse(proof.verify(bad))
            admitted = false; assertFalse(proof.verify(hit.target)); admitted = true
            artifact.writeText("changed"); assertFalse(proof.verify(hit.target)); artifact.writeBytes(originalArtifact)
            assertTrue(library.updateMetadata(book.id, book.sha256, "Edited title", "Edited writer")); assertTrue(proof.verify(hit.target))
            File(files, book.storageName).writeBytes(bytes + byteArrayOf(32)); assertFalse(proof.verify(hit.target))
            File(files, book.storageName).writeBytes(bytes)
            main.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id=?", arrayOf(book.id))
            assertFalse(proof.verify(hit.target))
            assertNull(main.reading().resume(book.id, book.sha256)); assertTrue(main.reading().observed(book.id, book.sha256).isEmpty())
            assertArrayEquals(originalArtifact, artifact.readBytes())
        }
    }
    @Test fun corruptTypedCacheAddressesAndForeignPageOrFormatCannotBeReturnedAsFb2Hits() = runBlocking {
        fixture {
            val bytes = original("<section><p>cache needle</p></section>").toByteArray()
            val book = add(bytes)
            for (change in listOf("resourceHref='../Text/b1-p1.xhtml'", "pdfPageIndex=0", "sourceFormat='EPUB'", "readingOrderIndex=1000")) {
                index.rebuild {}
                cache.openHelper.writableDatabase.execSQL("UPDATE text_index_chunks SET $change WHERE bookId=?", arrayOf(book.id))
                assertEquals(LibraryTextIndexFailure.SOURCE, (runCatching { index.observe("needle").first() }.exceptionOrNull() as LibraryTextIndexException).reason)
            }
            assertArrayEquals(bytes, File(files, book.storageName).readBytes())
            assertNull(main.reading().resume(book.id, book.sha256))
        }
    }
    @Test fun callerCancellationKeepsThePreviousFtsGenerationAndNativeLoaderExposesOnlyCurrentProof() = runBlocking {
        fixture {
            val book = add(original("<section><p>retained needle</p></section>").toByteArray())
            index.rebuild {}; val hit = index.observe("needle").first().hits.single()
            val entered = CompletableDeferred<Unit>()
            val waiting = object : BookTextExtractor {
                override fun supports(book: Book) = true
                override suspend fun extract(book: Book): ExtractedBookText { entered.complete(Unit); awaitCancellation() }
            }
            val job = async { LocalLibraryTextIndex(cache, library, waiting).rebuild {} }; entered.await(); job.cancelAndJoin()
            assertEquals(hit, index.observe("needle").first().hits.single())
            val reading = object : ReadingStateRepository {
                override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
                override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) { fail("Source proof cannot record reading") }
            }
            val session = Fb2ReaderLoader(context, library, reading).open(book.id)
            try {
                val first = session.createSurface(null, false, {}, {})
                assertNotNull(first.indexedChapterNavigator)
                assertEquals("0", first.contentsNavigator!!.entryIdForResource(hit.target.resourceHref))
                assertNull(first.indexedChapterNavigator!!.verifiedChapterHref(hit.target)) // Actual native Fragment is unattached.
                session.createSurface(null, false, {}, {})
                assertNull(first.contentsNavigator!!.entryIdForResource(hit.target.resourceHref))
                session.close(); assertNull(first.indexedChapterNavigator!!.verifiedChapterHref(hit.target))
            } finally { session.close() }
        }
    }
}

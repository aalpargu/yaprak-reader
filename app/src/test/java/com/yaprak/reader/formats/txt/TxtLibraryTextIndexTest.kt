package com.yaprak.reader.formats.txt

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.search.*
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TxtLibraryTextIndexTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private class Fixture(val main: LibraryDatabase, var cache: TextIndexDatabase, val library: RoomLibraryRepository,
        val directory: File, val cacheName: String) {
        val extractor = TxtBookTextExtractor(VerifiedBookFile(directory))
        val service get() = LocalLibraryTextIndex(cache, library, extractor)
        suspend fun add(text: ByteArray, id: String = UUID.randomUUID().toString()): Book {
            val file = File(directory, "$id.txt").apply { writeBytes(text) }
            val hash = MessageDigest.getInstance("SHA-256").digest(text).joinToString("") { "%02x".format(it) }
            return Book(id, "Kitap $id", "Yazar", BookFormat.TXT, file.name, file.name, file.length(), hash, 1)
                .also { library.add(it) }
        }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val main = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        // Native Windows SQLite cannot open the long method-named Robolectric context path.
        // Keep a real file-backed database in this fixture's short owned directory.
        val name = File(temp.newFolder(), "i.db").absolutePath
        val directory = File(context.cacheDir, "original-${UUID.randomUUID()}").apply { mkdirs() }
        val cache = Room.databaseBuilder(context, TextIndexDatabase::class.java, name).build()
        val fixture = Fixture(main, cache, RoomLibraryRepository(main.books()), directory, name)
        try { fixture.block() } finally {
            fixture.cache.close(); main.close(); context.deleteDatabase(name)
            directory.listFiles().orEmpty().forEach { it.delete() }; directory.delete()
        }
    }
    @Test fun fullUtf16AndUtf8OriginalsAreSearchableTogetherWithCanonicalContextAndStableReopenedFTS() = runBlocking {
        fixture {
            val text = "first ".repeat(11_000) + "İstanbul dependency injection 🌿\r\n<script>literal</script>"
            val book = add(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE), "first")
            val second = add("İstanbul dependency injection second".toByteArray(), "second")
            val progress = ReadingProgressEntity(book.id, book.sha256, 1, "opaque actual locator", "Text/part0001.xhtml", 123L)
            main.reading().save(progress)
            service.rebuild {}
            val all = service.observe("DEPENDENCY injection").first()
            assertEquals(setOf(book.id, second.id), all.hits.map { it.book.id }.toSet())
            assertEquals(2, all.totalMatches)
            val hit = all.hits.single { it.book.id == book.id }
            val part = StrictTxt.read(File(directory, book.storageName)).last()
            assertEquals(BookFormat.TXT, hit.target.sourceFormat); assertEquals(part.href, hit.target.resourceHref)
            assertEquals(part.sha256, hit.target.resourceSha256); assertEquals(part.title, hit.chapterTitle)
            assertEquals(part.index, hit.readingOrderIndex); assertNull(hit.target.pdfPageIndex)
            assertTrue(hit.text.contains("<script>literal</script>")); assertTrue(hit.snippet.contains("İstanbul"))
            assertFalse(hit.text.contains('\r')); assertEquals(hit, service.resolve(hit))
            assertEquals(2, service.observe("istanbul").first().totalMatches)
            assertEquals(0, service.observe("dependency missing").first().totalMatches)
            assertEquals(1, service.observeBook("injection", BookTextScope(second.id, second.sha256)).first().totalMatches)
            cache.close(); cache = Room.databaseBuilder(context, TextIndexDatabase::class.java, cacheName).build()
            assertEquals(hit, service.observeBook("dependency injection", BookTextScope(book.id, book.sha256)).first().hits.single())
            assertEquals(progress, main.reading().resume(book.id, book.sha256))
            assertTrue(main.reading().observed(book.id, book.sha256).isEmpty())
        }
    }
    @Test fun fullCanonicalPartHashesDoNotBecomeXhtmlHashesAndBlankPartsRetainTheirRealReadingOrder() = runBlocking {
        fixture {
            val book = add((" ".repeat(TXT_PART_CHARACTERS) + "🌿".repeat(1100) + " end marker").toByteArray())
            val document = extractor.extract(book); val parts = StrictTxt.read(File(directory, book.storageName))
            assertEquals(2, document.resourceCount); assertEquals(BookFormat.TXT, document.sourceFormat)
            assertEquals(setOf(1), document.chunks.map { it.readingOrderIndex }.toSet())
            assertTrue(document.chunks.size > 1)
            assertTrue(document.chunks.all { it.resourceSha256 == parts.last().sha256 && it.chapterTitle == "Metin 2" &&
                wellFormedIndexText(it.text) && it.pdfPageIndex == null })
            assertEquals(parts.last().text.take(MAX_INDEX_CHUNK_TEXT), document.chunks.first().text)
            assertTrue(document.chunks.last().text.endsWith("end marker"))
            assertTrue(validateDocument(book, document) <= MAX_INDEX_BOOK_STRING_BYTES)
        }
    }
    @Test fun olderUnsupportedTxtLedgerBecomesNotIndexedAndExplicitRebuildPublishesACompleteGeneration() = runBlocking {
        fixture {
            val book = add("upgrade searchable original".toByteArray())
            cache.index().insertBook(TextIndexBookEntity(book.id, book.sha256, 1, UUID.randomUUID().toString(),
                LibraryTextBookState.UNSUPPORTED.name, 0, 0, BookFormat.TXT.name))
            assertEquals(LibraryTextBookState.NOT_INDEXED, service.observe("").first().books.single().state)
            assertEquals(0, service.observe("searchable").first().totalMatches)
            service.rebuild {}
            assertEquals(LibraryTextBookState.READY, service.observe("searchable").first().books.single().state)
            assertEquals(1, service.observe("searchable").first().totalMatches)
        }
    }
    @Test fun malformedAndChangedOriginalsAndIndexPayloadLimitNeverPublishAPrefixAsReady() = runBlocking {
        fixture {
            val changed = add("source changed".toByteArray(), "changed")
            File(directory, changed.storageName).writeText("source replaced")
            val malformed = add(byteArrayOf(0xc3.toByte(), 0x28), "malformed")
            val huge = add("漢".repeat(MAX_TXT_CHARACTERS).toByteArray(), "huge")
            val failure = runCatching { extractor.extract(huge) }.exceptionOrNull() as TextExtractionException
            assertEquals(TextExtractionFailure.LIMIT, failure.reason)
            service.rebuild {}
            val statuses = service.observe("").first().books.associate { it.book.id to it.state }
            assertEquals(LibraryTextBookState.FAILED, statuses[changed.id]); assertEquals(LibraryTextBookState.FAILED, statuses[malformed.id])
            assertEquals(LibraryTextBookState.LIMIT, statuses[huge.id])
            assertTrue(service.observe("漢 source").first().hits.isEmpty())
            assertEquals(0, cache.openHelper.readableDatabase.query("SELECT COUNT(*) FROM text_index_chunks").use { it.moveToFirst(); it.getInt(0) })
        }
    }
    @Test fun corruptedTypedPartOrderTitleAndForeignPageAreRefusedInStoredHitsAndExtractedDocuments() = runBlocking {
        fixture {
            val book = add("corruption needle".toByteArray())
            val doc = extractor.extract(book); val chunk = doc.chunks.single()
            for (bad in listOf(chunk.copy(resourceHref = "Text/part0002.xhtml"), chunk.copy(readingOrderIndex = 1),
                chunk.copy(chapterTitle = "Invented chapter"), chunk.copy(pdfPageIndex = 0))) {
                assertEquals(TextExtractionFailure.INVALID,
                    (runCatching { validateDocument(book, ExtractedBookText(book.id, book.sha256, listOf(bad), 2, sourceFormat = BookFormat.TXT)) }
                        .exceptionOrNull() as TextExtractionException).reason)
            }
            for (change in listOf("resourceHref='Text/part0002.xhtml'", "readingOrderIndex=1", "chapterTitle='Forged'", "pdfPageIndex=0")) {
                service.rebuild {}
                cache.openHelper.writableDatabase.execSQL("UPDATE text_index_chunks SET $change WHERE bookId=?", arrayOf(book.id))
                assertEquals(LibraryTextIndexFailure.SOURCE,
                    (runCatching { service.observe("needle").first() }.exceptionOrNull() as LibraryTextIndexException).reason)
            }
        }
    }
    @Test fun canceledTxtRebuildKeepsPreviousGenerationAndQuestionSourcesRetainOriginalTxtParts() = runBlocking {
        fixture {
            val book = add("retained generation needle".toByteArray())
            service.rebuild {}; val hit = service.observe("needle").first().hits.single()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val waiting = object : BookTextExtractor {
                override fun supports(book: Book) = extractor.supports(book)
                override suspend fun extract(book: Book): ExtractedBookText { entered.complete(Unit); release.await(); return extractor.extract(book) }
            }
            val rebuilding = async { LocalLibraryTextIndex(cache, library, waiting).rebuild {} }
            entered.await(); rebuilding.cancelAndJoin()
            assertEquals(hit, service.observe("needle").first().hits.single())
            assertEquals(hit, service.resolve(hit))
            val sources = service.prepare(BookTextScope(book.id, book.sha256), "needle")
            assertEquals(listOf(hit), sources.references.map { it.hit })
            val request = com.yaprak.reader.domain.assistant.BookQuestionRequest.create(sources, "Bu kavramı açıkla")
            assertEquals(BookFormat.TXT, request.sources.references.single().hit.target.sourceFormat)
            assertNull(request.sources.section); assertNull(request.sources.chapter)
            assertEquals(TextExtractionFailure.UNSUPPORTED,
                (runCatching { extractor.extractChapters(book) }.exceptionOrNull() as TextExtractionException).reason)
        }
    }
}

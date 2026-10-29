package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.search.*
import com.yaprak.reader.domain.assistant.BookQuestionRequest
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.TestChapterBooks
import com.yaprak.reader.formats.epub.EpubBookTextExtractor
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LocalBookSourceChapterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private suspend fun fixture(length: Int = 2500, nested: Boolean = false, nav: String? = null,
        block: suspend (Book, File, LibraryDatabase, TextIndexDatabase, RoomLibraryRepository, LocalLibraryTextIndex) -> Unit) {
        val main = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val cache = Room.inMemoryDatabaseBuilder(context, TextIndexDatabase::class.java).build()
        val directory = File(context.filesDir, "books").apply { mkdirs() }
        val file = File(directory, "toc-" + UUID.randomUUID() + ".epub")
        try {
            TestChapterBooks.epub(file, length, nested, nav)
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val book = Book("toc-book", "TOC book", null, BookFormat.EPUB, "toc.epub", file.name, file.length(), hash, 1)
            val library = RoomLibraryRepository(main.books()); library.add(book)
            val service = LocalLibraryTextIndex(cache, library, EpubBookTextExtractor(context))
            service.rebuild {}
            block(book, file, main, cache, library, service)
        } finally { cache.close(); main.close(); if (file.exists()) assertTrue(file.delete()) }
    }
    private suspend fun failure(reason: BookSourceFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected chapter failure") }
        catch (failed: BookSourceException) { assertEquals(reason, failed.reason) }
    }
    private fun text(sources: PreparedBookSources) = sources.references.joinToString("") { it.text }

    @Test fun actualTocSeparatesSameFileHeadingsAndSpansFilesWithoutWritingProgressOrIndex() = runBlocking {
        fixture { book, _, main, cache, _, service ->
            val scope = BookTextScope(book.id, book.sha256)
            val ledger = cache.index().books()
            val progress = ReadingProgressEntity(book.id, book.sha256, 1, "{}", "EPUB/chapter0.xhtml", 123)
            main.reading().save(progress)
            val chapters = service.listChapters(scope)
            assertEquals(listOf("First chapter", "Later chapter", "Last chapter"), chapters.map { it.title })
            try { (chapters as MutableList<*>).clear(); fail("Mutable TOC") } catch (_: UnsupportedOperationException) {}
            val first = service.prepareChapter(scope, chapters[0])
            val later = service.prepareChapter(scope, chapters[1])
            val last = service.prepareChapter(scope, chapters[2])
            assertEquals("FIRST\n" + "a".repeat(2500) + "\n", text(first))
            assertEquals("LATER ONLY 🌿\nEXCLUSIVE LATER CONTENT\nBRIDGE CONTENT\n", text(later))
            assertEquals("LAST ONLY\nLAST CHAPTER CONTENT\n", text(last))
            assertEquals(listOf(0, 1), later.references.map { it.hit.readingOrderIndex })
            assertTrue(later.references.first().hit.text.contains("aaa"))
            assertFalse(later.references.first().text.contains("aaa"))
            assertTrue(last.references.first().hit.text.contains("BRIDGE"))
            assertFalse(last.references.first().text.contains("BRIDGE"))
            for (sources in listOf(first, later, last)) {
                BookQuestionRequest.create(sources, "Özetle")
                assertFalse(sources.truncated); assertNull(sources.section); assertNotNull(sources.chapter)
                assertTrue(sources.references.all { service.resolve(it.hit) == it.hit })
            }
            assertNotEquals(later.requestId, service.prepareChapter(scope, chapters[1]).requestId)
            assertEquals(ledger, cache.index().books()); assertEquals(progress, main.reading().resume(book.id, book.sha256))
        }
    }

    @Test fun actualNestedParentIncludesChildSourcesButNotTheNextParent() = runBlocking {
        fixture(nested = true) { book, _, main, _, _, service ->
            val scope = BookTextScope(book.id, book.sha256)
            val chapters = service.listChapters(scope)
            assertEquals(listOf(0, 1, 1, 0), chapters.map { it.depth })
            val parent = text(service.prepareChapter(scope, chapters[0]))
            assertTrue(parent.contains("FIRST")); assertTrue(parent.contains("LATER ONLY")); assertTrue(parent.contains("BRIDGE"))
            assertFalse(parent.contains("LAST ONLY"))
            assertEquals("FIRST\n" + "a".repeat(2500) + "\n", text(service.prepareChapter(scope, chapters[1])))
            assertNull(main.reading().resume(book.id, book.sha256))
        }
    }

    @Test fun largeChapterRefusesPartialSuccessWhileTheSmallLaterChapterCanStillBePrepared() = runBlocking {
        fixture(length = 115000) { book, _, main, cache, _, service ->
            val scope = BookTextScope(book.id, book.sha256)
            val chapters = service.listChapters(scope); val ledger = cache.index().books()
            assertTrue(chapters.first().sourceCount > 64)
            failure(BookSourceFailure.LIMIT) { service.prepareChapter(scope, chapters.first()) }
            val later = service.prepareChapter(scope, chapters[1])
            assertEquals("LATER ONLY 🌿\nEXCLUSIVE LATER CONTENT\nBRIDGE CONTENT\n", text(later))
            assertTrue(later.references.size <= 2); BookQuestionRequest.create(later, "Özetle")
            assertEquals(ledger, cache.index().books()); assertNull(main.reading().resume(book.id, book.sha256))
        }
    }

    @Test fun generationForgedExcerptSourceMetadataAndChangedActualFileCannotRetargetSelection() = runBlocking {
        fixture { book, file, main, cache, library, service ->
            val scope = BookTextScope(book.id, book.sha256)
            var chapter = service.listChapters(scope)[1]
            for (wrong in listOf(chapter.copy(tocSha256 = "d".repeat(64)),
                chapter.copy(start = chapter.start.copy(textOffset = chapter.start.textOffset + 1)),
                chapter.copy(sourceCount = chapter.sourceCount + 1))) {
                failure(BookSourceFailure.SOURCE) { service.prepareChapter(scope, wrong) }
            }
            service.rebuild {}
            failure(BookSourceFailure.SOURCE) { service.prepareChapter(scope, chapter) }
            chapter = service.listChapters(scope)[1]
            val prepared = service.prepareChapter(scope, chapter)
            cache.openHelper.writableDatabase.execSQL(
                "UPDATE text_index_chunks SET text = ?, searchText = ? WHERE rowid = ?",
                arrayOf<Any>("forged chapter text", "forged chapter text", prepared.references.last().hit.id))
            failure(BookSourceFailure.SOURCE) { service.prepareChapter(scope, chapter) }
            service.rebuild {}; chapter = service.listChapters(scope)[1]
            val original = EpubBookTextExtractor(context)
            val changing = LocalLibraryTextIndex(cache, library, object : BookTextExtractor {
                override suspend fun extract(book: Book) = original.extract(book)
                override suspend fun extractChapters(book: Book): ExtractedBookChapters {
                    val result = original.extractChapters(book)
                    library.updateMetadata(book.id, book.sha256, "Renamed", null)
                    return result
                }
            })
            failure(BookSourceFailure.SOURCE) { changing.prepareChapter(scope, chapter) }
            RandomAccessFile(file, "rw").use { stored -> val first = stored.readByte(); stored.seek(0); stored.writeByte(first.toInt() xor 1) }
            failure(BookSourceFailure.SOURCE) { service.listChapters(scope) }
            assertNull(main.reading().resume(book.id, book.sha256))
        }
    }

    @Test fun missingAnchorRefusesChapterInferenceAndLeavesCompleteFileAndKeywordModesAvailable() = runBlocking {
        fixture(nav = "<li><a href='chapter0.xhtml#missing'>Missing</a></li>") { book, _, main, _, _, service ->
            val scope = BookTextScope(book.id, book.sha256)
            failure(BookSourceFailure.UNSUPPORTED) { service.listChapters(scope) }
            val files = service.listSections(scope)
            assertEquals(2, files.size)
            BookQuestionRequest.create(service.prepareSection(scope, files.first()), "Özetle")
            assertTrue(service.prepare(scope, "LATER").references.isNotEmpty())
            assertNull(main.reading().resume(book.id, book.sha256))
        }
    }

    @Test fun chapterExtractionRunsOutsideTheCacheLeaseAndCancellationCannotPublishLateEvidence() = runBlocking {
        fixture { book, _, _, cache, library, service ->
            val scope = BookTextScope(book.id, book.sha256)
            val chapter = service.listChapters(scope)[1]
            val actual = EpubBookTextExtractor(context); val evidence = actual.extractChapters(book)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val mutex = Mutex()
            val wrapper = object : BookTextExtractor {
                override suspend fun extract(book: Book) = actual.extract(book)
                override suspend fun extractChapters(book: Book): ExtractedBookChapters {
                    assertFalse(mutex.tryLock())
                    cache.index().books() // The outer operation has released its cache reader.
                    entered.complete(Unit); withContext(NonCancellable) { release.await() }
                    return evidence
                }
            }
            val controlled = LocalLibraryTextIndex(cache, library, wrapper, fileMutex = mutex)
            val caller = async { controlled.prepareChapter(scope, chapter) }
            entered.await(); caller.cancel(); release.complete(Unit)
            try { caller.await(); fail("Late chapter evidence") } catch (_: CancellationException) {}
            val denied = LocalLibraryTextIndex(cache, library, wrapper, validateRead = {
                throw LibraryTextIndexException(LibraryTextIndexFailure.RECOVERY)
            })
            failure(BookSourceFailure.RECOVERY) { denied.listChapters(scope) }
        }
    }
}

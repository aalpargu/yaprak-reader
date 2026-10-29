package com.yaprak.reader.formats.fb2

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.assistant.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.search.*
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Fb2AssistantSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun original(body: String) = "<FictionBook xmlns=\"$FB2_NAMESPACE\"><description><title-info><book-title>Original metadata</book-title><lang>tr</lang></title-info></description><body>$body</body></FictionBook>".toByteArray()
    private inner class Fixture(val database: LibraryDatabase, val cache: TextIndexDatabase) {
        val library = RoomLibraryRepository(database.books())
        val directory = File(context.filesDir, "books").apply { mkdirs() }
        val owned = mutableListOf<File>()
        val extractor = Fb2BookTextExtractor(VerifiedBookFile(directory))
        val index = LocalLibraryTextIndex(cache, library, extractor)
        suspend fun add(bytes: ByteArray): Book {
            val id = UUID.randomUUID().toString()
            val file = File(directory, "$id.fb2").apply { writeBytes(bytes); owned += this }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return Book(id, "Private library title", "Private author", BookFormat.FB2, file.name, file.name, file.length(), hash, 1)
                .also { library.add(it) }
        }
        fun scope(book: Book) = BookTextScope(book.id, book.sha256)
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val cache = Room.databaseBuilder(context, TextIndexDatabase::class.java, File(temporary.newFolder(), "i.db").absolutePath).build()
        val f = Fixture(database, cache)
        try { f.block() } finally { cache.close(); database.close(); f.owned.forEach { it.delete() } }
    }
    @Test fun realOriginalKeywordSourcesSerializeOnlyExplicitQuestionAndTextAndBindExactAnswerQuotes() = runBlocking {
        fixture {
            val bytes = original("<section><title><p>Başlık</p></title><p>Güneş doğdu.</p></section>")
            val book = add(bytes); index.rebuild {}
            val sources = index.prepare(scope(book), "Güneş")
            assertEquals(BookFormat.FB2, sources.references.single().hit.target.sourceFormat)
            assertNull(sources.section); assertNull(sources.chapter)
            val request = BookQuestionRequest.create(sources, "Kaynağı açıkla")
            val wire = JSONObject(String(bookQuestionBytes(request), Charsets.UTF_8))
            assertEquals(setOf("request_id", "question", "sources"), wire.keys().asSequence().toSet())
            val sent = wire.getJSONArray("sources").getJSONObject(0)
            assertEquals(setOf("id", "text"), sent.keys().asSequence().toSet())
            assertEquals(sources.references.single().text, sent.getString("text"))
            val response = """{"request_id":"${sources.requestId}","status":"answered","statements":[{"text":"Kaynağın açıklaması","citations":[{"source":"S1","quote":"Güneş doğdu."}]}],"message":""}"""
            val answer = parseBookAnswer(request, response.toByteArray())
            assertSame(sources.references.single(), answer.statements.single().citations.single().source)
            assertEquals("Güneş doğdu.", answer.statements.single().citations.single().quote)
            for (bad in listOf(response.replace("S1", "S99"), response.replace("Güneş doğdu.", "güneş doğdu.")))
                assertEquals(BookAnswerFailure.INVALID_RESPONSE, (runCatching { parseBookAnswer(request, bad.toByteArray()) }.exceptionOrNull() as BookAnswerException).reason)
            assertArrayEquals(bytes, File(directory, book.storageName).readBytes())
            assertNull(database.reading().resume(book.id, book.sha256)); assertTrue(database.reading().observed(book.id, book.sha256).isEmpty())
        }
    }
    @Test fun completePartIncludesItsLastOriginalChunkAndOriginalTitleWithoutPullingAnotherPart() = runBlocking {
        fixture {
            val book = add(original("<section><p>unrelated prefix</p></section><section><title><p>Seçilen özgün başlık</p></title><p>${"metin ".repeat(3500)}last needle</p></section>"))
            index.rebuild {}
            val catalog = index.listSections(scope(book))
            assertEquals(2, catalog.size)
            val chosen = catalog.last()
            assertEquals(BookFormat.FB2, chosen.sourceFormat); assertEquals("Seçilen özgün başlık", chosen.chapterTitle)
            assertTrue(chosen.chunkCount > MAX_BOOK_SOURCE_REFERENCES); assertTrue(chosen.fitsSingleRequest)
            val sources = index.prepareSection(scope(book), chosen)
            assertEquals(chosen.chunkCount, sources.references.size); assertEquals(chosen, sources.section); assertNull(sources.chapter)
            assertTrue(sources.references.last().text.endsWith("last needle"))
            assertTrue(sources.references.all { it.hit.readingOrderIndex == 1 && !it.text.contains("unrelated prefix") && it.excerpt == null })
            val request = BookQuestionRequest.create(sources, BookStudyTask.SUMMARY.questionForSection(BookFormat.FB2))
            assertTrue(bookQuestionBytes(request).size <= MAX_COMPLETE_BOOK_REQUEST_BYTES)
            index.rebuild {}
            assertEquals(BookSourceFailure.SOURCE, (runCatching { index.prepareSection(scope(book), chosen) }.exceptionOrNull() as BookSourceException).reason)
        }
    }
    @Test fun oversizedCompletePartIsAnExplicitLimitAndKeywordSourcesRemainAvailable() = runBlocking {
        fixture {
            val book = add(original("<section><p>${"x".repeat(114_000)} final needle</p></section>"))
            index.rebuild {}
            val section = index.listSections(scope(book)).single()
            assertTrue(section.chunkCount > MAX_COMPLETE_BOOK_SOURCE_REFERENCES); assertFalse(section.fitsSingleRequest)
            assertEquals(BookSourceFailure.LIMIT, (runCatching { index.prepareSection(scope(book), section) }.exceptionOrNull() as BookSourceException).reason)
            val keyword = index.prepare(scope(book), "needle")
            assertEquals(1, keyword.references.size); assertNull(keyword.section)
            assertNotNull(BookQuestionRequest.create(keyword, "Açıkla"))
        }
    }
    @Test fun changedOriginalAndForgedCachedTextCannotBecomeAssistantEvidence() = runBlocking {
        fixture {
            val bytes = original("<section><p>source needle</p></section>")
            val book = add(bytes); index.rebuild {}
            assertTrue(library.updateMetadata(book.id, book.sha256, "Edited private title", null))
            assertEquals("Edited private title", index.prepare(scope(book), "needle").references.single().hit.book.title)
            cache.openHelper.writableDatabase.execSQL("UPDATE text_index_chunks SET text='forged source',searchText='forged source' WHERE bookId=?", arrayOf(book.id))
            assertEquals(BookSourceFailure.SOURCE, (runCatching { index.prepare(scope(book), "forged") }.exceptionOrNull() as BookSourceException).reason)
            index.rebuild {}
            File(directory, book.storageName).appendText(" ")
            assertEquals(BookSourceFailure.SOURCE, (runCatching { index.prepare(scope(book), "needle") }.exceptionOrNull() as BookSourceException).reason)
            assertNull(database.reading().resume(book.id, book.sha256))
        }
    }
    @Test fun canceledFreshSourceValidationPropagatesWithoutChangingTheIndexedGeneration() = runBlocking {
        fixture {
            val book = add(original("<section><p>source needle</p></section>")); index.rebuild {}
            val before = index.observe("needle").first().hits.single()
            val entered = CompletableDeferred<Unit>()
            val waiting = object : BookTextExtractor by extractor {
                override suspend fun extract(book: Book): ExtractedBookText { entered.complete(Unit); awaitCancellation() }
            }
            val pending = async { LocalLibraryTextIndex(cache, library, waiting).prepare(scope(book), "needle") }
            entered.await(); pending.cancelAndJoin(); assertTrue(pending.isCancelled)
            assertEquals(before, index.observe("needle").first().hits.single())
            assertNull(database.reading().resume(book.id, book.sha256))
        }
    }
    @Test fun noMatchingOriginalTextCannotCreateAnAnsweredRequestOrInferAReadPrefix() = runBlocking {
        fixture {
            val book = add(original("<section><p>source needle</p></section>")); index.rebuild {}
            val empty = index.prepare(scope(book), "missing")
            assertTrue(empty.references.isEmpty()); assertEquals(0, empty.totalMatches)
            assertNull(empty.section); assertNull(empty.chapter)
            assertEquals(BookAnswerFailure.SOURCE, (runCatching { BookQuestionRequest.create(empty, "Ne oldu?") }.exceptionOrNull() as BookAnswerException).reason)
        }
    }
}

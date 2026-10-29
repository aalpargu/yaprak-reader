package com.yaprak.reader.formats.epub

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.domain.assistant.BookQuestionRequest
import com.yaprak.reader.fixtures.TestChapterBooks
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class EpubPrefixQueryExtractionTest {
    private suspend fun fixture(length: Int=2500,block: suspend (Book,ExtractedBookChapters)->Unit) {
        val parent=ApplicationProvider.getApplicationContext<Context>()
        val root=File(parent.cacheDir,"owned-prefix-query-"+UUID.randomUUID()).apply {check(mkdirs())}
        val context=object: ContextWrapper(parent) {override fun getFilesDir()=root}
        val file=File(File(root,"books").apply {check(mkdirs())},"owned.epub")
        try {
            TestChapterBooks.epub(file,length)
            val original=file.readBytes()
            val hash=MessageDigest.getInstance("SHA-256").digest(original).joinToString("") {"%02x".format(it)}
            val book=Book("owned-prefix-query","Owned",null,BookFormat.EPUB,"owned.epub",file.name,file.length(),hash,1)
            block(book,EpubBookTextExtractor(context).extractChapters(book))
            assertArrayEquals(original,file.readBytes())
        } finally {check(root.canonicalFile.parentFile==parent.cacheDir.canonicalFile);check(root.deleteRecursively())}
    }
    private fun prepare(book: Book,evidence: ExtractedBookChapters,query: String): PreparedBookSources {
        val selected=evidence.chapters.first();val generation=UUID.randomUUID().toString()
        val scope=BookTextScope(book.id,book.sha256)
        val chapter=BookSourceChapter(scope,generation,selected.tocSha256,selected.tocIndex,selected.title,selected.depth,
            selected.start,selected.end,selected.sourceCount)
        val first=evidence.document.chunks.first()
        val start=BookChapterBoundary(first.readingOrderIndex,first.resourceHref,first.resourceSha256,0)
        val plan=extractedPrefixQueryPieces(evidence,start,chapter.end,query)
        val refs=plan.pieces.mapIndexed {i,piece->
            val chunk=evidence.document.chunks[piece.chunkIndex]
            BookSourceReference("S"+(i+1),LibraryTextHit(i+1L,generation,book,
                LibraryTextTarget(book.id,book.sha256,chunk.resourceHref,chunk.resourceSha256),chunk.chapterTitle,
                chunk.text,"",chunk.readingOrderIndex,chunk.chunkIndex),piece.excerpt)
        }
        return PreparedBookSources(UUID.randomUUID().toString(),scope,query,plan.totalMatches,refs,
            prefixQuery=BookSourcePrefixQuery(chapter,start,plan.candidateCount))
    }
    @Test fun actualOriginalSameFileAndCrossFileFutureKeywordsAreExcludedBeforeMatching()=runBlocking {
        fixture {book,evidence->
            assertTrue(evidence.document.chunks.any {it.text.contains("LATER")})
            assertTrue(evidence.document.chunks.any {it.text.contains("LAST")})
            for(query in listOf("LATER","LAST")) {
                val empty=prepare(book,evidence,query)
                assertEquals(0,empty.totalMatches);assertTrue(empty.references.isEmpty());assertTrue(validPrefixQuerySources(empty))
            }
            val admitted=prepare(book,evidence,"aaa");BookQuestionRequest.create(admitted,"Soru")
            assertTrue(admitted.references.isNotEmpty())
            assertTrue(admitted.references.any {it.hit.text.contains("LATER")})
            assertFalse(admitted.references.any {it.text.contains("LATER") || it.text.contains("LAST")})
        }
    }
    @Test fun actualOriginalPrefixAboveCompleteLimitStillProvidesEightBoundedMatchingWindows()=runBlocking {
        fixture(130_000) {book,evidence->
            val admitted=prepare(book,evidence,"aaa")
            assertTrue(admitted.prefixQuery!!.candidateCount>64)
            assertTrue(admitted.totalMatches>64);assertEquals(8,admitted.references.size);assertTrue(admitted.truncated)
            BookQuestionRequest.create(admitted,"Bu alıntılara göre ne var?")
            assertTrue(admitted.references.all {it.text.length<=MAX_INDEX_CHUNK_TEXT})
            assertFalse(admitted.references.any {it.text.contains("LATER") || it.text.contains("LAST")})
            assertNull(admitted.prefix);assertNull(admitted.chapter);assertNull(admitted.section)
        }
    }
}

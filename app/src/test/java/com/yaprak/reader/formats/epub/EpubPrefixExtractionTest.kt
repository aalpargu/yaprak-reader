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
class EpubPrefixExtractionTest {
    private suspend fun fixture(nav: String?=null,block: suspend (Book,ExtractedBookChapters)->Unit) {
        val parent=ApplicationProvider.getApplicationContext<Context>()
        val root=File(parent.cacheDir,"owned-prefix-"+UUID.randomUUID()).apply { check(mkdirs()) }
        val context=object: ContextWrapper(parent) { override fun getFilesDir()=root }
        val file=File(File(root,"books").apply { check(mkdirs()) },"owned.epub")
        try {
            TestChapterBooks.epub(file,2500,navOverride=nav)
            val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val book=Book("owned-prefix","Owned",null,BookFormat.EPUB,"owned.epub",file.name,file.length(),hash,1)
            block(book,EpubBookTextExtractor(context).extractChapters(book))
        } finally { check(root.canonicalFile.parentFile==parent.cacheDir.canonicalFile);check(root.deleteRecursively()) }
    }
    private fun sources(book: Book,evidence: ExtractedBookChapters,index: Int): PreparedBookSources {
        val chosen=evidence.chapters[index];val generation=UUID.randomUUID().toString()
        val scope=BookTextScope(book.id,book.sha256)
        val chapter=BookSourceChapter(scope,generation,chosen.tocSha256,chosen.tocIndex,chosen.title,chosen.depth,chosen.start,chosen.end,chosen.sourceCount)
        val first=evidence.document.chunks.first()
        val start=BookChapterBoundary(first.readingOrderIndex,first.resourceHref,first.resourceSha256,0)
        val pieces=extractedChapterPieces(evidence,start,chapter.end)
        val refs=pieces.mapIndexed { i,piece->
            val chunk=evidence.document.chunks[piece.chunkIndex]
            BookSourceReference("S"+(i+1),LibraryTextHit(i+1L,generation,book,
                LibraryTextTarget(book.id,book.sha256,chunk.resourceHref,chunk.resourceSha256),chunk.chapterTitle,chunk.text,
                "",chunk.readingOrderIndex,chunk.chunkIndex),piece.excerpt)
        }
        return PreparedBookSources(UUID.randomUUID().toString(),scope,"",refs.size,refs,prefix=BookSourcePrefix(chapter,start,refs.size))
    }
    @Test fun actualOriginalEpubSameFileCutoffAndCrossFilePrefixExcludeFutureEvenInsideCachedChunks()=runBlocking {
        fixture { book,evidence->
            val first=sources(book,evidence,0);BookQuestionRequest.create(first,"Soru")
            assertEquals("FIRST\n"+"a".repeat(2500)+"\n",first.references.joinToString("") { it.text })
            assertFalse(first.references.any { it.text.contains("LATER ONLY") })
            assertTrue(first.references.any { it.hit.text.contains("LATER ONLY") })
            val later=sources(book,evidence,1);BookQuestionRequest.create(later,"Soru")
            assertEquals("FIRST\n"+"a".repeat(2500)+"\nLATER ONLY 🌿\nEXCLUSIVE LATER CONTENT\nBRIDGE CONTENT\n",later.references.joinToString("") { it.text })
            assertTrue(later.references.any { it.hit.text.contains("LAST ONLY") })
            assertFalse(later.references.any { it.text.contains("LAST ONLY") })
            assertEquals(setOf(0,1),later.references.map { it.hit.readingOrderIndex }.toSet())
        }
    }
    @Test fun textBeforeTheFirstActualTocEntryIsStillPartOfExplicitBeginningScope()=runBlocking {
        fixture("<li><a href='chapter0.xhtml#later'>Chosen</a></li><li><a href='chapter1.xhtml#last'>Last</a></li>") { book,evidence->
            val prefix=sources(book,evidence,0);BookQuestionRequest.create(prefix,"Soru")
            assertTrue(prefix.references.joinToString("") { it.text }.startsWith("FIRST\n"))
            assertTrue(prefix.prefix!!.throughChapter.start.textOffset>0)
            assertEquals(0,prefix.prefix!!.start.textOffset)
            assertFalse(prefix.references.any { it.text.contains("LAST ONLY") })
        }
    }
}

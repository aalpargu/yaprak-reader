package com.yaprak.reader.formats.docx

import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.domain.search.MAX_INDEX_RESOURCE_BYTES
import com.yaprak.reader.reader.BookPositionNavigator
import com.yaprak.reader.reader.IndexedChapterNavigator
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Canonical ORIGINAL text plus exact original identity and unchanged opened artifact. */
internal class DocxIndexedSource(private val book: Book,private val library: LibraryRepository,
    private val files: VerifiedBookFile,private val artifact: File,private val artifactHash: String,
    private val fileMutex: Mutex,private val validateRead: suspend ()->Unit,
    private val io: CoroutineDispatcher=Dispatchers.IO) {
    suspend fun verify(target: LibraryTextTarget): Boolean=withContext(io) {
        if(book.format!=BookFormat.DOCX || !target.isValid || target.sourceFormat!=BookFormat.DOCX ||
            target.bookId!=book.id || target.contentSha256!=book.sha256) return@withContext false
        try {
            fileMutex.withLock {
                currentCoroutineContext().ensureActive(); validateRead()
                if(!currentBook()) return@withLock false
                val original=files.resolve(book)
                val actual=docxOriginalText(StrictDocx.read(original))
                if(actual.text.isBlank() || actual.text.toByteArray(Charsets.UTF_8).size>MAX_INDEX_RESOURCE_BYTES) return@withLock false
                if(actual.sha256!=target.resourceSha256 || docxViewHash(artifact)!=artifactHash) return@withLock false
                files.resolve(book)
                currentCoroutineContext().ensureActive()
                currentBook()
            }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { false }
    }
    private suspend fun currentBook()=library.findById(book.id)?.let {
        it.format==BookFormat.DOCX && it.sha256==book.sha256 && it.storageName==book.storageName && it.byteSize==book.byteSize
    }==true
}

/** Opens the complete document at an ACTUAL native first position; no guessed chapter/selection. */
internal class DocxIndexedNavigator(private val book: Book,private val current: ()->Boolean,
    private val verifySource: suspend (LibraryTextTarget)->Boolean,private val positions: BookPositionNavigator): IndexedChapterNavigator {
    private data class Proof(val href: String,val location: ReadingLocation,val owner: Job?)
    private var proof: Proof?=null
    private var generation=0L
    fun invalidate() { proof=null; generation++ }
    override suspend fun verifiedChapterHref(target: LibraryTextTarget): String? {
        val attempt=++generation; proof=null
        val caller=currentCoroutineContext(); caller.ensureActive()
        if(!current() || !target.isValid || target.sourceFormat!=BookFormat.DOCX || book.format!=BookFormat.DOCX ||
            target.bookId!=book.id || target.contentSha256!=book.sha256) return null
        val location=try { positions.resolvePercent(0) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { null }
        caller.ensureActive()
        if(location==null || location.resourceHref!=DOCX_INDEX_HREF || generation!=attempt || !current()) return null
        val verified=try { verifySource(target) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { false }
        caller.ensureActive()
        if(!verified || generation!=attempt || !current()) return null
        proof=Proof(target.resourceHref,location,caller[Job])
        return target.resourceHref
    }
    override fun goToChapter(href: String): Boolean {
        val accepted=proof; proof=null; generation++
        if(accepted==null || href!=accepted.href || !current() || accepted.owner?.isActive==false) return false
        return positions.goTo(accepted.location)
    }
}

package com.yaprak.reader.formats.fb2

import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.reader.ContentsNavigator
import com.yaprak.reader.reader.IndexedChapterNavigator
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Original FB2 hashes plus the unchanged artifact actually opened by Readium. */
internal class Fb2IndexedSource(private val book: Book, private val library: LibraryRepository,
    private val files: VerifiedBookFile, private val artifact: File, private val artifactHash: String,
    private val fileMutex: Mutex, private val validateRead: suspend () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun verify(target: LibraryTextTarget): Boolean = withContext(io) {
        if (book.format != BookFormat.FB2 || !target.isValid || target.sourceFormat != BookFormat.FB2 || target.bookId != book.id ||
            target.contentSha256 != book.sha256) return@withContext false
        try {
            fileMutex.withLock {
                currentCoroutineContext().ensureActive(); validateRead()
                if (!currentBook()) return@withLock false
                val original = files.resolve(book)
                val actual = fb2OriginalText(StrictFb2.read(original)).singleOrNull { it.href == target.resourceHref }
                if (actual?.sha256 != target.resourceSha256 || fb2ViewHash(artifact) != artifactHash) return@withLock false
                files.resolve(book)
                currentCoroutineContext().ensureActive()
                currentBook()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    private suspend fun currentBook() = library.findById(book.id)?.let {
        it.format == BookFormat.FB2 && it.sha256 == book.sha256 && it.storageName == book.storageName && it.byteSize == book.byteSize
    } == true
}

/** Maps a verified original part only to the native publication's real TOC entry. */
internal class Fb2IndexedNavigator(private val book: Book, private val current: () -> Boolean,
    private val verifySource: suspend (LibraryTextTarget) -> Boolean, private val contents: ContentsNavigator,
) : IndexedChapterNavigator {
    private data class Proof(val href: String, val entryId: String, val owner: Job?)
    private var proof: Proof? = null
    private var generation = 0L
    fun invalidate() { proof = null; generation++ }
    override suspend fun verifiedChapterHref(target: LibraryTextTarget): String? {
        val attempt = ++generation
        proof = null
        val caller = currentCoroutineContext(); caller.ensureActive()
        if (!current() || !target.isValid || target.sourceFormat != BookFormat.FB2 || book.format != BookFormat.FB2 ||
            target.bookId != book.id || target.contentSha256 != book.sha256) return null
        val entry = contents.entryIdForResource(target.resourceHref) ?: return null
        if (contents.contents.truncated || contents.contents.entries.singleOrNull { it.id == entry }?.navigable != true) return null
        val verified = try { verifySource(target) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        caller.ensureActive()
        if (!verified || generation != attempt || !current() || contents.contents.truncated || contents.entryIdForResource(target.resourceHref) != entry ||
            contents.contents.entries.singleOrNull { it.id == entry }?.navigable != true) return null
        proof = Proof(target.resourceHref, entry, caller[Job])
        return target.resourceHref
    }
    override fun goToChapter(href: String): Boolean {
        val accepted = proof
        proof = null; generation++
        if (accepted == null || href != accepted.href || !current() || contents.contents.truncated || accepted.owner?.isActive == false ||
            contents.entryIdForResource(href) != accepted.entryId) return false
        return contents.goTo(accepted.entryId)
    }
}

package com.yaprak.reader.formats.txt

import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.domain.search.txtLibraryTextPartIndex
import com.yaprak.reader.reader.ContentsNavigator
import com.yaprak.reader.reader.IndexedChapterNavigator
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Revalidates the original bytes, exact canonical part and the artifact actually opened by Readium.
 * No indexed offset is a locator, and no proof writes progress/coverage or grants EPUB provenance.
 */
internal class TxtIndexedSource(
    private val book: Book,
    private val library: LibraryRepository,
    private val files: VerifiedBookFile,
    private val artifact: File,
    private val artifactHash: String,
    parts: List<TxtPart>,
    private val fileMutex: Mutex,
    private val validateRead: suspend () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val preparedHashes = parts.associate { it.href to it.sha256 }
    suspend fun verify(target: LibraryTextTarget): Boolean = withContext(io) {
        if (!target.isValid || target.sourceFormat != BookFormat.TXT || target.bookId != book.id ||
            target.contentSha256 != book.sha256 || preparedHashes[target.resourceHref] != target.resourceSha256) return@withContext false
        try {
            fileMutex.withLock {
                currentCoroutineContext().ensureActive()
                validateRead()
                if (!currentBook()) return@withLock false
                val file = files.resolve(book)
                val parts = StrictTxt.read(file)
                val index = txtLibraryTextPartIndex(target.resourceHref) ?: return@withLock false
                if (parts.getOrNull(index)?.sha256 != target.resourceSha256) return@withLock false
                if (txtViewHash(artifact) != artifactHash) return@withLock false
                files.resolve(book)
                currentCoroutineContext().ensureActive()
                currentBook()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    private suspend fun currentBook() = library.findById(book.id)?.let {
        it.format == BookFormat.TXT && it.sha256 == book.sha256 && it.storageName == book.storageName && it.byteSize == book.byteSize
    } == true
}

/** Main-thread, current resumed surface, caller-owned and single-use proof.
 * Only the generated publication's real contents entry can be submitted to the native engine.
 */
internal class TxtIndexedNavigator(
    private val book: Book,
    private val current: () -> Boolean,
    private val verifySource: suspend (LibraryTextTarget) -> Boolean,
    private val contents: ContentsNavigator,
) : IndexedChapterNavigator {
    private data class Proof(val href: String, val entryId: String, val owner: Job?)
    private var proof: Proof? = null
    private var generation = 0L

    fun invalidate() { proof = null; generation++ }

    override suspend fun verifiedChapterHref(target: LibraryTextTarget): String? {
        val attempt = ++generation
        proof = null
        val caller = currentCoroutineContext()
        caller.ensureActive()
        if (!current() || !target.isValid || target.sourceFormat != BookFormat.TXT || book.format != BookFormat.TXT ||
            target.bookId != book.id || target.contentSha256 != book.sha256) return null
        val index = txtLibraryTextPartIndex(target.resourceHref) ?: return null
        val entry = contents.contents.entries.singleOrNull { it.id == index.toString() && it.navigable &&
            it.depth == 0 && it.title == "Metin " + (index + 1) } ?: return null
        val verified = try { verifySource(target) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        caller.ensureActive()
        if (!verified || generation != attempt || !current()) return null
        proof = Proof(target.resourceHref, entry.id, caller[Job])
        return target.resourceHref
    }

    override fun goToChapter(href: String): Boolean {
        val accepted = proof
        proof = null
        generation++
        if (accepted == null || href != accepted.href || !current() || accepted.owner?.isActive == false) return false
        return contents.goTo(accepted.entryId)
    }
}

/** Bounded complete digest of an owned viewing artifact; independent of original text hashes. */
internal suspend fun txtViewHash(file: File): String {
    val limit = 64L * 1024 * 1024
    currentCoroutineContext().ensureActive()
    check(file.isFile && file.length() in 1..limit)
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            bytes += count
            check(bytes <= limit)
            digest.update(buffer, 0, count)
        }
    }
    currentCoroutineContext().ensureActive()
    check(bytes > 0)
    return digest.digest().joinToString("") { "%02x".format(it) }
}

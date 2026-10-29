package com.yaprak.reader.data.discovery

import android.os.SystemClock
import com.yaprak.reader.domain.library.BookCandidate
import com.yaprak.reader.domain.library.BookDiscovery
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.DiscoveryFailure
import com.yaprak.reader.domain.library.DiscoveryLimit
import com.yaprak.reader.domain.library.DiscoveryProgress
import com.yaprak.reader.domain.library.DiscoveryResult
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveryLimits(
    val maxDepth: Int = 16,
    val maxDocuments: Int = 10_000,
    val maxCandidates: Int = 1_000,
    val budgetMillis: Long = 60_000,
) {
    init {
        require(maxDepth in 0..16 && maxDocuments in 1..10_000 && maxCandidates in 1..1_000 && budgetMillis in 1..60_000)
    }
}

class DefaultBookDiscovery(
    private val documents: DiscoveryDocumentProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val limits: DiscoveryLimits = DiscoveryLimits(),
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) : BookDiscovery {
    private data class Folder(val id: String, val depth: Int)

    override suspend fun scan(treeUri: String, onProgress: (DiscoveryProgress) -> Unit): DiscoveryResult = withContext(io) {
        try {
            val tree = documents.resolveTree(treeUri)
            val candidates = ArrayList<BookCandidate>()
            val seen = hashSetOf(tree.rootId)
            val folders = ArrayDeque<Folder>().apply { add(Folder(tree.rootId, 0)) }
            var visited = 0
            var skippedDepth = false
            var truncated: DiscoveryLimit? = null
            val started = elapsedMillis()
            fun outOfTime() = elapsedMillis() - started >= limits.budgetMillis
            fun progress() = onProgress(DiscoveryProgress(visited, candidates.size))
            progress()
            val finished = withTimeoutOrNull(limits.budgetMillis) {
                val coroutine = currentCoroutineContext()
                while (folders.isNotEmpty() && truncated == null) {
                    coroutine.ensureActive()
                    if (outOfTime()) { truncated = DiscoveryLimit.TIME; break }
                    val folder = folders.removeFirst()
                    documents.children(tree, folder.id) each@{ document ->
                        coroutine.ensureActive()
                        if (outOfTime()) { truncated = DiscoveryLimit.TIME; return@each false }
                        if (visited >= limits.maxDocuments) { truncated = DiscoveryLimit.DOCUMENTS; return@each false }
                        visited++
                        if (visited % 32 == 0) progress()
                        val id = document.id
                        if (!validDiscoveryId(id) || !seen.add(id!!) || document.virtual) return@each true
                        val mime = document.mimeType?.trim()?.lowercase(Locale.ROOT)
                        if (mime == DIRECTORY_MIME) {
                            if (folder.depth >= limits.maxDepth) skippedDepth = true
                            else folders.add(Folder(id, folder.depth + 1))
                            return@each true
                        }
                        val name = document.name?.trim()?.takeIf { it.isNotEmpty() && it.none(Character::isISOControl) }
                            ?: return@each true
                        val format = when {
                            mime == "application/pdf" -> BookFormat.PDF
                            mime == "application/epub+zip" -> BookFormat.EPUB
                            mime in setOf("application/vnd.comicbook-rar", "application/x-cbr") -> BookFormat.CBR
                            name.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
                            name.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
                            name.endsWith(".txt", ignoreCase = true) -> BookFormat.TXT
                            name.endsWith(".cbz", ignoreCase = true) -> BookFormat.CBZ
                            name.endsWith(".cbr", ignoreCase = true) -> BookFormat.CBR
                            name.endsWith(".docx", ignoreCase = true) -> BookFormat.DOCX
                            name.endsWith(".fb2", ignoreCase = true) -> BookFormat.FB2
                            name.endsWith(".fb2.zip", ignoreCase = true) -> BookFormat.FB2
                            else -> return@each true
                        }
                        if (candidates.size >= limits.maxCandidates) { truncated = DiscoveryLimit.CANDIDATES; return@each false }
                        candidates.add(BookCandidate(id, name.take(512), documents.documentUri(tree, id),
                            document.sizeBytes?.takeIf { it >= 0 }, format))
                        if (candidates.size % 16 == 0) progress()
                        true
                    }
                }
                true
            }
            currentCoroutineContext().ensureActive()
            if (finished == null) truncated = DiscoveryLimit.TIME
            progress()
            val reason = truncated ?: if (skippedDepth) DiscoveryLimit.DEPTH else null
            if (reason == null) DiscoveryResult.Complete(candidates.toList(), visited)
            else DiscoveryResult.Truncated(candidates.toList(), visited, reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidDiscoveryTreeException) {
            DiscoveryResult.Failed(DiscoveryFailure.INVALID_TREE)
        } catch (_: SecurityException) {
            DiscoveryResult.Failed(DiscoveryFailure.ACCESS)
        } catch (_: IOException) {
            DiscoveryResult.Failed(DiscoveryFailure.IO)
        } catch (_: Exception) {
            DiscoveryResult.Failed(DiscoveryFailure.UNEXPECTED)
        }
    }
}

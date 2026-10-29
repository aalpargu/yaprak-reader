package com.yaprak.reader.feature.reader

import android.content.Intent
import android.os.Bundle
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.domain.library.BookFormat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class IndexedChapterRequest(val id: String, val target: LibraryTextTarget) {
    val isValid: Boolean get() = target.isValid && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
}
internal enum class IndexedChapterPhase { NONE, PENDING, VERIFYING, ACCEPTED, FAILED }
internal enum class IndexedChapterFailure { SOURCE, UNSUPPORTED, NAVIGATION, INTERRUPTED }
internal data class IndexedChapterState(
    val request: IndexedChapterRequest? = null,
    val phase: IndexedChapterPhase = IndexedChapterPhase.NONE,
    val failure: IndexedChapterFailure? = null,
    val attemptId: String? = null,
    val revision: Long = 0,
)

/** Retained reader state. A navigation claim is consumed before its synchronous SDK effect. */
internal class IndexedChapterOpen {
    private var initialized = false
    private val mutable = MutableStateFlow(IndexedChapterState())
    val state = mutable.asStateFlow()
    fun initialize(request: IndexedChapterRequest?, restored: IndexedChapterState?) {
        if (initialized) return
        initialized = true
        if (request?.isValid != true) return
        mutable.value = restored?.takeIf { it.request == request } ?: IndexedChapterState(request, IndexedChapterPhase.PENDING)
    }
    fun begin(expected: IndexedChapterRequest): String? {
        val old = state.value
        if (old.request != expected || old.phase != IndexedChapterPhase.PENDING) return null
        val attempt = UUID.randomUUID().toString()
        mutable.value = old.copy(phase = IndexedChapterPhase.VERIFYING, attemptId = attempt, revision = old.revision + 1)
        return attempt
    }
    fun isVerifying(request: IndexedChapterRequest, attempt: String) =
        state.value.let { it.request == request && it.phase == IndexedChapterPhase.VERIFYING && it.attemptId == attempt }
    fun cancel(attempt: String) {
        val old = state.value
        if (old.phase == IndexedChapterPhase.VERIFYING && old.attemptId == attempt)
            mutable.value = old.copy(phase = IndexedChapterPhase.PENDING, attemptId = null, revision = old.revision + 1)
    }
    fun failPending(request: IndexedChapterRequest, reason: IndexedChapterFailure) {
        val old = state.value
        if (old.phase == IndexedChapterPhase.PENDING && old.request == request)
            mutable.value = old.copy(phase = IndexedChapterPhase.FAILED, failure = reason, revision = old.revision + 1)
    }
    fun fail(request: IndexedChapterRequest, attempt: String, reason: IndexedChapterFailure) {
        val old = state.value
        if (old.request == request && old.attemptId == attempt &&
            old.phase in listOf(IndexedChapterPhase.VERIFYING, IndexedChapterPhase.ACCEPTED))
            mutable.value = old.copy(phase = IndexedChapterPhase.FAILED, failure = reason, revision = old.revision + 1)
    }
    fun consume(request: IndexedChapterRequest, attempt: String): Boolean {
        if (!isVerifying(request, attempt)) return false
        val old = state.value
        mutable.value = old.copy(phase = IndexedChapterPhase.ACCEPTED, failure = null, revision = old.revision + 1)
        return true
    }
    fun retry(expected: IndexedChapterState): Boolean {
        if (state.value != expected || expected.phase != IndexedChapterPhase.FAILED) return false
        mutable.value = expected.copy(phase = IndexedChapterPhase.PENDING, failure = null, attemptId = null,
            revision = expected.revision + 1)
        return true
    }
}

/** Only bounded addresses and receipt status cross Android state boundaries; no book excerpts. */
internal object IndexedChapterLaunch {
    private const val PREFIX = "readerIndexedChapter"
    private const val INTENT_PREFIX = "indexedChapter"
    private val fields = listOf("Nonce", "Book", "Hash", "Href", "ResourceHash", "Version", "Format", "PdfPage", "Phase", "Failure")

    fun addTo(intent: Intent, target: LibraryTextTarget): Intent {
        require(target.isValid)
        val request = IndexedChapterRequest(UUID.randomUUID().toString(), target)
        val extras = Bundle(); writeRequest(extras, INTENT_PREFIX, request)
        return intent.putExtras(extras)
    }
    fun read(intent: Intent): IndexedChapterRequest? {
        val request = intent.extras?.let { readRequest(it, INTENT_PREFIX) } ?: return null
        return request.takeIf { intent.getStringExtra("bookId") == it.target.bookId }
    }
    @Suppress("DEPRECATION")
    fun restore(bundle: Bundle?, launch: IndexedChapterRequest?): IndexedChapterState? {
        if (bundle == null || launch == null || readRequest(bundle, PREFIX) != launch) return null
        val phase = (bundle.get(PREFIX + "Phase") as? String)?.let { name -> IndexedChapterPhase.entries.find { it.name == name } }
            ?.takeIf { it in listOf(IndexedChapterPhase.PENDING, IndexedChapterPhase.ACCEPTED, IndexedChapterPhase.FAILED) } ?: return null
        val failure = (bundle.get(PREFIX + "Failure") as? String)?.let { name -> IndexedChapterFailure.entries.find { it.name == name } }
        if ((phase == IndexedChapterPhase.FAILED) != (failure != null)) return null
        return IndexedChapterState(launch, phase, failure)
    }
    fun save(bundle: Bundle, state: IndexedChapterState) {
        fields.forEach { bundle.remove(PREFIX + it) }
        val request = state.request?.takeIf { it.isValid } ?: return
        writeRequest(bundle, PREFIX, request)
        val phase = if (state.phase == IndexedChapterPhase.VERIFYING) IndexedChapterPhase.PENDING else state.phase
        bundle.putString(PREFIX + "Phase", phase.name)
        state.failure?.let { bundle.putString(PREFIX + "Failure", it.name) }
    }
    private fun writeRequest(bundle: Bundle, prefix: String, request: IndexedChapterRequest) {
        bundle.putString(prefix + "Nonce", request.id)
        bundle.putString(prefix + "Book", request.target.bookId)
        bundle.putString(prefix + "Hash", request.target.contentSha256)
        bundle.putString(prefix + "Href", request.target.resourceHref)
        bundle.putString(prefix + "ResourceHash", request.target.resourceSha256)
        bundle.putInt(prefix + "Version", request.target.extractionVersion)
        if (request.target.sourceFormat != BookFormat.EPUB) {
            bundle.putString(prefix + "Format", request.target.sourceFormat.name)
        }
        if (request.target.sourceFormat == BookFormat.PDF) {
            bundle.putInt(prefix + "PdfPage", requireNotNull(request.target.pdfPageIndex))
        }
    }
    @Suppress("DEPRECATION")
    private fun readRequest(bundle: Bundle, prefix: String): IndexedChapterRequest? {
        fun text(field: String) = bundle.get(prefix + field) as? String
        val format = if (bundle.containsKey(prefix + "Format"))
            BookFormat.entries.find { it.name == text("Format") } ?: return null else BookFormat.EPUB
        val page = if (bundle.containsKey(prefix + "PdfPage"))
            bundle.get(prefix + "PdfPage") as? Int ?: return null else null
        val request = IndexedChapterRequest(text("Nonce") ?: return null, LibraryTextTarget(
            text("Book") ?: return null, text("Hash") ?: return null, text("Href") ?: return null,
            text("ResourceHash") ?: return null, bundle.get(prefix + "Version") as? Int ?: return null,
            format, page))
        return request.takeIf { it.isValid }
    }
}

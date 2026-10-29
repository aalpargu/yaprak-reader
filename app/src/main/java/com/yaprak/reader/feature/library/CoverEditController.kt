package com.yaprak.reader.feature.library

import androidx.lifecycle.SavedStateHandle
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookCoverEditor
import com.yaprak.reader.domain.library.CoverEditFailure
import com.yaprak.reader.domain.library.CoverEditResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface CoverEditState {
    data object Idle : CoverEditState
    data class Selecting(val bookId: String, val contentSha256: String) : CoverEditState
    data class Changing(val bookId: String, val contentSha256: String) : CoverEditState
    data class Resetting(val bookId: String, val contentSha256: String) : CoverEditState
    data class Finished(val bookId: String, val contentSha256: String, val result: CoverEditResult,
        val eventId: Long) : CoverEditState
}

/** A picker may outlive its Activity; its URI must still target the exact reserved cover revision. */
internal class CoverEditController(
    private val editor: BookCoverEditor?,
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
    private val currentBook: suspend (String) -> Book?,
) {
    private data class Identity(val bookId: String, val contentSha256: String, val expectedCoverId: String?,
        val requestId: String = UUID.randomUUID().toString()) {
        fun matches(book: Book) = book.id == bookId && book.sha256 == contentSha256 &&
            book.customCover?.id == expectedCoverId
    }
    private var pending = restorePending()
    private val _state = MutableStateFlow<CoverEditState>(pending?.let {
        CoverEditState.Selecting(it.bookId, it.contentSha256)
    } ?: CoverEditState.Idle)
    val state = _state.asStateFlow()
    private val _pickerPending = MutableStateFlow(pending != null)
    val pickerPending = _pickerPending.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    val available: Boolean get() = editor != null
    val busy: Boolean get() = _pickerPending.value || _running.value
    private var eventId = 0L

    init { if (pending == null) clearPending() }

    fun prepare(book: Book): Boolean {
        if (!available || busy || book.id.isBlank() || book.sha256.isBlank()) return false
        val identity = Identity(book.id, book.sha256, book.customCover?.id)
        pending = identity
        savedState[BOOK_ID] = identity.bookId
        savedState[CONTENT_SHA256] = identity.contentSha256
        // Empty string explicitly means no override; a missing key is an invalid reservation.
        savedState[EXPECTED_COVER_ID] = identity.expectedCoverId.orEmpty()
        savedState[REQUEST_ID] = identity.requestId
        _pickerPending.value = true
        _state.value = CoverEditState.Selecting(book.id, book.sha256)
        return true
    }

    fun onDocumentResult(sourceUri: String?) {
        val identity = consumePending() ?: return
        if (sourceUri == null || editor == null) {
            _state.value = CoverEditState.Idle
            return
        }
        execute(identity, reset = false) { currentEditor ->
            currentEditor.replace(identity.bookId, identity.contentSha256, identity.expectedCoverId, sourceUri)
        }
    }

    fun reset(book: Book): Boolean {
        if (!available || busy || book.customCover == null) return false
        val identity = Identity(book.id, book.sha256, book.customCover.id)
        execute(identity, reset = true) { currentEditor ->
            currentEditor.reset(identity.bookId, identity.contentSha256, identity.expectedCoverId)
        }
        return true
    }

    fun onLaunchFailed() {
        val identity = consumePending() ?: return
        finish(identity, CoverEditResult.Failed(CoverEditFailure.ACCESS))
    }

    fun rejectPending() {
        val identity = consumePending() ?: return
        finish(identity, CoverEditResult.Stale)
    }

    fun clearFinished(bookId: String, contentSha256: String) {
        val current = _state.value as? CoverEditState.Finished ?: return
        if (current.bookId == bookId && current.contentSha256 == contentSha256) _state.value = CoverEditState.Idle
    }

    private fun execute(identity: Identity, reset: Boolean, operation: suspend (BookCoverEditor) -> CoverEditResult) {
        val currentEditor = editor ?: return
        _running.value = true
        _state.value = if (reset) CoverEditState.Resetting(identity.bookId, identity.contentSha256)
            else CoverEditState.Changing(identity.bookId, identity.contentSha256)
        // Establish finally before a cleared owner can cancel dispatch.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                val result = try {
                    val book = currentBook(identity.bookId)
                    when {
                        book == null -> CoverEditResult.Missing
                        !identity.matches(book) -> CoverEditResult.Stale
                        else -> operation(currentEditor)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    CoverEditResult.Failed(CoverEditFailure.IO)
                }
                currentCoroutineContext().ensureActive()
                finish(identity, result)
            } finally {
                _running.value = false
                if (_state.value is CoverEditState.Changing || _state.value is CoverEditState.Resetting)
                    _state.value = CoverEditState.Idle
            }
        }
    }

    private fun finish(identity: Identity, result: CoverEditResult) {
        _state.value = CoverEditState.Finished(identity.bookId, identity.contentSha256, result, ++eventId)
    }

    private fun consumePending(): Identity? {
        val identity = pending ?: return null
        // A second controller restored from this handle cannot consume the same result twice.
        val persisted = restorePending()
        if (identity != persisted) {
            pending = null
            _pickerPending.value = false
            return null
        }
        clearPending()
        return identity
    }

    private fun restorePending(): Identity? {
        if (editor == null) return null
        val id = (savedState.get<Any>(BOOK_ID) as? String)?.takeIf(String::isNotBlank) ?: return null
        val hash = (savedState.get<Any>(CONTENT_SHA256) as? String)?.takeIf(String::isNotBlank) ?: return null
        val revision = savedState.get<Any>(EXPECTED_COVER_ID) as? String ?: return null
        val requestId = savedState.get<Any>(REQUEST_ID) as? String ?: return null
        if (runCatching { UUID.fromString(requestId).toString() }.getOrNull() != requestId) return null
        if (revision.isNotEmpty() && revision.isBlank()) return null
        return Identity(id, hash, revision.ifEmpty { null }, requestId)
    }

    private fun clearPending() {
        pending = null
        savedState.remove<String>(BOOK_ID)
        savedState.remove<String>(CONTENT_SHA256)
        savedState.remove<String>(EXPECTED_COVER_ID)
        savedState.remove<String>(REQUEST_ID)
        _pickerPending.value = false
    }

    private companion object {
        const val BOOK_ID = "pendingCoverBookId"
        const val CONTENT_SHA256 = "pendingCoverContentSha256"
        const val EXPECTED_COVER_ID = "pendingCoverExpectedId"
        const val REQUEST_ID = "pendingCoverRequestId"
    }
}

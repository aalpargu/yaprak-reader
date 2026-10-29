package com.yaprak.reader.feature.library

import androidx.lifecycle.SavedStateHandle
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookStudyExporter
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.library.StudyExportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class StudyExportNotice(val id: Long, val result: StudyExportResult)

/** The callback and native picker both belong to this immutable reservation. */
data class BookStudyExportRequest(val requestId: String, val bookId: String,
    val contentSha256: String, val format: StudyExportFormat, val suggestedFileName: String)

/** Keeps a picker result bound to its original book and content version. */
internal class StudyExportController(
    private val exporter: BookStudyExporter?,
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
) {
    private data class Pending(val request: BookStudyExportRequest, val launched: Boolean,
        val legacyCallbackAllowed: Boolean = false)
    private var pending = restorePending(migrateLegacy = true)
    private val _request = MutableStateFlow(pending?.request)
    val request = _request.asStateFlow()
    private val _pickerPending = MutableStateFlow(pending != null)
    val pickerPending = _pickerPending.asStateFlow()
    private val _exporting = MutableStateFlow(false)
    val exporting = _exporting.asStateFlow()
    private val _notice = MutableStateFlow<StudyExportNotice?>(null)
    val notice = _notice.asStateFlow()
    private var noticeId = 0L
    val available: Boolean get() = exporter != null
    val busy: Boolean get() = _pickerPending.value || _exporting.value

    init {
        if (pending == null) clearPending()
    }

    fun prepare(book: Book): String? {
        // Old callers launch synchronously themselves. Recreation must not launch it again.
        return prepare(book, StudyExportFormat.TEXT, alreadyLaunched = true, legacyCallbackAllowed = true)?.suggestedFileName
    }

    fun prepare(book: Book, format: StudyExportFormat, alreadyLaunched: Boolean = false,
        legacyCallbackAllowed: Boolean = false): BookStudyExportRequest? {
        if (!available || busy || book.id.isBlank() || book.sha256.isBlank()) return null
        // A stale controller must not overwrite another owner's pending picker.
        if (restorePending() != null) return null
        val suffix = when (format) {
            StudyExportFormat.ANKI_STUDY_TSV -> "-anki-calisma-kartlari"
            StudyExportFormat.NOTION_CSV -> "-notion-kayitlari"
            StudyExportFormat.OBSIDIAN_ZIP -> "-obsidian-calisma-kayitlari"
            else -> "-calisma-kayitlari"
        }
        val request = BookStudyExportRequest(UUID.randomUUID().toString(), book.id, book.sha256, format,
            suggestedExportFileName(book).removeSuffix(".${book.originalExtension}") + "$suffix.${format.extension}")
        val reservation = Pending(request, alreadyLaunched, legacyCallbackAllowed)
        save(reservation)
        pending = reservation
        _request.value = request
        _pickerPending.value = true
        return request
    }

    /** Persist before native launch; all calls run on the UI thread without suspension. */
    fun claimLaunch(requestId: String, format: StudyExportFormat): Boolean {
        val identity = matchingPending(requestId, format) ?: return false
        if (identity.launched) return false
        val claimed = identity.copy(launched = true)
        save(claimed)
        pending = claimed
        return true
    }

    fun onDocumentResult(destinationUri: String?) {
        val request = pending?.takeIf { it.legacyCallbackAllowed }?.request ?: return
        onDocumentResult(request.requestId, request.format, destinationUri)
    }

    fun onDocumentResult(requestId: String, format: StudyExportFormat, destinationUri: String?) {
        val identity = consume(requestId, format)?.request ?: return
        val currentExporter = exporter
        if (destinationUri == null || currentExporter == null) {
            return
        }
        _exporting.value = true
        // Enter try/finally immediately so clearing the owner before dispatch also clears running.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                val result = try {
                    currentExporter.export(identity.bookId, identity.contentSha256, destinationUri, identity.format)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    StudyExportResult.Failed(StudyExportFailure.IO, partialFileMayRemain = true)
                }
                currentCoroutineContext().ensureActive()
                _notice.value = StudyExportNotice(++noticeId, result)
            } finally {
                _exporting.value = false
            }
        }
    }

    fun onLaunchFailed() {
        val request = pending?.takeIf { it.legacyCallbackAllowed }?.request ?: return
        onLaunchFailed(request.requestId, request.format)
    }

    fun onLaunchFailed(requestId: String, format: StudyExportFormat) {
        consume(requestId, format) ?: return
        _notice.value = StudyExportNotice(++noticeId, StudyExportResult.Failed(StudyExportFailure.ACCESS))
    }

    fun acknowledgeNotice(id: Long) {
        if (_notice.value?.id == id) _notice.value = null
    }

    private fun restorePending(migrateLegacy: Boolean = false): Pending? {
        if (exporter == null) return null
        val raw = savedState.get<Any>(RESERVATION)
        if (savedState.contains(RESERVATION)) {
            val fields = (raw as? ArrayList<*>)?.takeIf { it.size == 7 } ?: return null
            if (fields.any { it !is String }) return null
            val id = fields[0] as String
            val hash = fields[1] as String
            val format = StudyExportFormat.entries.singleOrNull { it.name == fields[2] } ?: return null
            val nonce = fields[3] as String
            val name = fields[4] as String
            val launched = when (fields[5]) { "true" -> true; "false" -> false; else -> return null }
            val legacy = when (fields[6]) { "true" -> true; "false" -> false; else -> return null }
            if (id.isBlank() || hash.isBlank() || runCatching { UUID.fromString(nonce).toString() }.getOrNull() != nonce ||
                name.isBlank() || !name.endsWith(".${format.extension}") ||
                name.any { it.isISOControl() || it in "\\/:*?\"<>|" }) return null
            if (legacy && (format != StudyExportFormat.TEXT || !launched)) return null
            return Pending(BookStudyExportRequest(nonce, id, hash, format, name), launched, legacy)
        }
        if (!migrateLegacy) return null
        val bookId = (savedState.get<Any>(BOOK_ID) as? String)?.takeIf(String::isNotBlank) ?: return null
        val hash = (savedState.get<Any>(CONTENT_SHA256) as? String)?.takeIf(String::isNotBlank) ?: return null
        return Pending(BookStudyExportRequest(UUID.randomUUID().toString(), bookId, hash,
            StudyExportFormat.TEXT, "Yaprak-kitap-calisma-kayitlari.txt"), launched = true,
            legacyCallbackAllowed = true).also(::save)
    }

    private fun save(identity: Pending) {
        val request = identity.request
        // One saved value is authoritative, avoiding torn identity/launch-state comparisons.
        savedState[RESERVATION] = arrayListOf(request.bookId, request.contentSha256, request.format.name,
            request.requestId, request.suggestedFileName, identity.launched.toString(), identity.legacyCallbackAllowed.toString())
        savedState[BOOK_ID] = request.bookId
        savedState[CONTENT_SHA256] = request.contentSha256
    }

    private fun matchingPending(requestId: String, format: StudyExportFormat): Pending? {
        val identity = pending ?: return null
        if (identity.request.requestId != requestId || identity.request.format != format) return null
        val persisted = restorePending()
        if (persisted?.request != identity.request) {
            // Drop only this owner's local state; the persisted reservation may belong to a new owner.
            pending = null
            _request.value = null
            _pickerPending.value = false
            return null
        }
        pending = persisted
        return persisted
    }

    private fun consume(requestId: String, format: StudyExportFormat): Pending? {
        val identity = matchingPending(requestId, format) ?: return null
        if (!identity.launched) return null
        clearPending()
        return identity
    }

    private fun clearPending() {
        pending = null
        _request.value = null
        savedState.remove<ArrayList<String>>(RESERVATION)
        savedState.remove<String>(BOOK_ID)
        savedState.remove<String>(CONTENT_SHA256)
        _pickerPending.value = false
    }

    private companion object {
        const val BOOK_ID = "pendingStudyExportBookId"
        const val CONTENT_SHA256 = "pendingStudyExportContentSha256"
        const val RESERVATION = "pendingStudyExportReservation"
    }
}

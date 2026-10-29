package com.yaprak.reader.feature.librarynotes

import androidx.lifecycle.SavedStateHandle
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportResult
import com.yaprak.reader.domain.notes.*
import com.yaprak.reader.domain.passages.isWellFormedEpubAnnotationText
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LibraryNotesExportRequest(val requestId: String, val query: String,
    val format: LibraryNotesExportFormat) {
    val suggestedFileName: String get() = if (format == LibraryNotesExportFormat.OBSIDIAN_ZIP)
        "Yaprak-obsidian-notlari.zip" else "Yaprak-kutuphane-notlari.${format.extension}"
}
internal data class LibraryNotesExportNotice(val id: Long, val query: String,
    val format: LibraryNotesExportFormat, val result: StudyExportResult)

/** Exact named query/format/nonce reservation. Restored owners share one authoritative launch claim. */
internal class LibraryNotesExportController(private val exporter: LibraryNotesExporter?,
    private val saved: SavedStateHandle, private val scope: CoroutineScope) {
    private data class Pending(val request: LibraryNotesExportRequest, val launched: Boolean)
    private var pending = restore()
    private val mutableRequest = MutableStateFlow(pending?.request)
    val request = mutableRequest.asStateFlow()
    private val mutableExporting = MutableStateFlow(false)
    val exporting = mutableExporting.asStateFlow()
    private val mutableNotice = MutableStateFlow<LibraryNotesExportNotice?>(null)
    val notice = mutableNotice.asStateFlow()
    private var serial = 0L
    val available: Boolean get() = exporter != null
    val busy: Boolean get() = mutableRequest.value != null || mutableExporting.value
    init { if (pending == null) saved.remove<Any>(KEY) }

    fun prepare(query: String, format: LibraryNotesExportFormat): LibraryNotesExportRequest? {
        if (!available || busy || restore() != null || !validQuery(query)) return null
        val request = LibraryNotesExportRequest(UUID.randomUUID().toString(), query, format)
        val identity = Pending(request, false)
        save(identity); pending = identity; mutableRequest.value = request
        mutableNotice.value = null
        return request
    }
    fun claimLaunch(requestId: String, format: LibraryNotesExportFormat): Boolean {
        val identity = matching(requestId, format) ?: return false
        if (identity.launched) return false
        val claimed = identity.copy(launched = true)
        save(claimed); pending = claimed; return true
    }
    fun onResult(requestId: String, format: LibraryNotesExportFormat, uri: String?) {
        val identity = consume(requestId, format)?.request ?: return
        val currentExporter = exporter ?: return
        if (uri == null) return
        mutableExporting.value = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Enter the exporter even for a cancelled scope: it can clean an already SAF-created target.
                val result = try { currentExporter.export(identity.query, uri, identity.format) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { StudyExportResult.Failed(StudyExportFailure.IO, partialFileMayRemain = true) }
                currentCoroutineContext().ensureActive()
                mutableNotice.value = LibraryNotesExportNotice(++serial, identity.query, identity.format, result)
            } finally { mutableExporting.value = false }
        }
    }
    fun onLaunchFailed(requestId: String, format: LibraryNotesExportFormat) {
        val request = consume(requestId, format)?.request ?: return
        mutableNotice.value = LibraryNotesExportNotice(++serial, request.query, request.format,
            StudyExportResult.Failed(StudyExportFailure.ACCESS))
    }
    fun acknowledge(id: Long) { if (mutableNotice.value?.id == id) mutableNotice.value = null }

    private fun matching(requestId: String, format: LibraryNotesExportFormat): Pending? {
        val local = pending ?: return null
        if (local.request.requestId != requestId || local.request.format != format) return null
        val current = restore()
        if (current?.request != local.request) {
            pending = null; mutableRequest.value = null; return null
        }
        pending = current; return current
    }
    private fun consume(requestId: String, format: LibraryNotesExportFormat): Pending? {
        val identity = matching(requestId, format)?.takeIf { it.launched } ?: return null
        pending = null; mutableRequest.value = null; saved.remove<Any>(KEY)
        return identity
    }
    private fun save(identity: Pending) {
        val request = identity.request
        saved[KEY] = arrayListOf(request.requestId, request.query, request.format.name, identity.launched.toString())
    }
    private fun restore(): Pending? {
        if (exporter == null) return null
        val fields = (saved.get<Any?>(KEY) as? ArrayList<*>)?.takeIf { it.size == 4 && it.all { field -> field is String } }
            ?: return null
        val nonce = fields[0] as String
        val query = fields[1] as String
        val format = LibraryNotesExportFormat.entries.singleOrNull { it.name == fields[2] } ?: return null
        val launched = when (fields[3]) { "true" -> true; "false" -> false; else -> return null }
        if (!validQuery(query) || runCatching { UUID.fromString(nonce).toString() }.getOrNull() != nonce) return null
        return Pending(LibraryNotesExportRequest(nonce, query, format), launched)
    }
    private fun validQuery(query: String) = query.length <= MAX_LIBRARY_NOTE_QUERY_LENGTH && '\u0000' !in query &&
        (query.isEmpty() || isWellFormedEpubAnnotationText(query))
    private companion object { const val KEY = "libraryNotesExportReservationV1" }
}

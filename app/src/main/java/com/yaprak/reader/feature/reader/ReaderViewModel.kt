package com.yaprak.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.MainThread
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.reader.ReadingStateRepository
import com.yaprak.reader.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.statistics.ReadingDurationRepository
import com.yaprak.reader.domain.statistics.ReadingDurationFailure
import kotlinx.coroutines.flow.StateFlow

sealed interface ReaderState {
    data object Loading : ReaderState
    data class Ready(val session: ReaderSession) : ReaderState
    data class Error(val reason: ReaderFailure) : ReaderState
}

class ReaderViewModel(private val bookId: String, private val loader: ReaderLoader, private val reading: ReadingStateRepository,
    durationRepository: ReadingDurationRepository? = null) : ViewModel() {
    private val duration = durationRepository?.let { ReaderDurationController(it, SystemReadingDurationClock, viewModelScope) }
    val durationFailure: StateFlow<ReadingDurationFailure?> = duration?.failure ?: MutableStateFlow(null)
    fun claimDuration(owner: String) { duration?.claim(owner) }
    fun updateDuration(owner: String, book: Book, allowed: Boolean) { duration?.update(owner, book, allowed) }
    fun suspendDuration(owner: String) { duration?.suspend(owner) }
    fun releaseDuration(owner: String) { duration?.release(owner) }
    fun retryDuration() { duration?.retry() }
    internal val indexedChapterOpen = IndexedChapterOpen()
    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state = _state.asStateFlow()
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed = _saveFailed.asStateFlow()
    private class Save(var snapshot: ReadingSnapshot?, val completed: CompletableDeferred<Boolean>? = null)
    private val pending = Channel<Save>(Channel.UNLIMITED)
    // Main-thread owned; only the last not-yet-started, location-only PDF write can be replaced.
    // Coverage records, retries and flush barriers seal the slot, preserving their original order.
    private var pendingPdfLocation: Save? = null
    var latestLocation: ReadingLocation? = null
        private set
    private var latestSnapshot: ReadingSnapshot? = null
    private var session: ReaderSession? = null
    private var cleared = false

    init {
        load()
        viewModelScope.launch {
            try {
                for (save in pending) {
                    if (pendingPdfLocation === save) pendingPdfLocation = null
                    val book = session?.book
                    val snapshot = save.snapshot
                    try {
                        if (book != null && snapshot != null) {
                            reading.record(book.id, book.sha256, snapshot)
                            _saveFailed.value = false
                        }
                    }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { _saveFailed.value = true }
                    // Capture this barrier's result before later writes can change global failure state.
                    save.completed?.complete(!_saveFailed.value)
                }
            } finally {
                discardPendingWrites()
            }
        }
    }

    /** A saved panel is presentation only. Re-prove imported bytes through the existing loader
     * before a recreated Activity exposes any restored editor on a new rendering surface. */
    internal suspend fun verifyRestorationSource(expected: Book): Boolean {
        val retained = session ?: return false
        if (cleared || (_state.value as? ReaderState.Ready)?.session !== retained ||
            retained.book.id != expected.id || retained.book.sha256 != expected.sha256) return false
        val verified = loader.open(expected.id)
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return !cleared && session === retained && (_state.value as? ReaderState.Ready)?.session === retained &&
                verified.book.let { book -> book.id == expected.id && book.sha256 == expected.sha256 &&
                    book.format == expected.format && book.storageName == expected.storageName && book.byteSize == expected.byteSize }
        } finally {
            // Some controlled loaders return the live session; validation must never close it.
            if (verified !== retained && verified !== session) verified.close()
        }
    }

    fun retryOpen() { if (_state.value is ReaderState.Error) load() }
    private fun load() {
        _state.value = ReaderState.Loading
        viewModelScope.launch {
            try {
                val opened = loader.open(bookId)
                session = opened
                latestLocation = opened.resume
                _state.value = ReaderState.Ready(opened)
            } catch (error: CancellationException) { throw error }
            catch (error: ReaderOpenException) { _state.value = ReaderState.Error(error.reason) }
            catch (_: Exception) { _state.value = ReaderState.Error(ReaderFailure.INVALID) }
        }
    }

    @MainThread fun observe(snapshot: ReadingSnapshot) {
        // Observation only runs while the page is really on screen for reading (coverage gate).
        com.yaprak.reader.domain.statistics.readingPageKey(snapshot.location)?.let { duration?.pageShown(it) }
        latestLocation = snapshot.location
        latestSnapshot = snapshot
        if (session?.book?.format == com.yaprak.reader.domain.library.BookFormat.PDF && snapshot.spans.isEmpty()) {
            val queued = pendingPdfLocation
            if (queued != null) queued.snapshot = snapshot
            else {
                val save = Save(snapshot)
                // A waiting Main.immediate receiver may start during trySend itself.
                pendingPdfLocation = save
                if (pending.trySend(save).isFailure && pendingPdfLocation === save) pendingPdfLocation = null
            }
        } else {
            pendingPdfLocation = null
            pending.trySend(Save(snapshot))
        }
    }

    @MainThread fun retrySave() {
        pendingPdfLocation = null
        latestSnapshot?.let { pending.trySend(Save(it)) }
    }
    @MainThread suspend fun flush(): Boolean {
        pendingPdfLocation = null
        val completed = CompletableDeferred<Boolean>()
        pending.send(Save(null, completed))
        val readingSaved = completed.await()
        val durationSaved = duration?.flush() ?: true
        return readingSaved && durationSaved
    }
    private fun discardPendingWrites() {
        pendingPdfLocation = null
        pending.close()
        while (true) {
            val abandoned = pending.tryReceive().getOrNull() ?: break
            abandoned.completed?.cancel()
        }
    }
    override fun onCleared() { cleared = true; discardPendingWrites(); session?.close() }
}

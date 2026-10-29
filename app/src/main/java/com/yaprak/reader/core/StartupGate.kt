package com.yaprak.reader.core

import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.backup.BackupResult
import com.yaprak.reader.domain.backup.LibraryBackup
import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.ReaderOpenException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface StartupState {
    data object Loading : StartupState
    data object Ready : StartupState
    data object Failed : StartupState
}

/** Keeps readers and the library closed until interrupted restore recovery has completed. */
class StartupGate(
    private val scope: CoroutineScope,
    private val recoverRestore: suspend () -> Unit,
    private val cleanBookFiles: suspend () -> Unit,
) {
    private val _state = MutableStateFlow<StartupState>(StartupState.Loading)
    val state = _state.asStateFlow()
    private var startupJob: Job? = null

    init { retry() }

    fun retry() = synchronized(this) {
        if (_state.value == StartupState.Ready || startupJob?.isActive == true) return@synchronized
        _state.value = StartupState.Loading
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                recoverRestore()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(this@StartupGate) {
                    startupJob = null
                    _state.value = StartupState.Failed
                }
                return@launch
            }
            try {
                cleanBookFiles()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Unreferenced files can be retried on the next launch; no restored data is at risk.
            }
            synchronized(this@StartupGate) {
                startupJob = null
                _state.value = StartupState.Ready
            }
        }
        startupJob = next
        next.start()
    }

    /** A restore rollback failed while this process was already showing the library. */
    fun requireRecovery() = synchronized(this) {
        startupJob?.cancel()
        startupJob = null
        _state.value = StartupState.Failed
    }

    suspend fun awaitReady() {
        when (state.first { it != StartupState.Loading }) {
            StartupState.Ready -> Unit
            StartupState.Failed -> throw ReaderOpenException(ReaderFailure.RECOVERY)
            StartupState.Loading -> error("Startup was not resolved")
        }
    }
}

/** Closes the live library immediately when restore leaves a pending recovery journal. */
class RecoveryGatedLibraryBackup(
    private val delegate: LibraryBackup,
    private val gate: StartupGate,
) : LibraryBackup {
    override suspend fun create(destinationUri: String): BackupResult =
        delegate.create(destinationUri).also(::failClosedIfNeeded)

    override suspend fun restore(sourceUri: String): BackupResult =
        delegate.restore(sourceUri).also(::failClosedIfNeeded)

    private fun failClosedIfNeeded(result: BackupResult) {
        if (result is BackupResult.Failed && result.reason == BackupFailure.RECOVERY_REQUIRED) {
            gate.requireRecovery()
        }
    }

    override suspend fun recoverPendingRestore() = delegate.recoverPendingRestore()
}

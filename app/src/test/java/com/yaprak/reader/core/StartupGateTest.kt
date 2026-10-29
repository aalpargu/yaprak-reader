package com.yaprak.reader.core

import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.backup.BackupResult
import com.yaprak.reader.domain.backup.LibraryBackup
import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.ReaderOpenException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupGateTest {
    @Test fun `reader waits for restore recovery and orphan sweep`() = runTest {
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val gate = StartupGate(backgroundScope,
            recoverRestore = { release.await(); order += "recovered" },
            cleanBookFiles = { order += "cleaned" })
        val opening = backgroundScope.async { gate.awaitReady() }
        runCurrent()
        assertFalse(opening.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertTrue(opening.isCompleted)
        assertEquals(listOf("recovered", "cleaned"), order)
        assertEquals(StartupState.Ready, gate.state.value)
    }

    @Test fun `failed recovery blocks reading and immediate retry is not lost`() = runTest {
        var attempts = 0
        var cleanups = 0
        val gate = StartupGate(backgroundScope,
            recoverRestore = { if (++attempts == 1) throw IOException("journal") },
            cleanBookFiles = { cleanups++ })
        var firstError: Throwable? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            gate.state.first { it == StartupState.Failed }
            firstError = runCatching { gate.awaitReady() }.exceptionOrNull()
            gate.retry()
        }
        runCurrent()
        assertEquals(ReaderFailure.RECOVERY, (firstError as ReaderOpenException).reason)
        assertEquals(2, attempts)
        assertEquals(1, cleanups)
        assertEquals(StartupState.Ready, gate.state.value)
    }

    @Test fun `failed orphan sweep does not hide an otherwise recovered library`() = runTest {
        val gate = StartupGate(backgroundScope,
            recoverRestore = {},
            cleanBookFiles = { throw IOException("orphan") })
        runCurrent()
        assertEquals(StartupState.Ready, gate.state.value)
        gate.awaitReady()
    }

    @Test fun `runtime restore recovery failure closes reader and library until retry`() = runTest {
        var recoveries = 0
        val delegate = object : LibraryBackup {
            override suspend fun create(destinationUri: String) = BackupResult.Created(0)
            override suspend fun restore(sourceUri: String) =
                BackupResult.Failed(BackupFailure.RECOVERY_REQUIRED)
            override suspend fun recoverPendingRestore() { recoveries++ }
        }
        val gate = StartupGate(backgroundScope, delegate::recoverPendingRestore, {})
        runCurrent()
        assertEquals(StartupState.Ready, gate.state.value)

        val backup = RecoveryGatedLibraryBackup(delegate, gate)
        assertEquals(BackupResult.Failed(BackupFailure.RECOVERY_REQUIRED),
            backup.restore("content://archive"))
        assertEquals(StartupState.Failed, gate.state.value)
        assertEquals(ReaderFailure.RECOVERY,
            (runCatching { gate.awaitReady() }.exceptionOrNull() as ReaderOpenException).reason)

        gate.retry()
        runCurrent()
        assertEquals(2, recoveries)
        assertEquals(StartupState.Ready, gate.state.value)
        gate.awaitReady()
    }
}

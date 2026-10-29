package com.yaprak.reader.data.discovery

import android.content.Context
import com.yaprak.reader.domain.library.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Concrete persisted selection and read-grant ownership, separate from library/appearance backup.
 * OS rights and preferences have distinct commits; journals preserve that distinction after a crash.
 */
internal class PersistentBookFolderRepository(
    private val records: BookFolderRecords,
    private val access: BookFolderAccess,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookFolderRepository {
    constructor(context: Context) : this(BookFolderRecords(context), AndroidBookFolderAccess(context.contentResolver))
    private val mutex = Mutex()

    override fun observe(): Flow<BookFolderSnapshot> = flow {
        retryCleanup()
        emitAll(records.records.map { record ->
            if (record.pendingTake != null || record.pendingRelease != null) fail(BookFolderFailure.RECOVERY)
            record.snapshot
        })
    }.flowOn(io)

    override suspend fun retryCleanup() = guarded {
        mutex.withLock { currentCoroutineContext().ensureActive(); recover() }
        Unit
    }

    override suspend fun resolveForScan(expected: BookFolderSnapshot): String = guarded {
        mutex.withLock {
            currentCoroutineContext().ensureActive(); recover()
            val current = records.read().snapshot
            if (!expected.valid || current != expected) fail(BookFolderFailure.CHANGED)
            val uri = current.folder?.treeUri ?: fail(BookFolderFailure.CHANGED)
            if (!access.hasRead(uri)) fail(BookFolderFailure.ACCESS)
            currentCoroutineContext().ensureActive()
            uri
        }
    }

    override suspend fun save(expected: BookFolderSnapshot, selectedTreeUri: String): BookFolderWrite = guarded {
        mutate(expected) { access.inspect(selectedTreeUri).also { if (!it.valid) fail(BookFolderFailure.INVALID) } }
    }
    override suspend fun forget(expected: BookFolderSnapshot): BookFolderWrite = guarded { mutate(expected) { null } }

    override suspend fun setAutomaticScan(expected: BookFolderSnapshot, enabled: Boolean): BookFolderWrite = guarded {
        mutex.withLock {
            currentCoroutineContext().ensureActive(); recover()
            val previous = records.read()
            if (!expected.valid || previous.snapshot != expected) fail(BookFolderFailure.CHANGED)
            val folder = previous.snapshot.folder ?: fail(BookFolderFailure.CHANGED)
            // Turning off remains possible after permission revocation. Turning on cannot create
            // a grant from a stale saved URI; the folder must still have native persisted read.
            if (enabled && !access.hasRead(folder.treeUri)) fail(BookFolderFailure.ACCESS)
            currentCoroutineContext().ensureActive()
            if (previous.snapshot.automaticScan == enabled) return@withLock BookFolderWrite(previous.snapshot)
            val next = previous.copy(snapshot = previous.snapshot.copy(revision = UUID.randomUUID().toString(), automaticScan = enabled))
            withContext(NonCancellable) {
                try { records.write(next) }
                catch (failure: Exception) {
                    val actual = try { records.read() } catch (read: Exception) { failure.addSuppressed(read); throw failure }
                    if (actual != next) throw failure
                }
                BookFolderWrite(next.snapshot)
            }
        }
    }

    private suspend fun mutate(expected: BookFolderSnapshot, selection: suspend () -> SavedBookFolder?): BookFolderWrite = mutex.withLock {
        currentCoroutineContext().ensureActive(); recover()
        val previous = records.read()
        if (!expected.valid || previous.snapshot != expected) fail(BookFolderFailure.CHANGED)
        val folder = selection()
        currentCoroutineContext().ensureActive()
        val hasRead = folder?.let { access.hasRead(it.treeUri) } ?: false
        val ownsRead = folder != null && (!hasRead || (previous.ownsReadGrant && previous.snapshot.folder?.treeUri == folder.treeUri))
        if (folder == previous.snapshot.folder && ownsRead == previous.ownsReadGrant && (folder == null || hasRead))
            return@withLock BookFolderWrite(previous.snapshot)
        val automatic = folder != null && folder.treeUri == previous.snapshot.folder?.treeUri && previous.snapshot.automaticScan
        val next = BookFolderRecord(BookFolderSnapshot(UUID.randomUUID().toString(), folder, automatic), ownsRead,
            pendingRelease = previous.snapshot.folder?.treeUri?.takeIf { previous.ownsReadGrant && it != folder?.treeUri })
        // An admitted grant/preference mutation finishes or leaves a durable recovery marker.
        // Inspect/query and mutex waiting stay cancellable before this commit window.
        withContext(NonCancellable) {
            if (folder != null && !hasRead) {
                records.write(previous.copy(pendingTake = folder.treeUri))
                try { access.takeRead(folder.treeUri) }
                catch (failure: Exception) {
                    try { recover() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
            try { records.write(next) }
            catch (failure: Exception) {
                // If an I/O acknowledgement failed after the atomic write, trust only a fresh
                // exact record read. Otherwise undo the feature-owned pending new grant.
                val actual = try { records.read() } catch (read: Exception) { failure.addSuppressed(read); throw failure }
                if (actual != next) {
                    try { recover() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
            val pending = try { recover(); false } catch (_: BookFolderException) { true }
            BookFolderWrite(next.snapshot, cleanupPending = pending)
        }
    }

    private suspend fun recover() {
        val record = records.read()
        val ownedUri = record.pendingTake ?: record.pendingRelease ?: return
        try {
            if (access.hasRead(ownedUri)) access.releaseRead(ownedUri)
            records.write(record.copy(pendingTake = null, pendingRelease = null))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail(BookFolderFailure.RECOVERY) }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = withContext(io) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: BookFolderException) { throw failure }
        catch (_: InvalidDiscoveryTreeException) { fail(BookFolderFailure.INVALID) }
        catch (_: SecurityException) { fail(BookFolderFailure.ACCESS) }
        catch (_: Exception) { fail(BookFolderFailure.SOURCE) }
    }
}

private fun fail(reason: BookFolderFailure): Nothing = throw BookFolderException(reason)

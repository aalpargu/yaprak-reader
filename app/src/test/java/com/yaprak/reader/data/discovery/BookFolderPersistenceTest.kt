package com.yaprak.reader.data.discovery

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.yaprak.reader.domain.library.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookFolderPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = "content://books.provider/tree/primary%3ABooks"
    private val second = "content://books.provider/tree/primary%3AStudy"
    private val foreign = "content://other.provider/tree/Foreign"
    private val empty = BookFolderRecord().snapshot

    @Test fun `selection and owned read grant survive reopening without touching imported artifacts`() = runBlocking {
        val file = File(temporary.root, "folder.preferences_pb")
        val book = temporary.newFile("original.txt").apply { writeText("Özgün kitap 🌿\nİkinci satır") }
        val original = book.readBytes()
        val access = Access()
        lateinit var saved: BookFolderSnapshot
        withStore(file) { store ->
            val unrelated = stringPreferencesKey("another-owned-setting")
            store.edit { it[unrelated] = "preserve" }
            val before = file.readBytes()
            val repo = repository(store, access)
            assertEquals(empty, repo.observe().first())
            assertArrayEquals(before, file.readBytes())
            saved = repo.save(empty, first).snapshot
            assertEquals("Kitaplar 🌿 İ", saved.folder!!.displayName)
            assertEquals(listOf(first), access.takes)
            assertTrue(BookFolderRecords(store).read().ownsReadGrant)
            assertEquals("preserve", store.data.first()[unrelated])
        }
        withStore(file) { store ->
            val repo = repository(store, access)
            assertEquals(saved, repo.observe().first())
            assertEquals(first, repo.resolveForScan(saved))
            assertNull(repo.forget(saved).snapshot.folder)
            assertEquals(listOf(first), access.releases)
            assertArrayEquals(original, book.readBytes())
        }
    }

    @Test fun `preexisting permission remains unowned through replacement and forget`() = runBlocking {
        withStore { store ->
            val access = Access().apply { grants += listOf(first, foreign) }
            val repo = repository(store, access)
            val one = repo.save(empty, first).snapshot
            assertFalse(BookFolderRecords(store).read().ownsReadGrant)
            val two = repo.save(one, second).snapshot
            assertEquals(listOf(second), access.takes)
            assertTrue(access.releases.isEmpty())
            repo.forget(two)
            assertEquals(listOf(second), access.releases)
            assertEquals(setOf(first, foreign), access.grants)
        }
    }

    @Test fun `same folder is a no-op until name or actual permission changes`() = runBlocking {
        withStore { store ->
            val access = Access(); val repo = repository(store, access)
            val one = repo.save(empty, first).snapshot
            assertEquals(one, repo.save(one, first).snapshot)
            access.name = "Ders kitapları"
            val renamed = repo.save(one, first).snapshot
            assertNotEquals(one.revision, renamed.revision)
            assertEquals(1, access.takes.size)
            access.grants -= first
            assertFailure(BookFolderFailure.ACCESS) { repo.resolveForScan(renamed) }
            val retaken = repo.save(renamed, first).snapshot
            assertNotEquals(renamed.revision, retaken.revision)
            assertEquals(listOf(first, first), access.takes)
            assertTrue(access.releases.isEmpty())
            assertEquals(first, repo.resolveForScan(retaken))
        }
    }

    @Test fun `revision compare and shared mutex reject stale concurrent writers before acquiring permissions`() = runBlocking {
        withStore { store ->
            val access = Access(); val repo = repository(store, access)
            val start = CompletableDeferred<Unit>()
            val jobs = listOf(first, second).map { uri -> async { start.await(); runCatching { repo.save(empty, uri) } } }
            start.complete(Unit)
            val results = jobs.awaitAll()
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(BookFolderFailure.CHANGED, (results.single { it.isFailure }.exceptionOrNull() as BookFolderException).reason)
            assertEquals(1, access.takes.size)
            assertFailure(BookFolderFailure.CHANGED) { repo.forget(empty) }
            assertFailure(BookFolderFailure.CHANGED) { repo.resolveForScan(empty) }
            assertTrue(access.releases.isEmpty())
        }
    }

    @Test fun `failed permission acquisition is rolled back before and after a provider grants read`() = runBlocking {
        for (grantBeforeThrow in listOf(false, true)) withStore { store ->
            val access = Access(); val repo = repository(store, access)
            val original = repo.save(empty, first).snapshot
            access.takeFailure = { uri -> if (grantBeforeThrow) access.grants += uri; throw SecurityException("denied") }
            assertFailure(BookFolderFailure.ACCESS) { repo.save(original, second) }
            val record = BookFolderRecords(store).read()
            assertEquals(original, record.snapshot)
            assertNull(record.pendingTake)
            assertEquals(setOf(first), access.grants)
            assertEquals(if (grantBeforeThrow) listOf(second) else emptyList<String>(), access.releases)
        }
    }

    @Test fun `atomic write failure rolls back new permission while lost acknowledgement trusts exact fresh record`() = runBlocking {
        for (afterCommit in listOf(false, true)) withStore { real ->
            val access = Access(); val wrapped = Writes(real)
            val repo = repository(wrapped, access)
            val original = repo.save(empty, first).snapshot
            wrapped.count = 0
            wrapped.failureAt = 2; wrapped.afterCommit = afterCommit
            val result = runCatching { repo.save(original, second) }
            val actual = BookFolderRecords(real).read()
            assertNull(actual.pendingTake); assertNull(actual.pendingRelease)
            if (afterCommit) {
                assertTrue(result.isSuccess)
                assertEquals(result.getOrThrow().snapshot, actual.snapshot)
                assertEquals(second, actual.snapshot.folder!!.treeUri)
                assertEquals(setOf(second), access.grants)
                assertEquals(listOf(first), access.releases)
            } else {
                assertEquals(BookFolderFailure.SOURCE, (result.exceptionOrNull() as BookFolderException).reason)
                assertEquals(original, actual.snapshot)
                assertEquals(setOf(first), access.grants)
                assertEquals(listOf(second), access.releases)
            }
        }
    }

    @Test fun `process death between take and commit recovers only journaled new permission`() = runBlocking {
        val file = File(temporary.root, "crash.preferences_pb")
        val access = Access().apply { grants += listOf(first, second, foreign) }
        val original = BookFolderSnapshot(UUID.randomUUID().toString(), SavedBookFolder(first, "Önceki"))
        withStore(file) { BookFolderRecords(it).write(BookFolderRecord(original, ownsReadGrant = true, pendingTake = second)) }
        withStore(file) { store ->
            val repo = repository(store, access)
            assertEquals(original, repo.observe().first())
            assertNull(BookFolderRecords(store).read().pendingTake)
            assertEquals(listOf(second), access.releases)
            assertEquals(setOf(first, foreign), access.grants)
            assertEquals(first, repo.resolveForScan(original))
        }
    }

    @Test fun `old permission cleanup failure retains committed new folder and survives restart`() = runBlocking {
        val file = File(temporary.root, "release.preferences_pb")
        val access = Access().apply { grants += foreign }
        lateinit var committed: BookFolderSnapshot
        withStore(file) { store ->
            val repo = repository(store, access)
            val original = repo.save(empty, first).snapshot
            access.releaseFailure = true
            val result = repo.save(original, second)
            committed = result.snapshot
            assertTrue(result.cleanupPending)
            assertEquals(second, committed.folder!!.treeUri)
            assertEquals(first, BookFolderRecords(store).read().pendingRelease)
            assertFailure(BookFolderFailure.RECOVERY) { repo.observe().first() }
        }
        withStore(file) { store ->
            access.releaseFailure = false
            val repo = repository(store, access)
            repo.retryCleanup()
            assertEquals(committed, repo.observe().first())
            assertEquals(setOf(second, foreign), access.grants)
            assertNull(BookFolderRecords(store).read().pendingRelease)
        }
    }

    @Test fun `cancelled inspection and mutex wait never acquire or rewrite a folder`() = runBlocking {
        withStore { store ->
            val access = Access(); val repo = repository(store, access)
            val entered = CompletableDeferred<Unit>()
            access.inspectAction = { entered.complete(Unit); awaitCancellation() }
            val active = async { repo.save(empty, first) }; entered.await()
            val waiting = async { repo.save(empty, second) }
            waiting.cancelAndJoin(); active.cancelAndJoin()
            assertEquals(empty, BookFolderRecords(store).read().snapshot)
            assertTrue(access.takes.isEmpty()); assertTrue(access.releases.isEmpty())
        }
    }

    @Test fun `cancellation after journal admission completes real preference and permission commit`() = runBlocking {
        withStore { real ->
            val access = Access(); val wrapped = Writes(real)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            wrapped.onWrite = { count -> if (count == 1) { entered.complete(Unit); release.await() } }
            val repo = repository(wrapped, access)
            val job = async { repo.save(empty, first) }
            entered.await(); job.cancel(); release.complete(Unit); job.join()
            assertTrue(job.isCancelled)
            val actual = BookFolderRecords(real).read()
            assertEquals(first, actual.snapshot.folder!!.treeUri)
            assertTrue(actual.ownsReadGrant); assertNull(actual.pendingTake)
            assertEquals(setOf(first), access.grants)
            assertTrue(access.releases.isEmpty())
        }
    }

    @Test fun `malformed persisted ownership fails closed without releasing a provider permission`() = runBlocking {
        val good = JSONObject().put("version", 1).put("revision", empty.revision)
            .put("uri", JSONObject.NULL).put("name", JSONObject.NULL).put("owns", false)
            .put("take", JSONObject.NULL).put("release", JSONObject.NULL)
        val corruptions: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", "1") }, { it.put("extra", true) }, { it.remove("release") },
            { it.put("owns", "true") }, { it.put("owns", true) }, { it.put("name", "orphan") },
            { it.put("revision", "not-a-revision") }, { it.put("uri", "file:///Books") },
            { it.put("take", second).put("release", first) },
            { it.put("uri", first).put("release", first) }, { it.put("take", 17) },
        )
        for (corrupt in corruptions) withStore { store ->
            val access = Access().apply { grants += listOf(first, second, foreign) }
            val raw = JSONObject(good.toString()).also(corrupt).toString()
            store.edit { it[BookFolderRecords.recordKey] = raw }
            val before = store.data.first()
            assertFailure(BookFolderFailure.SOURCE) { repository(store, access).observe().first() }
            assertEquals(before, store.data.first())
            assertTrue(access.takes.isEmpty()); assertTrue(access.releases.isEmpty())
        }
        withStore { store ->
            store.edit { it[intPreferencesKey(BookFolderRecords.recordKey.name)] = 1 }
            assertFailure(BookFolderFailure.SOURCE) { repository(store, Access()).observe().first() }
        }
    }

    @Test fun `unsafe URI and bounded display name never become a saved selection`() = runBlocking {
        val invalid = listOf("file:///Books", "$first?query=1", "$first#fragment", "$first/document/book",
            "content://user@provider/tree/root", "content://provider/tree/%0A", "content://provider/tree/%ZZ",
            "content://provider/tree/\\root", "content://provider/tree/\uD800", "content://provider/tree/%20",
            "content://provider/tree/" + "x".repeat(4097), "content://provider/tree/" + "x".repeat(20000))
        invalid.forEach { assertFalse(it, SavedBookFolder(it, null).valid) }
        assertFalse(SavedBookFolder(first, "x".repeat(513)).valid)
        assertFalse(SavedBookFolder(first, "\uD800").valid)
        assertTrue(SavedBookFolder(first, "Kitaplar 🌿").valid)
        withStore { store ->
            val access = Access(); val repo = repository(store, access)
            assertFailure(BookFolderFailure.INVALID) { repo.save(empty, "file:///Books") }
            assertEquals(empty, repo.observe().first())
            assertTrue(access.takes.isEmpty())
        }
    }

    @Test fun `frozen version1 reads opening scan off without rewrite then version2 persists explicit opt-in`() = runBlocking {
        val file = File(temporary.root, "legacy.preferences_pb")
        val access = Access().apply { grants += first }
        val legacy = BookFolderSnapshot(UUID.randomUUID().toString(), SavedBookFolder(first, "Kitaplar"))
        lateinit var enabled: BookFolderSnapshot
        withStore(file) { store ->
            val raw = JSONObject().put("version", 1).put("revision", legacy.revision).put("uri", first).put("name", "Kitaplar")
                .put("owns", false).put("take", JSONObject.NULL).put("release", JSONObject.NULL).toString()
            store.edit { it[BookFolderRecords.recordKey] = raw }
            val before = file.readBytes()
            val repo = repository(store, access)
            assertEquals(legacy, repo.observe().first()); assertFalse(repo.observe().first().automaticScan)
            assertArrayEquals(before, file.readBytes())
            enabled = repo.setAutomaticScan(legacy, true).snapshot
            assertTrue(enabled.automaticScan); assertNotEquals(legacy.revision, enabled.revision)
            val actual = JSONObject(store.data.first()[BookFolderRecords.recordKey]!!)
            assertEquals(2, actual.getInt("version")); assertEquals(true, actual.get("automatic"))
            assertFalse(BookFolderRecords(store).read().ownsReadGrant)
        }
        withStore(file) { store -> assertEquals(enabled, repository(store, access).observe().first()) }
        assertTrue(access.takes.isEmpty()); assertTrue(access.releases.isEmpty())
    }

    @Test fun `opt-in requires native read while disabling after revocation does not acquire or release rights`() = runBlocking {
        withStore { store ->
            val access = Access(); val repo = repository(store, access)
            val saved = repo.save(empty, first).snapshot
            access.inspectAction = { error("Preference toggle must not query or reselect source") }
            val enabled = repo.setAutomaticScan(saved, true).snapshot
            assertEquals(enabled, repo.setAutomaticScan(enabled, true).snapshot)
            assertFailure(BookFolderFailure.CHANGED) { repo.setAutomaticScan(saved, false) }
            access.grants -= first
            assertFailure(BookFolderFailure.ACCESS) { repo.setAutomaticScan(enabled, true) }
            val disabled = repo.setAutomaticScan(enabled, false).snapshot
            assertFalse(disabled.automaticScan); assertEquals(first, disabled.folder!!.treeUri)
            assertTrue(BookFolderRecords(store).read().ownsReadGrant)
            assertFailure(BookFolderFailure.ACCESS) { repo.setAutomaticScan(disabled, true) }
            assertEquals(listOf(first), access.takes); assertTrue(access.releases.isEmpty())
        }
    }

    @Test fun `same folder renewal preserves opt-in but replacement and forget reset it`() = runBlocking {
        withStore { store ->
            val access = Access(); val repo = repository(store, access)
            val saved = repo.save(empty, first).snapshot
            val enabled = repo.setAutomaticScan(saved, true).snapshot
            access.name = "Ders kitapları"
            val renamed = repo.save(enabled, first).snapshot
            assertTrue(renamed.automaticScan)
            val replacement = repo.save(renamed, second).snapshot
            assertFalse(replacement.automaticScan)
            assertEquals(listOf(first), access.releases)
            val optedIn = repo.setAutomaticScan(replacement, true).snapshot
            val forgotten = repo.forget(optedIn).snapshot
            assertNull(forgotten.folder); assertFalse(forgotten.automaticScan)
            assertEquals(listOf(first, second), access.releases)
            assertFailure(BookFolderFailure.CHANGED) { repo.setAutomaticScan(forgotten, true) }
        }
    }

    @Test fun `opening preference write failure acknowledgement and admitted cancellation preserve atomic actual state`() = runBlocking {
        for (after in listOf(false, true)) withStore { real ->
            val access = Access(); val wrapped = Writes(real); val repo = repository(wrapped, access)
            val saved = repo.save(empty, first).snapshot
            wrapped.count = 0; wrapped.failureAt = 1; wrapped.afterCommit = after
            val result = runCatching { repo.setAutomaticScan(saved, true) }
            val actual = BookFolderRecords(real).read()
            assertEquals(after, actual.snapshot.automaticScan)
            assertTrue(actual.ownsReadGrant); assertNull(actual.pendingTake); assertNull(actual.pendingRelease)
            if (after) assertEquals(result.getOrThrow().snapshot, actual.snapshot)
            else { assertTrue(result.isFailure); assertEquals(saved, actual.snapshot) }
            assertEquals(listOf(first), access.takes); assertTrue(access.releases.isEmpty())
        }
        withStore { real ->
            val access = Access(); val wrapped = Writes(real); val repo = repository(wrapped, access)
            val saved = repo.save(empty, first).snapshot
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            wrapped.onWrite = { entered.complete(Unit); release.await() }
            val job = async { repo.setAutomaticScan(saved, true) }
            entered.await(); job.cancel(); release.complete(Unit); job.join()
            assertTrue(job.isCancelled); assertTrue(BookFolderRecords(real).read().snapshot.automaticScan)
            assertEquals(listOf(first), access.takes); assertTrue(access.releases.isEmpty())
        }
    }

    @Test fun `new record rejects coerced missing extra boolean and opt-in without selected folder`() = runBlocking {
        for (automatic in listOf<Any?>("true", 1, JSONObject.NULL, true)) withStore { store ->
            val raw = JSONObject().put("version", 2).put("revision", empty.revision).put("uri", JSONObject.NULL).put("name", JSONObject.NULL)
                .put("owns", false).put("take", JSONObject.NULL).put("release", JSONObject.NULL).put("automatic", automatic).toString()
            store.edit { it[BookFolderRecords.recordKey] = raw }
            val access = Access().apply { grants += foreign }
            assertFailure(BookFolderFailure.SOURCE) { repository(store, access).observe().first() }
            assertEquals(raw, store.data.first()[BookFolderRecords.recordKey])
            assertTrue(access.takes.isEmpty()); assertTrue(access.releases.isEmpty())
        }
        val shape = JSONObject().put("version", 2).put("revision", empty.revision).put("uri", first).put("name", "Kitaplar")
            .put("owns", false).put("take", JSONObject.NULL).put("release", JSONObject.NULL).put("automatic", false)
        for (mutation in listOf<(JSONObject) -> Unit>({ it.remove("automatic") }, { it.put("extra", false) }, { it.put("version", 1) }))
            withStore { store ->
                val raw = JSONObject(shape.toString()).also(mutation).toString()
                store.edit { it[BookFolderRecords.recordKey] = raw }
                assertFailure(BookFolderFailure.SOURCE) { repository(store, Access()).observe().first() }
                assertEquals(raw, store.data.first()[BookFolderRecords.recordKey])
            }
        assertFalse(BookFolderSnapshot(empty.revision, null, automaticScan = true).valid)
    }

    private fun repository(store: DataStore<Preferences>, access: Access) = PersistentBookFolderRepository(BookFolderRecords(store), access)
    private suspend fun withStore(block: suspend (DataStore<Preferences>) -> Unit) =
        withStore(File(temporary.newFolder(), "folder.preferences_pb"), block)
    private suspend fun withStore(file: File, block: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try { block(store) } finally { job.cancelAndJoin() }
    }
    private suspend fun assertFailure(reason: BookFolderFailure, action: suspend () -> Any?) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected $reason, got $failure", failure is BookFolderException)
        assertEquals(reason, (failure as BookFolderException).reason)
    }
    private class Access : BookFolderAccess {
        val grants = linkedSetOf<String>()
        val takes = mutableListOf<String>(); val releases = mutableListOf<String>()
        var name = "Kitaplar 🌿 İ"
        var inspectAction: (suspend () -> Unit)? = null
        var takeFailure: ((String) -> Unit)? = null
        var releaseFailure = false
        override suspend fun inspect(selectedTreeUri: String): SavedBookFolder {
            inspectAction?.invoke()
            return SavedBookFolder(selectedTreeUri, name)
        }
        override fun hasRead(treeUri: String) = treeUri in grants
        override fun takeRead(treeUri: String) { takes += treeUri; takeFailure?.invoke(treeUri); grants += treeUri }
        override fun releaseRead(treeUri: String) {
            if (releaseFailure) throw IOException("provider unavailable")
            releases += treeUri; grants -= treeUri
        }
    }
    private class Writes(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        override val data = real.data
        var count = 0; var failureAt: Int? = null; var afterCommit = false
        var onWrite: (suspend (Int) -> Unit)? = null
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val number = ++count
            onWrite?.invoke(number)
            if (number == failureAt && !afterCommit) throw IOException("write failed")
            val result = real.updateData(transform)
            if (number == failureAt) throw IOException("acknowledgement lost")
            return result
        }
    }
}

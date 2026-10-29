package com.yaprak.reader.feature

import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.library.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookFolderControllerTest {
    private val uri = "content://books.provider/tree/primary%3ABooks"
    private fun snapshot(folder: Boolean = true) = BookFolderSnapshot(UUID.randomUUID().toString(),
        if (folder) SavedBookFolder(uri, "Kitaplar 🌿") else null)
    private fun preview(id: String = "scan", tree: String = uri) = DiscoveryState.Preview(id, emptyList(), 0, treeUri = tree)

    @Test fun `saved folder can be scanned only by current foreground owner without importing anything`() = runTest {
        val fixture = Fixture(this)
        runCurrent()
        val expected = fixture.repo.source.value
        fixture.controller.scan("screen", expected)
        runCurrent(); assertTrue(fixture.scans.isEmpty())
        fixture.controller.bind("screen", true)
        fixture.controller.scan("screen", expected); fixture.controller.scan("screen", expected)
        runCurrent()
        assertEquals(listOf(expected), fixture.repo.resolved)
        assertEquals(listOf(uri), fixture.scans)
        assertEquals(0, fixture.repo.writes)
        assertFalse(fixture.controller.state.value.busy)
    }

    @Test fun `pause new owner disposal and explicit cancellation reject late resolved permission`() = runTest {
        for (cancel in 0..3) {
            val fixture = Fixture(this); runCurrent()
            fixture.controller.bind("old", true)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            fixture.repo.resolveAction = { withContext(NonCancellable) { entered.complete(Unit); release.await() }; uri }
            fixture.controller.scan("old", fixture.repo.source.value); runCurrent(); entered.await()
            when (cancel) {
                0 -> fixture.controller.bind("old", false)
                1 -> fixture.controller.bind("new", true)
                2 -> fixture.controller.release("old")
                else -> fixture.controller.cancelScan("old")
            }
            assertFalse(fixture.controller.state.value.resolving)
            release.complete(Unit); runCurrent()
            assertTrue(fixture.scans.isEmpty())
            assertNull(fixture.controller.state.value.feedback)
        }
    }

    @Test fun `disposed owner cannot cancel or forget the new owner's current work`() = runTest {
        val fixture = Fixture(this); runCurrent()
        fixture.controller.bind("old", true); fixture.controller.bind("new", true)
        val release = CompletableDeferred<Unit>()
        fixture.repo.resolveAction = { release.await(); uri }
        fixture.controller.scan("new", fixture.repo.source.value); runCurrent()
        fixture.controller.release("old"); fixture.controller.cancelScan("old")
        fixture.controller.forget("old", fixture.repo.source.value)
        assertTrue(fixture.controller.state.value.resolving)
        release.complete(Unit); runCurrent()
        assertEquals(listOf(uri), fixture.scans)
        assertEquals(0, fixture.repo.writes)
    }

    @Test fun `source revision modal preview and other mutation changes refuse late scan`() = runTest {
        for (change in 0..2) {
            val fixture = Fixture(this); runCurrent(); fixture.controller.bind("screen", true)
            val release = CompletableDeferred<Unit>()
            fixture.repo.resolveAction = { release.await(); uri }
            val expected = fixture.repo.source.value
            fixture.controller.scan("screen", expected); runCurrent()
            when (change) {
                0 -> fixture.repo.source.value = snapshot()
                1 -> fixture.currentPreview = preview()
                else -> fixture.allowed = false
            }
            runCurrent(); release.complete(Unit); runCurrent()
            assertTrue(fixture.scans.isEmpty()); assertFalse(fixture.controller.state.value.busy)
        }
    }

    @Test fun `remember binds current scan uri revision owner and duplicate mutation admission`() = runTest {
        val fixture = Fixture(this, snapshot(false)); runCurrent(); fixture.controller.bind("screen", true)
        fixture.currentPreview = preview()
        val expected = fixture.repo.source.value
        fixture.controller.remember("old", "scan", uri, expected)
        fixture.controller.remember("screen", "old-scan", uri, expected)
        fixture.controller.remember("screen", "scan", "$uri-other", expected)
        fixture.controller.remember("screen", "scan", uri, snapshot(false))
        runCurrent(); assertEquals(0, fixture.repo.writes)
        val release = CompletableDeferred<Unit>(); fixture.repo.writeAction = { release.await() }
        fixture.controller.remember("screen", "scan", uri, expected)
        fixture.controller.remember("screen", "scan", uri, expected)
        runCurrent(); assertTrue(fixture.controller.state.value.saving)
        assertEquals(1, fixture.repo.writes)
        fixture.controller.scan("screen", expected)
        fixture.controller.forget("screen", expected)
        fixture.controller.bind("screen", false)
        release.complete(Unit); runCurrent()
        assertFalse(fixture.controller.state.value.busy)
        assertEquals(uri, fixture.controller.state.value.snapshot!!.folder!!.treeUri)
        assertEquals(BookFolderFeedback("scan", BookFolderFeedback.Kind.SAVED), fixture.controller.state.value.feedback)
        assertTrue(fixture.scans.isEmpty())
    }

    @Test fun `unknown source never becomes empty configuration and explicit retry restores it`() = runTest {
        val fixture = Fixture(this)
        fixture.repo.observeFailure = BookFolderException(BookFolderFailure.RECOVERY)
        runCurrent(); fixture.controller.bind("screen", true)
        val state = fixture.controller.state.value
        assertFalse(state.ready); assertNull(state.snapshot)
        assertEquals(BookFolderFailure.RECOVERY, state.failure)
        fixture.controller.scan("screen", fixture.repo.source.value)
        fixture.currentPreview = preview()
        fixture.controller.remember("screen", "scan", uri, fixture.repo.source.value)
        runCurrent(); assertEquals(0, fixture.repo.writes); assertTrue(fixture.scans.isEmpty())
        fixture.repo.observeFailure = null; fixture.controller.reload(); runCurrent()
        assertTrue(fixture.controller.state.value.ready)
        assertEquals(fixture.repo.source.value, fixture.controller.state.value.snapshot)
    }

    @Test fun `permission or stale revision failure is visible without producing a scan or write`() = runTest {
        for (reason in listOf(BookFolderFailure.ACCESS, BookFolderFailure.CHANGED, BookFolderFailure.SOURCE)) {
            val fixture = Fixture(this); runCurrent(); fixture.controller.bind("screen", true)
            fixture.repo.resolveAction = { throw BookFolderException(reason) }
            fixture.controller.scan("screen", fixture.repo.source.value); runCurrent()
            val kind = when (reason) {
                BookFolderFailure.ACCESS -> BookFolderFeedback.Kind.ACCESS
                BookFolderFailure.CHANGED -> BookFolderFeedback.Kind.CHANGED
                else -> BookFolderFeedback.Kind.FAILED
            }
            assertEquals(BookFolderFeedback(null, kind), fixture.controller.state.value.feedback)
            assertTrue(fixture.scans.isEmpty()); assertEquals(0, fixture.repo.writes)
            assertFalse(fixture.controller.state.value.busy)
        }
    }

    @Test fun `cleanup warning keeps actual committed folder and forget refuses an open preview`() = runTest {
        val fixture = Fixture(this, snapshot(false)); runCurrent(); fixture.controller.bind("screen", true)
        fixture.currentPreview = preview(); fixture.repo.cleanupPending = true
        fixture.controller.remember("screen", "scan", uri, fixture.repo.source.value); runCurrent()
        assertEquals(BookFolderFeedback.Kind.CLEANUP, fixture.controller.state.value.feedback!!.kind)
        assertEquals(uri, fixture.controller.state.value.snapshot!!.folder!!.treeUri)
        fixture.controller.forget("screen", fixture.repo.source.value); runCurrent()
        assertEquals(1, fixture.repo.writes)
        fixture.currentPreview = null; fixture.repo.cleanupPending = false
        fixture.controller.forget("screen", fixture.repo.source.value); runCurrent()
        assertNull(fixture.controller.state.value.snapshot!!.folder)
        assertEquals(BookFolderFeedback(null, BookFolderFeedback.Kind.FORGOTTEN), fixture.controller.state.value.feedback)
    }

    @Test fun `opening check waits for actual foreground loaded eligible ui and runs once without preference writes`() = runTest {
        val fixture = Fixture(this, snapshot().copy(automaticScan = true)); runCurrent()
        fixture.controller.bind("screen", false)
        fixture.controller.reportOpeningUi("screen", false, false); runCurrent()
        assertTrue(fixture.scans.isEmpty())
        fixture.controller.bind("screen", true)
        fixture.controller.reportOpeningUi("screen", false, false); runCurrent()
        assertTrue(fixture.scans.isEmpty())
        fixture.controller.reportOpeningUi("screen", true, true); runCurrent()
        assertEquals(1, fixture.scans.size)
        fixture.controller.bind("screen", false); fixture.controller.bind("screen", true)
        fixture.controller.bind("next-owner", true); fixture.controller.reportOpeningUi("next-owner", true, true)
        fixture.controller.reload(); runCurrent()
        assertEquals(listOf(uri), fixture.scans); assertEquals(0, fixture.repo.writes)
    }

    @Test fun `default off explicit enable waits for next library instance and old opt-in callbacks do nothing`() = runTest {
        val fixture = Fixture(this); runCurrent(); fixture.controller.bind("screen", true)
        fixture.controller.reportOpeningUi("screen", true, true); runCurrent()
        val original = fixture.repo.source.value
        assertFalse(original.automaticScan); assertTrue(fixture.scans.isEmpty())
        fixture.controller.setAutomaticScan("old", original, true)
        fixture.controller.setAutomaticScan("screen", snapshot(), true); runCurrent()
        assertEquals(0, fixture.repo.writes)
        fixture.controller.setAutomaticScan("screen", original, true); runCurrent()
        assertTrue(fixture.controller.state.value.snapshot!!.automaticScan)
        assertEquals(BookFolderFeedback.Kind.AUTO_ENABLED, fixture.controller.state.value.feedback!!.kind)
        fixture.controller.reportOpeningUi("screen", true, true)
        fixture.controller.bind("screen", false); fixture.controller.bind("screen", true); runCurrent()
        assertTrue(fixture.scans.isEmpty())
        val next = Fixture(this, fixture.repo.source.value); runCurrent()
        next.controller.bind("next", true); next.controller.reportOpeningUi("next", true, true); runCurrent()
        assertEquals(listOf(uri), next.scans)
    }

    @Test fun `initial modal busy or error screen suppresses opening scan without deferred replay`() = runTest {
        for (block in 0..2) {
            val fixture = Fixture(this, snapshot().copy(automaticScan = true)); runCurrent()
            fixture.controller.bind("screen", true)
            when (block) { 0 -> fixture.currentPreview = preview(); 1 -> fixture.allowed = false }
            fixture.controller.reportOpeningUi("screen", true, block != 2); runCurrent()
            fixture.currentPreview = null; fixture.allowed = true
            fixture.controller.reportOpeningUi("screen", true, true); fixture.controller.reload(); runCurrent()
            assertTrue(fixture.scans.isEmpty())
        }
        val failure = Fixture(this, snapshot().copy(automaticScan = true))
        failure.repo.observeFailure = BookFolderException(BookFolderFailure.SOURCE)
        runCurrent(); failure.controller.bind("screen", true)
        failure.controller.reportOpeningUi("screen", true, true); runCurrent()
        failure.repo.observeFailure = null; failure.controller.reload(); runCurrent()
        assertTrue(failure.scans.isEmpty())
    }

    @Test fun `opening owner cannot inherit disposed ui and cancelled native proof cannot replay on resume`() = runTest {
        val fixture = Fixture(this, snapshot().copy(automaticScan = true)); runCurrent()
        fixture.controller.bind("old", true)
        fixture.controller.reportOpeningUi("old", false, false)
        fixture.controller.bind("new", true)
        fixture.controller.reportOpeningUi("old", true, true); runCurrent()
        assertTrue(fixture.scans.isEmpty())
        val release = CompletableDeferred<Unit>()
        fixture.repo.resolveAction = { withContext(NonCancellable) { release.await() }; uri }
        fixture.controller.reportOpeningUi("new", true, true); runCurrent()
        assertTrue(fixture.controller.state.value.resolving)
        fixture.controller.bind("new", false); release.complete(Unit); runCurrent()
        fixture.controller.bind("new", true); fixture.controller.reportOpeningUi("new", true, true); runCurrent()
        assertTrue(fixture.scans.isEmpty()); assertEquals(1, fixture.repo.resolved.size)
    }

    @Test fun `explicit read or preference action before delayed ui reporting consumes opening check`() = runTest {
        for (write in listOf(false, true)) {
            val fixture = Fixture(this, snapshot().copy(automaticScan = true)); runCurrent(); fixture.controller.bind("screen", true)
            if (write) fixture.controller.setAutomaticScan("screen", fixture.repo.source.value, false)
            else fixture.controller.scan("screen", fixture.repo.source.value)
            runCurrent(); fixture.controller.reportOpeningUi("screen", true, true); runCurrent()
            assertEquals(if (write) 0 else 1, fixture.scans.size)
        }
    }

    @Test fun `pause cancels exact opening scanner but cannot cancel a newer manually started scan`() = runTest {
        for (newManual in listOf(false, true)) {
            val fixture = Fixture(this, snapshot().copy(automaticScan = true)); runCurrent()
            fixture.controller.bind("screen", true); fixture.controller.reportOpeningUi("screen", true, true); runCurrent()
            assertEquals("scan-1", fixture.nativeScanId)
            if (newManual) {
                fixture.nativeScanId = null // Opening preview completed and was explicitly dismissed.
                fixture.controller.scan("screen", fixture.repo.source.value); runCurrent()
                assertEquals("scan-2", fixture.nativeScanId)
            }
            fixture.controller.bind("screen", false)
            assertEquals(if (newManual) emptyList<String>() else listOf("scan-1"), fixture.cancelledNativeScans)
            assertEquals(if (newManual) "scan-2" else null, fixture.nativeScanId)
        }
    }

    private inner class Fixture(scope: TestScope, initial: BookFolderSnapshot = snapshot()) {
        val repo = Repository(initial)
        var allowed = true
        var currentPreview: DiscoveryState.Preview? = null
        val scans = mutableListOf<String>()
        var nativeScanId: String? = null
        val cancelledNativeScans = mutableListOf<String>()
        val controller = BookFolderController(repo, scope.backgroundScope, { allowed }, { currentPreview },
            { scans += it; nativeScanId = "scan-${scans.size}"; true }, currentScanId = { nativeScanId },
            cancelOpeningScan = { id -> if (nativeScanId == id) { cancelledNativeScans += id; nativeScanId = null } })
    }
    private inner class Repository(initial: BookFolderSnapshot) : BookFolderRepository {
        val source = MutableStateFlow(initial)
        val resolved = mutableListOf<BookFolderSnapshot>()
        var writes = 0; var cleanupPending = false
        var observeFailure: Exception? = null
        var resolveAction: (suspend () -> String)? = null
        var writeAction: (suspend () -> Unit)? = null
        override fun observe() = flow { observeFailure?.let { throw it }; emitAll(source) }
        override suspend fun save(expected: BookFolderSnapshot, selectedTreeUri: String): BookFolderWrite {
            writes++; writeAction?.invoke()
            source.value = snapshot().copy(folder = SavedBookFolder(selectedTreeUri, "Kitaplar 🌿"))
            return BookFolderWrite(source.value, cleanupPending)
        }
        override suspend fun forget(expected: BookFolderSnapshot): BookFolderWrite {
            writes++; writeAction?.invoke(); source.value = snapshot(false)
            return BookFolderWrite(source.value, cleanupPending)
        }
        override suspend fun setAutomaticScan(expected: BookFolderSnapshot, enabled: Boolean): BookFolderWrite {
            writes++; writeAction?.invoke()
            source.value = source.value.copy(revision = UUID.randomUUID().toString(), automaticScan = enabled)
            return BookFolderWrite(source.value, cleanupPending)
        }
        override suspend fun resolveForScan(expected: BookFolderSnapshot): String {
            resolved += expected; return resolveAction?.invoke() ?: uri
        }
        override suspend fun retryCleanup() = Unit
    }
}

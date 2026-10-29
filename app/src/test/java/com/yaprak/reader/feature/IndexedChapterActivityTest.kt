package com.yaprak.reader.feature

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.*
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Actual Activity/lifecycle, controlled reader capability and replayable actual locator.
 * Native XHTML verification is tested by the EPUB adapter fixtures, not by this fake boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
class IndexedChapterActivityTest {
    private class Storage : ReadingStateRepository {
        val records = mutableListOf<ReadingSnapshot>()
        override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
        override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) { records += snapshot }
    }
    private class Navigator : IndexedChapterNavigator {
        val verifications = mutableListOf<LibraryTextTarget>()
        val navigations = mutableListOf<String>()
        var verify: suspend (LibraryTextTarget) -> String? = { it.resourceHref }
        var go: (String) -> Boolean = { true }
        override suspend fun verifiedChapterHref(target: LibraryTextTarget): String? {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            verifications += target; return verify(target)
        }
        override fun goToChapter(href: String): Boolean {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            navigations += href; return go(href)
        }
    }
    private class PdfNavigator : IndexedPdfPageNavigator {
        override var supported = true
        val verifications = mutableListOf<LibraryTextTarget>()
        val navigations = mutableListOf<Int>()
        var verify: suspend (LibraryTextTarget) -> Int? = { it.pdfPageIndex }
        var go: (Int) -> Boolean = { true }
        override suspend fun verifiedPageIndex(target: LibraryTextTarget): Int? {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            verifications += target; return verify(target)
        }
        override fun goToPage(pageIndex: Int): Boolean {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            navigations += pageIndex; return go(pageIndex)
        }
    }

    private class Surface(val tap: () -> Unit) : ReaderSurface {
        override val fragment = Fragment()
        override val renderingFailed = MutableStateFlow(false)
        val navigator = Navigator()
        override var indexedChapterNavigator: IndexedChapterNavigator? = navigator
        val pdfNavigator = PdfNavigator()
        override var indexedPdfPageNavigator: IndexedPdfPageNavigator? = null
        private val actual = MutableStateFlow<ReadingSnapshot?>(null)
        var observations = 0
        var captureEffect: () -> Unit = {}
        override val bookmarkNavigator = object : BookmarkNavigator {
            override fun capture(): BookmarkTarget {
                captureEffect(); return BookmarkTarget("Current", actual.value?.location ?: location("old.xhtml"))
            }
            override fun goTo(location: ReadingLocation) = true
        }
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
            observations++; actual.filterNotNull().collect { onSnapshot(it) }
        }
        fun reportActual(location: ReadingLocation) { actual.value = ReadingSnapshot(location) }
        override fun forward() = true
        override fun backward() = true
    }
    private class Session(val configure: (Surface) -> Unit = {}) : ReaderSession {
        override var book = Book("indexed-book", "Library search integration", null, BookFormat.EPUB,
            "search.epub", "search.epub", 1, "a".repeat(64), 1)
        override val resume = location("old.xhtml")
        val initials = mutableListOf<ReadingLocation?>()
        val surfaces = mutableListOf<Surface>()
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface {
            initials += initial
            return Surface(onTap).also { configure(it); surfaces += it }
        }
        override fun close() = Unit
        fun target() = if (book.format == BookFormat.PDF) LibraryTextTarget(book.id, book.sha256, "", "b".repeat(64),
            sourceFormat = BookFormat.PDF, pdfPageIndex = 2)
            else if (book.format == BookFormat.TXT) LibraryTextTarget(book.id, book.sha256, "Text/part0002.xhtml", "b".repeat(64), sourceFormat = BookFormat.TXT)
            else if (book.format == BookFormat.FB2) LibraryTextTarget(book.id, book.sha256, "Text/b1-p2.xhtml", "b".repeat(64), sourceFormat = BookFormat.FB2)
            else LibraryTextTarget(book.id, book.sha256, "OPS/chapter.xhtml", "b".repeat(64))
    }
    private class AndroidMainDispatcher : CoroutineDispatcher() {
        private val handler = Handler(Looper.getMainLooper())
        override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() !== handler.looper
        override fun dispatch(context: CoroutineContext, block: Runnable) { check(handler.post(block)) }
    }
    private class Fixture(val host: ActivityController<ReaderActivity>, val session: Session,
        val storage: Storage, val reader: ReaderViewModel) {
        val activity get() = host.get()
        val surface get() = session.surfaces.last()
        val state get() = reader.indexedChapterOpen.state.value
        fun awaitPhase(phase: IndexedChapterPhase) = await("chapter phase $phase") { state.phase == phase }
    }

    @Test fun `verified chapter opens once and only actual reader snapshots record progress without observer replay`() = runTest {
        val verified = CompletableDeferred<String?>()
        val session = Session { it.navigator.verify = { verified.await() } }
        withHost(session) {
            awaitPhase(IndexedChapterPhase.VERIFYING)
            assertEquals(listOf(session.target()), surface.navigator.verifications)
            val subscriptions = surface.observations
            surface.reportActual(location("old.xhtml")); tick()
            assertEquals(listOf(location("old.xhtml")), storage.records.map { it.location })
            verified.complete(session.target().resourceHref); awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertEquals(listOf(session.target().resourceHref), surface.navigator.navigations)
            assertEquals(subscriptions, surface.observations)
            assertEquals(listOf(location("old.xhtml")), storage.records.map { it.location })
            assertEquals(location("old.xhtml"), reader.latestLocation)
            invoke(activity, "startIndexedChapter"); tick()
            assertEquals(1, surface.navigator.verifications.size); assertEquals(1, surface.navigator.navigations.size)
            surface.reportActual(location(session.target().resourceHref)); tick()
            assertEquals(listOf(location("old.xhtml"), location(session.target().resourceHref)), storage.records.map { it.location })
            assertEquals(location(session.target().resourceHref), reader.latestLocation)
            assertTrue(storage.records.all { it.spans.isEmpty() })
        }
    }

    @Test fun `wrong book hash and unsupported capability fail without native requests or progress writes`() = runTest {
        for (kind in listOf("book", "hash", "format", "capability")) {
            val session = Session { if (kind == "capability") it.indexedChapterNavigator = null }
            val target = session.target()
            when (kind) {
                "book" -> session.book = session.book.copy(id = "other")
                "hash" -> session.book = session.book.copy(sha256 = "c".repeat(64))
                "format" -> session.book = session.book.copy(format = BookFormat.PDF)
            }
            withHost(session, launchTarget = target) {
                awaitPhase(IndexedChapterPhase.FAILED)
                assertEquals(if (kind in listOf("book", "hash")) IndexedChapterFailure.SOURCE
                    else IndexedChapterFailure.UNSUPPORTED, state.failure)
                assertTrue(surface.navigator.verifications.isEmpty()); assertTrue(surface.navigator.navigations.isEmpty())
                assertTrue(storage.records.isEmpty()); assertFalse(property(activity, "getLoadFailed") as Boolean)
                invoke(activity, "startIndexedChapter"); tick(); assertEquals(IndexedChapterPhase.FAILED, state.phase)
            }
        }
    }

    @Test fun `null wrong or exceptional source verification keeps Ready and requires explicit retry`() = runTest {
        for (kind in listOf("null", "wrong", "exception")) {
            val session = Session { it.navigator.verify = {
                when (kind) { "null" -> null; "wrong" -> "other.xhtml"; else -> throw java.io.IOException("Unavailable") }
            } }
            withHost(session) {
                awaitPhase(IndexedChapterPhase.FAILED)
                assertEquals(IndexedChapterFailure.SOURCE, state.failure)
                assertTrue(surface.navigator.navigations.isEmpty()); assertTrue(storage.records.isEmpty())
                assertFalse(property(activity, "getLoadFailed") as Boolean)
                val failed = state
                invoke(activity, "startIndexedChapter"); tick(); assertEquals(1, surface.navigator.verifications.size)
                surface.navigator.verify = { it.resourceHref }
                assertTrue(retry(activity, failed)); awaitPhase(IndexedChapterPhase.ACCEPTED)
                assertEquals(2, surface.navigator.verifications.size); assertEquals(1, surface.navigator.navigations.size)
                assertFalse(retry(activity, failed)); assertTrue(storage.records.isEmpty())
            }
        }
    }

    @Test fun `rejected or throwing navigation never fabricates destination and only fresh retry can succeed`() = runTest {
        for (throws in listOf(false, true)) {
            val session = Session { it.navigator.go = { if (throws) throw java.io.IOException("Rejected") else false } }
            withHost(session) {
                awaitPhase(IndexedChapterPhase.FAILED)
                assertEquals(IndexedChapterFailure.NAVIGATION, state.failure)
                assertEquals(session.resume, reader.latestLocation); assertTrue(storage.records.isEmpty())
                assertEquals(1, surface.navigator.navigations.size)
                val failed = state; val subscriptions = surface.observations
                invoke(activity, "startIndexedChapter"); tick(); assertEquals(1, surface.navigator.navigations.size)
                surface.navigator.go = { true }
                assertTrue(retry(activity, failed)); awaitPhase(IndexedChapterPhase.ACCEPTED)
                assertEquals(2, surface.navigator.navigations.size); assertEquals(subscriptions, surface.observations)
                assertTrue(storage.records.isEmpty())
            }
        }
    }

    @Test fun `late verification rejects changed full Book source capability surface modal or failure state`() = runTest {
        for (change in listOf("metadata", "hash", "book", "surface", "capability", "modal", "load", "save")) {
            val late = CompletableDeferred<String?>()
            val session = Session { it.navigator.verify = { late.await() } }
            withHost(session) {
                awaitPhase(IndexedChapterPhase.VERIFYING)
                val original = surface
                when (change) {
                    "metadata" -> session.book = session.book.copy(title = "Changed")
                    "hash" -> session.book = session.book.copy(sha256 = "c".repeat(64))
                    "book" -> session.book = session.book.copy(id = "other")
                    "surface" -> setSurface(activity, Surface {})
                    "capability" -> original.indexedChapterNavigator = Navigator()
                    "modal" -> flag(activity, "setContentsOpen", true)
                    "load" -> flag(activity, "setLoadFailed", true)
                    "save" -> {
                        @Suppress("UNCHECKED_CAST")
                        val failed = ReaderViewModel::class.java.getDeclaredField("_saveFailed")
                            .apply { isAccessible = true }.get(reader) as MutableStateFlow<Boolean>
                        failed.value = true
                    }
                }
                late.complete("OPS/chapter.xhtml"); awaitPhase(IndexedChapterPhase.FAILED)
                assertEquals(IndexedChapterFailure.INTERRUPTED, state.failure)
                assertTrue(original.navigator.navigations.isEmpty()); assertTrue(storage.records.isEmpty())
            }
        }
    }

    @Test fun `bookmark capture cannot admit old navigation after reentrant modal surface or book replacement`() = runTest {
        for (change in listOf("surface", "book", "modal")) {
            val late = CompletableDeferred<String?>()
            val session = Session { it.navigator.verify = { late.await() } }
            withHost(session) {
                awaitPhase(IndexedChapterPhase.VERIFYING)
                val original = surface
                original.captureEffect = {
                    when (change) {
                        "surface" -> setSurface(activity, Surface {})
                        "book" -> session.book = session.book.copy(title = "Changed in capture")
                        "modal" -> flag(activity, "setContentsOpen", true)
                    }
                }
                late.complete(session.target().resourceHref); awaitPhase(IndexedChapterPhase.FAILED)
                assertEquals(IndexedChapterFailure.INTERRUPTED, state.failure)
                assertTrue(original.navigator.navigations.isEmpty()); assertFalse(property(activity, "getCanJumpBack") as Boolean)
                assertTrue(storage.records.isEmpty())
            }
        }
    }

    @Test fun `pause cancels old verification and its late delivery cannot overwrite resumed attempt`() = runTest {
        val old = CompletableDeferred<String?>(); val current = CompletableDeferred<String?>()
        val queue = ArrayDeque(listOf(old, current))
        val session = Session { it.navigator.verify = { queue.removeFirst().await() } }
        withHost(session) {
            awaitPhase(IndexedChapterPhase.VERIFYING)
            val oldAttempt = state.attemptId
            host.pause(); tick(); assertEquals(IndexedChapterPhase.PENDING, state.phase)
            assertTrue(surface.navigator.navigations.isEmpty())
            host.resume(); awaitPhase(IndexedChapterPhase.VERIFYING)
            assertNotEquals(oldAttempt, state.attemptId); assertEquals(2, surface.navigator.verifications.size)
            old.complete(session.target().resourceHref); tick()
            assertEquals(IndexedChapterPhase.VERIFYING, state.phase); assertTrue(surface.navigator.navigations.isEmpty())
            current.complete(session.target().resourceHref); awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertEquals(1, surface.navigator.navigations.size); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `recreation preserves accepted receipt and resumes actual latest location without second jump`() = runTest {
        withHost {
            awaitPhase(IndexedChapterPhase.ACCEPTED)
            val old = surface
            val latest = ReadingLocation("{\"href\":\"OPS/chapter.xhtml\",\"locations\":{\"progression\":0.5}}", "OPS/chapter.xhtml")
            old.reportActual(latest); tick(); assertEquals(1, storage.records.size)
            host.recreate(); awaitSurface(activity, session, 2); tick()
            assertEquals(IndexedChapterPhase.ACCEPTED, state.phase)
            assertEquals(latest, session.initials.last()); assertTrue(surface.navigator.verifications.isEmpty())
            assertTrue(surface.navigator.navigations.isEmpty()); assertEquals(1, old.navigator.navigations.size)
            assertEquals(1, storage.records.size)
        }
    }

    @Test fun `accepted receipt is saved before SDK call and cold recreation will not repeat that call`() = runTest {
        val saved = Bundle(); var launch: Intent? = null
        val session = Session()
        val late = CompletableDeferred<String?>()
        val first = Session { it.navigator.verify = { late.await() } }
        withHost(first) {
            awaitPhase(IndexedChapterPhase.VERIFYING)
            launch = activity.intent
            surface.navigator.go = {
                host.saveInstanceState(saved)
                val receipt = IndexedChapterLaunch.restore(saved, IndexedChapterLaunch.read(activity.intent))!!
                assertEquals(IndexedChapterPhase.ACCEPTED, receipt.phase)
                true
            }
            late.complete(first.target().resourceHref); awaitPhase(IndexedChapterPhase.ACCEPTED)
        }
        withHost(session, intent = launch!!, saved = saved) {
            awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertTrue(surface.navigator.verifications.isEmpty()); assertTrue(surface.navigator.navigations.isEmpty())
            assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `failed receipt survives cold recreation and explicitly retries only on the new current surface`() = runTest {
        val saved = Bundle(); var launch: Intent? = null
        val first = Session { it.navigator.go = { false } }
        withHost(first) {
            awaitPhase(IndexedChapterPhase.FAILED); launch = activity.intent; host.saveInstanceState(saved)
        }
        withHost(intent = launch!!, saved = saved) {
            awaitPhase(IndexedChapterPhase.FAILED)
            assertTrue(surface.navigator.verifications.isEmpty()); assertTrue(surface.navigator.navigations.isEmpty())
            assertTrue(retry(activity, state)); awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertEquals(1, surface.navigator.navigations.size); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `recreation during verification drops old callback and verifies using the new exact surface`() = runTest {
        val old = CompletableDeferred<String?>(); val next = CompletableDeferred<String?>()
        val queue = ArrayDeque(listOf(old, next))
        val session = Session { it.navigator.verify = { queue.removeFirst().await() } }
        withHost(session) {
            awaitPhase(IndexedChapterPhase.VERIFYING); val original = surface
            host.recreate(); awaitSurface(activity, session, 2); awaitPhase(IndexedChapterPhase.VERIFYING)
            old.complete(session.target().resourceHref); tick()
            assertTrue(original.navigator.navigations.isEmpty()); assertTrue(surface.navigator.navigations.isEmpty())
            next.complete(session.target().resourceHref); awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertEquals(1, surface.navigator.navigations.size); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `old book id intent and malformed target continue ordinary reading without chapter action`() = runTest {
        val session = Session()
        val context = RuntimeEnvironment.getApplication()
        for (intent in listOf(ReaderActivity.intent(context, session.book.id),
            ReaderActivity.intent(context, session.target()).putExtra("indexedChapterVersion", 2))) {
            withHost(Session(), intent = intent) {
                assertEquals(IndexedChapterPhase.NONE, state.phase)
                assertTrue(surface.navigator.verifications.isEmpty()); assertTrue(surface.navigator.navigations.isEmpty())
                assertEquals(session.resume, reader.latestLocation); assertTrue(storage.records.isEmpty())
            }
        }
    }

    private suspend fun TestScope.withHost(session: Session = Session(), launchTarget: LibraryTextTarget = session.target(),
        intent: Intent = ReaderActivity.intent(RuntimeEnvironment.getApplication(), launchTarget), saved: Bundle? = null,
        test: suspend Fixture.() -> Unit) {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java, intent)
        try {
            val storage = Storage(); val reader = ReaderViewModel(session.book.id, ReaderLoader { session }, storage)
            host.create(saved)
            host.get().viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${ReaderViewModel::class.java.canonicalName}", reader)
            host.start().resume(); awaitSurface(host.get(), session)
            Fixture(host, session, storage, reader).test()
        } finally { host.pause().stop().destroy(); tick(); Dispatchers.resetMain() }
    }

    private fun pdfSession(configure: (Surface) -> Unit = {}) = Session {
        it.indexedChapterNavigator = null; it.indexedPdfPageNavigator = it.pdfNavigator; configure(it)
    }.apply { book = book.copy(format = BookFormat.PDF, pageCount = 3, storageName = "search.pdf") }

    private fun fb2Session(configure: (Surface) -> Unit = {}) = Session(configure)
        .apply { book = book.copy(format = BookFormat.FB2, storageName = "search.fb2") }

    @Test fun `typed FB2 original part opens once and accepted cold receipt never invents progress or EPUB coverage`() = runTest {
        val saved = Bundle(); var launch: Intent? = null
        withHost(fb2Session()) {
            awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertEquals(listOf(session.target()), surface.navigator.verifications)
            assertEquals(listOf("Text/b1-p2.xhtml"), surface.navigator.navigations)
            assertTrue(storage.records.isEmpty()); invoke(activity, "startIndexedChapter"); tick()
            assertEquals(1, surface.navigator.navigations.size)
            surface.reportActual(location("Text/b1-p2.xhtml")); tick()
            assertEquals(listOf(location("Text/b1-p2.xhtml")), storage.records.map { it.location })
            assertTrue(storage.records.single().spans.isEmpty())
            launch = activity.intent; host.saveInstanceState(saved)
        }
        withHost(fb2Session(), intent = launch!!, saved = saved) {
            awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertTrue(surface.navigator.verifications.isEmpty()); assertTrue(surface.navigator.navigations.isEmpty())
            assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun `FB2 launch cannot cross into legacy EPUB route or use a missing capability or changed hash`() = runTest {
        for (kind in listOf("legacy", "capability", "hash")) {
            val session = fb2Session { if (kind == "capability") it.indexedChapterNavigator = null }
            val target = session.target()
            val intent = ReaderActivity.intent(RuntimeEnvironment.getApplication(), target)
            if (kind == "legacy") intent.removeExtra("indexedChapterFormat")
            if (kind == "hash") session.book = session.book.copy(sha256 = "c".repeat(64))
            withHost(session, intent = intent) {
                awaitPhase(IndexedChapterPhase.FAILED)
                assertEquals(if (kind == "hash") IndexedChapterFailure.SOURCE else IndexedChapterFailure.UNSUPPORTED, state.failure)
                assertTrue(surface.navigator.verifications.isEmpty()); assertTrue(surface.navigator.navigations.isEmpty())
                assertTrue(storage.records.isEmpty())
            }
        }
    }

    private fun txtSession(configure: (Surface) -> Unit = {}) = Session(configure)
        .apply { book = book.copy(format = BookFormat.TXT, storageName = "search.txt") }

    @Test fun `typed TXT original part opens once and accepted cold receipt never invents progress or EPUB coverage`() = runTest {
        val saved = Bundle(); var launch: Intent? = null
        withHost(txtSession()) {
            awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertEquals(listOf(session.target()), surface.navigator.verifications)
            assertEquals(listOf("Text/part0002.xhtml"), surface.navigator.navigations)
            assertTrue(storage.records.isEmpty()); invoke(activity, "startIndexedChapter"); tick()
            assertEquals(1, surface.navigator.navigations.size)
            surface.reportActual(location("Text/part0002.xhtml")); tick()
            assertEquals(listOf(location("Text/part0002.xhtml")), storage.records.map { it.location })
            assertTrue(storage.records.single().spans.isEmpty())
            launch = activity.intent; host.saveInstanceState(saved)
        }
        withHost(txtSession(), intent = launch!!, saved = saved) {
            awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertTrue(surface.navigator.verifications.isEmpty()); assertTrue(surface.navigator.navigations.isEmpty())
            assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun `TXT launch cannot cross into legacy EPUB route or use a missing capability or changed hash`() = runTest {
        for (kind in listOf("legacy", "capability", "hash")) {
            val session = txtSession { if (kind == "capability") it.indexedChapterNavigator = null }
            val target = session.target()
            val intent = ReaderActivity.intent(RuntimeEnvironment.getApplication(), target)
            if (kind == "legacy") intent.removeExtra("indexedChapterFormat")
            if (kind == "hash") session.book = session.book.copy(sha256 = "c".repeat(64))
            withHost(session, intent = intent) {
                awaitPhase(IndexedChapterPhase.FAILED)
                assertEquals(if (kind == "hash") IndexedChapterFailure.SOURCE else IndexedChapterFailure.UNSUPPORTED, state.failure)
                assertTrue(surface.navigator.verifications.isEmpty()); assertTrue(surface.navigator.navigations.isEmpty())
                assertTrue(storage.records.isEmpty())
            }
        }
    }
    @Test fun `TXT recreation cancels old surface verification and only resumed new owner can open the part`() = runTest {
        val old = CompletableDeferred<String?>(); val next = CompletableDeferred<String?>()
        val queue = ArrayDeque(listOf(old, next))
        val session = txtSession { it.navigator.verify = { queue.removeFirst().await() } }
        withHost(session) {
            awaitPhase(IndexedChapterPhase.VERIFYING); val first = surface
            host.recreate(); awaitSurface(activity, session, 2); awaitPhase(IndexedChapterPhase.VERIFYING)
            old.complete(session.target().resourceHref); tick()
            assertTrue(first.navigator.navigations.isEmpty()); assertTrue(surface.navigator.navigations.isEmpty())
            next.complete(session.target().resourceHref); awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertEquals(listOf(session.target().resourceHref), surface.navigator.navigations); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `typed PDF result opens real page once and click alone cannot record progress`() = runTest {
        val verified = CompletableDeferred<Int?>()
        val session = pdfSession { it.pdfNavigator.verify = { verified.await() } }
        withHost(session) {
            awaitPhase(IndexedChapterPhase.VERIFYING)
            assertEquals(listOf(session.target()), surface.pdfNavigator.verifications); assertTrue(storage.records.isEmpty())
            val subscriptions = surface.observations
            verified.complete(2); awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertEquals(listOf(2), surface.pdfNavigator.navigations); assertTrue(storage.records.isEmpty())
            assertEquals(subscriptions, surface.observations)
            invoke(activity, "startIndexedChapter"); tick(); assertEquals(1, surface.pdfNavigator.navigations.size)
            val actualPage = com.yaprak.reader.formats.pdf.PdfPosition.encode(2)
            surface.reportActual(actualPage); tick()
            assertEquals(listOf(actualPage), storage.records.map { it.location }); assertTrue(storage.records.single().spans.isEmpty())
        }
    }

    @Test fun `unsupported native PDF capability or changed source book cannot request navigation`() = runTest {
        for (kind in listOf("native", "missing", "hash")) {
            val session = pdfSession {
                if (kind == "native") it.pdfNavigator.supported = false
                if (kind == "missing") it.indexedPdfPageNavigator = null
            }
            val target = session.target()
            if (kind == "hash") session.book = session.book.copy(sha256 = "c".repeat(64))
            withHost(session, launchTarget = target) {
                awaitPhase(IndexedChapterPhase.FAILED)
                assertEquals(if (kind == "hash") IndexedChapterFailure.SOURCE else IndexedChapterFailure.UNSUPPORTED, state.failure)
                assertTrue(surface.pdfNavigator.verifications.isEmpty()); assertTrue(surface.pdfNavigator.navigations.isEmpty())
                assertTrue(storage.records.isEmpty())
            }
        }
    }

    @Test fun `wrong PDF page proof and replaced surface capability cannot consume another target`() = runTest {
        for (kind in listOf("wrong", "replacement", "navigation")) {
            val session = pdfSession { surface ->
                surface.pdfNavigator.verify = {
                    if (kind == "replacement") surface.indexedPdfPageNavigator = null
                    if (kind == "wrong") 1 else 2
                }
                if (kind == "navigation") surface.pdfNavigator.go = { false }
            }
            withHost(session) {
                awaitPhase(IndexedChapterPhase.FAILED)
                assertEquals(when (kind) { "wrong" -> IndexedChapterFailure.SOURCE
                    "replacement" -> IndexedChapterFailure.INTERRUPTED; else -> IndexedChapterFailure.NAVIGATION }, state.failure)
                assertEquals(if (kind == "navigation") listOf(2) else emptyList<Int>(), surface.pdfNavigator.navigations)
                assertTrue(storage.records.isEmpty()); assertFalse(property(activity, "getLoadFailed") as Boolean)
            }
        }
    }

    @Test fun `PDF recreation discards old verification and accepted saved receipt prevents repeat page jump`() = runTest {
        val old = CompletableDeferred<Int?>(); val next = CompletableDeferred<Int?>(); val queue = ArrayDeque(listOf(old, next))
        val session = pdfSession { it.pdfNavigator.verify = { queue.removeFirst().await() } }
        val saved = Bundle(); var launch: Intent? = null
        withHost(session) {
            awaitPhase(IndexedChapterPhase.VERIFYING); val original = surface
            host.recreate(); awaitSurface(activity, session, 2); awaitPhase(IndexedChapterPhase.VERIFYING)
            old.complete(2); tick(); assertTrue(original.pdfNavigator.navigations.isEmpty()); assertTrue(surface.pdfNavigator.navigations.isEmpty())
            next.complete(2); awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertEquals(listOf(2), surface.pdfNavigator.navigations); assertTrue(storage.records.isEmpty())
            launch = activity.intent; host.saveInstanceState(saved)
        }
        withHost(pdfSession(), intent = launch!!, saved = saved) {
            awaitPhase(IndexedChapterPhase.ACCEPTED)
            assertTrue(surface.pdfNavigator.verifications.isEmpty()); assertTrue(surface.pdfNavigator.navigations.isEmpty())
            assertTrue(storage.records.isEmpty())
        }
    }
    private fun invoke(activity: ReaderActivity, method: String) {
        activity.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
    }
    private fun property(activity: ReaderActivity, getter: String): Any? =
        activity.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(activity)
    private fun retry(activity: ReaderActivity, expected: IndexedChapterState): Boolean =
        activity.javaClass.getDeclaredMethod("retryIndexedChapter", IndexedChapterState::class.java)
            .apply { isAccessible = true }.invoke(activity, expected) as Boolean
    private fun flag(activity: ReaderActivity, setter: String, value: Boolean) {
        activity.javaClass.getDeclaredMethod(setter, Boolean::class.javaPrimitiveType!!)
            .apply { isAccessible = true }.invoke(activity, value)
    }
    private fun setSurface(activity: ReaderActivity, surface: ReaderSurface) {
        activity.javaClass.getDeclaredField("surface").apply { isAccessible = true }.set(activity, surface)
    }
    private companion object {
        fun location(href: String) = ReadingLocation("{\"href\":\"$href\"}", href)
        fun tick() { ShadowLooper.idleMainLooper() }
        fun await(description: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition()) {
                tick(); check(System.nanoTime() < deadline) { "Android Main did not deliver $description" }; Thread.yield()
            }
            tick()
        }
        fun awaitSurface(activity: ReaderActivity, session: Session, count: Int = 1) {
            val field = activity.javaClass.getDeclaredField("surface").apply { isAccessible = true }
            await("reader surface") { field.get(activity) != null && session.surfaces.size >= count }
            assertEquals(count, session.surfaces.size); assertSame(session.surfaces.last(), field.get(activity))
        }
    }
}

package com.yaprak.reader.feature

import android.os.Handler
import android.os.Looper
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.fragment.app.Fragment
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.feature.passages.EpubAnnotationRequest
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.*
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Real Activity and lifecycle with a valid controlled reader boundary. Does not claim WebView paint. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
class EpubAnnotationActivityTest {
    private val anchor = SourceTextAnchor("chapter.xhtml", 0, 4, "a".repeat(64))
    private class Storage : ReadingStateRepository {
        val records = mutableListOf<ReadingSnapshot>()
        override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
        override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) { records += snapshot }
    }
    private class Surface(val tap: () -> Unit, format: BookFormat = BookFormat.EPUB) : ReaderSurface {
        override val fragment = Fragment()
        override val renderingFailed = MutableStateFlow(false)
        val selections = ArrayDeque<CompletableDeferred<SelectedText?>>()
        var nativeSelectionId: String? = null
        override val textSelection = object : TextSelectionSource {
            override suspend fun capture() = selections.removeFirst().await()
            override fun isCurrent(requestId: String?) = nativeSelectionId == null || nativeSelectionId == requestId
        }
        override val pageNavigator: PageNavigator? = if (format != BookFormat.PDF) null else object : PageNavigator {
            override val pageCount = 3
            override val currentPage = 2
            override fun captureStablePage() = currentPage
            override fun goTo(pageIndex: Int) = pageIndex in 0 until pageCount
        }
        var coverage: (() -> Boolean)? = null
        var observations = 0
        var forwards = 0; var retries = 0; var chapterCalls = 0
        val paints = MutableStateFlow<EpubHighlightStatus>(EpubHighlightStatus.Unsupported)
        override val epubHighlights = object : EpubHighlights {
            override val status = paints
            override suspend fun retry() { retries++ }
        }
        override val passageNavigator = object : PassageNavigator {
            override suspend fun verifiedChapterHref(passage: EpubPassage): String { chapterCalls++; return passage.anchor.resourceHref }
            override fun goToChapter(href: String): Boolean { chapterCalls++; return true }
        }
        override val contentsNavigator = object : ContentsNavigator {
            override val contents = BookContents(listOf(ContentsEntry("chapter", "Chapter", 0, true)))
            override fun goTo(entryId: String) = true
        }
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
            observations++; coverage = coverageAllowed; awaitCancellation()
        }
        override fun forward(): Boolean { forwards++; return true }
        override fun backward() = true
    }
    private class Session(format: BookFormat = BookFormat.EPUB) : ReaderSession {
        override val book = Book("annotation-book", "Annotation integration", null, BookFormat.EPUB,
            "annotation.epub", "annotation.epub", 1, "b".repeat(64), 1).copy(format = format,
                pageCount = if (format == BookFormat.PDF) 3 else null)
        override val resume = ReadingLocation("{\"href\":\"chapter.xhtml\"}", "epub")
        val initials = mutableListOf<ReadingLocation?>()
        val surfaces = mutableListOf<Surface>()
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface {
            initials += initial; return Surface(onTap, book.format).also { surfaces += it }
        }
        override fun close() = Unit
    }

    /** Unconfined Main can resume an async preference collector on its IO completion thread.
     * The 0.81 failure retained FragmentManager's actual "main thread of fragment host"
     * exception under the missing first surface assertion. This dispatcher keeps Android's
     * eager Main.immediate behavior while posting worker continuations to the real main Looper.
     */
    private class AndroidMainDispatcher : CoroutineDispatcher() {
        private val handler = Handler(Looper.getMainLooper())
        override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() !== handler.looper
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            check(handler.post(block)) { "Main Looper rejected test continuation" }
        }
    }

    @Test fun selectionAiKeepsReaderModalAndPauseCancelsLateAnswer() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        val late = CompletableDeferred<Unit>()
        try {
            val session = Session(); val storage = Storage(); var sends = 0
            val model = com.yaprak.reader.feature.assistant.SelectionAssistantViewModel(
                com.yaprak.reader.domain.assistant.SelectedTextSourceProvider { request ->
                    com.yaprak.reader.domain.assistant.PreparedSelectedTextSources(request, session.book,
                        listOf(com.yaprak.reader.domain.assistant.SelectedTextReference("S1", "Leaf")))
                }, com.yaprak.reader.domain.assistant.SelectedTextAnswerService { request, _ ->
                    sends++; withContext(NonCancellable) { late.await() }
                    com.yaprak.reader.domain.assistant.SelectedTextAnswer(request.sources.requestId,
                        com.yaprak.reader.domain.assistant.BookAnswerStatus.INSUFFICIENT, emptyList(), "Controlled insufficient")
                })
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, storage))
            host.get().viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${model.javaClass.canonicalName}", model)
            host.start().resume(); awaitSurface(host.get(), session); awaitStartup(host.get())
            val surface = session.surfaces.single(); surface.tap()
            surface.selections += CompletableDeferred(SelectedText("Leaf", sourceAnchor = anchor))
            invoke(host.get(), "captureSelectedText"); invoke(host.get(), "openSelectionAssistant"); runCurrent()
            val state = model.state.value!!
            assertEquals(session.book.id, state.request.bookId); assertNotNull(state.sources)
            assertFalse(surface.coverage!!.invoke())
            model.editEndpoint(state, "https://assistant.example/v1/book-answer")
            model.editToken(model.state.value!!, "controlled-server-access-code-1234")
            model.send(model.state.value!!) {
                host.get().javaClass.getDeclaredMethod("isSelectionAssistantCurrent",
                    com.yaprak.reader.domain.assistant.SelectedTextSourceRequest::class.java).apply { isAccessible = true }
                    .invoke(host.get(), state.request) as Boolean
            }
            runCurrent(); assertEquals(1, sends); assertTrue(model.state.value!!.sending)
            invoke(host.get(), "openOrientation"); assertFalse(property(host.get(), "getOrientationState")
                .let { it as com.yaprak.reader.feature.settings.ReaderOrientationState }.open)
            host.pause().stop(); late.complete(Unit); ShadowLooper.idleMainLooper(); runCurrent()
            assertNull(model.state.value); assertNull(property(host.get(), "getSelectionPreview"))
            assertTrue(storage.records.isEmpty())
        } finally { late.complete(Unit); host.destroy(); ShadowLooper.idleMainLooper(); Dispatchers.resetMain() }
    }

    @Test fun pdfNativeSelectionAdmitsTypedAiAndChangedNativeOwnerCannotSendAndPauseDisposesPanel() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(BookFormat.PDF); val storage = Storage(); var sends = 0; var proofs = 0
            val model = com.yaprak.reader.feature.assistant.SelectionAssistantViewModel(
                com.yaprak.reader.domain.assistant.SelectedTextSourceProvider { request ->
                    proofs++
                    com.yaprak.reader.domain.assistant.PreparedSelectedTextSources(request, session.book,
                        listOf(com.yaprak.reader.domain.assistant.SelectedTextReference("S1", "Leaf")))
                }, com.yaprak.reader.domain.assistant.SelectedTextAnswerService { _, _ -> sends++; error("Stale selection cannot send") })
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, storage))
            host.get().viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${model.javaClass.canonicalName}", model)
            host.start().resume(); awaitSurface(host.get(), session); awaitStartup(host.get())
            val surface = session.surfaces.single(); surface.tap()
            val id = java.util.UUID.randomUUID().toString(); surface.nativeSelectionId = id
            val pdfAnchor = com.yaprak.reader.fixtures.PdfSelectedAssistantFixtures.anchor
            surface.selections += CompletableDeferred(SelectedText("Leaf", pdfPageIndex = 2,
                sourceSelectionId = id, pdfSourceAnchor = pdfAnchor))
            invoke(host.get(), "captureSelectedText"); invoke(host.get(), "openSelectionAssistant"); runCurrent()
            val state = requireNotNull(model.state.value)
            assertEquals(BookFormat.PDF, state.request.sourceFormat); assertNull(state.request.anchor)
            assertEquals(pdfAnchor, state.request.pdfSourceAnchor); assertEquals(id, state.request.sourceSelectionId)
            assertNotNull(state.sources); assertEquals(1, proofs); assertEquals(0, sends)
            assertFalse(surface.coverage!!.invoke())
            fun current() = host.get().javaClass.getDeclaredMethod("isSelectionAssistantCurrent",
                com.yaprak.reader.domain.assistant.SelectedTextSourceRequest::class.java).apply { isAccessible = true }
                .invoke(host.get(), state.request) as Boolean
            assertTrue(current())
            model.editEndpoint(state, "https://assistant.example/v1/book-answer")
            model.editToken(model.state.value!!, "controlled-server-access-code-1234")
            surface.nativeSelectionId = java.util.UUID.randomUUID().toString()
            assertFalse(current()); model.send(model.state.value!!, ::current); runCurrent()
            assertEquals(1, proofs); assertEquals(0, sends)
            host.pause().stop(); ShadowLooper.idleMainLooper(); runCurrent()
            assertNull(model.state.value); assertNull(property(host.get(), "getSelectionPreview"))
            assertTrue(storage.records.isEmpty())
        } finally { host.destroy(); ShadowLooper.idleMainLooper(); Dispatchers.resetMain() }
    }

    @Test fun switchingSelectionToAnnotationDropsPendingAiPreparation() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        val late = CompletableDeferred<Unit>()
        try {
            val session = Session(); var sends = 0
            val model = com.yaprak.reader.feature.assistant.SelectionAssistantViewModel(
                com.yaprak.reader.domain.assistant.SelectedTextSourceProvider { request ->
                    withContext(NonCancellable) { late.await() }
                    com.yaprak.reader.domain.assistant.PreparedSelectedTextSources(request, session.book,
                        listOf(com.yaprak.reader.domain.assistant.SelectedTextReference("S1", "Leaf")))
                }, com.yaprak.reader.domain.assistant.SelectedTextAnswerService { _, _ -> sends++; error("No automatic send") })
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, Storage()))
            host.get().viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${model.javaClass.canonicalName}", model)
            host.start().resume(); awaitSurface(host.get(), session); awaitStartup(host.get())
            val surface = session.surfaces.single(); surface.tap()
            surface.selections += CompletableDeferred(SelectedText("Leaf", sourceAnchor = anchor))
            invoke(host.get(), "captureSelectedText"); invoke(host.get(), "openSelectionAssistant"); runCurrent()
            assertTrue(model.state.value!!.preparing)
            invoke(host.get(), "annotateSelectedText"); late.complete(Unit); ShadowLooper.idleMainLooper(); runCurrent()
            assertNull(model.state.value); assertNotNull(annotation(host.get())); assertEquals(0, sends)
            assertFalse(surface.coverage!!.invoke())
        } finally { late.complete(Unit); host.pause().stop().destroy(); ShadowLooper.idleMainLooper(); Dispatchers.resetMain() }
    }

    private fun awaitStartup(activity: ReaderActivity) {
        val container = (activity.application as YaprakApplication).container
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (container.startup.state.value != com.yaprak.reader.core.StartupState.Ready) {
            ShadowLooper.idleMainLooper(); check(System.nanoTime() < deadline) { "Library startup not ready" }; Thread.yield()
        }
    }

    @Test fun `ready EPUB selection admits exact annotation session and modal blocks navigation sampling and competing tools`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(); val storage = Storage()
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, storage))
            host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            val surface = session.surfaces.single()
            surface.tap(); runCurrent(); assertTrue(surface.coverage!!.invoke())
            surface.selections += CompletableDeferred(SelectedText("Leaf", sourceAnchor = anchor))
            invoke(host.get(), "captureSelectedText"); runCurrent()
            val selection = property(host.get(), "getSelectionPreview") as SelectedTextPreview.Ready
            invoke(host.get(), "annotateSelectedText"); runCurrent()
            val request = annotation(host.get())!!
            assertEquals(selection.sessionId, request.sessionId); assertEquals("Leaf", request.text); assertEquals(anchor, request.anchor)
            assertEquals(session.book.id, request.bookId); assertEquals(session.book.sha256, request.contentSha256)
            assertNull(property(host.get(), "getSelectionPreview")); assertTrue(canMutate(host.get(), request))
            assertFalse(surface.coverage!!.invoke())
            invoke(host.get(), "openOrientation"); invoke(host.get(), "openBrightness"); invoke(host.get(), "retryHighlights")
            assertFalse(property(host.get(), "getBrightnessOpen") as Boolean)
            assertEquals(0, surface.retries)
            val navigate = host.get().javaClass.getDeclaredMethod("navigate", Function0::class.java).apply { isAccessible = true }
            assertFalse(navigate.invoke(host.get(), { surface.forward() }) as Boolean)
            assertEquals(0, surface.forwards); assertTrue(storage.records.isEmpty())
        } finally { host.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun `repeated same source selection gets a new session and old request cannot target it or run while paused`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session()
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, Storage()))
            host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            val surface = session.surfaces.single()
            suspend fun select() {
                surface.selections += CompletableDeferred(SelectedText("Leaf", sourceAnchor = anchor))
                invoke(host.get(), "captureSelectedText"); runCurrent(); invoke(host.get(), "annotateSelectedText"); runCurrent()
            }
            select(); val old = annotation(host.get())!!
            invoke(host.get(), "clearAnnotation"); runCurrent(); select()
            val current = annotation(host.get())!!
            assertNotEquals(old.sessionId, current.sessionId); assertFalse(canMutate(host.get(), old)); assertTrue(canMutate(host.get(), current))
            host.pause(); runCurrent(); assertFalse(canMutate(host.get(), current))
            host.resume(); runCurrent(); assertTrue(canMutate(host.get(), current))
            host.pause().stop(); runCurrent()
            assertNull(annotation(host.get())); assertFalse(canMutate(host.get(), current))
        } finally { host.destroy(); Dispatchers.resetMain() }
    }

    @Test fun `pending capture blocks another capture and an explicitly replaced session cannot replace an admitted annotation`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session()
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, Storage()))
            host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            val surface = session.surfaces.single()
            val late = CompletableDeferred<SelectedText?>()
            surface.selections += late
            surface.selections += CompletableDeferred(SelectedText("Leaf", sourceAnchor = anchor))
            invoke(host.get(), "captureSelectedText"); runCurrent()
            assertTrue(property(host.get(), "getSelectionPreview") is SelectedTextPreview.Loading)
            invoke(host.get(), "captureSelectedText"); runCurrent()
            assertEquals(1, surface.selections.size)
            invoke(host.get(), "clearSelectionPreview"); runCurrent()
            invoke(host.get(), "captureSelectedText"); runCurrent(); invoke(host.get(), "annotateSelectedText"); runCurrent()
            val request = annotation(host.get())!!
            late.complete(SelectedText("Late", sourceAnchor = anchor)); runCurrent()
            assertSame(request, annotation(host.get())); assertNull(property(host.get(), "getSelectionPreview"))
            surface.renderingFailed.value = true; runCurrent(); assertFalse(canMutate(host.get(), request))
            surface.renderingFailed.value = false; runCurrent(); assertTrue(canMutate(host.get(), request))
        } finally { host.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun `recreated Activity drops transient annotation and resumes real latest location without a fake record`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(); val storage = Storage()
            val reader = ReaderViewModel(session.book.id, ReaderLoader { session }, storage)
            host.create(); install(host.get(), reader); host.start().resume()
            awaitSurface(host.get(), session); runCurrent()
            val surface = session.surfaces.single()
            surface.selections += CompletableDeferred(SelectedText("Leaf", sourceAnchor = anchor))
            invoke(host.get(), "captureSelectedText"); runCurrent(); invoke(host.get(), "annotateSelectedText"); runCurrent()
            val request = annotation(host.get())!!
            val latest = ReadingLocation("{\"href\":\"chapter.xhtml\",\"locations\":{\"progression\":0.4}}", "epub")
            reader.observe(ReadingSnapshot(latest)); runCurrent()
            assertEquals(1, storage.records.size)
            host.recreate(); awaitSurface(host.get(), session, expectedCount = 2); runCurrent()
            assertNull(annotation(host.get())); assertFalse(canMutate(host.get(), request))
            assertEquals(latest, session.initials.last()); assertEquals(2, session.initials.size)
            assertEquals(1, storage.records.size)
        } finally { host.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun `pending capture owns the modal and prevents book notes from opening until it is closed`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(); val storage = Storage()
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, storage))
            host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            val surface = session.surfaces.single()
            val late = CompletableDeferred<SelectedText?>()
            surface.selections += late
            invoke(host.get(), "captureSelectedText"); runCurrent()
            invoke(host.get(), "openBookNotes"); runCurrent()
            assertFalse(property(host.get(), "getBookNotesOpen") as Boolean)
            assertTrue(property(host.get(), "getSelectionPreview") is SelectedTextPreview.Loading)
            invoke(host.get(), "clearSelectionPreview"); invoke(host.get(), "observeSurface"); runCurrent()
            invoke(host.get(), "openBookNotes"); runCurrent()
            assertTrue(property(host.get(), "getBookNotesOpen") as Boolean)
            late.complete(SelectedText("Leaf", sourceAnchor = anchor)); runCurrent()
            assertNull(property(host.get(), "getSelectionPreview")); assertNull(annotation(host.get()))
            invoke(host.get(), "annotateSelectedText"); runCurrent(); assertNull(annotation(host.get()))
            assertTrue(storage.records.isEmpty())
        } finally { host.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun `rejected late capture restores observer under contents modal and simple close resumes sampling`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(); val storage = Storage()
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, storage))
            host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            val surface = session.surfaces.single()
            surface.tap(); runCurrent()
            val beforeCapture = surface.observations
            assertTrue(surface.coverage!!.invoke())
            val late = CompletableDeferred<SelectedText?>()
            surface.selections += late
            invoke(host.get(), "captureSelectedText"); runCurrent()
            // These flags are exactly the Contents toolbar and dismiss handlers' state changes.
            setFlag(host.get(), "setContentsOpen", true); runCurrent()
            late.complete(SelectedText("Leaf", sourceAnchor = anchor)); runCurrent()
            assertNull(property(host.get(), "getSelectionPreview"))
            assertEquals(beforeCapture + 1, surface.observations)
            val resumedObserver = surface.coverage!!
            assertFalse(resumedObserver())
            setFlag(host.get(), "setContentsOpen", false); runCurrent()
            assertSame(resumedObserver, surface.coverage)
            assertTrue(resumedObserver()); assertTrue(storage.records.isEmpty())
        } finally { host.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun `explicit selected Web action sends only exact payload closes selection and blocks coverage until resume`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(); val storage = Storage()
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, storage))
            host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            val surface = session.surfaces.single(); surface.tap(); runCurrent()
            val selected = "  İ/ı 😀\nexact & e\u0301  "
            registerWebSearch(host.get())
            surface.selections += CompletableDeferred(SelectedText(selected))
            invoke(host.get(), "captureSelectedText"); runCurrent()
            assertNotNull(property(host.get(), "getSelectionPreview"))
            launchExternal(host.get()); runCurrent()
            val intent = shadowOf(host.get()).nextStartedActivity
            assertEquals(Intent.ACTION_WEB_SEARCH, intent.action)
            assertEquals(selected, intent.getStringExtra(SearchManager.QUERY))
            assertEquals(setOf(SearchManager.QUERY), intent.extras!!.keySet()); assertNull(intent.data); assertNull(intent.clipData)
            assertNull(property(host.get(), "getSelectionPreview")); assertFalse(surface.coverage!!.invoke())
            launchExternal(host.get()); runCurrent(); assertNull(shadowOf(host.get()).nextStartedActivity)
            host.pause().resume(); runCurrent()
            assertTrue(surface.coverage!!.invoke()); assertTrue(storage.records.isEmpty())
        } finally { host.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun `failed external launch preserves exact selection with visible failure and explicit same payload retry`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(); val storage = Storage()
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, storage))
            host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            val sent = mutableListOf<String>(); var fail = true
            val context = object : ContextWrapper(host.get()) {
                override fun startActivity(intent: Intent) {
                    sent += intent.getStringExtra(SearchManager.QUERY)!!
                    if (fail) throw ActivityNotFoundException()
                    super.startActivity(intent)
                }
            }
            host.get().javaClass.getDeclaredField("externalTextTools\$delegate").apply { isAccessible = true }
                .set(host.get(), lazy { AndroidSelectedTextTools(context) })
            val surface = session.surfaces.single(); surface.tap(); runCurrent()
            surface.selections += CompletableDeferred(SelectedText("Exact selection 🌿"))
            invoke(host.get(), "captureSelectedText"); runCurrent()
            val preview = property(host.get(), "getSelectionPreview") as SelectedTextPreview.Ready
            launchExternal(host.get()); runCurrent()
            assertEquals(preview, property(host.get(), "getSelectionPreview"))
            val tools = property(host.get(), "getSelectionTools") as SelectedTextToolsState
            assertEquals(SelectedTextToolFailure.NO_HANDLER, tools.launchFailure)
            assertFalse(surface.coverage?.invoke() ?: false); assertNull(shadowOf(host.get()).nextStartedActivity)
            registerWebSearch(host.get()); fail = false; launchExternal(host.get()); runCurrent()
            assertEquals(listOf(preview.text, preview.text), sent)
            assertNull(property(host.get(), "getSelectionPreview")); assertTrue(storage.records.isEmpty())
        } finally { host.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun `paused capture or a current selection under another modal cannot launch external text`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session()
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, Storage()))
            host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            val surface = session.surfaces.single(); surface.tap(); runCurrent()
            val late = CompletableDeferred<SelectedText?>(); surface.selections += late
            val beforePause = surface.observations
            invoke(host.get(), "captureSelectedText"); runCurrent()
            host.pause(); late.complete(SelectedText("Obsolete text")); runCurrent()
            assertNull(property(host.get(), "getSelectionPreview")); launchExternal(host.get())
            assertNull(shadowOf(host.get()).nextStartedActivity)
            host.resume(); runCurrent()
            assertEquals(beforePause + 1, surface.observations)
            assertTrue(surface.coverage!!.invoke())
            surface.selections += CompletableDeferred(SelectedText("Current text"))
            invoke(host.get(), "captureSelectedText"); runCurrent()
            assertNotNull(property(host.get(), "getSelectionPreview"))
            setFlag(host.get(), "setContentsOpen", true); runCurrent()
            launchExternal(host.get()); assertNull(shadowOf(host.get()).nextStartedActivity)
        } finally { host.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun `internal dictionary is explicit stays in reader and invalid paragraph sends nothing`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(); val storage = Storage(); val words = mutableListOf<String>()
            val dictionary = com.yaprak.reader.feature.dictionary.DictionaryLookupViewModel(
                com.yaprak.reader.domain.dictionary.EnglishDictionary { word -> words += word; dictionaryResult(word) })
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, storage))
            installDictionary(host.get(), dictionary)
            host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            val surface = session.surfaces.single(); surface.tap(); runCurrent()
            surface.selections += CompletableDeferred(SelectedText("two words"))
            invoke(host.get(), "captureSelectedText"); runCurrent(); assertTrue(words.isEmpty())
            invoke(host.get(), "lookupSelectionDictionary"); runCurrent()
            assertEquals(com.yaprak.reader.domain.dictionary.DictionaryFailure.INVALID_WORD, dictionary.state.value!!.failure)
            assertTrue(words.isEmpty()); invoke(host.get(), "clearSelectionPreview")
            surface.selections += CompletableDeferred(SelectedText(" LEAF "))
            invoke(host.get(), "captureSelectedText"); runCurrent()
            val preview = property(host.get(), "getSelectionPreview") as SelectedTextPreview.Ready
            invoke(host.get(), "lookupSelectionDictionary"); runCurrent()
            assertEquals(listOf("leaf"), words); assertEquals(preview, property(host.get(), "getSelectionPreview"))
            val visible = property(host.get(), "getDictionaryState") as com.yaprak.reader.feature.dictionary.DictionaryLookupState
            assertEquals(preview.sessionId, visible.requestId); assertEquals(dictionaryResult("leaf"), visible.result)
            assertNull(shadowOf(host.get()).nextStartedActivity); assertTrue(storage.records.isEmpty())
            host.pause(); runCurrent(); assertNull(dictionary.state.value); assertNull(property(host.get(), "getDictionaryState"))
        } finally { host.stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun `dictionary pause rejects late cancelled result and modal cannot query new selection`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        val late = CompletableDeferred<com.yaprak.reader.domain.dictionary.DictionaryResult>()
        try {
            val session = Session(); val storage = Storage(); var calls = 0
            val dictionary = com.yaprak.reader.feature.dictionary.DictionaryLookupViewModel(
                com.yaprak.reader.domain.dictionary.EnglishDictionary { word ->
                    if (++calls == 1) withContext(NonCancellable) { late.await() } else dictionaryResult(word)
                })
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, storage))
            installDictionary(host.get(), dictionary)
            host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            val surface = session.surfaces.single(); surface.tap(); runCurrent()
            surface.selections += CompletableDeferred(SelectedText("leaf"))
            invoke(host.get(), "captureSelectedText"); runCurrent(); invoke(host.get(), "lookupSelectionDictionary"); runCurrent()
            assertTrue(dictionary.state.value!!.loading)
            host.pause(); runCurrent(); host.resume(); runCurrent()
            surface.selections += CompletableDeferred(SelectedText("book"))
            invoke(host.get(), "captureSelectedText"); runCurrent(); invoke(host.get(), "lookupSelectionDictionary"); runCurrent()
            val current = dictionary.state.value!!; assertEquals("book", current.word)
            late.complete(dictionaryResult("leaf")); runCurrent(); assertSame(current, dictionary.state.value)
            assertSame(current, property(host.get(), "getDictionaryState"))
            setFlag(host.get(), "setContentsOpen", true); runCurrent()
            invoke(host.get(), "lookupSelectionDictionary"); runCurrent(); assertEquals(2, calls)
            assertTrue(storage.records.isEmpty()); assertNull(shadowOf(host.get()).nextStartedActivity)
        } finally { late.complete(dictionaryResult("leaf")); host.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun `ready reader gesture previews native brightness gates coverage commits UP and rolls back CANCEL`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(); val storage = Storage()
            host.create(); install(host.get(), ReaderViewModel(session.book.id, ReaderLoader { session }, storage))
            host.start().resume().visible().windowFocusChanged(true); awaitSurface(host.get(), session); runCurrent()
            val activity = host.get(); val surface = session.surfaces.single(); surface.tap(); runCurrent()
            assertTrue(activity.window.decorView.hasWindowFocus())
            invoke(activity, "openBrightness")
            activity.javaClass.getDeclaredMethod("editBrightness", Int::class.javaObjectType).apply { isAccessible = true }.invoke(activity, 50)
            activity.javaClass.getDeclaredMethod("editBrightnessGesture", Boolean::class.javaPrimitiveType!!).apply { isAccessible = true }.invoke(activity, true)
            activity.javaClass.getDeclaredMethod("applyBrightness", Int::class.javaObjectType).apply { isAccessible = true }.invoke(activity, 50)
            runCurrent(); assertEquals(0.5f, activity.window.attributes.screenBrightness, 0f)
            val root = activity.javaClass.getDeclaredField("brightnessGestureLayout").apply { isAccessible = true }
                .get(activity) as ReaderBrightnessGestureLayout
            val childActions = mutableListOf<Int>()
            root.addView(android.view.View(activity).apply { setOnTouchListener { _, event -> childActions += event.actionMasked; true } },
                0, android.widget.FrameLayout.LayoutParams(-1, -1))
            ShadowLooper.idleMainLooper(); assertTrue(root.width > 0 && root.height > 0)
            val start = root.height / 2f; val up = start - root.height / 5f
            brightnessEvent(root, android.view.MotionEvent.ACTION_DOWN, start, 0)
            brightnessEvent(root, android.view.MotionEvent.ACTION_MOVE, up, 30); runCurrent()
            assertEquals(0.68f, activity.window.attributes.screenBrightness, 0f)
            assertEquals(50, property(activity, "getBrightnessPercent")); assertFalse(surface.coverage!!.invoke())
            brightnessEvent(root, android.view.MotionEvent.ACTION_UP, up, 40); runCurrent()
            assertEquals(68, property(activity, "getBrightnessPercent")); assertTrue(surface.coverage!!.invoke())
            assertEquals(listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_CANCEL), childActions)
            brightnessEvent(root, android.view.MotionEvent.ACTION_DOWN, start, 0, 2000)
            brightnessEvent(root, android.view.MotionEvent.ACTION_MOVE, up, 30, 2000); runCurrent()
            assertEquals(0.86f, activity.window.attributes.screenBrightness, 0f)
            brightnessEvent(root, android.view.MotionEvent.ACTION_CANCEL, up, 40, 2000); runCurrent()
            assertEquals(68, property(activity, "getBrightnessPercent")); assertEquals(0.68f, activity.window.attributes.screenBrightness, 0f)
            assertTrue(storage.records.isEmpty())
        } finally { host.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    private fun brightnessEvent(root: ReaderBrightnessGestureLayout, action: Int, y: Float, elapsed: Long, down: Long = 1000) {
        val properties = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = android.view.MotionEvent.PointerCoords().apply { x = 32f; this.y = y; pressure = 1f; size = 1f }
        val event = android.view.MotionEvent.obtain(down, down + elapsed, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, 7, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { root.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun dictionaryResult(word: String) = com.yaprak.reader.domain.dictionary.DictionaryResult(word,
        listOf(com.yaprak.reader.domain.dictionary.DictionaryEntry(word, null,
            listOf(com.yaprak.reader.domain.dictionary.DictionaryDefinition("noun", "Controlled definition.", null)), null, emptyList())))
    private fun installDictionary(activity: ReaderActivity, dictionary: com.yaprak.reader.feature.dictionary.DictionaryLookupViewModel) {
        activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${dictionary.javaClass.canonicalName}", dictionary)
    }

    private fun launchExternal(activity: ReaderActivity) {
        activity.javaClass.getDeclaredMethod("launchSelectionTool", SelectedTextTool::class.java)
            .apply { isAccessible = true }.invoke(activity, null)
    }

    private fun registerWebSearch(activity: ReaderActivity) {
        shadowOf(activity.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_WEB_SEARCH),
            android.content.pm.ResolveInfo().apply { activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "web.fixture"; name = "web.fixture.Search"; exported = true; enabled = true
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "web.fixture"; enabled = true }
            } })
    }

    private fun install(activity: ReaderActivity, reader: ReaderViewModel) {
        activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${ReaderViewModel::class.java.canonicalName}", reader)
    }

    private fun awaitSurface(activity: ReaderActivity, session: Session, expectedCount: Int = 1) {
        val surfaceField = activity.javaClass.getDeclaredField("surface").apply { isAccessible = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (surfaceField.get(activity) == null || session.surfaces.size < expectedCount) {
            // Drain actual Android events; do not assume a fixed sleep finishes DataStore IO.
            // Errors dispatched by production code are allowed to fail this test normally.
            ShadowLooper.idleMainLooper()
            check(System.nanoTime() < deadline) { "Reader did not install its expected surface on Android Main" }
            Thread.yield()
        }
        assertEquals(expectedCount, session.surfaces.size)
        assertSame(session.surfaces.last(), surfaceField.get(activity))
        ShadowLooper.idleMainLooper()
    }
    private fun invoke(activity: ReaderActivity, name: String) {
        activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }
    private fun property(activity: ReaderActivity, getter: String): Any? =
        activity.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(activity)
    private fun annotation(activity: ReaderActivity) = property(activity, "getAnnotationRequest") as EpubAnnotationRequest?
    private fun setFlag(activity: ReaderActivity, setter: String, value: Boolean) {
        activity.javaClass.getDeclaredMethod(setter, Boolean::class.javaPrimitiveType!!)
            .apply { isAccessible = true }.invoke(activity, value)
    }
    private fun canMutate(activity: ReaderActivity, request: EpubAnnotationRequest) =
        activity.javaClass.getDeclaredMethod("canMutateAnnotation", EpubAnnotationRequest::class.java)
            .apply { isAccessible = true }.invoke(activity, request) as Boolean
}

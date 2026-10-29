package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.library.DiscoveryState
import com.yaprak.reader.feature.library.LibraryScreen
import com.yaprak.reader.feature.library.LibraryState
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.IndexedChapterLaunch
import com.yaprak.reader.feature.search.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryTextSearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("book", "İstanbul 🌿", "Deniz", BookFormat.EPUB, "book.epub", "book.epub", 12, "a".repeat(64), 1)
    private val hit = LibraryTextHit(1, "generation", book,
        LibraryTextTarget(book.id, book.sha256, "OPS/chapter.xhtml", "b".repeat(64)), "Kavramlar",
        "Original İ/ı 😀\nBody", "Original İ/ı 😀\nBody")
    private val result = LibraryTextSearchResult("Original", listOf(hit), 1,
        listOf(LibraryTextBookStatus(book, LibraryTextBookState.READY, 3)))
    private val ready = LibraryTextSearchState(query = "Original", searchedQuery = "Original", result = result,
        loading = false, active = true, revision = 7)

    @Test fun `TXT result offers original part and explicit source question at native large font`() {
        largeFont()
        val txt = book.copy(format = BookFormat.TXT)
        val part = hit.copy(book = txt, target = hit.target.copy(resourceHref = "Text/part0002.xhtml", sourceFormat = BookFormat.TXT),
            chapterTitle = "Metin 2")
        val scope = BookTextScope(txt.id, txt.sha256)
        val state = ready.copy(scope = scope, result = result.copy(hits = listOf(part), scope = scope,
            books = listOf(LibraryTextBookStatus(txt, LibraryTextBookState.READY, 3))))
        val opened = mutableListOf<LibraryTextHit>()
        compose.setContent { YaprakTheme { LibraryTextSearchScreen(state, {}, { _, _ -> }, {}, {}, {}, opened::add, {}) } }
        scrollText(text(R.string.library_text_txt_part, "Metin 2")).assertIsDisplayed()
        readable(text(R.string.library_text_txt_part, "Metin 2"))
        scrollTag("library-text-open-1").assertIsDisplayed(); readable(text(R.string.library_text_txt_open))
        capture("library-txt-result-font2")
        scrollTag("library-text-open-1").performClick(); assertEquals(listOf(part), opened)
        scrollTag("library-text-assistant").assertIsDisplayed(); readable(text(R.string.assistant_title))
        compose.onNodeWithText(text(R.string.library_text_chapter, "Metin 2")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_text_pdf_open)).assertDoesNotExist()
    }

    @Test fun `PDF result shows real page opening and explicit source question at native large font`() {
        largeFont()
        val pdf = book.copy(format = BookFormat.PDF, pageCount = 9)
        val page = hit.copy(book = pdf, target = hit.target.copy(resourceHref = "", sourceFormat = BookFormat.PDF, pdfPageIndex = 6),
            chapterTitle = null)
        val scope = BookTextScope(pdf.id, pdf.sha256)
        val state = ready.copy(scope = scope, result = result.copy(hits = listOf(page), scope = scope,
            books = listOf(LibraryTextBookStatus(pdf, LibraryTextBookState.READY, 3))))
        val opened = mutableListOf<LibraryTextHit>()
        compose.setContent { YaprakTheme { LibraryTextSearchScreen(state, {}, { _, _ -> }, {}, {}, {}, opened::add, {}) } }
        scrollText(text(R.string.library_text_pdf_page, 7)).assertIsDisplayed()
        readable(text(R.string.library_text_pdf_page, 7))
        scrollTag("library-text-open-1").assertIsDisplayed()
        readable(text(R.string.library_text_pdf_open))
        capture("library-pdf-result-font2")
        scrollTag("library-text-open-1").performClick(); assertEquals(listOf(page), opened)
        scrollTag("library-text-assistant").assertIsDisplayed(); readable(text(R.string.assistant_title))
        compose.onNodeWithText(text(R.string.library_text_chapter, "Kavramlar")).assertDoesNotExist()
    }

    @Test fun `PDF index status explains empty native text separately from unsupported API at large font`() {
        largeFont()
        val scan = book.copy(id = "scan", format = BookFormat.PDF, title = "Taranmış kitap")
        val old = scan.copy(id = "old", title = "Eski cihaz PDF")
        val state = ready.copy(result = result.copy(hits = emptyList(), totalMatches = 0, books = listOf(
            LibraryTextBookStatus(scan, LibraryTextBookState.NO_TEXT), LibraryTextBookStatus(old, LibraryTextBookState.UNSUPPORTED))))
        compose.setContent { YaprakTheme { LibraryTextSearchScreen(state, {}, { _, _ -> }, {}, {}, {}, {}, {}) } }
        scrollTag("library-text-index-tab").performClick()
        scrollText(text(R.string.library_text_status_no_text)).assertIsDisplayed(); readable(text(R.string.library_text_status_no_text))
        capture("library-pdf-no-text-font2")
        scrollText(text(R.string.library_text_status_unsupported)).assertIsDisplayed(); readable(text(R.string.library_text_status_unsupported))
    }

    @Test fun `assistant launch belongs to exact rendered single book scope query and disposal`() {
        val scope = BookTextScope(book.id, book.sha256)
        val scoped = ready.copy(scope = scope, result = result.copy(scope = scope))
        var state by mutableStateOf(scoped)
        val launches = mutableListOf<Triple<BookTextScope, String, Long>>()
        compose.setContent { YaprakTheme { LibraryTextSearchScreen(state, {}, { _, _ -> }, {}, {}, {}, {}, {},
            onAssistant = { selected, query, revision -> launches += Triple(selected, query, revision) }) } }
        val old = action("library-text-assistant")
        compose.runOnIdle { state = scoped.copy(revision = 8) }; compose.runOnIdle { old() }; assertTrue(launches.isEmpty())
        scrollTag("library-text-assistant").performClick(); assertEquals(listOf(Triple(scope, "Original", 8L)), launches)
        val fresh = action("library-text-assistant")
        compose.runOnIdle { state = ready }; compose.runOnIdle { old(); fresh() }; assertEquals(1, launches.size)
        compose.onNodeWithTag("library-text-assistant").assertDoesNotExist()
        compose.runOnIdle { state = scoped }; compose.runOnIdle { old() }; assertEquals(1, launches.size)
    }

    @Test fun `old hit cannot retarget changed metadata source generation or paused loading and rebuild state`() {
        var state by mutableStateOf(ready)
        val opened = mutableListOf<LibraryTextHit>()
        compose.setContent { YaprakTheme { LibraryTextSearchScreen(state, {}, { _, _ -> }, {}, {}, {}, opened::add, {}) } }
        val old = action("library-text-open-1")
        val changed = hit.copy(book = book.copy(title = "Changed metadata"), generation = "new")
        compose.runOnIdle { state = ready.copy(result = result.copy(hits = listOf(changed))) }
        compose.runOnIdle { old() }
        assertTrue(opened.isEmpty())
        scrollTag("library-text-open-1").performClick()
        assertEquals(listOf(changed), opened)
        opened.clear()
        val fresh = action("library-text-open-1")
        listOf(state.copy(active = false), state.copy(loading = true), state.copy(failure = LibraryTextIndexFailure.SOURCE),
            state.copy(build = LibraryTextBuildPhase.Running(5))).forEach { gated ->
            compose.runOnIdle { state = gated }
            compose.runOnIdle { old(); fresh() }
            assertTrue(opened.isEmpty())
        }
    }

    @Test fun `search submit cancel and rebuild callbacks retain their exact query revision and run`() {
        var state by mutableStateOf(ready.copy(searchedQuery = null, result = result.copy(query = "", hits = emptyList(), totalMatches = 0)))
        val searches = mutableListOf<Pair<String, Long>>()
        val starts = mutableListOf<LibraryTextBuildPhase>()
        val stops = mutableListOf<Long>()
        compose.setContent { YaprakTheme { LibraryTextSearchScreen(state, {}, { q, r -> searches += q to r }, {}, starts::add, stops::add, {}, {}) } }
        val oldSearch = action("library-text-search")
        compose.runOnIdle { state = state.copy(revision = 8, query = "other") }
        compose.runOnIdle { state = state.copy(revision = 9, query = "Original") }
        compose.runOnIdle { oldSearch() }
        assertTrue(searches.isEmpty())
        scrollTag("library-text-search").performClick()
        assertEquals(listOf("Original" to 9L), searches)
        scrollTag("library-text-index-tab").performClick()
        val oldBuild = action("library-text-rebuild")
        compose.runOnIdle { state = state.copy(build = LibraryTextBuildPhase.Running(1,
            LibraryTextIndexProgress(1, 3, "Actual book"))) }
        val oldCancel = action("library-text-cancel")
        compose.runOnIdle { state = state.copy(build = LibraryTextBuildPhase.Running(2)) }
        compose.runOnIdle { oldBuild(); oldCancel() }
        assertTrue(starts.isEmpty()); assertTrue(stops.isEmpty())
        scrollTag("library-text-cancel").performClick()
        assertEquals(listOf(2L), stops)
        compose.runOnIdle { state = state.copy(build = LibraryTextBuildPhase.Running(2, stopping = true)) }
        compose.onNodeWithTag("library-text-cancel").assertDoesNotExist()
        compose.onNodeWithTag("library-text-rebuild").assertDoesNotExist()
        scrollText(text(R.string.library_text_stopping)).assertIsDisplayed()
    }

    @Test fun `status view truthfully distinguishes missing unsupported failed limited and committed books`() {
        val statuses = listOf(
            LibraryTextBookStatus(book, LibraryTextBookState.READY, 12),
            LibraryTextBookStatus(book.copy(id = "missing", title = "Yeni EPUB"), LibraryTextBookState.NOT_INDEXED),
            LibraryTextBookStatus(book.copy(id = "failed", title = "Bozuk EPUB"), LibraryTextBookState.FAILED),
            LibraryTextBookStatus(book.copy(id = "limit", title = "Büyük EPUB"), LibraryTextBookState.LIMIT),
            LibraryTextBookStatus(book.copy(id = "pdf", title = "PDF", format = BookFormat.PDF), LibraryTextBookState.UNSUPPORTED),
            LibraryTextBookStatus(book.copy(id = "unsupported", title = "Desteklenmeyen EPUB"), LibraryTextBookState.UNSUPPORTED))
        compose.setContent { YaprakTheme { LibraryTextSearchScreen(ready.copy(result = result.copy(books = statuses)),
            {}, { _, _ -> }, {}, {}, {}, {}, {}) } }
        scrollText(text(R.string.library_text_coverage, 1, 4)).assertIsDisplayed()
        scrollText(text(R.string.library_text_incomplete)).assertIsDisplayed()
        scrollTag("library-text-index-tab").performClick()
        listOf(text(R.string.library_text_status_ready, 12), text(R.string.library_text_status_not_indexed),
            text(R.string.library_text_status_failed), text(R.string.library_text_status_limit),
            text(R.string.library_text_status_unsupported), text(R.string.library_text_status_unsupported_epub)).forEach {
            scrollText(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("library-text-open-1").assertDoesNotExist()
        compose.onNodeWithTag("library-text-back").assertIsDisplayed()
    }

    @Test fun `200 results retain original excerpt chapter source limit and full action labels at native double type`() {
        largeFont()
        val hits = (1L..200L).map { hit.copy(id = it, book = book.copy(title = "Kitap $it")) }
        var opened: LibraryTextHit? = null
        var backs = 0
        compose.setContent { YaprakTheme { LibraryTextSearchScreen(ready.copy(result = result.copy(hits = hits, totalMatches = 240)),
            {}, { _, _ -> }, {}, {}, {}, { opened = it }, { backs++ }) } }
        readable(text(R.string.library_text_back))
        scrollTag("library-text-query").assertIsDisplayed().assert(hasContentDescription(text(R.string.library_text_query)))
        readable(text(R.string.library_text_query))
        capture("library-text-query-double-type")
        scrollText(text(R.string.library_text_limit, 200, 240)).assertIsDisplayed()
        readable(text(R.string.library_text_limit, 200, 240))
        compose.onNodeWithTag("library-text-list").performScrollToIndex(205)
        scrollTag("library-text-open-200").assertIsDisplayed().performClick()
        readable(text(R.string.library_text_open), "library-text-open-200")
        val snippet = hasText(hit.snippet) and hasAnyAncestor(hasTestTag("library-text-hit-200"))
        compose.onNode(snippet).performScrollTo().assertIsDisplayed()
        readable(hit.snippet, "library-text-hit-200")
        val source = hasText(text(R.string.library_text_source, "OPS/chapter.xhtml")) and
            hasAnyAncestor(hasTestTag("library-text-hit-200"))
        compose.onNode(source).performScrollTo().assertIsDisplayed()
        readable(text(R.string.library_text_source, "OPS/chapter.xhtml"), "library-text-hit-200")
        capture("library-text-results-double-type")
        compose.onNodeWithTag("library-text-back").assertIsDisplayed().performClick()
        assertEquals(hits.last(), opened); assertEquals(1, backs)
    }

    @Test fun `narrow native double type index actions limits failure and cancellation remain readable`() {
        largeFont()
        var state by mutableStateOf(ready.copy(build = LibraryTextBuildPhase.Failed(1, LibraryTextIndexFailure.SOURCE)))
        compose.setContent { YaprakTheme { LibraryTextSearchScreen(state, {}, { _, _ -> }, {}, {}, {}, {}, {}) } }
        scrollTag("library-text-index-tab").performClick()
        listOf(R.string.library_text_limits, R.string.library_text_rebuild, R.string.library_text_build_failed,
            R.string.library_text_failed).forEach { scrollText(text(it)).assertIsDisplayed(); readable(text(it)) }
        compose.onNodeWithTag("library-text-back").assertIsDisplayed(); readable(text(R.string.library_text_back))
        capture("library-text-index-double-type")
        compose.runOnIdle { state = state.copy(build = LibraryTextBuildPhase.Running(2,
            LibraryTextIndexProgress(2, 10, "Özgün kitap 🌿"))) }
        scrollText(text(R.string.library_text_build_progress, 2, 10)).assertIsDisplayed()
        readable(text(R.string.library_text_build_progress, 2, 10))
        scrollText(text(R.string.library_text_build_book, "Özgün kitap 🌿")).assertIsDisplayed()
        readable(text(R.string.library_text_build_book, "Özgün kitap 🌿"))
        scrollTag("library-text-cancel").assertIsDisplayed(); readable(text(R.string.library_text_cancel))
        capture("library-text-progress-double-type")
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short landscape native double type scrolls rebuild cancel and retry with fixed back`() {
        largeFont()
        var state by mutableStateOf(ready.copy(build = LibraryTextBuildPhase.Running(4,
            LibraryTextIndexProgress(1, 4, "Aktif kitap"))))
        compose.setContent { YaprakTheme { LibraryTextSearchScreen(state, {}, { _, _ -> }, {}, {}, {}, {}, {}) } }
        scrollTag("library-text-index-tab").performClick()
        scrollTag("library-text-cancel").assertIsDisplayed(); readable(text(R.string.library_text_cancel))
        compose.onNodeWithTag("library-text-back").assertIsDisplayed(); readable(text(R.string.library_text_back))
        capture("library-text-landscape-double-type")
        compose.runOnIdle { state = state.copy(build = LibraryTextBuildPhase.Finished(4, true), failure = LibraryTextIndexFailure.SOURCE) }
        scrollText(text(R.string.library_text_retry)).assertIsDisplayed(); readable(text(R.string.library_text_retry))
        scrollTag("library-text-rebuild").assertIsDisplayed(); readable(text(R.string.library_text_rebuild))
        compose.onNodeWithTag("library-text-back").assertIsDisplayed()
    }

    @Test fun `disposed screen and latest pause predicate reject old back and chapter callbacks`() {
        var shown by mutableStateOf(true)
        var state by mutableStateOf(ready)
        var effects = 0
        compose.setContent { YaprakTheme { if (shown) LibraryTextSearchScreen(state,
            {}, { _, _ -> }, {}, {}, {}, { effects++ }, { effects++ }) } }
        val oldOpen = action("library-text-open-1")
        val oldBack = compose.onNodeWithTag("library-text-back").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(active = false) }
        compose.runOnIdle { oldOpen(); oldBack() }
        assertEquals(0, effects)
        compose.runOnIdle { state = ready; shown = false }
        compose.runOnIdle { oldOpen(); oldBack() }
        assertEquals(0, effects)
    }

    @Test fun `optional library entry leaves metadata query unchanged and old callback cannot act during mutation or replacement`() {
        var discovery by mutableStateOf<DiscoveryState>(DiscoveryState.Idle)
        var available by mutableStateOf(true)
        var version by mutableStateOf(1)
        val effects = mutableListOf<Int>()
        val metadataChanges = mutableListOf<String>()
        val one: () -> Unit = { effects += 1 }; val two: () -> Unit = { effects += 2 }
        compose.setContent { YaprakTheme { LibraryScreen(LibraryState.Ready(emptyList()), false,
            SnackbarHostState(), {}, {}, {}, "metadata only", metadataChanges::add, discoveryState = discovery,
            onTextSearch = if (available) if (version == 1) one else two else null) } }
        val old = compose.onNodeWithText(text(R.string.library_text_title)).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { discovery = DiscoveryState.Choosing("picker") }
        compose.onNodeWithText(text(R.string.library_text_title)).assertIsNotEnabled()
        compose.runOnIdle { old() }
        assertTrue(effects.isEmpty())
        compose.runOnIdle { discovery = DiscoveryState.Idle; version = 2 }
        compose.runOnIdle { old() }
        assertTrue(effects.isEmpty())
        compose.onNodeWithText(text(R.string.library_text_title)).performClick()
        assertEquals(listOf(2), effects); assertTrue(metadataChanges.isEmpty())
        compose.runOnIdle { available = false }
        compose.onNodeWithText(text(R.string.library_text_title)).assertDoesNotExist()
        compose.runOnIdle { old() }
        assertEquals(listOf(2), effects)
    }

    @Test fun `route verifies exact hit before actual Reader chapter intent and pause cancels delayed navigation`() {
        val index = Index()
        val model = LibraryTextSearchViewModel(index, SavedStateHandle())
        compose.activity.viewModelStore.put("text", model)
        compose.setContent { YaprakTheme { LibraryTextSearchRoute(model,
            { compose.activity.startActivity(ReaderActivity.intent(compose.activity, it.target)) }, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("library-text-query").fetchSemanticsNodes().isNotEmpty() }
        scrollTag("library-text-query").performTextReplacement("Original")
        scrollTag("library-text-search").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("library-text-list").fetchSemanticsNodes(); model.state.value.result?.hits?.isNotEmpty() == true }
        scrollTag("library-text-open-1").performClick()
        compose.waitUntil { index.resolves == 1 }
        compose.waitForIdle()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(ReaderActivity::class.java.name, intent.component!!.className)
        assertEquals(book.id, intent.getStringExtra("bookId"))
        assertEquals(hit.target, IndexedChapterLaunch.read(intent)!!.target)
        assertFalse(intent.extras!!.keySet().any { key -> intent.extras!!.get(key) == hit.snippet })
        val release = CompletableDeferred<Unit>()
        index.resolveAction = { withContext(NonCancellable) { release.await() }; hit }
        scrollTag("library-text-open-1").performClick()
        compose.waitUntil { index.resolves == 2 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        release.complete(Unit)
        compose.waitForIdle()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil { model.state.value.active && !model.state.value.loading }
        assertEquals(0, index.builds)
    }

    private inner class Index : LibraryTextIndex {
        val source = MutableStateFlow(result)
        var resolves = 0
        var builds = 0
        var resolveAction: (suspend () -> LibraryTextHit?)? = null
        override fun observe(query: String) = source.map { it.copy(query = query,
            hits = if (query.isBlank()) emptyList() else it.hits, totalMatches = if (query.isBlank()) 0 else it.totalMatches) }
        override suspend fun rebuild(onProgress: (LibraryTextIndexProgress) -> Unit) { builds++ }
        override suspend fun resolve(hit: LibraryTextHit): LibraryTextHit? {
            resolves++
            resolveAction?.let { return it() }
            return source.value.hits.singleOrNull { it == hit }
        }
    }
    private fun scrollTag(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("library-text-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun scrollText(value: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("library-text-list").performScrollToNode(hasText(value))
        return compose.onNodeWithText(value)
    }
    private fun action(tag: String): () -> Boolean = scrollTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
    }
    private fun readable(value: String, ancestorTag: String? = null) {
        val layouts = mutableListOf<TextLayoutResult>()
        val matcher = if (ancestorTag == null) hasText(value) else hasText(value) and hasAnyAncestor(hasTestTag(ancestorTag))
        compose.onNode(matcher, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse("Text overflow: $value (${layout.size}); width=${layout.didOverflowWidth}, " +
            "height=${layout.didOverflowHeight}, constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, " +
            "lastBottom=${layout.getLineBottom(layout.lineCount - 1)}", layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

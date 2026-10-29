package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTextSearchViewModelTest {
    private val book = Book("book", "Güncel kitap", "Deniz", BookFormat.EPUB, "book.epub", "book.epub", 12, "a".repeat(64), 1)
    private val hit = LibraryTextHit(1, "generation", book,
        LibraryTextTarget(book.id, book.sha256, "OPS/chapter.xhtml", "b".repeat(64)), "Bölüm 🌿",
        "Original İ/ı 😀\nBody", "Original İ/ı 😀\nBody")

    private inner class Index : LibraryTextIndex {
        val result = MutableStateFlow(LibraryTextSearchResult("", listOf(hit), 1,
            listOf(LibraryTextBookStatus(book, LibraryTextBookState.READY, 3))))
        val queries = mutableListOf<String>()
        val scopes = mutableListOf<BookTextScope>()
        var scopedTransform: (LibraryTextSearchResult, BookTextScope) -> LibraryTextSearchResult = { value, scope ->
            val hits = value.hits.filter { it.book.id == scope.bookId && it.book.sha256 == scope.contentSha256 }
            value.copy(scope = scope, hits = hits, totalMatches = hits.size)
        }
        var collectors = 0
        var failure: LibraryTextIndexFailure? = null
        var builds = 0
        var resolves = 0
        var buildAction: (suspend ((LibraryTextIndexProgress) -> Unit) -> Unit)? = null
        var resolveAction: (suspend (LibraryTextHit) -> LibraryTextHit?)? = null
        override fun observe(query: String) = flow {
            queries += query
            collectors++
            try {
                failure?.let { throw LibraryTextIndexException(it) }
                result.collect { value -> emit(value.copy(query = query,
                    hits = if (query.isBlank()) emptyList() else value.hits, totalMatches = if (query.isBlank()) 0 else value.totalMatches)) }
            } finally { collectors-- }
        }
        override suspend fun rebuild(onProgress: (LibraryTextIndexProgress) -> Unit) {
            builds++
            buildAction?.let { it(onProgress); return }
            onProgress(LibraryTextIndexProgress(0, 1, book.title))
            result.value = result.value.copy(books = listOf(LibraryTextBookStatus(book, LibraryTextBookState.READY, 8)))
            onProgress(LibraryTextIndexProgress(1, 1, null))
        }
        override fun observeBook(query: String, scope: BookTextScope) = observe(query).map {
            scopes += scope; scopedTransform(it, scope)
        }
        override suspend fun resolve(hit: LibraryTextHit): LibraryTextHit? {
            resolves++
            resolveAction?.let { return it(hit) }
            return result.value.hits.singleOrNull { it.id == hit.id && it.generation == hit.generation }
        }
    }

    private suspend fun TestScope.withModel(saved: SavedStateHandle = SavedStateHandle(),
        active: Boolean = true, block: suspend TestScope.(LibraryTextSearchViewModel, Index) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val index = Index()
        try {
            val model = LibraryTextSearchViewModel(index, saved).also { store.put("search", it) }
            if (active) model.setActive(true)
            runCurrent()
            block(model, index)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun TestScope.search(model: LibraryTextSearchViewModel, query: String = "Original") {
        model.changeQuery(query); runCurrent(); model.submit(); runCurrent()
    }

    @Test fun `supported PDF scope searches and resolves typed pages while unsupported PDF cannot be selected`() = runTest {
        withModel { model, index ->
            val pdf = book.copy(format = BookFormat.PDF, pageCount = 3)
            val page = hit.copy(book = pdf, target = hit.target.copy(resourceHref = "", sourceFormat = BookFormat.PDF, pdfPageIndex = 2),
                chapterTitle = null)
            index.result.value = index.result.value.copy(hits = listOf(page), books = listOf(LibraryTextBookStatus(pdf, LibraryTextBookState.READY, 1)))
            runCurrent()
            val scope = BookTextScope(pdf.id, pdf.sha256)
            model.changeScope(scope); runCurrent(); search(model)
            assertEquals(scope, model.state.value.scope); assertEquals(listOf(page), model.state.value.result!!.hits)
            val opened = mutableListOf<LibraryTextHit>(); model.open(page, opened::add); runCurrent(); assertEquals(listOf(page), opened)
            model.changeScope(null); runCurrent()
            index.result.value = index.result.value.copy(books = listOf(LibraryTextBookStatus(pdf, LibraryTextBookState.UNSUPPORTED)))
            runCurrent(); model.changeScope(scope); assertNull(model.state.value.scope)
        }
    }

    @Test fun `restored queries are bounded and only explicit submit searches an existing index`() = runTest {
        val saved = SavedStateHandle(mapOf(LibraryTextSearchViewModel.QUERY to "x".repeat(199) + "😀rest"))
        withModel(saved, active = false) { model, index ->
            assertEquals("x".repeat(199), model.state.value.query)
            assertTrue(index.queries.isEmpty())
            model.setActive(true); runCurrent()
            assertEquals(listOf(""), index.queries)
            assertNull(model.state.value.searchedQuery)
            model.changeQuery("y".repeat(198) + "😀rest"); runCurrent()
            assertEquals("y".repeat(198) + "😀", saved.get<String>(LibraryTextSearchViewModel.QUERY))
            assertTrue(model.state.value.result!!.hits.isEmpty())
            assertEquals(0, index.builds)
            model.submit(); runCurrent()
            assertEquals(model.state.value.query, index.queries.last())
            assertEquals(listOf(hit), model.state.value.result!!.hits)
        }
        withModel(SavedStateHandle(mapOf(LibraryTextSearchViewModel.QUERY to 42)), active = false) { model, _ ->
            assertEquals("", model.state.value.query)
        }
    }

    @Test fun `query changes immediately remove old actions and stale submit revision cannot search ABA draft`() = runTest {
        withModel { model, index ->
            search(model)
            val oldRevision = model.state.value.revision
            model.changeQuery("different")
            assertNull(model.state.value.result)
            assertFalse(model.state.value.actionsEnabled)
            model.changeQuery("Original"); runCurrent()
            model.submit("Original", oldRevision); runCurrent()
            assertNull(model.state.value.searchedQuery)
            var opens = 0
            model.open(hit) { opens++ }; runCurrent()
            assertEquals(0, opens); assertEquals(0, index.resolves)
            model.submit(); runCurrent()
            assertTrue(model.state.value.actionsEnabled)
        }
    }

    @Test fun `invalid query source recovery and busy are distinct failures and retry retains submitted query`() = runTest {
        withModel { model, index ->
            for (reason in LibraryTextIndexFailure.entries) {
                index.failure = reason
                search(model, reason.name)
                assertEquals(reason, model.state.value.failure)
                assertNull(model.state.value.result)
                assertFalse(model.state.value.actionsEnabled)
                index.failure = null
                model.retry(); runCurrent()
                assertNull(model.state.value.failure)
                assertEquals(reason.name, model.state.value.result!!.query)
            }
        }
    }

    @Test fun `open resolves exact hit and preserves source target and original excerpt`() = runTest {
        withModel { model, index ->
            search(model)
            val opened = mutableListOf<LibraryTextHit>()
            model.open(hit, opened::add)
            assertEquals(hit, model.state.value.action)
            model.open(hit, opened::add); runCurrent()
            assertEquals(1, index.resolves)
            assertEquals(listOf(hit), opened)
            assertNull(model.state.value.action)
            assertEquals("Original İ/ı 😀\nBody", opened.single().snippet)
        }
    }

    @Test fun `resolved metadata content generation or deleted hit cannot retarget existing action`() = runTest {
        withModel { model, index ->
            search(model)
            val replacements = listOf(hit.copy(book = book.copy(title = "New title")),
                hit.copy(generation = "new"), hit.copy(target = hit.target.copy(resourceSha256 = "c".repeat(64))), null)
            var opens = 0
            for (replacement in replacements) {
                index.resolveAction = { replacement }
                model.open(hit) { opens++ }; runCurrent()
                assertEquals(LibraryTextActionFeedback.CHANGED, model.state.value.feedback!!.kind)
                assertEquals(0, opens)
            }
        }
    }

    @Test fun `late cancellation ignoring resolve cannot open after query change or pause`() = runTest {
        withModel { model, index ->
            for (pause in listOf(false, true)) {
                if (!model.state.value.active) model.setActive(true)
                search(model)
                val release = CompletableDeferred<Unit>()
                index.resolveAction = { withContext(NonCancellable) { release.await() }; hit }
                var opens = 0
                model.open(hit) { opens++ }; runCurrent()
                if (pause) model.setActive(false) else model.changeQuery("changed")
                release.complete(Unit); runCurrent()
                assertEquals(0, opens); assertNull(model.state.value.action)
            }
        }
    }

    @Test fun `changed live hit cancels pending open and throwing launch remains retryable`() = runTest {
        withModel { model, index ->
            search(model)
            val release = CompletableDeferred<Unit>()
            index.resolveAction = { withContext(NonCancellable) { release.await() }; hit }
            var opens = 0
            model.open(hit) { opens++ }; runCurrent()
            index.result.value = index.result.value.copy(hits = listOf(hit.copy(text = "changed", snippet = "changed")))
            runCurrent(); release.complete(Unit); runCurrent()
            assertEquals(0, opens)
            index.resolveAction = null
            val current = model.state.value.result!!.hits.single()
            model.open(current) { throw IllegalStateException() }; runCurrent()
            assertEquals(LibraryTextActionFeedback.OPEN_FAILED, model.state.value.feedback!!.kind)
            model.open(current) { opens++ }; runCurrent()
            assertEquals(1, opens)
        }
    }

    @Test fun `explicit rebuild clears old hits reports actual book progress and refreshes committed statuses`() = runTest {
        withModel { model, index ->
            search(model)
            assertEquals(0, index.builds)
            model.rebuild()
            assertFalse(model.state.value.actionsEnabled)
            assertNull(model.state.value.searchedQuery)
            model.rebuild(); runCurrent()
            assertEquals(1, index.builds)
            assertTrue(model.state.value.build is LibraryTextBuildPhase.Finished)
            assertFalse((model.state.value.build as LibraryTextBuildPhase.Finished).cancelled)
            assertEquals(8, model.state.value.result!!.books.single().chunkCount)
            assertTrue(model.state.value.result!!.hits.isEmpty())
            assertTrue(model.state.value.canRebuild)
        }
    }

    @Test fun `cancel keeps stopping and rejects rebuild until source cleanup finishes then preserves completed books`() = runTest {
        withModel { model, index ->
            val cleanup = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            var callback: ((LibraryTextIndexProgress) -> Unit)? = null
            index.buildAction = { progress ->
                callback = progress
                progress(LibraryTextIndexProgress(1, 3, "Actual second book"))
                try { awaitCancellation() } finally {
                    entered.complete(Unit)
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
            model.rebuild(); runCurrent()
            val running = model.state.value.build as LibraryTextBuildPhase.Running
            assertEquals(LibraryTextIndexProgress(1, 3, "Actual second book"), running.progress)
            model.cancelBuild(running.id); runCurrent()
            assertTrue(entered.isCompleted)
            assertTrue((model.state.value.build as LibraryTextBuildPhase.Running).stopping)
            assertFalse(model.state.value.canRebuild)
            model.rebuild(); model.submit(); callback!!(LibraryTextIndexProgress(3, 3, "stale")); runCurrent()
            assertEquals(1, index.builds)
            assertEquals(running.progress, (model.state.value.build as LibraryTextBuildPhase.Running).progress)
            cleanup.complete(Unit); runCurrent()
            assertTrue((model.state.value.build as LibraryTextBuildPhase.Finished).cancelled)
            assertEquals(LibraryTextBookState.READY, model.state.value.result!!.books.single().state)
            assertTrue(model.state.value.canRebuild)
        }
    }

    @Test fun `cancel before first job dispatch never leaves rebuilding permanently busy`() = runTest {
        withModel { model, index ->
            model.rebuild()
            model.cancelBuild()
            runCurrent()
            assertEquals(0, index.builds)
            assertTrue((model.state.value.build as LibraryTextBuildPhase.Finished).cancelled)
            assertTrue(model.state.value.canRebuild)
        }
    }

    @Test fun `failed rebuild is retryable and old build start or cancel cannot affect a new run`() = runTest {
        withModel { model, index ->
            val idle = model.state.value.build
            index.buildAction = { throw LibraryTextIndexException(LibraryTextIndexFailure.BUSY) }
            model.rebuild(); runCurrent()
            val failed = model.state.value.build as LibraryTextBuildPhase.Failed
            assertEquals(LibraryTextIndexFailure.BUSY, failed.reason)
            index.buildAction = { awaitCancellation() }
            model.rebuild(failed); runCurrent()
            val current = model.state.value.build as LibraryTextBuildPhase.Running
            model.rebuild(idle); model.cancelBuild(failed.id); runCurrent()
            assertEquals(2, index.builds)
            assertFalse((model.state.value.build as LibraryTextBuildPhase.Running).stopping)
            model.cancelBuild(current.id); runCurrent()
        }
    }

    @Test fun `pause cancels indexing and old owner pause or disposal does not stop replacement work`() = runTest {
        withModel { model, index ->
            val first = Any(); val second = Any()
            model.attachLifecycleOwner(first); model.setLifecycleOwnerActive(first, true); runCurrent()
            index.buildAction = { awaitCancellation() }
            model.rebuild(); runCurrent()
            model.setLifecycleOwnerActive(first, false); runCurrent()
            assertTrue((model.state.value.build as LibraryTextBuildPhase.Finished).cancelled)
            assertEquals(0, index.collectors)
            model.attachLifecycleOwner(second); model.setLifecycleOwnerActive(second, true); runCurrent()
            model.rebuild(); runCurrent()
            val running = model.state.value.build
            model.setLifecycleOwnerActive(first, false); model.detachLifecycleOwner(first); runCurrent()
            assertTrue(model.isLifecycleOwnerActive(second))
            assertEquals(running, model.state.value.build)
            model.cancelBuild(); runCurrent()
            model.setLifecycleOwnerActive(second, false); runCurrent()
            model.setLifecycleOwnerActive(second, true); runCurrent()
            assertEquals(2, index.builds)
            assertEquals(1, index.collectors)
        }
    }

    @Test fun `explicit current book scope clears old search saves exact identity and uses scoped source`() = runTest {
        val saved = SavedStateHandle()
        withModel(saved) { model, index ->
            search(model)
            val scope = BookTextScope(book.id, book.sha256)
            val revision = model.state.value.revision
            model.changeScope(BookTextScope("missing", book.sha256), revision)
            model.changeScope(BookTextScope(book.id, "f".repeat(64)), revision)
            assertNull(model.state.value.scope)
            model.changeScope(scope, revision)
            assertNull(model.state.value.searchedQuery); assertNull(model.state.value.result)
            runCurrent()
            assertEquals(scope, model.state.value.scope); assertEquals(scope, model.state.value.result!!.scope)
            assertTrue(model.state.value.result!!.hits.isEmpty()); assertEquals(0, index.builds)
            assertEquals(book.id, saved.get<String>(LibraryTextSearchViewModel.SCOPE_BOOK))
            assertEquals(book.sha256, saved.get<String>(LibraryTextSearchViewModel.SCOPE_HASH))
            model.submit(); runCurrent(); assertEquals(listOf(hit), model.state.value.result!!.hits)
            assertTrue(index.scopes.isNotEmpty()); assertTrue(index.scopes.all { it == scope })
            model.changeScope(null); runCurrent()
            assertNull(model.state.value.scope); assertNull(model.state.value.searchedQuery)
            assertFalse(saved.contains(LibraryTextSearchViewModel.SCOPE_BOOK)); assertFalse(saved.contains(LibraryTextSearchViewModel.SCOPE_HASH))
        }
    }

    @Test fun `scope restoration is strict and never automatically submits remembered query`() = runTest {
        val saved = SavedStateHandle(mapOf(LibraryTextSearchViewModel.SCOPE_BOOK to book.id,
            LibraryTextSearchViewModel.SCOPE_HASH to book.sha256, LibraryTextSearchViewModel.QUERY to "Original"))
        withModel(saved) { model, index ->
            assertEquals(BookTextScope(book.id, book.sha256), model.state.value.scope)
            assertEquals(listOf(""), index.queries); assertTrue(model.state.value.result!!.hits.isEmpty())
            assertNull(model.state.value.searchedQuery); assertEquals(0, index.builds)
        }
        for (fields in listOf(mapOf<String, Any>(LibraryTextSearchViewModel.SCOPE_BOOK to 42, LibraryTextSearchViewModel.SCOPE_HASH to book.sha256),
            mapOf(LibraryTextSearchViewModel.SCOPE_BOOK to book.id),
            mapOf(LibraryTextSearchViewModel.SCOPE_BOOK to book.id, LibraryTextSearchViewModel.SCOPE_HASH to "INVALID"))) {
            val malformed = SavedStateHandle(fields)
            withModel(malformed) { model, index ->
                assertNull(model.state.value.scope); assertTrue(index.scopes.isEmpty())
                assertFalse(malformed.contains(LibraryTextSearchViewModel.SCOPE_BOOK)); assertFalse(malformed.contains(LibraryTextSearchViewModel.SCOPE_HASH))
            }
        }
    }

    @Test fun `scope ABA rejects old revision and cancellation ignoring open cannot escape its original search`() = runTest {
        withModel { model, index ->
            search(model); val oldRevision = model.state.value.revision
            val release = CompletableDeferred<Unit>(); var opens = 0
            index.resolveAction = { withContext(NonCancellable) { release.await() }; hit }
            model.open(hit) { opens++ }; runCurrent()
            // Scope edits are blocked while a chapter open is pending; query editing cancels it first.
            model.changeScope(BookTextScope(book.id, book.sha256)); assertNull(model.state.value.scope)
            model.changeQuery("different"); runCurrent()
            model.changeScope(BookTextScope(book.id, book.sha256)); runCurrent()
            model.changeScope(null); runCurrent(); search(model)
            model.open(hit, oldRevision) { opens++ }; release.complete(Unit); runCurrent()
            assertEquals(0, opens)
            model.changeScope(BookTextScope(book.id, book.sha256), oldRevision); runCurrent()
            assertNull(model.state.value.scope)
        }
    }

    @Test fun `wrong scope or foreign book returned by source is rejected until explicit retry`() = runTest {
        withModel { model, index ->
            val scope = BookTextScope(book.id, book.sha256)
            model.changeScope(scope); runCurrent()
            index.scopedTransform = { value, _ -> value.copy(scope = null) }
            search(model); assertEquals(LibraryTextIndexFailure.SOURCE, model.state.value.failure)
            index.scopedTransform = { value, requested -> value.copy(scope = requested,
                hits = listOf(hit.copy(book = book.copy(id = "foreign")))) }
            model.retry(); runCurrent(); assertEquals(LibraryTextIndexFailure.SOURCE, model.state.value.failure)
            index.scopedTransform = { value, requested -> value.copy(scope = requested) }
            model.retry(); runCurrent()
            assertNull(model.state.value.failure); assertEquals(listOf(hit), model.state.value.result!!.hits)
        }
    }

    @Test fun `inactive and building scope actions cannot mutate restored or current scope`() = runTest {
        withModel { model, index ->
            val scope = BookTextScope(book.id, book.sha256)
            model.setActive(false); model.changeScope(scope); assertNull(model.state.value.scope)
            model.setActive(true); runCurrent()
            index.buildAction = { awaitCancellation() }
            model.rebuild(); runCurrent(); model.changeScope(scope); assertNull(model.state.value.scope)
            model.cancelBuild(); runCurrent(); model.changeScope(scope); runCurrent()
            assertEquals(scope, model.state.value.scope)
            model.setActive(false); model.changeScope(null); assertEquals(scope, model.state.value.scope)
        }
    }
}

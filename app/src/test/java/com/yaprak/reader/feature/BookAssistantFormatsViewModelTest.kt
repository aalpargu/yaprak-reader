package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookAssistantFormatsViewModelTest {
    private fun hit(format: BookFormat): LibraryTextHit = if (format == BookFormat.EPUB) F.hit else {
        val book = F.book.copy(format = format, pageCount = if (format == BookFormat.PDF) 3 else null)
        F.hit.copy(book = book, readingOrderIndex = 2, chapterTitle = if (format == BookFormat.TXT || format == BookFormat.FB2) "Metin 3" else null,
            target = F.hit.target.copy(sourceFormat = format,
                resourceHref = when (format) { BookFormat.PDF -> ""; BookFormat.FB2 -> "Text/b1-p3.xhtml"; else -> "Text/part0003.xhtml" },
                pdfPageIndex = if (format == BookFormat.PDF) 2 else null))
    }
    private class Index(book: Book) : LibraryTextIndex {
        val book = MutableStateFlow(book)
        var resolve: suspend (LibraryTextHit) -> LibraryTextHit? = { it }
        override fun observe(query: String) = error("Scope required")
        override fun observeBook(query: String, scope: BookTextScope) = book.map {
            LibraryTextSearchResult(query, emptyList(), 0, listOf(LibraryTextBookStatus(it, LibraryTextBookState.READY)), scope)
        }
        override suspend fun rebuild(onProgress: (LibraryTextIndexProgress) -> Unit) = Unit
        override suspend fun resolve(hit: LibraryTextHit) = resolve.invoke(hit)
    }
    private suspend fun TestScope.fixture(format: BookFormat, proof: () -> LibraryTextHit = { hit(format) },
        service: BookAnswerService = BookAnswerService { request, _ -> F.answer(request) },
        block: suspend TestScope.(BookAssistantViewModel, Index, Any) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val index = Index(hit(format).book); val store = ViewModelStore(); val owner = Any()
        val model = BookAssistantViewModel(F.scope, "Güneş", BookSourceRetriever { _, query -> F.sources(proof(), query) }, index, service)
        store.put("formats", model)
        try { model.attachOwner(owner); model.setOwnerActive(owner, true); runCurrent(); block(model, index, owner) }
        finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun configure(model: BookAssistantViewModel) {
        model.editQuestion("Ne oldu?"); model.editEndpoint(F.connection().endpoint.toString()); model.editToken(F.connection().accessToken)
    }
    private fun TestScope.prepare(model: BookAssistantViewModel) { model.prepare(model.state.value.revision); runCurrent() }
    @Test fun typedFormatsPreviewStayLocalAndOnlyExplicitSendTransmitsFreshExactSources() = runTest {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT, BookFormat.FB2)) {
            val requests = mutableListOf<BookQuestionRequest>()
            fixture(format, service = BookAnswerService { request, _ -> requests += request; F.answer(request) }) { model, _, _ ->
                assertEquals(format, model.state.value.book!!.format); assertTrue(requests.isEmpty())
                configure(model); prepare(model); val preview = model.state.value.sources!!
                assertTrue(requests.isEmpty()); assertTrue(model.state.value.canSend); assertFalse(model.state.value.canStudy)
                model.chooseMode(BookAssistantSourceMode.CHAPTER, model.state.value.revision)
                model.loadSections(model.state.value.revision); model.loadChapters(model.state.value.revision)
                assertEquals(BookAssistantSourceMode.KEYWORDS, model.state.value.sourceMode); assertSame(preview, model.state.value.sources)
                model.send(model.state.value.revision); runCurrent()
                assertEquals(1, requests.size); assertNotEquals(preview.requestId, requests.single().sources.requestId)
                assertEquals(hit(format).target, requests.single().sources.references.single().hit.target)
                assertEquals(requests.single().sources.requestId, model.state.value.answer!!.requestId)
            }
        }
    }
    @Test fun changedTypedSourceMustBePreviewedAgainBeforeAProviderCall() = runTest {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT, BookFormat.FB2)) {
            var current = hit(format); var calls = 0
            fixture(format, proof = { current }, service = BookAnswerService { request, _ -> calls++; F.answer(request) }) { model, _, _ ->
                configure(model); prepare(model)
                current = current.copy(text = "Replaced original text")
                model.send(model.state.value.revision); runCurrent()
                assertEquals(0, calls); assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure)
                assertNull(model.state.value.answer)
            }
        }
    }
    @Test fun citationsOpenOnlyTheirRevalidatedRealPageOrPartAndRejectForeignResolve() = runTest {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT, BookFormat.FB2)) fixture(format) { model, index, _ ->
            configure(model); prepare(model); model.send(model.state.value.revision); runCurrent()
            val citation = model.state.value.answer!!.statements.single().citations.single()
            val opened = mutableListOf<LibraryTextHit>()
            model.open(citation, model.state.value.revision) { opened += it }; runCurrent()
            assertEquals(listOf(hit(format)), opened)
            index.resolve = { it.copy(target = if (format == BookFormat.PDF) it.target.copy(pdfPageIndex = 0)
                else it.target.copy(resourceHref = "Text/part0001.xhtml")) }
            model.open(citation, model.state.value.revision) { opened += it }; runCurrent()
            assertEquals(1, opened.size); assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure)
        }
    }
    @Test fun changingAnEpubOwnerToAnotherFormatDropsWholeChapterModeAndItsActions() = runTest {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT, BookFormat.FB2)) {
            var current = F.hit
            fixture(BookFormat.EPUB, proof = { current }) { model, index, _ ->
                model.chooseMode(BookAssistantSourceMode.SECTION, model.state.value.revision)
                assertEquals(BookAssistantSourceMode.SECTION, model.state.value.sourceMode)
                current = hit(format); index.book.value = current.book; runCurrent()
                assertEquals(BookAssistantSourceMode.KEYWORDS, model.state.value.sourceMode)
                assertNull(model.state.value.section); assertNull(model.state.value.chapter); assertFalse(model.state.value.canStudy)
                model.chooseMode(BookAssistantSourceMode.CHAPTER, model.state.value.revision)
                assertEquals(BookAssistantSourceMode.KEYWORDS, model.state.value.sourceMode)
                configure(model); prepare(model); assertTrue(model.state.value.canSend)
            }
        }
    }
    @Test fun lateTypedAnswerCannotReplaceTheCurrentResumedRequest() = runTest {
        for (format in listOf(BookFormat.PDF, BookFormat.TXT, BookFormat.FB2)) {
            val late = CompletableDeferred<Unit>(); val requests = mutableListOf<BookQuestionRequest>()
            fixture(format, service = BookAnswerService { request, _ ->
                requests += request; if (requests.size == 1) withContext(NonCancellable) { late.await() }; F.answer(request)
            }) { model, _, owner ->
                configure(model); prepare(model); model.send(model.state.value.revision); runCurrent()
                model.setOwnerActive(owner, false); model.setOwnerActive(owner, true); runCurrent()
                prepare(model); model.send(model.state.value.revision); runCurrent(); val answer = model.state.value.answer!!
                late.complete(Unit); runCurrent()
                assertSame(answer, model.state.value.answer); assertEquals(2, requests.size)
                assertNotEquals(requests.first().sources.requestId, answer.requestId)
            }
        }
    }
}

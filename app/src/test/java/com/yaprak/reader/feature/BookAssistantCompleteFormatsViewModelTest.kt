package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.CompleteFormatSources as C
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookAssistantCompleteFormatsViewModelTest {
    private val formats = listOf(BookFormat.PDF, BookFormat.TXT, BookFormat.FB2, BookFormat.DOCX)
    private class Index(book: Book) : LibraryTextIndex {
        val book = MutableStateFlow(book)
        override fun observe(query: String) = error("Book scope required")
        override fun observeBook(query: String, scope: BookTextScope) = book.map {
            LibraryTextSearchResult(query, emptyList(), 0, listOf(LibraryTextBookStatus(it, LibraryTextBookState.READY)), scope)
        }
        override suspend fun rebuild(onProgress: (LibraryTextIndexProgress) -> Unit) = Unit
        override suspend fun resolve(hit: LibraryTextHit) = hit
    }
    private class Retriever(val section: BookSourceSection) : BookSourceRetriever {
        var catalogs = 0; var proofs = 0; var keywordProofs = 0
        var catalog: suspend () -> List<BookSourceSection> = { listOf(section) }
        var proof: suspend () -> PreparedBookSources = { C.sources(section) }
        override suspend fun prepare(scope: BookTextScope, query: String): PreparedBookSources {
            keywordProofs++; return F.sources(query = query)
        }
        override suspend fun listSections(scope: BookTextScope): List<BookSourceSection> { catalogs++; return catalog() }
        override suspend fun prepareSection(scope: BookTextScope, section: BookSourceSection): PreparedBookSources {
            assertEquals(this.section, section); proofs++; return proof()
        }
    }
    private fun answer(request: BookQuestionRequest): BookAnswer {
        val source = request.sources.references.last()
        return BookAnswer(request.sources.requestId, BookAnswerStatus.ANSWERED,
            listOf(BookAnswerStatement("Kaynağın açıklaması", listOf(BookAnswerCitation(source, source.text)))), "")
    }
    private suspend fun TestScope.fixture(format: BookFormat,
        service: BookAnswerService = BookAnswerService { request, _ -> answer(request) },
        block: suspend TestScope.(BookAssistantViewModel, Retriever, Index, Any) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val index = Index(C.book(format)); val retriever = Retriever(C.section(format)); val store = ViewModelStore(); val owner = Any()
        val model = BookAssistantViewModel(F.scope, "Güneş", retriever, index, service); store.put("complete", model)
        try { model.attachOwner(owner); model.setOwnerActive(owner, true); runCurrent(); block(model, retriever, index, owner) }
        finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun TestScope.select(model: BookAssistantViewModel) {
        model.chooseMode(BookAssistantSourceMode.SECTION, model.state.value.revision)
        model.loadSections(model.state.value.revision); runCurrent()
        model.chooseSection(model.state.value.sections.single(), model.state.value.revision)
    }
    private fun TestScope.prepare(model: BookAssistantViewModel) { model.prepare(model.state.value.revision); runCurrent() }
    private fun configure(model: BookAssistantViewModel) {
        model.editEndpoint(F.connection().endpoint.toString()); model.editToken(F.connection().accessToken)
    }
    @Test fun catalogFullPreviewAndTypedPresetsStayLocalUntilExplicitOnceSendWithFreshRequestAndLastCitation() = runTest {
        for (format in formats) {
            val sent = mutableListOf<BookQuestionRequest>()
            fixture(format, BookAnswerService { request, _ -> sent += request; answer(request) }) { model, retriever, _, _ ->
                assertEquals(0, retriever.catalogs); configure(model); select(model)
                model.study(BookStudyTask.SUMMARY, model.state.value.revision); assertEquals("", model.state.value.question)
                prepare(model); val preview = model.state.value.sources!!
                assertEquals(12, preview.references.size); assertTrue(model.state.value.canStudy); assertTrue(sent.isEmpty())
                val old = model.state.value.revision; model.study(BookStudyTask.SUMMARY, old)
                assertEquals(BookStudyTask.SUMMARY.questionForSection(format), model.state.value.question)
                model.study(BookStudyTask.PRACTICE, old)
                assertEquals(BookStudyTask.SUMMARY.questionForSection(format), model.state.value.question)
                model.study(BookStudyTask.PRACTICE, model.state.value.revision); assertTrue(sent.isEmpty())
                val revision = model.state.value.revision; model.send(revision); model.send(revision); runCurrent()
                assertEquals(1, sent.size); assertEquals(2, retriever.proofs); assertEquals(0, retriever.keywordProofs)
                assertNotEquals(preview.requestId, sent.single().sources.requestId)
                assertEquals(BookStudyTask.PRACTICE.questionForSection(format), sent.single().question)
                val citation = model.state.value.answer!!.statements.single().citations.single()
                assertEquals("S12", citation.source.label); assertEquals(format, citation.source.hit.target.sourceFormat)
                val opened = mutableListOf<LibraryTextHit>()
                model.open(citation, model.state.value.revision) { opened += it }; runCurrent()
                assertEquals(listOf(citation.source.hit), opened)
            }
        }
    }
    @Test fun partialOrChangedCompleteEvidenceRequiresANewPreviewBeforeAnyProviderCall() = runTest {
        for (format in formats) for (changedText in listOf(false, true)) {
            var calls = 0
            fixture(format, BookAnswerService { request, _ -> calls++; answer(request) }) { model, retriever, _, _ ->
                configure(model); select(model); prepare(model); model.study(BookStudyTask.SUMMARY, model.state.value.revision)
                retriever.proof = {
                    val fresh = C.sources(retriever.section, suffix = if (changedText) "changed" else "")
                    if (changedText) fresh else PreparedBookSources(fresh.requestId, fresh.scope, "", 12, fresh.references.take(8), fresh.section)
                }
                model.send(model.state.value.revision); runCurrent()
                assertEquals(0, calls); assertNull(model.state.value.sources); assertNull(model.state.value.answer)
                assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure)
            }
        }
    }
    @Test fun wrongTypedCatalogCannotEnableStudyAndOldModeActionsCannotRestoreASource() = runTest {
        for (format in formats) fixture(format) { model, retriever, _, _ ->
            retriever.catalog = { listOf(C.section(if (format == BookFormat.PDF) BookFormat.TXT else BookFormat.PDF)) }
            model.chooseMode(BookAssistantSourceMode.SECTION, model.state.value.revision)
            model.loadSections(model.state.value.revision); runCurrent()
            assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure); assertTrue(model.state.value.sections.isEmpty())
            assertFalse(model.state.value.canPrepare); assertFalse(model.state.value.canStudy)
            retriever.catalog = { listOf(retriever.section) }; model.loadSections(model.state.value.revision); runCurrent()
            model.chooseSection(retriever.section, model.state.value.revision); prepare(model); val old = model.state.value.revision
            model.chooseMode(BookAssistantSourceMode.KEYWORDS, old)
            model.study(BookStudyTask.SUMMARY, old); model.chooseSection(retriever.section, old); model.send(old)
            assertEquals("", model.state.value.question); assertNull(model.state.value.section); assertNull(model.state.value.sources)
            model.chooseMode(BookAssistantSourceMode.CHAPTER, model.state.value.revision)
            assertEquals(BookAssistantSourceMode.KEYWORDS, model.state.value.sourceMode)
        }
    }
    @Test fun metadataChangeDuringNoncancellableProofCannotPublishOldPageOrPart() = runTest {
        for (format in formats) fixture(format) { model, retriever, index, _ ->
            select(model); val release = CompletableDeferred<Unit>()
            retriever.proof = { withContext(NonCancellable) { release.await() }; C.sources(retriever.section) }
            model.prepare(model.state.value.revision); runCurrent(); assertTrue(model.state.value.busy)
            index.book.value = index.book.value.copy(title = "Changed metadata"); runCurrent()
            release.complete(Unit); runCurrent()
            assertNull(model.state.value.sources); assertNull(model.state.value.section); assertFalse(model.state.value.busy)
            assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure)
        }
    }
    @Test fun pauseAndNewOwnerIgnoreLateCatalogAndOldSelectionCallbacks() = runTest {
        for (format in formats) fixture(format) { model, retriever, _, owner ->
            val release = CompletableDeferred<Unit>(); var loads = 0
            retriever.catalog = { if (++loads == 1) withContext(NonCancellable) { release.await() }; listOf(retriever.section) }
            model.chooseMode(BookAssistantSourceMode.SECTION, model.state.value.revision)
            model.loadSections(model.state.value.revision); runCurrent(); val old = model.state.value.revision
            model.setOwnerActive(owner, false); val nextOwner = Any(); model.attachOwner(nextOwner); model.setOwnerActive(nextOwner, true); runCurrent()
            model.loadSections(model.state.value.revision); runCurrent(); model.chooseSection(retriever.section, model.state.value.revision)
            release.complete(Unit); runCurrent(); val revision = model.state.value.revision
            model.chooseMode(BookAssistantSourceMode.KEYWORDS, old); model.study(BookStudyTask.SUMMARY, old)
            assertEquals(revision, model.state.value.revision); assertEquals(retriever.section, model.state.value.section)
            assertEquals(2, loads); assertEquals("", model.state.value.question)
        }
    }
    @Test fun completeScopeLimitAndSourceCancellationHaveNoAutomaticRetryOrDraftSend() = runTest {
        for (format in formats) fixture(format) { model, retriever, _, _ ->
            select(model); retriever.proof = { throw BookSourceException(BookSourceFailure.LIMIT) }; prepare(model)
            assertEquals(BookSourceFailure.LIMIT, model.state.value.sourceFailure); assertFalse(model.state.value.canStudy)
            assertEquals(1, retriever.proofs); model.study(BookStudyTask.SUMMARY, model.state.value.revision)
            assertEquals("", model.state.value.question)
            retriever.proof = { throw CancellationException("Cancelled by caller") }; prepare(model)
            assertFalse(model.state.value.busy); assertNull(model.state.value.failure); assertNull(model.state.value.sourceFailure)
            assertNull(model.state.value.sources); assertEquals(2, retriever.proofs)
        }
    }
}

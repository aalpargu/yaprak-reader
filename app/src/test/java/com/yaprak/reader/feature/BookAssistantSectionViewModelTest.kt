package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookAssistantSectionViewModelTest {
    private val section = BookSourceSection(F.scope, UUID.randomUUID().toString(), 0,
        F.hit.target.resourceHref, F.hit.target.resourceSha256, F.hit.chapterTitle, 0, 1)
    private val second = section.copy(readingOrderIndex = 2, resourceHref = "EPUB/second.xhtml", firstChunkIndex = 1)
    private fun sources(selected: BookSourceSection) = PreparedBookSources(UUID.randomUUID().toString(), F.scope, "", 1,
        listOf(BookSourceReference("S1", F.hit.copy(generation = selected.generation,
            readingOrderIndex = selected.readingOrderIndex, chunkIndex = selected.firstChunkIndex,
            target = F.hit.target.copy(resourceHref = selected.resourceHref)))), selected)
    private open inner class Retriever : BookSourceRetriever {
        var catalogs = 0; var preparations = 0; var keywordProofs = 0
        override suspend fun prepare(scope: BookTextScope, query: String): PreparedBookSources {
            keywordProofs++; return F.sources(query = query)
        }
        override suspend fun listSections(scope: BookTextScope): List<BookSourceSection> {
            catalogs++; return listOf(section, second)
        }
        override suspend fun prepareSection(scope: BookTextScope, section: BookSourceSection): PreparedBookSources {
            preparations++; return sources(section)
        }
    }

    @Test fun completeLongSectionReprovesAndOpensTheSixtyFourthSourceWithoutKeywordFallback() = runTest {
        val long = section.copy(chunkCount = 64)
        var proofs = 0; var sends = 0
        val retriever = object : Retriever() {
            override suspend fun listSections(scope: BookTextScope) = listOf(long)
            override suspend fun prepareSection(scope: BookTextScope, section: BookSourceSection): PreparedBookSources {
                proofs++
                return PreparedBookSources(UUID.randomUUID().toString(), F.scope, "", 64,
                    (0 until 64).map { index -> BookSourceReference("S" + (index + 1),
                        F.hit.copy(id = index + 1L, generation = long.generation, chunkIndex = index,
                            text = "Güneş doğdu. Kaynak parçası $index.")) }, long)
            }
        }
        withModel(retriever, BookAnswerService { request, _ ->
            sends++; assertEquals(64, request.sources.references.size)
            val source = request.sources.references.last()
            BookAnswer(request.sources.requestId, BookAnswerStatus.ANSWERED,
                listOf(BookAnswerStatement("Son parçayı açıklama.", listOf(BookAnswerCitation(source, source.hit.text)))), "")
        }) { model, _, _ ->
            configure(model); model.chooseMode(BookAssistantSourceMode.SECTION, model.state.value.revision)
            model.loadSections(model.state.value.revision); runCurrent()
            model.chooseSection(long, model.state.value.revision); prepare(model)
            assertEquals(64, model.state.value.sources!!.references.size); assertEquals(0, sends)
            model.study(BookStudyTask.SUMMARY, model.state.value.revision)
            model.send(model.state.value.revision); runCurrent()
            assertEquals(2, proofs); assertEquals(1, sends); assertEquals(0, retriever.keywordProofs)
            val citation = model.state.value.answer!!.statements.single().citations.single()
            assertEquals("S64", citation.source.label); assertEquals(63, citation.source.hit.chunkIndex)
            val opened = mutableListOf<LibraryTextHit>()
            model.open(citation, model.state.value.revision) { opened += it }; runCurrent()
            assertEquals(listOf(citation.source.hit), opened)
        }
    }

    private class Index : LibraryTextIndex {
        val book = MutableStateFlow<Book?>(F.book)
        override fun observe(query: String) = flowOf(LibraryTextSearchResult(query, emptyList(), 0, emptyList()))
        override fun observeBook(query: String, scope: BookTextScope) = book.map { value ->
            LibraryTextSearchResult(query, emptyList(), 0, listOfNotNull(value?.let {
                LibraryTextBookStatus(it, LibraryTextBookState.READY) }), scope)
        }
        override suspend fun rebuild(onProgress: (LibraryTextIndexProgress) -> Unit) = Unit
        override suspend fun resolve(hit: LibraryTextHit) = hit
    }
    private suspend fun TestScope.withModel(retriever: BookSourceRetriever = Retriever(),
        service: BookAnswerService = BookAnswerService { request, _ -> F.answer(request) },
        block: suspend TestScope.(BookAssistantViewModel, Index, Any) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); val index = Index(); val owner = Any()
        val model = BookAssistantViewModel(F.scope, "Güneş", retriever, index, service).also { store.put("assistant", it) }
        try { model.attachOwner(owner); model.setOwnerActive(owner, true); runCurrent(); block(model, index, owner) }
        finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun configure(model: BookAssistantViewModel) {
        model.editEndpoint(F.connection().endpoint.toString()); model.editToken(F.connection().accessToken)
    }
    private suspend fun TestScope.select(model: BookAssistantViewModel) {
        model.chooseMode(BookAssistantSourceMode.SECTION, model.state.value.revision)
        model.loadSections(model.state.value.revision); runCurrent()
        model.chooseSection(section, model.state.value.revision)
    }
    private suspend fun TestScope.prepare(model: BookAssistantViewModel) {
        model.prepare(model.state.value.revision); runCurrent()
    }
    @Test fun explicitSelectionPreparationAndPresetsNeverSendAndSendReprovesCompleteSources() = runTest {
        val retriever = Retriever(); val sent = mutableListOf<BookQuestionRequest>()
        withModel(retriever, BookAnswerService { request, _ -> sent += request; F.answer(request) }) { model, _, _ ->
            assertEquals(0, retriever.catalogs); assertEquals(0, retriever.preparations); assertTrue(sent.isEmpty())
            configure(model); select(model)
            assertEquals(1, retriever.catalogs); assertEquals(0, retriever.preparations)
            model.study(BookStudyTask.SUMMARY, model.state.value.revision); assertEquals("", model.state.value.question)
            prepare(model); val preview = model.state.value.sources!!
            val old = model.state.value.revision
            model.study(BookStudyTask.SUMMARY, old); assertEquals(BookStudyTask.SUMMARY.question, model.state.value.question)
            model.study(BookStudyTask.PRACTICE, old); assertEquals(BookStudyTask.SUMMARY.question, model.state.value.question)
            model.study(BookStudyTask.PRACTICE, model.state.value.revision)
            assertEquals(BookStudyTask.PRACTICE.question, model.state.value.question)
            assertTrue(sent.isEmpty()); assertEquals(0, retriever.keywordProofs)
            val revision = model.state.value.revision; model.send(revision); model.send(revision); runCurrent()
            assertEquals(2, retriever.preparations); assertEquals(1, sent.size)
            assertEquals(section, sent.single().sources.section); assertNotEquals(preview.requestId, sent.single().sources.requestId)
            assertEquals(BookStudyTask.PRACTICE.question, sent.single().question)
        }
    }
    @Test fun partialOrChangedFreshSourcesNeverTransmit() = runTest {
        var calls = 0; var proofs = 0
        val retriever = object : Retriever() {
            override suspend fun prepareSection(scope: BookTextScope, section: BookSourceSection): PreparedBookSources {
                proofs++
                val full = sources(section)
                return if (proofs == 1) full else PreparedBookSources(full.requestId, full.scope, "", 1, emptyList(), section)
            }
        }
        withModel(retriever, BookAnswerService { request, _ -> calls++; F.answer(request) }) { model, _, _ ->
            configure(model); select(model); prepare(model); model.study(BookStudyTask.SUMMARY, model.state.value.revision)
            model.send(model.state.value.revision); runCurrent()
            assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure)
            assertNull(model.state.value.sources); assertNull(model.state.value.answer); assertEquals(0, calls)
        }
    }
    @Test fun oldCatalogChoicesAndPresetsCannotActAfterModeOrSelectionChanges() = runTest {
        var calls = 0
        withModel(service = BookAnswerService { request, _ -> calls++; F.answer(request) }) { model, _, _ ->
            configure(model); select(model); prepare(model); val old = model.state.value.revision
            model.chooseSection(second, old); assertNull(model.state.value.sources)
            model.send(old); model.study(BookStudyTask.SUMMARY, old); assertEquals("", model.state.value.question)
            model.chooseSection(section, old); assertEquals(second, model.state.value.section)
            model.chooseMode(BookAssistantSourceMode.KEYWORDS, model.state.value.revision)
            assertTrue(model.state.value.sections.isEmpty()); assertNull(model.state.value.section)
            val revision = model.state.value.revision
            model.chooseSection(section, revision); model.loadSections(revision); runCurrent()
            assertNull(model.state.value.section); assertEquals(0, calls)
            prepare(model); assertNull(model.state.value.sources!!.section)
        }
    }
    @Test fun lateCatalogAfterPauseCannotReplaceResumedSelection() = runTest {
        val release = CompletableDeferred<Unit>(); var catalogs = 0
        val retriever = object : Retriever() {
            override suspend fun listSections(scope: BookTextScope): List<BookSourceSection> {
                if (++catalogs == 1) { withContext(NonCancellable) { release.await() }; return listOf(section, second) }
                return listOf(section)
            }
        }
        withModel(retriever) { model, _, owner ->
            model.chooseMode(BookAssistantSourceMode.SECTION, model.state.value.revision)
            model.loadSections(model.state.value.revision); runCurrent(); assertTrue(model.state.value.busy)
            model.setOwnerActive(owner, false); model.setOwnerActive(owner, true); runCurrent()
            assertFalse(model.state.value.sectionsLoaded); assertNull(model.state.value.section)
            model.loadSections(model.state.value.revision); runCurrent()
            model.chooseSection(section, model.state.value.revision)
            release.complete(Unit); runCurrent()
            assertEquals(listOf(section), model.state.value.sections); assertEquals(section, model.state.value.section)
        }
    }
    @Test fun metadataChangeDuringProofDropsPreparedSectionAndCatalog() = runTest {
        val release = CompletableDeferred<Unit>()
        val retriever = object : Retriever() {
            override suspend fun prepareSection(scope: BookTextScope, section: BookSourceSection): PreparedBookSources {
                withContext(NonCancellable) { release.await() }; return sources(section)
            }
        }
        withModel(retriever) { model, index, _ ->
            select(model); model.prepare(model.state.value.revision); runCurrent(); assertTrue(model.state.value.busy)
            index.book.value = F.book.copy(title = "Changed"); runCurrent()
            assertTrue(model.state.value.sections.isEmpty()); assertNull(model.state.value.section)
            release.complete(Unit); runCurrent()
            assertNull(model.state.value.sources); assertFalse(model.state.value.busy)
            assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure)
        }
    }
    @Test fun oversizeAndRealCancellationRemainExplicitAndNoAutomaticRetryOccurs() = runTest {
        var proofs = 0
        val retriever = object : Retriever() {
            override suspend fun prepareSection(scope: BookTextScope, section: BookSourceSection): PreparedBookSources {
                if (++proofs == 1) throw BookSourceException(BookSourceFailure.LIMIT)
                throw CancellationException()
            }
        }
        withModel(retriever) { model, _, _ ->
            select(model); prepare(model)
            assertEquals(BookSourceFailure.LIMIT, model.state.value.sourceFailure); assertEquals(section, model.state.value.section)
            assertNull(model.state.value.sources); assertFalse(model.state.value.canSend); assertEquals(1, proofs)
            prepare(model); assertFalse(model.state.value.busy); assertNull(model.state.value.sourceFailure)
            assertNull(model.state.value.failure); assertEquals(2, proofs)
        }
    }
}

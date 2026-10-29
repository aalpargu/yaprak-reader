package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.ChapterAssistantFixtures as C
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookAssistantChapterViewModelTest {
    private open class Retriever : BookSourceRetriever {
        var catalogs = 0; var proofs = 0; var keywords = 0
        override suspend fun prepare(scope: BookTextScope, query: String): PreparedBookSources {
            keywords++; return F.sources(query = query)
        }
        override suspend fun listChapters(scope: BookTextScope): List<BookSourceChapter> { catalogs++; return listOf(C.chapter) }
        override suspend fun prepareChapter(scope: BookTextScope, chapter: BookSourceChapter): PreparedBookSources {
            proofs++; return C.sources(chapter)
        }
    }
    private class Index : LibraryTextIndex {
        val book = MutableStateFlow<Book?>(F.book)
        val resolved = mutableListOf<LibraryTextHit>()
        override fun observe(query: String) = flowOf(LibraryTextSearchResult(query, emptyList(), 0, emptyList()))
        override fun observeBook(query: String, scope: BookTextScope) = book.map { value ->
            LibraryTextSearchResult(query, emptyList(), 0, listOfNotNull(value?.let {
                LibraryTextBookStatus(it, LibraryTextBookState.READY) }), scope)
        }
        override suspend fun rebuild(onProgress: (LibraryTextIndexProgress) -> Unit) = Unit
        override suspend fun resolve(hit: LibraryTextHit): LibraryTextHit { resolved += hit; return hit }
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
    private suspend fun TestScope.select(model: BookAssistantViewModel) {
        model.chooseMode(BookAssistantSourceMode.CHAPTER, model.state.value.revision)
        model.loadChapters(model.state.value.revision); runCurrent()
        model.chooseChapter(C.chapter, model.state.value.revision)
    }
    private fun configure(model: BookAssistantViewModel) {
        model.editEndpoint(F.connection().endpoint.toString()); model.editToken(F.connection().accessToken)
    }

    @Test fun explicitChapterPreparationAndPresetStayLocalAndSendReprovesTheExactExcerpt() = runTest {
        val retriever = Retriever(); val sent = mutableListOf<BookQuestionRequest>()
        withModel(retriever, BookAnswerService { request, _ -> sent += request; F.answer(request) }) { model, index, _ ->
            assertEquals(0, retriever.catalogs); assertEquals(0, retriever.proofs); assertTrue(sent.isEmpty())
            configure(model); select(model)
            model.study(BookStudyTask.SUMMARY, model.state.value.revision); assertEquals("", model.state.value.question)
            model.prepare(model.state.value.revision); runCurrent()
            assertTrue(model.state.value.canStudy); assertTrue(sent.isEmpty())
            val preview = model.state.value.sources!!
            model.study(BookStudyTask.SUMMARY, model.state.value.revision)
            assertEquals(BookStudyTask.SUMMARY.questionForChapter(), model.state.value.question)
            val revision = model.state.value.revision; model.send(revision); model.send(revision); runCurrent()
            assertEquals(2, retriever.proofs); assertEquals(0, retriever.keywords); assertEquals(1, sent.size)
            assertEquals(preview.references, sent.single().sources.references)
            assertNotEquals(preview.requestId, sent.single().sources.requestId)
            val opened = mutableListOf<LibraryTextHit>()
            val citation = model.state.value.answer!!.statements.single().citations.single()
            model.open(citation, model.state.value.revision) { opened += it }; runCurrent()
            assertEquals(listOf(C.hit), opened); assertEquals(listOf(C.hit), index.resolved)
        }
    }

    @Test fun changedExcerptBeforeSendNeverTransmitsAndRequiresNewPreview() = runTest {
        var sends = 0; var proofs = 0
        val retriever = object : Retriever() {
            override suspend fun prepareChapter(scope: BookTextScope, chapter: BookSourceChapter): PreparedBookSources {
                proofs++
                return if (proofs == 1) C.sources() else C.sources(slice = BookSourceExcerpt(0, C.from + 1, C.until))
            }
        }
        withModel(retriever, BookAnswerService { request, _ -> sends++; F.answer(request) }) { model, _, _ ->
            configure(model); select(model); model.prepare(model.state.value.revision); runCurrent()
            model.study(BookStudyTask.PRACTICE, model.state.value.revision)
            model.send(model.state.value.revision); runCurrent()
            assertEquals(0, sends); assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure)
            assertNull(model.state.value.sources); assertNull(model.state.value.answer)
        }
    }

    @Test fun oldChapterChoicesAndSendCallbacksCannotActAfterModeChanges() = runTest {
        val retriever = Retriever(); var sends = 0
        withModel(retriever, BookAnswerService { request, _ -> sends++; F.answer(request) }) { model, _, _ ->
            configure(model); select(model); model.prepare(model.state.value.revision); runCurrent()
            val old = model.state.value.revision
            model.chooseMode(BookAssistantSourceMode.KEYWORDS, old)
            model.chooseChapter(C.chapter, old); model.study(BookStudyTask.SUMMARY, old); model.send(old)
            model.loadChapters(model.state.value.revision); runCurrent()
            assertEquals("", model.state.value.question); assertEquals(0, sends)
            assertTrue(model.state.value.chapters.isEmpty()); assertNull(model.state.value.chapter)
            assertNull(model.state.value.sources); assertEquals(1, retriever.catalogs)
        }
    }

    @Test fun cancellationIgnoringCatalogCannotPublishAfterPauseOrOwnerReplacement() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val retriever = object : Retriever() {
            override suspend fun listChapters(scope: BookTextScope): List<BookSourceChapter> {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; return listOf(C.chapter)
            }
        }
        withModel(retriever) { model, _, owner ->
            model.chooseMode(BookAssistantSourceMode.CHAPTER, model.state.value.revision)
            model.loadChapters(model.state.value.revision); runCurrent(); assertTrue(entered.isCompleted)
            model.setOwnerActive(owner, false); val newer = Any(); model.attachOwner(newer); model.setOwnerActive(newer, true)
            runCurrent(); release.complete(Unit); runCurrent()
            assertFalse(model.state.value.chaptersLoaded); assertTrue(model.state.value.chapters.isEmpty())
            assertFalse(model.state.value.busy); assertNull(model.state.value.sources)
        }
    }

    @Test fun metadataChangeDropsTheChosenChapterAndUnsupportedSourcesNeverFallBackSilently() = runTest {
        val retriever = object : Retriever() {
            override suspend fun prepareChapter(scope: BookTextScope, chapter: BookSourceChapter): PreparedBookSources =
                throw BookSourceException(BookSourceFailure.UNSUPPORTED)
        }
        withModel(retriever) { model, index, _ ->
            select(model); model.prepare(model.state.value.revision); runCurrent()
            assertEquals(BookSourceFailure.UNSUPPORTED, model.state.value.sourceFailure)
            assertEquals(0, retriever.keywords); assertNull(model.state.value.sources)
            index.book.value = F.book.copy(title = "Changed"); runCurrent()
            assertNull(model.state.value.chapter); assertFalse(model.state.value.chaptersLoaded)
            assertTrue(model.state.value.chapters.isEmpty()); assertFalse(model.state.value.canPrepare)
        }
    }
}

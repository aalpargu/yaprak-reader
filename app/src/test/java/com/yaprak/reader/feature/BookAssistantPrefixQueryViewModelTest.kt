package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.PrefixAssistantFixtures as C
import com.yaprak.reader.fixtures.PrefixQueryAssistantFixtures as Q
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookAssistantPrefixQueryViewModelTest {
    private open class Retriever : BookSourceRetriever {
        var catalogs = 0; var proofs = 0; var keywords = 0
        override suspend fun prepare(scope: BookTextScope, query: String): PreparedBookSources {
            keywords++; return F.sources(query = query)
        }
        override suspend fun listChapters(scope: BookTextScope): List<BookSourceChapter> { catalogs++; return listOf(C.chapter) }
        override suspend fun preparePrefixQuery(scope: BookTextScope, throughChapter: BookSourceChapter, query: String): PreparedBookSources {
            proofs++; return Q.sources(query,throughChapter)
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
        model.chooseMode(BookAssistantSourceMode.PREFIX_KEYWORDS, model.state.value.revision)
        model.loadChapters(model.state.value.revision); runCurrent()
        model.chooseChapter(C.chapter, model.state.value.revision)
    }
    private fun configure(model: BookAssistantViewModel) {
        model.editEndpoint(F.connection().endpoint.toString()); model.editToken(F.connection().accessToken)
    }

    @Test fun explicitKeywordBoundaryPreparationStaysLocalCannotDraftAWholeSummaryAndSendReprovesExactQuery() = runTest {
        val retriever = Retriever(); val sent = mutableListOf<BookQuestionRequest>()
        withModel(retriever, BookAnswerService { request, _ -> sent += request; F.answer(request) }) { model, index, _ ->
            assertEquals(0,retriever.catalogs); assertEquals(0,retriever.proofs); assertTrue(sent.isEmpty())
            configure(model); select(model); model.editQuestion("Soru")
            model.prepare(model.state.value.revision); runCurrent()
            val preview=model.state.value.sources!!
            assertEquals("Güneş",preview.query); assertFalse(model.state.value.canStudy); assertTrue(model.state.value.canSend)
            model.study(BookStudyTask.SUMMARY,model.state.value.revision)
            model.study(BookStudyTask.PRACTICE,model.state.value.revision)
            assertEquals("Soru",model.state.value.question); assertTrue(sent.isEmpty())
            val revision=model.state.value.revision;model.send(revision);model.send(revision);runCurrent()
            assertEquals(2,retriever.proofs);assertEquals(0,retriever.keywords);assertEquals(1,sent.size)
            assertEquals(preview.references,sent.single().sources.references)
            assertEquals(preview.prefixQuery,sent.single().sources.prefixQuery)
            assertNotEquals(preview.requestId,sent.single().sources.requestId)
            val opened=mutableListOf<LibraryTextHit>()
            val citation=model.state.value.answer!!.statements.single().citations.single()
            model.open(citation,model.state.value.revision) { opened+=it };runCurrent()
            assertEquals(listOf(C.hit),opened);assertEquals(listOf(C.hit),index.resolved)
        }
    }

    @Test fun changedExcerptBeforeSendNeverTransmitsAndRequiresNewPreview() = runTest {
        var sends = 0; var proofs = 0
        val retriever = object : Retriever() {
            override suspend fun preparePrefixQuery(scope: BookTextScope, throughChapter: BookSourceChapter, query: String): PreparedBookSources {
                proofs++
                val fresh = if (proofs == 1) Q.sources(query,throughChapter) else Q.sources(query,throughChapter,slice = BookSourceExcerpt(0, C.from, C.until - 1))
                assertTrue(validPrefixQuerySources(fresh))
                return fresh
            }
        }
        withModel(retriever, BookAnswerService { request, _ -> sends++; F.answer(request) }) { model, _, _ ->
            configure(model); select(model); model.prepare(model.state.value.revision); runCurrent()
            model.editQuestion("Soru")
            model.send(model.state.value.revision); runCurrent()
            assertEquals(0, sends); assertEquals(BookSourceFailure.SOURCE, model.state.value.sourceFailure)
            assertNull(model.state.value.sources); assertNull(model.state.value.answer)
        }
    }

    @Test fun oldBoundaryChoicesAndSendCallbacksCannotActAfterModeChanges() = runTest {
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
            model.chooseMode(BookAssistantSourceMode.PREFIX_KEYWORDS, model.state.value.revision)
            model.loadChapters(model.state.value.revision); runCurrent(); assertTrue(entered.isCompleted)
            model.setOwnerActive(owner, false); val newer = Any(); model.attachOwner(newer); model.setOwnerActive(newer, true)
            runCurrent(); release.complete(Unit); runCurrent()
            assertFalse(model.state.value.chaptersLoaded); assertTrue(model.state.value.chapters.isEmpty())
            assertFalse(model.state.value.busy); assertNull(model.state.value.sources)
        }
    }

    @Test fun metadataChangeDropsTheChosenPrefixAndUnsupportedSourcesNeverFallBackSilently() = runTest {
        val retriever = object : Retriever() {
            override suspend fun preparePrefixQuery(scope: BookTextScope, throughChapter: BookSourceChapter, query: String): PreparedBookSources =
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

    @Test fun nonEpubBooksCannotEnterPrefixModeOrLoadBoundarySources() = runTest {
        val retriever=Retriever()
        withModel(retriever) { model,index,_ ->
            for(format in com.yaprak.reader.domain.library.BookFormat.entries.filter { it != com.yaprak.reader.domain.library.BookFormat.EPUB }) {
                index.book.value=F.book.copy(format=format);runCurrent()
                model.chooseMode(BookAssistantSourceMode.PREFIX_KEYWORDS,model.state.value.revision)
                model.loadChapters(model.state.value.revision);runCurrent()
                assertEquals(BookAssistantSourceMode.KEYWORDS,model.state.value.sourceMode)
                assertNull(model.state.value.sources);assertEquals(0,retriever.catalogs);assertEquals(0,retriever.proofs)
            }
        }
    }

    @Test fun editingKeywordsInvalidatesPreviewWithoutChangingBoundaryAndOldSendCannotAct() = runTest {
        val retriever=Retriever();var sends=0
        withModel(retriever,BookAnswerService { request,_->sends++;F.answer(request) }) { model,_,_->
            configure(model);select(model);model.editQuestion("Soru");model.prepare(model.state.value.revision);runCurrent()
            assertTrue(model.state.value.canSend);val old=model.state.value.revision
            model.editKeywords("Giriş")
            assertNull(model.state.value.sources);assertNull(model.state.value.answer);assertFalse(model.state.value.canSend)
            assertEquals(C.chapter,model.state.value.chapter);assertTrue(model.state.value.canPrepare)
            model.send(old);runCurrent();assertEquals(0,sends);assertEquals(1,retriever.proofs)
            model.prepare(model.state.value.revision);runCurrent()
            assertEquals("Giriş",model.state.value.sources!!.query);assertEquals(2,retriever.proofs);assertEquals(0,retriever.keywords)
        }
    }

    @Test fun emptyMatchesAndUnsupportedLegacyRetrieverNeverBecomeSendOrGlobalFallback() = runTest {
        var global=0;var sends=0
        val empty=object: Retriever() {
            override suspend fun preparePrefixQuery(scope: BookTextScope,throughChapter: BookSourceChapter,query: String)=
                Q.sources(query,throughChapter,references=emptyList(),total=0)
        }
        withModel(empty,BookAnswerService { request,_->sends++;F.answer(request) }) { model,_,_->
            configure(model);select(model);model.editQuestion("Soru");model.prepare(model.state.value.revision);runCurrent()
            assertEquals(0,model.state.value.sources!!.totalMatches);assertNull(model.state.value.sourceFailure)
            assertFalse(model.state.value.canSend);assertFalse(model.state.value.canStudy)
            model.send(model.state.value.revision);runCurrent();assertEquals(0,sends);assertEquals(0,empty.keywords)
        }
        val legacy=object: BookSourceRetriever {
            override suspend fun prepare(scope: BookTextScope,query: String): PreparedBookSources {global++;return F.sources(query=query)}
            override suspend fun listChapters(scope: BookTextScope)=listOf(C.chapter)
        }
        withModel(legacy) { model,_,_->
            select(model);model.prepare(model.state.value.revision);runCurrent()
            assertEquals(BookSourceFailure.UNSUPPORTED,model.state.value.sourceFailure);assertNull(model.state.value.sources)
            assertEquals(0,global)
        }
    }
}

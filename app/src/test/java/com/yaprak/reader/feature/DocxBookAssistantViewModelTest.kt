package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.DocxAssistantSources as D
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocxBookAssistantViewModelTest {
    private class Index : LibraryTextIndex {
        val book=MutableStateFlow(D.book)
        var resolved: suspend (LibraryTextHit)->LibraryTextHit? = { it }
        override fun observe(query: String) = error("Book scope required")
        override fun observeBook(query: String, scope: BookTextScope) = book.map {
            LibraryTextSearchResult(query,emptyList(),0,listOf(LibraryTextBookStatus(it,LibraryTextBookState.READY)),scope)
        }
        override suspend fun rebuild(onProgress: (LibraryTextIndexProgress)->Unit)=Unit
        override suspend fun resolve(hit: LibraryTextHit)=resolved(hit)
    }
    private class Retriever : BookSourceRetriever {
        val section=D.section();var proofs=0;var change=false
        override suspend fun prepare(scope: BookTextScope,query: String): PreparedBookSources {
            assertEquals(D.scope,scope);proofs++
            val refs=D.sources(section).references.take(8).map { if(change) it.copy(hit=it.hit.copy(text="Changed original source")) else it }
            return PreparedBookSources(UUID.randomUUID().toString(),scope,query,12,refs)
        }
    }
    private fun answer(request: BookQuestionRequest): BookAnswer {
        val source=request.sources.references.last()
        return BookAnswer(request.sources.requestId,BookAnswerStatus.ANSWERED,
            listOf(BookAnswerStatement("Source explanation",listOf(BookAnswerCitation(source,source.text)))),"")
    }
    private suspend fun TestScope.fixture(service: BookAnswerService=BookAnswerService { request,_->answer(request) },
        block: suspend TestScope.(BookAssistantViewModel,Retriever,Index,Any)->Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val index=Index();val retriever=Retriever();val store=ViewModelStore();val owner=Any()
        val model=BookAssistantViewModel(D.scope,"literal",retriever,index,service);store.put("docx",model)
        try { model.attachOwner(owner);model.setOwnerActive(owner,true);runCurrent();block(model,retriever,index,owner) }
        finally { store.clear();runCurrent();Dispatchers.resetMain() }
    }
    private fun TestScope.ready(model: BookAssistantViewModel) {
        model.prepare(model.state.value.revision);runCurrent()
        model.editQuestion("Explain these sources");model.editEndpoint(F.connection().endpoint.toString());model.editToken(F.connection().accessToken)
    }
    @Test fun keywordPreviewRemainsLocalUntilOneExplicitSendReprovesDocxAndOpensItsVerifiedSource()=runTest {
        val sent=mutableListOf<BookQuestionRequest>()
        fixture(BookAnswerService { request,_->sent+=request;answer(request) }) { model,retriever,_,_->
            assertEquals(D.book,model.state.value.book);assertEquals(0,retriever.proofs)
            ready(model);val preview=model.state.value.sources!!
            assertEquals(1,retriever.proofs);assertTrue(sent.isEmpty());assertTrue(preview.truncated)
            val revision=model.state.value.revision;model.send(revision);model.send(revision);runCurrent()
            assertEquals(1,sent.size);assertEquals(2,retriever.proofs);assertNotEquals(preview.requestId,sent.single().sources.requestId)
            val citation=model.state.value.answer!!.statements.single().citations.single();assertEquals("S8",citation.source.label)
            val opened=mutableListOf<LibraryTextHit>();model.open(citation,model.state.value.revision) { opened+=it };runCurrent()
            assertEquals(listOf(citation.source.hit),opened)
        }
    }
    @Test fun changedFreshDocxEvidenceRequiresANewPreviewAndNeverCallsService()=runTest {
        var calls=0
        fixture(BookAnswerService { request,_->calls++;answer(request) }) { model,retriever,_,_->
            ready(model);retriever.change=true;model.send(model.state.value.revision);runCurrent()
            assertEquals(0,calls);assertEquals(2,retriever.proofs);assertNull(model.state.value.sources)
            assertNull(model.state.value.answer);assertEquals(BookSourceFailure.SOURCE,model.state.value.sourceFailure)
        }
    }
    @Test fun pausedDocxOwnerCannotPublishLateProviderAnswerOrReuseOldActions()=runTest {
        val release=CompletableDeferred<Unit>();var calls=0
        fixture(BookAnswerService { request,_->calls++;withContext(NonCancellable) { release.await() };answer(request) }) { model,_,_,owner->
            ready(model);val old=model.state.value.revision;model.send(old);runCurrent();assertTrue(model.state.value.busy)
            model.setOwnerActive(owner,false);release.complete(Unit);runCurrent()
            model.send(old);model.prepare(old);assertEquals(1,calls);assertNull(model.state.value.answer)
            assertNull(model.state.value.sources);assertFalse(model.state.value.active);assertFalse(model.state.value.busy)
        }
    }
    @Test fun unavailableOrLateSourceResolutionCannotOpenAnObsoleteDocxCitation()=runTest {
        fixture { model,_,index,owner->
            ready(model);model.send(model.state.value.revision);runCurrent()
            val citation=model.state.value.answer!!.statements.single().citations.single()
            val release=CompletableDeferred<Unit>();index.resolved={ hit->withContext(NonCancellable) { release.await() };hit }
            val opened=mutableListOf<LibraryTextHit>();val old=model.state.value.revision
            model.open(citation,old) { opened+=it };runCurrent();assertTrue(model.state.value.busy)
            model.setOwnerActive(owner,false);release.complete(Unit);runCurrent();model.open(citation,old) { opened+=it }
            assertTrue(opened.isEmpty());assertNull(model.state.value.answer)
        }
    }
}

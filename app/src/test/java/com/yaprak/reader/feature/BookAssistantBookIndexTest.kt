package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.BookAssistantViewModel
import com.yaprak.reader.fixtures.AssistantFixtures as F
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookAssistantBookIndexTest {
    private class Index(format: BookFormat) : LibraryTextIndex {
        val book=F.book.copy(format=format,originalName="source."+format.extension,storageName="source."+format.extension)
        val status=MutableStateFlow(LibraryTextBookStatus(book,LibraryTextBookState.NOT_INDEXED))
        var builds=0
        var build: suspend ()->LibraryTextBookState={ status.value=LibraryTextBookStatus(book,LibraryTextBookState.READY);LibraryTextBookState.READY }
        override fun observe(query: String)=error("Book scope required")
        override fun observeBook(query: String,scope: BookTextScope)=status.map { LibraryTextSearchResult(query,emptyList(),0,listOf(it),scope) }
        override suspend fun rebuild(onProgress: (LibraryTextIndexProgress)->Unit)=error("Never rebuild other books")
        override suspend fun rebuildBook(scope: BookTextScope,onProgress: (LibraryTextIndexProgress)->Unit): LibraryTextBookState {
            assertEquals(F.scope,scope);builds++;return build()
        }
        override suspend fun resolve(hit: LibraryTextHit)=hit
    }
    private suspend fun TestScope.fixture(format: BookFormat=BookFormat.EPUB,
        block: suspend TestScope.(BookAssistantViewModel,Index,Any,()->Pair<Int,Int>)->Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore();val index=Index(format)
        var proofs=0;var sends=0;val owner=Any()
        val model=BookAssistantViewModel(F.scope,"Güneş",BookSourceRetriever { _,query->proofs++;F.sources(query=query) },index,
            BookAnswerService { request,_->sends++;F.answer(request) });store.put("book-index",model)
        try { model.attachOwner(owner);model.setOwnerActive(owner,true);runCurrent();block(model,index,owner) { proofs to sends } }
        finally { store.clear();runCurrent();Dispatchers.resetMain() }
    }
    @Test fun oneExplicitBookIndexActionDoesNotPrepareSourcesOrSendAndRequiresAnotherExplicitPreview()=runTest {
        fixture { model,index,_,counts->
            model.editQuestion("Keep this question");val revision=model.state.value.revision
            model.prepareIndex(revision);model.prepareIndex(revision);runCurrent()
            assertEquals(1,index.builds);assertEquals(LibraryTextBookState.READY,model.state.value.indexState)
            assertEquals("Keep this question",model.state.value.question);assertEquals(0 to 0,counts())
            assertNull(model.state.value.sources);assertFalse(model.state.value.canSend)
            model.prepare(model.state.value.revision);runCurrent();assertEquals(1 to 0,counts());assertNotNull(model.state.value.sources)
            model.prepareIndex(model.state.value.revision);runCurrent();assertEquals(1,index.builds)
        }
    }
    @Test fun pausedOwnerCannotPublishLateNoncancellableIndexResultOrReuseItsOldButton()=runTest {
        val release=CompletableDeferred<Unit>()
        try { fixture { model,index,owner,counts->
            index.build={ withContext(NonCancellable) { release.await() };index.status.value=LibraryTextBookStatus(index.book,LibraryTextBookState.READY);LibraryTextBookState.READY }
            val old=model.state.value.revision;model.prepareIndex(old);runCurrent();assertTrue(model.state.value.busy)
            model.setOwnerActive(owner,false);release.complete(Unit);runCurrent();model.prepareIndex(old)
            assertEquals(1,index.builds);assertNull(model.state.value.indexState);assertNull(model.state.value.book)
            assertNull(model.state.value.sources);assertFalse(model.state.value.busy);assertEquals(0 to 0,counts())
        } } finally { release.complete(Unit) }
    }
    @Test fun honestCacheFailuresAndTypedBusyRecoveryErrorsNeverBecomeSourcesOrAutomaticRetries()=runTest {
        val states=mapOf(LibraryTextBookState.NO_TEXT to BookSourceFailure.UNSUPPORTED,
            LibraryTextBookState.UNSUPPORTED to BookSourceFailure.UNSUPPORTED,LibraryTextBookState.LIMIT to BookSourceFailure.LIMIT,
            LibraryTextBookState.FAILED to BookSourceFailure.SOURCE)
        for((result,expected) in states) fixture(BookFormat.DOCX) { model,index,_,counts->
            index.build={ index.status.value=LibraryTextBookStatus(index.book,result);result }
            model.prepareIndex(model.state.value.revision);runCurrent()
            assertEquals(result,model.state.value.indexState);assertEquals(expected,model.state.value.sourceFailure)
            assertEquals(1,index.builds);assertNull(model.state.value.sources);assertEquals(0 to 0,counts())
        }
        for(reason in LibraryTextIndexFailure.entries) fixture { model,index,_,counts->
            index.build={ throw LibraryTextIndexException(reason) };model.prepareIndex(model.state.value.revision);runCurrent()
            assertEquals(BookSourceFailure.valueOf(reason.name),model.state.value.sourceFailure)
            assertNull(model.state.value.failure);assertEquals(1,index.builds);assertEquals(0 to 0,counts())
        }
    }
}

package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.BookAssistantViewModel
import com.yaprak.reader.fixtures.AssistantFixtures as F
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookAssistantIndexStateTest {
    private class Index : LibraryTextIndex {
        val status=MutableStateFlow(LibraryTextBookStatus(F.book,LibraryTextBookState.NOT_INDEXED))
        override fun observe(query: String)=error("Book scope required")
        override fun observeBook(query: String,scope: BookTextScope)=status.map { LibraryTextSearchResult(query,emptyList(),0,listOf(it),scope) }
        override suspend fun rebuild(onProgress: (LibraryTextIndexProgress)->Unit)=error("Opening assistant must not rebuild")
        override suspend fun resolve(hit: LibraryTextHit)=hit
    }
    @Test fun actualObservedIndexStateRefreshesOnReturnWithoutExtractionRebuildOrSendAndIsClearedWhenPaused()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore();val index=Index();var proofs=0;var sends=0
        val model=BookAssistantViewModel(F.scope,"",BookSourceRetriever { _,_->proofs++;F.sources() },index,
            BookAnswerService { request,_->sends++;F.answer(request) });val owner=Any();store.put("index",model)
        try {
            model.attachOwner(owner);model.setOwnerActive(owner,true);runCurrent()
            assertEquals(LibraryTextBookState.NOT_INDEXED,model.state.value.indexState);assertEquals(F.book,model.state.value.book)
            model.editQuestion("Keep this question while preparing local index")
            model.setOwnerActive(owner,false);assertNull(model.state.value.indexState);assertNull(model.state.value.book)
            index.status.value=LibraryTextBookStatus(F.book,LibraryTextBookState.READY);runCurrent();assertNull(model.state.value.indexState)
            model.setOwnerActive(owner,true);runCurrent()
            assertEquals(LibraryTextBookState.READY,model.state.value.indexState)
            assertEquals("Keep this question while preparing local index",model.state.value.question)
            assertFalse(model.state.value.canSend);assertNull(model.state.value.sources);assertEquals(0,proofs);assertEquals(0,sends)
        } finally { store.clear();runCurrent();Dispatchers.resetMain() }
    }
    @Test fun foreignOrReplacedBookCannotExposeAnIndexPreparationStateForTheOriginalScope()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore();val index=Index()
        val model=BookAssistantViewModel(F.scope,"",BookSourceRetriever { _,_->error("No automatic extraction") },index,
            BookAnswerService { _,_->error("No automatic send") });val owner=Any();store.put("index",model)
        try {
            model.attachOwner(owner);model.setOwnerActive(owner,true);runCurrent()
            assertEquals(LibraryTextBookState.NOT_INDEXED,model.state.value.indexState)
            index.status.value=LibraryTextBookStatus(F.book.copy(sha256="b".repeat(64)),LibraryTextBookState.NOT_INDEXED);runCurrent()
            assertNull(model.state.value.indexState);assertNull(model.state.value.book);assertEquals(BookSourceFailure.SOURCE,model.state.value.sourceFailure)
            model.setOwnerActive(owner,false);index.status.value=LibraryTextBookStatus(F.book.copy(id="foreign"),LibraryTextBookState.READY)
            model.setOwnerActive(owner,true);runCurrent();assertNull(model.state.value.indexState);assertNull(model.state.value.book)
            assertFalse(model.state.value.canPrepare);assertFalse(model.state.value.canSend)
        } finally { store.clear();runCurrent();Dispatchers.resetMain() }
    }
}

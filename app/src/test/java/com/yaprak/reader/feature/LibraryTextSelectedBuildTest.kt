package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTextSelectedBuildTest {
    private class Index: LibraryTextIndex {
        var all=0;val books=mutableListOf<BookTextScope>()
        var action: suspend ((LibraryTextIndexProgress)->Unit)->LibraryTextBookState={ LibraryTextBookState.READY }
        override fun observe(query: String)=flowOf(LibraryTextSearchResult(query,emptyList(),0,listOf(LibraryTextBookStatus(F.book,LibraryTextBookState.READY))))
        override fun observeBook(query: String,scope: BookTextScope)=observe(query).map { it.copy(scope=scope) }
        override suspend fun rebuild(onProgress: (LibraryTextIndexProgress)->Unit) { all++ }
        override suspend fun rebuildBook(scope: BookTextScope,onProgress: (LibraryTextIndexProgress)->Unit): LibraryTextBookState { books+=scope;return action(onProgress) }
        override suspend fun resolve(hit: LibraryTextHit)=hit
    }
    private suspend fun TestScope.fixture(source: LibraryTextIndex?=null,block: suspend TestScope.(LibraryTextSearchViewModel,Index)->Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore();val index=Index()
        val model=LibraryTextSearchViewModel(source ?: index,SavedStateHandle());store.put("selected-build",model)
        try { model.setActive(true);runCurrent();block(model,index) } finally { store.clear();runCurrent();Dispatchers.resetMain() }
    }
    @Test fun explicitScopeRebuildsOnlyThatOriginalWhileAllBooksSelectionRetainsGlobalBehavior()=runTest {
        fixture { model,index->
            assertEquals(0,index.all);assertTrue(index.books.isEmpty())
            model.changeScope(F.scope);runCurrent();val revision=model.state.value.revision
            model.rebuild(model.state.value.build,F.scope,revision);model.rebuild();runCurrent()
            assertEquals(listOf(F.scope),index.books);assertEquals(0,index.all)
            assertEquals(LibraryTextBookState.READY,(model.state.value.build as LibraryTextBuildPhase.Finished).bookState)
            model.changeScope(null);runCurrent();model.rebuild();runCurrent()
            assertEquals(1,index.all);assertEquals(1,index.books.size)
            assertNull((model.state.value.build as LibraryTextBuildPhase.Finished).bookState)
        }
    }
    @Test fun idleScopeAbaAndOldRevisionCannotTurnAnOldButtonIntoANewGlobalOrBookJob()=runTest {
        fixture { model,index->
            val idle=model.state.value.build;val old=model.state.value.revision
            model.changeScope(F.scope);runCurrent();model.rebuild(idle,null,old)
            model.changeScope(null);runCurrent();model.rebuild(idle,null,old);runCurrent()
            assertEquals(0,index.all);assertTrue(index.books.isEmpty())
            model.rebuild(idle,null,model.state.value.revision);runCurrent();assertEquals(1,index.all)
        }
    }
    @Test fun everyScopedOutcomeAndTypedFailureIsHonestAndNeverFallsBackToAllBooks()=runTest {
        for(result in LibraryTextBookState.entries) fixture { model,index->
            model.changeScope(F.scope);runCurrent();index.action={ result };model.rebuild();runCurrent()
            if(result==LibraryTextBookState.NOT_INDEXED) assertEquals(LibraryTextIndexFailure.SOURCE,(model.state.value.build as LibraryTextBuildPhase.Failed).reason)
            else assertEquals(result,(model.state.value.build as LibraryTextBuildPhase.Finished).bookState)
            assertEquals(0,index.all);assertEquals(listOf(F.scope),index.books)
        }
        for(reason in LibraryTextIndexFailure.entries) fixture { model,index->
            model.changeScope(F.scope);runCurrent();index.action={ throw LibraryTextIndexException(reason) };model.rebuild();runCurrent()
            assertEquals(reason,(model.state.value.build as LibraryTextBuildPhase.Failed).reason);assertEquals(0,index.all)
        }
    }
    @Test fun cancellationAndPauseKeepCapturedScopeIgnoreLateProgressAndDoNotPublishLateReady()=runTest {
        val release=CompletableDeferred<Unit>()
        try { fixture { model,index->
            var progress: ((LibraryTextIndexProgress)->Unit)?=null
            index.action={ callback->progress=callback;withContext(NonCancellable) { release.await() };LibraryTextBookState.READY }
            model.changeScope(F.scope);runCurrent();model.rebuild();runCurrent()
            val running=model.state.value.build as LibraryTextBuildPhase.Running
            model.changeScope(null);assertEquals(F.scope,model.state.value.scope)
            model.cancelBuild(running.id);runCurrent();progress!!(LibraryTextIndexProgress(1,1,"late"))
            assertNull((model.state.value.build as LibraryTextBuildPhase.Running).progress)
            model.setActive(false);release.complete(Unit);runCurrent()
            val finished=model.state.value.build as LibraryTextBuildPhase.Finished
            assertTrue(finished.cancelled);assertNull(finished.bookState);assertEquals(0,index.all)
            assertEquals(listOf(F.scope),index.books)
        } } finally { release.complete(Unit) }
    }
    @Test fun anOlderIndexImplementationFailsClosedInsteadOfRebuildingTheWholeLibrary()=runTest {
        var all=0
        val legacy=object: LibraryTextIndex {
            override fun observe(query: String)=flowOf(LibraryTextSearchResult(query,emptyList(),0,listOf(LibraryTextBookStatus(F.book,LibraryTextBookState.READY))))
            override fun observeBook(query: String,scope: BookTextScope)=observe(query).map { it.copy(scope=scope) }
            override suspend fun rebuild(onProgress: (LibraryTextIndexProgress)->Unit) { all++ }
            override suspend fun resolve(hit: LibraryTextHit)=hit
        }
        fixture(legacy) { model,_->
            model.changeScope(F.scope);runCurrent();model.rebuild();runCurrent()
            assertEquals(LibraryTextIndexFailure.SOURCE,(model.state.value.build as LibraryTextBuildPhase.Failed).reason)
            assertEquals(0,all)
        }
    }
}

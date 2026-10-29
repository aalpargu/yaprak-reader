package com.yaprak.reader.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.assistant.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.fixtures.SelectedAssistantFixtures as F
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalSelectedTextSourcesTest {
    @Test fun actualRoomPdfSourceKeepsCurrentMetadataAndMutexAndRejectsChangesAfterNativeProof() = runBlocking {
        val fixture = com.yaprak.reader.fixtures.PdfSelectedAssistantFixtures
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java).build()
        try {
            database.books().insert(fixture.book.toEntity()); val library = RoomLibraryRepository(database.books()); val mutex = Mutex()
            var change = false; var reads = 0; var guards = 0
            val provider = LocalSelectedTextSources(library, SelectedTextOriginalSource { book, request ->
                reads++; assertTrue(mutex.isLocked); assertEquals(BookFormat.PDF, book.format)
                if (change) assertTrue(library.updateMetadata(book.id, book.sha256, "Changed PDF title", book.authors))
                fixture.sources(request).references
            }, mutex) { guards++; assertTrue(mutex.isLocked) }
            val request = fixture.request(); assertEquals(fixture.book, provider.prepare(request).book)
            change = true; bad(BookAnswerFailure.SOURCE) { provider.prepare(request) }
            assertEquals(2, reads); assertEquals(4, guards); assertFalse(mutex.isLocked)
            assertTrue(database.reading().observed(fixture.book.id, fixture.book.sha256).isEmpty())
            assertNull(database.reading().resume(fixture.book.id, fixture.book.sha256))
        } finally { database.close() }
    }
    private class Library : LibraryRepository {
        var book: Book? = F.book
        var reads = 0
        override fun observeBooks() = flowOf(listOfNotNull(book))
        override suspend fun findById(id: String): Book? { reads++; return book?.takeIf { it.id == id } }
        override suspend fun findByHash(sha256: String) = book?.takeIf { it.sha256 == sha256 }
        override suspend fun storedFileNames() = setOfNotNull(book?.storageName)
        override suspend fun add(book: Book) = error("No writes allowed")
        override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?) = error("No writes allowed")
    }
    @Test fun invalidActualPdfPageCountNeverCallsOriginalSource() = runBlocking {
        val fixture = com.yaprak.reader.fixtures.PdfSelectedAssistantFixtures
        val library = Library(); var originals = 0; val mutex = Mutex()
        val provider = LocalSelectedTextSources(library, SelectedTextOriginalSource { _, _ -> originals++; error("Invalid page") }, mutex) {}
        for (pageCount in listOf(null, 2)) {
            library.book = fixture.book.copy(pageCount = pageCount)
            bad(BookAnswerFailure.SOURCE) { provider.prepare(fixture.request()) }
        }
        assertEquals(0, originals); assertFalse(mutex.isLocked)
    }
    @Test fun actualBoundaryHoldsSharedMutexAndChecksRecoveryBeforeAndAfterProof() = runBlocking {
        val library = Library(); val mutex = Mutex(); var guards = 0; var reads = 0
        val provider = LocalSelectedTextSources(library, SelectedTextOriginalSource { book, request ->
            assertTrue(mutex.isLocked); assertEquals(F.book, book); assertEquals(request.bookId, book.id)
            reads++; F.sources(request).references
        }, mutex) { assertTrue(mutex.isLocked); guards++ }
        val result = provider.prepare(F.request())
        assertEquals(F.book, result.book); assertEquals(F.text, result.references.single().text)
        assertEquals(2, guards); assertEquals(2, library.reads); assertEquals(1, reads); assertFalse(mutex.isLocked)
    }
    @Test fun invalidRequestNeverTouchesLibraryOrSource() = runBlocking {
        val library = Library(); var originals = 0
        val provider = LocalSelectedTextSources(library, SelectedTextOriginalSource { _, request -> originals++; F.sources(request).references }, Mutex()) {}
        bad(BookAnswerFailure.SOURCE) { provider.prepare(F.request().copy(selectionId = "old")) }
        assertEquals(0, originals); assertEquals(0, library.reads)
    }
    @Test fun missingWrongHashAndPdfNeverReadOriginal() = runBlocking {
        val library = Library(); var originals = 0
        val provider = LocalSelectedTextSources(library, SelectedTextOriginalSource { _, request -> originals++; F.sources(request).references }, Mutex()) {}
        for (book in listOf(null, F.book.copy(sha256 = "b".repeat(64)), F.book.copy(format = BookFormat.PDF))) {
            library.book = book; bad(BookAnswerFailure.SOURCE) { provider.prepare(F.request()) }
        }
        assertEquals(0, originals)
    }
    @Test fun recoveryGuardIsRecheckedAfterQueuedLock() = runBlocking {
        val mutex = Mutex(locked = true); var ready = true; var originals = 0
        val provider = LocalSelectedTextSources(Library(), SelectedTextOriginalSource { _, request -> originals++; F.sources(request).references }, mutex) {
            if (!ready) failAnswer(BookAnswerFailure.SOURCE)
        }
        val pending = async(Dispatchers.IO) { runCatching { provider.prepare(F.request()) }.exceptionOrNull() }
        ready = false; mutex.unlock()
        assertEquals(BookAnswerFailure.SOURCE, (withTimeout(5000) { pending.await() } as BookAnswerException).reason)
        assertEquals(0, originals)
    }
    @Test fun metadataChangeDuringSourceProofRejectsWholeSnapshot() = runBlocking {
        val library = Library()
        val provider = LocalSelectedTextSources(library, SelectedTextOriginalSource { _, request ->
            library.book = F.book.copy(title = "Changed")
            F.sources(request).references
        }, Mutex()) {}
        bad(BookAnswerFailure.SOURCE) { provider.prepare(F.request()) }
    }
    @Test fun unrelatedLastReadChangePreservesSameEvidence() = runBlocking {
        val library = Library()
        val provider = LocalSelectedTextSources(library, SelectedTextOriginalSource { _, request ->
            library.book = F.book.copy(lastReadAt = 5)
            F.sources(request).references
        }, Mutex()) {}
        val request = F.request(); val fresh = provider.prepare(request)
        assertEquals(5L, fresh.book.lastReadAt)
        assertTrue(fresh.sameEvidence(F.sources(request)))
    }
    @Test fun originalCallerCancellationKeepsItsExceptionAndReleasesOwnership() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var cleaned = false; val mutex = Mutex()
        val provider = LocalSelectedTextSources(Library(), SelectedTextOriginalSource { _, _ ->
            try { entered.complete(Unit); awaitCancellation() } finally { cleaned = true }
        }, mutex) {}
        val pending = async(Dispatchers.IO) { provider.prepare(F.request()) }
        withTimeout(5000) { entered.await() }; pending.cancelAndJoin()
        assertTrue(pending.isCancelled); assertTrue(cleaned); assertFalse(mutex.isLocked)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
    }
    @Test fun closedRoomIsSourceFailureAndNeverReopens() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java).build()
        database.books().insert(F.book.toEntity())
        val library = RoomLibraryRepository(database.books())
        database.close()
        val provider = LocalSelectedTextSources(library, SelectedTextOriginalSource { _, request -> F.sources(request).references }, Mutex()) {}
        bad(BookAnswerFailure.SOURCE) { provider.prepare(F.request()) }
        assertFalse(database.isOpen)
    }
    private suspend fun bad(reason: BookAnswerFailure, block: suspend () -> Unit) {
        assertEquals(reason, (runCatching { block() }.exceptionOrNull() as? BookAnswerException)?.reason)
    }
}

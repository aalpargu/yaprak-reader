package com.yaprak.reader.feature.reader

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.reader.ReadingStateRepository
import com.yaprak.reader.reader.ReaderLoader
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderRestorationSourceTest {
    private class Session(override val book: Book = Book("book", "Original", null, BookFormat.EPUB,
        "book.epub", "book.epub", 1, "a".repeat(64), 0)) : ReaderSession {
        override val resume: ReadingLocation? = null
        var closes = 0
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit,
            onError: () -> Unit): ReaderSurface = error("No renderer is created by source reproof")
        override fun close() { closes++ }
    }
    private class Storage : ReadingStateRepository {
        var writes = 0
        override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
        override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) { writes++ }
    }
    private fun checkModel(block: suspend TestScope.(ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try { block(store) } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun newVerifiedPublicationIsClosedAndRetainedReaderAndWritesAreUntouched() = checkModel { store ->
        val retained = Session()
        val validation = Session(retained.book.copy(lastReadAt = 100))
        var opens = 0
        val storage = Storage()
        val model = ReaderViewModel("book", ReaderLoader { if (opens++ == 0) retained else validation }, storage)
        store.put("reader", model); runCurrent()
        assertTrue(model.verifyRestorationSource(retained.book))
        assertSame(retained, (model.state.value as ReaderState.Ready).session)
        assertEquals(0, retained.closes)
        assertEquals(1, validation.closes)
        assertEquals(0, storage.writes)
    }

    @Test fun mutableMetadataDoesNotInvalidateTheSameVerifiedOriginal() = checkModel { store ->
        val retained = Session()
        val coverId = "00000000-0000-4000-8000-000000000001"
        val validation = Session(retained.book.copy(title = "Updated title", authors = "Updated author",
            organization = retained.book.organization.copy(favorite = true, tags = listOf("Changed metadata")),
            customCover = com.yaprak.reader.domain.library.BookCover(coverId, "$coverId.jpg", "image/jpeg",
                1, "b".repeat(64), 1, 1), lastReadAt = 100))
        var opens = 0
        val model = ReaderViewModel("book", ReaderLoader { if (opens++ == 0) retained else validation }, Storage())
        store.put("reader", model); runCurrent()
        assertTrue(model.verifyRestorationSource(retained.book))
        assertSame(retained, (model.state.value as ReaderState.Ready).session)
        assertEquals(0, retained.closes)
        assertEquals(1, validation.closes)
    }

    @Test fun changedSourceIdentityFormatStorageOrByteCountCannotReuseTheSavedForm() = checkModel { store ->
        val retained = Session()
        val changes = listOf(retained.book.copy(id = "other"), retained.book.copy(format = BookFormat.PDF),
            retained.book.copy(storageName = "other.epub"), retained.book.copy(byteSize = 2))
        var opened = false
        var validation = Session(changes.first())
        val model = ReaderViewModel("book", ReaderLoader { if (!opened) { opened = true; retained } else validation }, Storage())
        store.put("reader", model); runCurrent()
        changes.forEach { changed ->
            validation = Session(changed)
            assertFalse(model.verifyRestorationSource(retained.book))
            assertEquals(1, validation.closes)
        }
        assertEquals(0, retained.closes)
    }

    @Test fun sharedTestLoaderNeverClosesTheLiveSessionDuringReproof() = checkModel { store ->
        val retained = Session()
        val model = ReaderViewModel("book", ReaderLoader { retained }, Storage())
        store.put("reader", model); runCurrent()
        assertTrue(model.verifyRestorationSource(retained.book))
        assertEquals(0, retained.closes)
    }

    @Test fun changedBookRejectsTheSnapshotAndClosesOnlyTheValidationSession() = checkModel { store ->
        val retained = Session()
        val changed = Session(retained.book.copy(sha256 = "b".repeat(64)))
        var opens = 0
        val model = ReaderViewModel("book", ReaderLoader { if (opens++ == 0) retained else changed }, Storage())
        store.put("reader", model); runCurrent()
        assertFalse(model.verifyRestorationSource(retained.book))
        assertEquals(0, retained.closes)
        assertEquals(1, changed.closes)
    }

    @Test fun sourceFailurePropagatesWithoutPublishingASuccessOrClosingTheReader() = checkModel { store ->
        val retained = Session()
        var opens = 0
        val failure = java.io.IOException("Original bytes changed")
        val model = ReaderViewModel("book", ReaderLoader { if (opens++ == 0) retained else throw failure }, Storage())
        store.put("reader", model); runCurrent()
        try { model.verifyRestorationSource(retained.book); fail("Source failure must remain retryable") }
        catch (actual: java.io.IOException) { assertSame(failure, actual) }
        assertEquals(0, retained.closes)
        assertSame(retained, (model.state.value as ReaderState.Ready).session)
    }

    @Test fun cancellationAfterLoaderReturnClosesValidationAndNeverBecomesAFailedSuccess() = checkModel { store ->
        val retained = Session()
        val validation = Session()
        var opens = 0
        val model = ReaderViewModel("book", ReaderLoader {
            if (opens++ == 0) retained else { currentCoroutineContext().cancel(); validation }
        }, Storage())
        store.put("reader", model); runCurrent()
        val result = async { model.verifyRestorationSource(retained.book) }
        runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(1, validation.closes)
        assertEquals(0, retained.closes)
    }

    @Test fun clearingTheRetainedOwnerWhileProofWaitsRejectsItsLateResult() = checkModel { store ->
        val retained = Session()
        val validation = Session()
        val gate = CompletableDeferred<Unit>()
        var opens = 0
        val model = ReaderViewModel("book", ReaderLoader {
            if (opens++ == 0) retained else { gate.await(); validation }
        }, Storage())
        store.put("reader", model); runCurrent()
        val result = async { model.verifyRestorationSource(retained.book) }
        runCurrent(); store.clear(); gate.complete(Unit); runCurrent()
        assertFalse(result.await())
        assertEquals(1, validation.closes)
        assertEquals(1, retained.closes)
    }
}

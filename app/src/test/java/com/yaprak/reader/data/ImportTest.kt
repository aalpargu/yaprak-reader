package com.yaprak.reader.data

import com.yaprak.reader.data.importing.DefaultBookImporter
import com.yaprak.reader.data.importing.DocumentProvider
import com.yaprak.reader.data.importing.SourceDocument
import com.yaprak.reader.data.storage.LocalBookFiles
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.domain.library.ImportResult
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.formats.BookMetadata
import com.yaprak.reader.formats.BookMetadataReader
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ImportTest {
    @get:Rule val temp = TemporaryFolder()
    private val pdf = "%PDF-fixture".toByteArray()

    @Test fun `copies bytes stores metadata and deduplicates renamed files`() = runTest {
        val folder = temp.newFolder()
        val repository = MemoryLibrary()
        val importer = DefaultBookImporter(
            DocumentProvider { SourceDocument(it) { pdf.inputStream() } },
            LocalBookFiles(folder), BookMetadataReader { _, _ -> BookMetadata(pageCount = 7) },
            repository, StandardTestDispatcher(testScheduler),
        )
        assertEquals(ImportResult.Added, importer.import("First.pdf"))
        assertEquals(ImportResult.Duplicate, importer.import("Renamed.pdf"))
        val book = repository.books.value.single()
        assertEquals("First", book.title)
        assertEquals(7, book.pageCount)
        assertEquals(BookFormat.PDF, book.format)
        assertEquals(64, book.sha256.length)
        assertArrayEquals(pdf, File(folder, book.storageName).readBytes())
        assertEquals(1, folder.listFiles()!!.size)
    }

    @Test fun `invalid and protected files do not enter library or leave copies`() = runTest {
        for (reason in listOf(ImportFailure.INVALID, ImportFailure.PROTECTED)) {
            val folder = temp.newFolder()
            val repository = MemoryLibrary()
            val importer = DefaultBookImporter(
                DocumentProvider { SourceDocument("bad.pdf") { pdf.inputStream() } },
                LocalBookFiles(folder), BookMetadataReader { _, _ -> throw BookImportException(reason) },
                repository, StandardTestDispatcher(testScheduler),
            )
            assertEquals(ImportResult.Failed(reason), importer.import("source"))
            assertTrue(repository.books.value.isEmpty())
            assertTrue(folder.listFiles()!!.isEmpty())
        }
    }

    @Test fun `empty unsupported and oversized input leaves no partial file`() = runTest {
        val samples = listOf(
            byteArrayOf() to ImportFailure.INVALID,
            "Not a book".toByteArray() to ImportFailure.UNSUPPORTED,
            (pdf + ByteArray(50)) to ImportFailure.TOO_LARGE,
        )
        for ((bytes, expected) in samples) {
            val folder = temp.newFolder()
            val importer = DefaultBookImporter(
                DocumentProvider { SourceDocument("wrong.epub") { bytes.inputStream() } },
                LocalBookFiles(folder, maxBytes = 20), BookMetadataReader { _, _ -> error("Must not parse") },
                MemoryLibrary(), StandardTestDispatcher(testScheduler),
            )
            assertEquals(ImportResult.Failed(expected), importer.import("source"))
            assertTrue(folder.listFiles()!!.isEmpty())
        }
    }

    @Test fun `database write failure compensates file commit`() = runTest {
        val folder = temp.newFolder()
        val importer = DefaultBookImporter(
            DocumentProvider { SourceDocument("book.pdf") { pdf.inputStream() } },
            LocalBookFiles(folder), BookMetadataReader { _, _ -> BookMetadata() },
            MemoryLibrary(onAdd = { throw IOException("disk full") }), StandardTestDispatcher(testScheduler),
        )
        assertEquals(ImportResult.Failed(ImportFailure.IO), importer.import("source"))
        assertTrue(folder.listFiles()!!.isEmpty())
    }

    @Test fun `revoked source grant is a recoverable access error`() = runTest {
        val folder = temp.newFolder()
        val importer = DefaultBookImporter(
            DocumentProvider { throw SecurityException("revoked") },
            LocalBookFiles(folder), BookMetadataReader { _, _ -> error("Must not parse") },
            MemoryLibrary(), StandardTestDispatcher(testScheduler),
        )
        assertEquals(ImportResult.Failed(ImportFailure.ACCESS), importer.import("source"))
        assertTrue(folder.listFiles()!!.isEmpty())
    }

    @Test fun `process-death leftovers removed without touching saved books`() = runTest {
        val folder = temp.newFolder()
        File(folder, "old.part").writeText("partial")
        File(folder, "orphan.pdf").writeText("uncommitted")
        File(folder, "saved.pdf").writeText("saved")
        LocalBookFiles(folder).recover(setOf("saved.pdf"))
        assertEquals(listOf("saved.pdf"), folder.listFiles()!!.map { it.name })
        assertEquals("saved", File(folder, "saved.pdf").readText())
    }

    @Test fun `cancellation during parsing propagates and cleans stage`() = runTest {
        val folder = temp.newFolder()
        val entered = CompletableDeferred<Unit>()
        val importer = DefaultBookImporter(
            DocumentProvider { SourceDocument("book.pdf") { pdf.inputStream() } },
            LocalBookFiles(folder), BookMetadataReader { _, _ ->
                entered.complete(Unit)
                CompletableDeferred<BookMetadata>().await()
            }, MemoryLibrary(), StandardTestDispatcher(testScheduler),
        )
        val job = launch { importer.import("source") }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(folder.listFiles()!!.isEmpty())
    }

    @Test fun `cancellation during commit cannot remove saved file`() = runTest {
        val folder = temp.newFolder()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val repository = MemoryLibrary(onAdd = { entered.complete(Unit); proceed.await() })
        val importer = DefaultBookImporter(
            DocumentProvider { SourceDocument("book.pdf") { pdf.inputStream() } },
            LocalBookFiles(folder), BookMetadataReader { _, _ -> BookMetadata() },
            repository, StandardTestDispatcher(testScheduler),
        )
        val job = launch { importer.import("source") }
        entered.await()
        job.cancel()
        proceed.complete(Unit)
        job.join()
        val book = repository.books.value.single()
        assertTrue(File(folder, book.storageName).isFile)
    }

    @Test fun `concurrent requests serialize and retain one complete copy`() = runTest {
        val folder = temp.newFolder()
        val repository = MemoryLibrary()
        val importer = DefaultBookImporter(
            DocumentProvider { SourceDocument("book.pdf") { pdf.inputStream() } },
            LocalBookFiles(folder), BookMetadataReader { _, _ -> BookMetadata() },
            repository, StandardTestDispatcher(testScheduler),
        )
        val first = async { importer.import("one") }
        val second = async { importer.import("two") }
        assertEquals(setOf(ImportResult.Added, ImportResult.Duplicate), setOf(first.await(), second.await()))
        assertEquals(1, repository.books.value.size)
        assertEquals(1, folder.listFiles()!!.size)
    }
}

internal class MemoryLibrary(private val onAdd: suspend () -> Unit = {}) : LibraryRepository {
    override suspend fun findById(id: String) = books.value.find { it.id == id }
    val books = MutableStateFlow<List<Book>>(emptyList())
    override fun observeBooks() = books
    override suspend fun findByHash(sha256: String) = books.value.find { it.sha256 == sha256 }
    override suspend fun storedFileNames() = books.value.map { it.storageName }.toSet()
    override suspend fun add(book: Book) { onAdd(); books.value += book }
    override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?): Boolean {
        if (books.value.none { it.id == id && it.sha256 == sha256 }) return false
        books.value = books.value.map { book ->
            if (book.id == id && book.sha256 == sha256) book.copy(title = title, authors = authors) else book
        }
        return true
    }
}

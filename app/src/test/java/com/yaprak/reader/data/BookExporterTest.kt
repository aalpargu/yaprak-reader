package com.yaprak.reader.data

import com.yaprak.reader.data.exporting.DefaultBookExporter
import com.yaprak.reader.data.exporting.ExportDocumentProvider
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.ExportFailure
import com.yaprak.reader.domain.library.ExportResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookExporterTest {
    @get:Rule val temp = TemporaryFolder()
    private val destination = "content://documents/book"

    @Test fun `exports exact original bytes in chunks without changing library`() = runTest {
        val bytes = ByteArray(140_000) { (it % 251).toByte() }
        val (directory, book) = savedBook(bytes)
        val library = MemoryLibrary().apply { add(book) }
        val output = ByteArrayOutputStream()
        val documents = FakeDocuments(output)
        val exporter = exporter(directory, library, documents)

        assertEquals(ExportResult.Exported, exporter.export(book.id, destination))
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(1, documents.opens)
        assertEquals(0, documents.deletes)
        assertEquals(listOf(book), library.books.value)
    }

    @Test fun `missing book and changed copy never open output`() = runTest {
        val (directory, book) = savedBook("original".toByteArray())
        val library = MemoryLibrary().apply { add(book) }
        val documents = FakeDocuments()
        val exporter = exporter(directory, library, documents)

        assertEquals(
            ExportResult.Failed(ExportFailure.MISSING_BOOK),
            exporter.export("not-in-library", destination),
        )
        File(directory, book.storageName).writeText("modified")
        assertEquals(
            ExportResult.Failed(ExportFailure.CHANGED_SOURCE),
            exporter.export(book.id, destination),
        )
        File(directory, book.storageName).delete()
        assertEquals(
            ExportResult.Failed(ExportFailure.CHANGED_SOURCE),
            exporter.export(book.id, destination),
        )
        assertEquals(0, documents.opens)
        assertEquals(3, documents.deletes)
        assertEquals(listOf(book), library.books.value)
    }

    @Test fun `changed source during copy is rejected and created document is deleted`() = runTest {
        val (directory, book) = savedBook("before".toByteArray())
        val library = MemoryLibrary().apply { add(book) }
        val source = File(directory, book.storageName)
        val documents = FakeDocuments(onOpen = {
            source.writeText("during") // Same byte count; the first verification has already passed.
        })

        assertEquals(
            ExportResult.Failed(ExportFailure.CHANGED_SOURCE),
            exporter(directory, library, documents).export(book.id, destination),
        )
        assertEquals(1, documents.opens)
        assertEquals(1, documents.deletes)
        assertEquals(listOf(book), library.books.value)
    }

    @Test fun `write failure removes partial document and reports cleanup failure honestly`() = runTest {
        val (directory, book) = savedBook("source".toByteArray())
        val library = MemoryLibrary().apply { add(book) }
        val partial = object : OutputStream() {
            override fun write(value: Int) = throw IOException("full")
            override fun write(bytes: ByteArray, off: Int, len: Int) = throw IOException("full")
        }
        val documents = FakeDocuments(partial)
        assertEquals(
            ExportResult.Failed(ExportFailure.IO),
            exporter(directory, library, documents).export(book.id, destination),
        )
        assertEquals(1, documents.deletes)

        documents.deleteSucceeds = false
        assertEquals(
            ExportResult.Failed(ExportFailure.IO, partialFileMayRemain = true),
            exporter(directory, library, documents).export(book.id, destination),
        )
        assertEquals(listOf(book), library.books.value)
    }

    @Test fun `cancellation is rethrown after best effort document deletion`() = runTest {
        val (directory, book) = savedBook("source".toByteArray())
        val library = MemoryLibrary().apply { add(book) }
        val cancelling = object : OutputStream() {
            override fun write(value: Int) = throw CancellationException("screen closed")
            override fun write(bytes: ByteArray, off: Int, len: Int) =
                throw CancellationException("screen closed")
        }
        val documents = FakeDocuments(cancelling)

        val error = runCatching { exporter(directory, library, documents).export(book.id, destination) }
            .exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, documents.deletes)
        assertEquals(listOf(book), library.books.value)
    }

    @Test fun `unavailable destination is access failure rather than changed source`() = runTest {
        val (directory, book) = savedBook("source".toByteArray())
        val library = MemoryLibrary().apply { add(book) }
        val documents = FakeDocuments(onOpen = { throw FileNotFoundException("provider unavailable") })

        assertEquals(
            ExportResult.Failed(ExportFailure.ACCESS),
            exporter(directory, library, documents).export(book.id, destination),
        )
        assertEquals(1, documents.opens)
        assertEquals(1, documents.deletes)
        assertEquals(listOf(book), library.books.value)
    }

    @Test fun `non content destination is refused without opening or deleting`() = runTest {
        val (directory, book) = savedBook("source".toByteArray())
        val library = MemoryLibrary().apply { add(book) }
        val documents = FakeDocuments()

        assertEquals(
            ExportResult.Failed(ExportFailure.ACCESS),
            exporter(directory, library, documents).export(book.id, "file:///tmp/export.epub"),
        )
        assertEquals(0, documents.opens)
        assertEquals(0, documents.deletes)
    }

    @Test fun `destination validation never swallows cancellation`() = runTest {
        val (directory, book) = savedBook("source".toByteArray())
        val library = MemoryLibrary().apply { add(book) }
        val documents = FakeDocuments(onAccept = { throw CancellationException("cancelled") })

        val error = runCatching { exporter(directory, library, documents).export(book.id, destination) }
            .exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(0, documents.opens)
    }

    @Test fun `cancellation before IO dispatch still removes created document`() = runTest {
        val (directory, book) = savedBook("source".toByteArray())
        val library = MemoryLibrary().apply { add(book) }
        val documents = FakeDocuments()
        val dispatcher = StandardTestDispatcher(testScheduler)

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            exporter(directory, library, documents, dispatcher).export(book.id, destination)
        }
        job.cancelAndJoin()
        assertEquals(0, documents.opens)
        assertEquals(1, documents.deletes)
    }

    private fun savedBook(bytes: ByteArray): Pair<File, Book> {
        val directory = temp.newFolder()
        val file = File(directory, "saved.epub").apply { writeBytes(bytes) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return directory to Book(
            id = "book-id",
            title = "Book",
            authors = null,
            format = BookFormat.EPUB,
            originalName = "original.epub",
            storageName = file.name,
            byteSize = bytes.size.toLong(),
            sha256 = hash,
            importedAt = 0L,
        )
    }

    private fun exporter(
        directory: File,
        library: MemoryLibrary,
        documents: FakeDocuments,
        io: CoroutineDispatcher = Dispatchers.Unconfined,
    ) =
        DefaultBookExporter(
            library,
            VerifiedBookFile(directory),
            documents,
            io,
        )

    private class FakeDocuments(
        private val output: OutputStream = ByteArrayOutputStream(),
        private val onOpen: () -> Unit = {},
        private val onAccept: () -> Unit = {},
    ) : ExportDocumentProvider {
        var opens = 0
        var deletes = 0
        var deleteSucceeds = true

        override fun accepts(uri: String): Boolean {
            onAccept()
            return uri.startsWith("content://")
        }

        override fun open(uri: String): OutputStream {
            opens++
            onOpen()
            return output
        }

        override fun delete(uri: String): Boolean {
            deletes++
            return deleteSucceeds
        }
    }
}

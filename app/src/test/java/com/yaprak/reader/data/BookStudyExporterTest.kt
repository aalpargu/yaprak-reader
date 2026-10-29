package com.yaprak.reader.data

import com.yaprak.reader.data.exporting.BookStudySnapshot
import com.yaprak.reader.data.exporting.BookStudySource
import com.yaprak.reader.data.exporting.DefaultBookStudyExporter
import com.yaprak.reader.data.exporting.ExportDocumentProvider
import com.yaprak.reader.domain.library.MAX_STUDY_EXPORT_BYTES
import com.yaprak.reader.domain.library.BookStudyExporter
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.library.StudyExportResult
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BookStudyExporterTest {
    private val uri = "content://documents/study.txt"
    private val saved = BookStudySnapshot("İstanbul\nNot: çığ 🌿\n".toByteArray(Charsets.UTF_8), 1, 2, 3)

    @Test fun `writes exact UTF8 and counts after releasing snapshot mutex`() = runTest {
        val mutex = Mutex()
        val documents = Documents(onOpen = { assertFalse(mutex.isLocked) })
        val source = BookStudySource { id, hash ->
            assertTrue(mutex.isLocked)
            assertEquals("book", id)
            assertEquals("hash", hash)
            saved
        }
        assertEquals(StudyExportResult.Exported(1, 2, 3),
            DefaultBookStudyExporter(source, documents, Dispatchers.Unconfined, mutex).export("book", "hash", uri))
        assertArrayEquals(saved.utf8, documents.bytes.toByteArray())
        assertEquals(1, documents.closes)
        assertEquals(0, documents.deletes)
    }

    @Test fun `open write close failures clean target and disclose unconfirmed deletion`() = runTest {
        val cases = listOf(
            Documents(onOpen = { throw FileNotFoundException("closed provider") }) to StudyExportFailure.ACCESS,
            Documents(onWrite = { throw IOException("write") }) to StudyExportFailure.IO,
            Documents(onClose = { throw IOException("close") }, deleteConfirmed = false) to StudyExportFailure.IO,
            Documents(onWrite = { throw IOException("write") }, onDelete = { throw SecurityException("delete") }) to StudyExportFailure.IO,
        )
        for ((documents, reason) in cases) {
            val result = export(documents) as StudyExportResult.Failed
            assertEquals(reason, result.reason)
            assertEquals(!documents.deleteConfirmed || documents.onDelete != null, result.partialFileMayRemain)
            assertEquals(1, documents.opens)
            assertEquals(1, documents.deletes)
        }
    }

    @Test fun `snapshot read failure empty records and byte overflow never open target`() = runTest {
        val cases = listOf(
            BookStudySource { _, _ -> throw IOException("database read") } to StudyExportFailure.IO,
            BookStudySource { _, _ -> saved.copy(noteCount = 0, wordCount = 0, passageCount = 0) } to StudyExportFailure.NO_RECORDS,
            BookStudySource { _, _ -> saved.copy(utf8 = ByteArray(MAX_STUDY_EXPORT_BYTES + 1)) } to StudyExportFailure.TOO_LARGE,
        )
        for ((source, reason) in cases) {
            val documents = Documents()
            assertEquals(StudyExportResult.Failed(reason), export(documents, source))
            assertEquals(0, documents.opens)
            assertEquals(1, documents.deletes)
        }
    }

    @Test fun `cancellation in snapshot open write or close propagates and cleans accepted target`() = runTest {
        for (stage in 0..3) {
            val cancel = { throw CancellationException("cancelled") }
            val source = BookStudySource { _, _ -> if (stage == 0) cancel(); saved }
            val documents = Documents(
                onOpen = { if (stage == 1) cancel() },
                onWrite = { if (stage == 2) cancel() },
                onClose = { if (stage == 3) cancel() },
            )
            assertTrue(runCatching { export(documents, source) }.exceptionOrNull() is CancellationException)
            assertEquals(1, documents.deletes)
            assertEquals(if (stage < 2) 0 else 1, documents.closes)
        }
    }

    @Test fun `unaccepted URI and acceptance failure never read open or delete`() = runTest {
        var reads = 0
        val source = BookStudySource { _, _ -> reads++; saved }
        for (documents in listOf(Documents(accepted = false), Documents(onAccept = { throw SecurityException("denied") }))) {
            assertEquals(StudyExportResult.Failed(StudyExportFailure.ACCESS), export(documents, source))
            assertEquals(0, documents.opens)
            assertEquals(0, documents.deletes)
        }
        val cancelled = Documents(onAccept = { throw CancellationException("validation cancelled") })
        assertTrue(runCatching { export(cancelled, source) }.exceptionOrNull() is CancellationException)
        assertEquals(0, cancelled.opens)
        assertEquals(0, cancelled.deletes)
        assertEquals(0, reads)
    }

    @Test fun `cancellation before IO dispatch cleans only already accepted target`() = runTest {
        val documents = Documents()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            DefaultBookStudyExporter(BookStudySource { _, _ -> saved }, documents, dispatcher)
                .export("book", "hash", uri)
        }
        job.cancelAndJoin()
        assertEquals(0, documents.opens)
        assertEquals(1, documents.deletes)
    }

    @Test fun `new formats dispatch exact format inside snapshot mutex then write exact UTF8 counts outside it`() = runTest {
        for (format in listOf(StudyExportFormat.MARKDOWN, StudyExportFormat.ANKI_TSV)) {
            val expectedFormat = format
            val mutex = Mutex()
            val bytes = if (format == StudyExportFormat.MARKDOWN) "# not\n```\n雪 🌿\n```\n" else
                "#separator:Tab\n#html:false\n#columns:Front\tBack\n\"雪\"\t\"kar\"\n"
            val snapshot = BookStudySnapshot(bytes.toByteArray(Charsets.UTF_8),
                if (format == StudyExportFormat.MARKDOWN) 1 else 0, 1, 0)
            var calls = 0
            val source = object : BookStudySource {
                override suspend fun snapshot(bookId: String, contentSha256: String): BookStudySnapshot =
                    error("Must dispatch requested format rather than format existing TXT")
                override suspend fun snapshot(bookId: String, contentSha256: String, format: StudyExportFormat): BookStudySnapshot {
                    assertTrue(mutex.isLocked)
                    assertEquals("book", bookId)
                    assertEquals("hash", contentSha256)
                    assertEquals(expectedFormat, format)
                    calls++
                    return snapshot
                }
            }
            val target = Documents(onOpen = { assertFalse(mutex.isLocked) })
            assertEquals(StudyExportResult.Exported(snapshot.noteCount, 1, 0),
                DefaultBookStudyExporter(source, target, Dispatchers.Unconfined, mutex).export("book", "hash", uri, format))
            assertArrayEquals(snapshot.utf8, target.bytes.toByteArray())
            assertEquals(1, calls)
            assertEquals(1, target.closes)
            assertEquals(0, target.deletes)
        }
    }

    @Test fun `legacy source supports TEXT but rejects new formats and accepted targets are removed`() = runTest {
        var reads = 0
        val source = BookStudySource { _, _ -> reads++; saved }
        val exporter = DefaultBookStudyExporter(source, Documents(), Dispatchers.Unconfined)
        assertEquals(StudyExportResult.Exported(1, 2, 3), exporter.export("book", "hash", uri, StudyExportFormat.TEXT))
        for (format in listOf(StudyExportFormat.MARKDOWN, StudyExportFormat.ANKI_TSV)) {
            val target = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.UNSUPPORTED_FORMAT),
                DefaultBookStudyExporter(source, target, Dispatchers.Unconfined).export("book", "hash", uri, format))
            assertEquals(0, target.opens)
            assertEquals(1, target.deletes)
        }
        assertEquals(1, reads)
    }

    @Test fun `legacy exporter supports TEXT and cannot falsely claim writing unsupported format or cleanup`() = runTest {
        var exports = 0
        val exporter = object : BookStudyExporter {
            override suspend fun export(bookId: String, contentSha256: String, destinationUri: String): StudyExportResult {
                assertEquals("book", bookId)
                assertEquals("hash", contentSha256)
                assertEquals(uri, destinationUri)
                exports++
                return StudyExportResult.Exported(1, 2, 3)
            }
        }
        assertEquals(StudyExportResult.Exported(1, 2, 3), exporter.export("book", "hash", uri, StudyExportFormat.TEXT))
        for (format in listOf(StudyExportFormat.MARKDOWN, StudyExportFormat.ANKI_TSV, StudyExportFormat.PDF)) {
            assertEquals(StudyExportResult.Failed(StudyExportFailure.UNSUPPORTED_FORMAT, partialFileMayRemain = true),
                exporter.export("book", "hash", uri, format))
        }
        assertEquals(1, exports)
    }

    @Test fun `new format cancellation in snapshot open write or close propagates and cleans target`() = runTest {
        for (format in listOf(StudyExportFormat.MARKDOWN, StudyExportFormat.ANKI_TSV)) {
            for (stage in 0..3) {
                val snapshot = BookStudySnapshot("record".toByteArray(), 0, 1, 0)
                val cancel = { throw CancellationException("cancelled") }
                val source = object : BookStudySource {
                    override suspend fun snapshot(bookId: String, contentSha256: String) = error("Legacy call")
                    override suspend fun snapshot(bookId: String, contentSha256: String, format: StudyExportFormat): BookStudySnapshot {
                        if (stage == 0) cancel()
                        return snapshot
                    }
                }
                val target = Documents(
                    onOpen = { if (stage == 1) cancel() },
                    onWrite = { if (stage == 2) cancel() },
                    onClose = { if (stage == 3) cancel() },
                )
                assertTrue(runCatching {
                    DefaultBookStudyExporter(source, target, Dispatchers.Unconfined).export("book", "hash", uri, format)
                }.exceptionOrNull() is CancellationException)
                assertEquals(1, target.deletes)
                assertEquals(if (stage < 2) 0 else 1, target.closes)
            }
        }
    }

    @Test fun `Anki rejects fake note passage counts and empty cards before target opens`() = runTest {
        for (snapshot in listOf(
            saved.copy(noteCount = 1, wordCount = 1, passageCount = 0),
            saved.copy(noteCount = 0, wordCount = 1, passageCount = 1),
            saved.copy(noteCount = 1, wordCount = 0, passageCount = 0),
        )) {
            val source = object : BookStudySource {
                override suspend fun snapshot(bookId: String, contentSha256: String) = error("Legacy call")
                override suspend fun snapshot(bookId: String, contentSha256: String, format: StudyExportFormat) = snapshot
            }
            val target = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO),
                DefaultBookStudyExporter(source, target, Dispatchers.Unconfined).export("book", "hash", uri, StudyExportFormat.ANKI_TSV))
            assertEquals(0, target.opens)
            assertEquals(1, target.deletes)
        }
    }

    private suspend fun export(documents: Documents, source: BookStudySource = BookStudySource { _, _ -> saved }) =
        DefaultBookStudyExporter(source, documents, Dispatchers.Unconfined).export("book", "hash", uri)

    private class Documents(
        val accepted: Boolean = true,
        val deleteConfirmed: Boolean = true,
        val onAccept: () -> Unit = {},
        val onOpen: () -> Unit = {},
        val onWrite: () -> Unit = {},
        val onClose: () -> Unit = {},
        val onDelete: (() -> Unit)? = null,
    ) : ExportDocumentProvider {
        val bytes = ByteArrayOutputStream()
        var opens = 0
        var closes = 0
        var deletes = 0
        override fun accepts(uri: String): Boolean { onAccept(); return accepted }
        override fun open(uri: String): OutputStream {
            opens++
            onOpen()
            return object : OutputStream() {
                override fun write(value: Int) { onWrite(); bytes.write(value) }
                override fun write(value: ByteArray, off: Int, len: Int) { onWrite(); bytes.write(value, off, len) }
                override fun close() { closes++; onClose() }
            }
        }
        override fun delete(uri: String): Boolean { deletes++; onDelete?.invoke(); return deleteConfirmed }
    }
}

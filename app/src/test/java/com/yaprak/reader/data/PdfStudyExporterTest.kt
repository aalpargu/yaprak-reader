package com.yaprak.reader.data

import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.MAX_STUDY_EXPORT_BYTES
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.library.StudyExportResult
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.reader.SourceTextAnchor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Tests the exporter boundary with an opaque renderer fixture, not real PDF container acceptance. */
class PdfStudyExporterTest {
    private val uri = "content://documents/study.pdf"
    private val records = StudyRecordsSnapshot(
        StudyExportBook("İstanbul <literal>", "Çağrı Şen", BookFormat.EPUB, null),
        listOf(StudyNoteRecord("  Çok satır\n雪 🌿  ", null)),
        listOf(StudyWordRecord("leaf", "yaprak")),
        listOf(StudyPassageRecord("Leaf", SourceTextAnchor("OPS/chapter.xhtml", 0, 4, "a".repeat(64)),
            EpubHighlightColor.PINK, " Kaynak notu 🌿 ", 3)),
    )
    private val rendererBytes = "opaque-renderer-fixture".toByteArray()

    @Test fun `PDF request uses coherent typed records inside lock then renders and writes outside it`() = runTest {
        val mutex = Mutex()
        var reads = 0; var renders = 0
        val source = source {
            assertTrue(mutex.isLocked); reads++; records
        }
        val renderer = StudyPdfRenderer {
            assertFalse(mutex.isLocked); assertSame(records, it)
            assertEquals("  Çok satır\n雪 🌿  ", it.notes.single().text)
            assertEquals(EpubHighlightColor.PINK, it.passages.single().highlightColor)
            assertEquals(" Kaynak notu 🌿 ", it.passages.single().attachedNote)
            renders++; rendererBytes
        }
        val documents = Documents(onOpen = { assertFalse(mutex.isLocked); assertEquals(1, renders) })
        assertEquals(StudyExportResult.Exported(2, 1, 1),
            exporter(source, documents, renderer, mutex).export("book", "hash", uri, StudyExportFormat.PDF))
        assertEquals(1, reads); assertEquals(1, renders)
        assertArrayEquals(rendererBytes, documents.bytes.toByteArray())
        assertEquals(1, documents.opens); assertEquals(1, documents.closes); assertEquals(0, documents.deletes)
    }

    @Test fun `slow PDF rendering releases library mutex and cancellation removes accepted unopened target`() = runTest {
        val mutex = Mutex()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val documents = Documents()
        val renderer = StudyPdfRenderer {
            assertFalse(mutex.isLocked); started.complete(Unit); finish.await(); rendererBytes
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            exporter(source { records }, documents, renderer, mutex).export("book", "hash", uri, StudyExportFormat.PDF)
        }
        started.await()
        var unrelatedWrite = false
        mutex.withLock { unrelatedWrite = true }
        assertTrue(unrelatedWrite); assertEquals(0, documents.opens)
        job.cancelAndJoin()
        assertTrue(job.isCancelled); assertFalse(mutex.isLocked)
        assertEquals(0, documents.opens); assertEquals(1, documents.deletes)
    }

    @Test fun `PDF source errors and empty or malformed records never enter renderer or open provider`() = runTest {
        val badRecords = listOf(
            source { throw StudyExportException(StudyExportFailure.CHANGED_BOOK) } to StudyExportFailure.CHANGED_BOOK,
            source { throw StudyExportException(StudyExportFailure.MISSING_BOOK) } to StudyExportFailure.MISSING_BOOK,
            source { StudyRecordsSnapshot(records.book, emptyList(), emptyList(), emptyList()) } to StudyExportFailure.NO_RECORDS,
            source { StudyRecordsSnapshot(records.book, listOf(StudyNoteRecord("bad\uD800", null)), emptyList(), emptyList()) } to StudyExportFailure.IO,
        )
        for ((source, failure) in badRecords) {
            val documents = Documents()
            val renderer = StudyPdfRenderer { error("Source must validate before rendering") }
            assertEquals(StudyExportResult.Failed(failure),
                exporter(source, documents, renderer).export("book", "hash", uri, StudyExportFormat.PDF))
            assertEquals(0, documents.opens); assertEquals(1, documents.deletes)
        }
    }

    @Test fun `PDF renderer errors retain failure reason and disclose target cleanup failure`() = runTest {
        val cases = listOf(
            StudyPdfRenderer { throw StudyExportException(StudyExportFailure.TOO_LARGE) } to StudyExportFailure.TOO_LARGE,
            StudyPdfRenderer { throw IOException("native document write failure") } to StudyExportFailure.IO,
            StudyPdfRenderer { throw SecurityException("native resource denied") } to StudyExportFailure.ACCESS,
        )
        for ((renderer, failure) in cases) {
            val documents = Documents(deleteConfirmed = false)
            assertEquals(StudyExportResult.Failed(failure, partialFileMayRemain = true),
                exporter(source { records }, documents, renderer).export("book", "hash", uri, StudyExportFormat.PDF))
            assertEquals(0, documents.opens); assertEquals(1, documents.deletes)
        }
        val documents = Documents()
        val renderer = StudyPdfRenderer { throw CancellationException("native draw cancelled") }
        assertTrue(runCatching { exporter(source { records }, documents, renderer)
            .export("book", "hash", uri, StudyExportFormat.PDF) }.exceptionOrNull() is CancellationException)
        assertEquals(0, documents.opens); assertEquals(1, documents.deletes)
    }

    @Test fun `empty and oversized PDF renderer bytes are rejected before any provider write`() = runTest {
        for ((bytes, failure) in listOf(ByteArray(0) to StudyExportFailure.IO,
            ByteArray(MAX_STUDY_EXPORT_BYTES + 1) to StudyExportFailure.TOO_LARGE)) {
            val documents = Documents()
            assertEquals(StudyExportResult.Failed(failure),
                exporter(source { records }, documents, StudyPdfRenderer { bytes })
                    .export("book", "hash", uri, StudyExportFormat.PDF))
            assertEquals(0, documents.opens); assertEquals(1, documents.deletes)
        }
    }

    @Test fun `legacy source or missing PDF renderer cannot claim PDF export success`() = runTest {
        var textReads = 0
        val legacy = BookStudySource { _, _ -> textReads++; BookStudySnapshot("legacy".toByteArray(), 1, 0, 0) }
        val documents = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.UNSUPPORTED_FORMAT),
            exporter(legacy, documents, StudyPdfRenderer { error("No typed source") })
                .export("book", "hash", uri, StudyExportFormat.PDF))
        assertEquals(0, textReads); assertEquals(0, documents.opens); assertEquals(1, documents.deletes)
        val noRenderer = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.UNSUPPORTED_FORMAT),
            DefaultBookStudyExporter(source { error("No configured renderer") }, noRenderer, Dispatchers.Unconfined)
                .export("book", "hash", uri, StudyExportFormat.PDF))
        assertEquals(0, noRenderer.opens); assertEquals(1, noRenderer.deletes)
    }

    private fun source(read: suspend () -> StudyRecordsSnapshot) = object : BookStudySource {
        override suspend fun snapshot(bookId: String, contentSha256: String): BookStudySnapshot =
            error("PDF must not parse or render a TXT snapshot")
        override suspend fun snapshotRecords(bookId: String, contentSha256: String): StudyRecordsSnapshot {
            assertEquals("book", bookId); assertEquals("hash", contentSha256); return read()
        }
    }
    private fun exporter(source: BookStudySource, documents: Documents, renderer: StudyPdfRenderer,
        mutex: Mutex = Mutex()) = DefaultBookStudyExporter(source, documents, Dispatchers.Unconfined, mutex, renderer)

    private inner class Documents(val deleteConfirmed: Boolean = true, val onOpen: () -> Unit = {}) : ExportDocumentProvider {
        val bytes = ByteArrayOutputStream()
        var opens = 0; var closes = 0; var deletes = 0
        override fun accepts(uri: String): Boolean = uri == this@PdfStudyExporterTest.uri
        override fun open(uri: String): OutputStream {
            opens++; onOpen()
            return object : OutputStream() {
                override fun write(value: Int) { bytes.write(value) }
                override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes.write(buffer, offset, length) }
                override fun close() { closes++ }
            }
        }
        override fun delete(uri: String): Boolean { deletes++; return deleteConfirmed }
    }
}

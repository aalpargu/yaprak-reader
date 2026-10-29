package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.library.StudyExportController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotionStudyExportReservationTest {
    @Test fun namedCsvReservationSurvivesRestoreAndAcceptsOnlyOneExactLaunchedOwnerResult() = runTest {
        val book = Book("book", "İstanbul", "Deniz", BookFormat.EPUB, "../ bad:\u0000書.epub", "book.epub", 1, "original-hash", 1)
        val calls = mutableListOf<List<String>>()
        val exporter = object : BookStudyExporter {
            override suspend fun export(bookId: String, contentSha256: String, destinationUri: String): StudyExportResult =
                error("No implicit TEXT export")
            override suspend fun export(bookId: String, contentSha256: String, destinationUri: String,
                format: StudyExportFormat): StudyExportResult {
                calls += listOf(bookId, contentSha256, destinationUri, format.name)
                return StudyExportResult.Exported(1, 0, 0)
            }
        }
        val saved = SavedStateHandle()
        val first = StudyExportController(exporter, saved, this)
        val request = requireNotNull(first.prepare(book, StudyExportFormat.NOTION_CSV))
        assertEquals("text/csv", request.format.mimeType)
        assertTrue(request.suggestedFileName.endsWith("-notion-kayitlari.csv"))
        assertFalse(request.suggestedFileName.any { it.isISOControl() || it in "\\/:*?\"<>|" })
        val restored = StudyExportController(exporter, saved, this)
        assertEquals(request, restored.request.value)
        assertFalse(restored.claimLaunch(request.requestId, StudyExportFormat.ANKI_TSV))
        restored.onDocumentResult(request.requestId, request.format, "content://before-launch")
        assertTrue(calls.isEmpty()); assertEquals(request, restored.request.value)
        assertTrue(restored.claimLaunch(request.requestId, request.format))
        assertFalse(first.claimLaunch(request.requestId, request.format))
        restored.onDocumentResult("old-id", request.format, "content://old")
        restored.onDocumentResult(request.requestId, StudyExportFormat.TEXT, "content://wrong-format")
        restored.onLaunchFailed("old-id", request.format)
        assertEquals(request, restored.request.value); assertTrue(calls.isEmpty())
        restored.onDocumentResult(request.requestId, request.format, "content://notion")
        first.onDocumentResult(request.requestId, request.format, "content://late-owner")
        restored.onDocumentResult(request.requestId, request.format, "content://duplicate")
        assertEquals(listOf(listOf(book.id, book.sha256, "content://notion", "NOTION_CSV")), calls)
        assertFalse(restored.busy); assertFalse(first.pickerPending.value)
        assertFalse(saved.keys().map { saved.get<Any?>(it) }.contains("content://notion"))
    }
}

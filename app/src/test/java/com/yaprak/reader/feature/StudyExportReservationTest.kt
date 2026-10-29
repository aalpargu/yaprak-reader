package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookStudyExporter
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.library.StudyExportResult
import com.yaprak.reader.feature.library.StudyExportController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StudyExportReservationTest {
    private val book = Book("book", "İstanbul", "Deniz", BookFormat.EPUB, "İstanbul.epub", "book.epub", 1, "original-hash", 1)
    private class Exporter : BookStudyExporter {
        data class Call(val id: String, val hash: String, val uri: String, val format: StudyExportFormat)
        val calls = mutableListOf<Call>()
        override suspend fun export(bookId: String, contentSha256: String, destinationUri: String) =
            export(bookId, contentSha256, destinationUri, StudyExportFormat.TEXT)
        override suspend fun export(bookId: String, contentSha256: String, destinationUri: String,
            format: StudyExportFormat): StudyExportResult {
            calls += Call(bookId, contentSha256, destinationUri, format)
            return StudyExportResult.Exported(1, 2, 3)
        }
    }

    @Test fun `chosen format and immutable identity are delivered once with safe matching suffix`() = runTest {
        StudyExportFormat.entries.forEach { format ->
            val exporter = Exporter()
            val saved = SavedStateHandle()
            val controller = StudyExportController(exporter, saved, this)
            val request = requireNotNull(controller.prepare(book.copy(originalName = "../ a:\u0000書.epub"), format))
            val suffix = when (format) { StudyExportFormat.ANKI_STUDY_TSV -> "-anki-calisma-kartlari"; StudyExportFormat.NOTION_CSV -> "-notion-kayitlari"; StudyExportFormat.OBSIDIAN_ZIP -> "-obsidian-calisma-kayitlari"; else -> "-calisma-kayitlari" }
            assertTrue(request.suggestedFileName.endsWith("$suffix.${format.extension}"))
            assertFalse(request.suggestedFileName.any { it.isISOControl() || it in "\\/:*?\"<>|" })
            assertTrue(controller.claimLaunch(request.requestId, format))
            assertFalse(controller.claimLaunch(request.requestId, format))
            controller.onDocumentResult(request.requestId, format, "content://correct-$format")
            controller.onDocumentResult(request.requestId, format, "content://duplicate")
            assertEquals(listOf(Exporter.Call(book.id, book.sha256, "content://correct-$format", format)), exporter.calls)
            assertFalse(saved.keys().map { saved.get<Any?>(it) }.contains("content://correct-$format"))
            assertFalse(controller.busy)
        }
    }

    @Test fun namedObsidianZipRetainsExactBookHashAcrossRestoreAndIgnoresOtherFormatsAndDuplicateResults() = runTest {
        val exporter = Exporter(); val saved = SavedStateHandle()
        val first = StudyExportController(exporter, saved, this)
        val original = book.copy(sha256 = "a".repeat(64))
        val request = requireNotNull(first.prepare(original, StudyExportFormat.OBSIDIAN_ZIP))
        assertTrue(request.suggestedFileName.endsWith("-obsidian-calisma-kayitlari.zip"))
        val restored = StudyExportController(exporter, saved, this)
        assertEquals(request, restored.request.value)
        restored.onDocumentResult(request.requestId, request.format, "content://before-launch")
        assertTrue(exporter.calls.isEmpty())
        assertFalse(restored.claimLaunch(request.requestId, StudyExportFormat.MARKDOWN))
        assertTrue(restored.claimLaunch(request.requestId, request.format))
        assertFalse(first.claimLaunch(request.requestId, request.format))
        restored.onDocumentResult(request.requestId, StudyExportFormat.TEXT, "content://wrong-format")
        assertEquals(request, restored.request.value)
        restored.onDocumentResult(request.requestId, request.format, "content://zip")
        first.onDocumentResult(request.requestId, request.format, "content://stale-owner")
        restored.onDocumentResult(request.requestId, request.format, "content://duplicate")
        assertEquals(listOf(Exporter.Call(original.id, original.sha256, "content://zip", StudyExportFormat.OBSIDIAN_ZIP)), exporter.calls)
        assertNull(restored.request.value)
    }

    @Test fun `restored owner retains exact request and cannot relaunch claimed document`() = runTest {
        val exporter = Exporter()
        val saved = SavedStateHandle()
        val first = StudyExportController(exporter, saved, this)
        val request = requireNotNull(first.prepare(book, StudyExportFormat.MARKDOWN))
        assertTrue(first.claimLaunch(request.requestId, request.format))
        val restored = StudyExportController(exporter, saved, this)
        assertEquals(request, restored.request.value)
        assertFalse(restored.claimLaunch(request.requestId, request.format))
        restored.onDocumentResult(request.requestId, request.format, "content://restored")
        first.onDocumentResult(request.requestId, request.format, "content://old-owner")
        assertEquals(listOf(Exporter.Call(book.id, book.sha256, "content://restored", request.format)), exporter.calls)
        assertFalse(first.pickerPending.value)
    }

    @Test fun `restoration before first launch allows precisely one owner to claim`() = runTest {
        val exporter = Exporter()
        val saved = SavedStateHandle()
        val first = StudyExportController(exporter, saved, this)
        val request = requireNotNull(first.prepare(book, StudyExportFormat.ANKI_TSV))
        val restored = StudyExportController(exporter, saved, this)
        assertTrue(restored.claimLaunch(request.requestId, request.format))
        assertFalse(first.claimLaunch(request.requestId, request.format))
        restored.onDocumentResult(request.requestId, request.format, null)
        assertTrue(exporter.calls.isEmpty())
        assertNull(restored.notice.value)
        assertFalse(restored.busy)
    }

    @Test fun `old owner result and launch failure cannot clear newer same format reservation`() = runTest {
        val exporter = Exporter()
        val saved = SavedStateHandle()
        val old = StudyExportController(exporter, saved, this)
        val first = requireNotNull(old.prepare(book, StudyExportFormat.TEXT))
        assertTrue(old.claimLaunch(first.requestId, first.format))
        val current = StudyExportController(exporter, saved, this)
        current.onDocumentResult(first.requestId, first.format, null)
        val second = requireNotNull(current.prepare(book.copy(id = "second", sha256 = "new-hash"), StudyExportFormat.TEXT))
        old.onDocumentResult(first.requestId, first.format, "content://late-first")
        old.onLaunchFailed(first.requestId, first.format)
        assertEquals(second, current.request.value)
        assertEquals(second, StudyExportController(exporter, saved, this).request.value)
        assertTrue(current.claimLaunch(second.requestId, second.format))
        current.onDocumentResult(second.requestId, second.format, "content://second")
        assertEquals(listOf(Exporter.Call("second", "new-hash", "content://second", StudyExportFormat.TEXT)), exporter.calls)
    }

    @Test fun `wrong nonce and format leave pending request intact including launch failure`() = runTest {
        val exporter = Exporter()
        val controller = StudyExportController(exporter, SavedStateHandle(), this)
        val request = requireNotNull(controller.prepare(book, StudyExportFormat.MARKDOWN))
        assertFalse(controller.claimLaunch("unrelated", request.format))
        assertFalse(controller.claimLaunch(request.requestId, StudyExportFormat.TEXT))
        controller.onDocumentResult(request.requestId, StudyExportFormat.TEXT, "content://wrong-format")
        controller.onLaunchFailed("old-request", request.format)
        assertEquals(request, controller.request.value)
        assertNull(controller.notice.value)
        assertTrue(exporter.calls.isEmpty())
        assertTrue(controller.claimLaunch(request.requestId, request.format))
        controller.onLaunchFailed(request.requestId, request.format)
        assertEquals(StudyExportResult.Failed(StudyExportFailure.ACCESS), controller.notice.value?.result)
        assertFalse(controller.busy)
    }

    @Test fun `correct callback before native launch cannot consume or export reservation`() = runTest {
        val exporter = Exporter()
        val controller = StudyExportController(exporter, SavedStateHandle(), this)
        val request = requireNotNull(controller.prepare(book, StudyExportFormat.MARKDOWN))
        controller.onDocumentResult(request.requestId, request.format, "content://premature")
        controller.onDocumentResult(request.requestId, request.format, null)
        controller.onLaunchFailed(request.requestId, request.format)
        assertEquals(request, controller.request.value)
        assertNull(controller.notice.value)
        assertTrue(exporter.calls.isEmpty())
        assertTrue(controller.claimLaunch(request.requestId, request.format))
        controller.onDocumentResult(request.requestId, request.format, "content://normal")
        assertEquals("content://normal", exporter.calls.single().uri)
    }

    @Test fun `old no nonce callbacks cannot consume production request even after launch`() = runTest {
        StudyExportFormat.entries.forEach { format ->
            val exporter = Exporter()
            val controller = StudyExportController(exporter, SavedStateHandle(), this)
            val request = requireNotNull(controller.prepare(book, format))
            assertTrue(controller.claimLaunch(request.requestId, request.format))
            controller.onDocumentResult("content://legacy-late")
            controller.onLaunchFailed()
            assertEquals(request, controller.request.value)
            assertNull(controller.notice.value)
            assertTrue(exporter.calls.isEmpty())
            controller.onDocumentResult(request.requestId, request.format, null)
            assertFalse(controller.busy)
        }
    }

    @Test fun `genuine old two key reservation migrates to already launched TXT without URI persistence`() = runTest {
        val exporter = Exporter()
        val saved = SavedStateHandle(mapOf("pendingStudyExportBookId" to book.id,
            "pendingStudyExportContentSha256" to book.sha256))
        val controller = StudyExportController(exporter, saved, this)
        val request = requireNotNull(controller.request.value)
        assertEquals(StudyExportFormat.TEXT, request.format)
        assertFalse(controller.claimLaunch(request.requestId, request.format))
        val restored = StudyExportController(exporter, saved, this)
        assertEquals(request, restored.request.value)
        restored.onDocumentResult("content://legacy-restored")
        assertEquals(StudyExportFormat.TEXT, exporter.calls.single().format)
        assertTrue(saved.keys().isEmpty())
    }

    @Test fun `corrupt new reservation cannot fall back to mirrored old keys`() = runTest {
        val nonce = "12345678-1234-1234-1234-123456789abc"
        val cases: List<Any?> = listOf(null, "wrong type", arrayListOf("short"),
            arrayListOf(book.id, book.sha256, "UNKNOWN", "bad-uuid", "name.txt", "true", "false"),
            arrayListOf(book.id, book.sha256, "PDF", nonce, "name.md", "true", "false"),
            arrayListOf(book.id, book.sha256, "PDF", nonce, "name.pdf", "true", "true"),
            arrayListOf(book.id, book.sha256, "PDF", nonce, "name.pdf", "maybe", "false"),
            arrayListOf(book.id, book.sha256, "PDF", nonce, "../name.pdf", "true", "false"),
            arrayListOf(book.id, book.sha256, "PDF", nonce, "name\u0000.pdf", "true", "false"),
            arrayListOf(book.id, book.sha256, "PDF", nonce, "name.pdf", true, "false"),
            arrayListOf(book.id, book.sha256, "Pdf", nonce, "name.pdf", "true", "false"),
            arrayListOf(book.id, book.sha256, "PDF", nonce.uppercase(), "name.pdf", "true", "false"))
        cases.forEach { invalid ->
            val saved = SavedStateHandle(mapOf("pendingStudyExportBookId" to book.id,
                "pendingStudyExportContentSha256" to book.sha256, "pendingStudyExportReservation" to invalid))
            val exporter = Exporter()
            val controller = StudyExportController(exporter, saved, this)
            assertNull(controller.request.value)
            controller.onDocumentResult("content://corrupt")
            assertTrue(exporter.calls.isEmpty())
            assertTrue(saved.keys().isEmpty())
        }
    }
}

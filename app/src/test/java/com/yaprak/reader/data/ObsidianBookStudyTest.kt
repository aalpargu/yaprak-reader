package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.reader.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ObsidianBookStudyTest {
    private val hash = "a".repeat(64)
    private val quote = "Özgün 🌿 alıntı"
    private fun epub(title: String = "İstanbul 🌿") = StudyRecordsSnapshot(StudyExportBook(title, "Deniz", BookFormat.EPUB),
        notes = listOf(StudyNoteRecord("  Kitap notu\n雪  ")),
        words = listOf(StudyWordRecord("leaf", "yaprak")),
        passages = listOf(StudyPassageRecord(quote, SourceTextAnchor("OPS/part.xhtml", 0, quote.length, "b".repeat(64)),
            EpubHighlightColor.PINK, "  Bağlı not\n  ")))
    private fun entries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream(), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse(entry.isDirectory); assertFalse(containsKey(entry.name))
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8)); zip.closeEntry()
            }
        }
    }

    @Test fun typedNotesWordsAndQuotedAttachedRecordsRemainDistinctAndComplete() = runBlocking {
        val files = entries(renderObsidianBookStudyZip("book", hash, epub()))
        assertEquals(2, files.size)
        val body = files.entries.single { it.key.startsWith("Books/") }.value
        assertTrue(body.contains("record_count: 3\n"))
        for (text in listOf("  Kitap notu\n雪  ", "leaf", "yaprak", quote, "  Bağlı not\n  ", "OPS/part.xhtml", "Pembe"))
            assertTrue(text, text in body)
        assertTrue(body.contains("### Not 1")); assertTrue(body.contains("### Kelime 1")); assertTrue(body.contains("### Pasaj 1"))
        assertTrue(files.getValue("Yaprak.md").contains("Not: 2 · Kelime: 1 · Alıntı/vurgu: 1"))
        assertTrue(files.keys.filter { it != "Yaprak.md" }.all { Regex("Books/[0-9a-f]{64}\\.md").matches(it) })
    }

    @Test fun pdfPageAndColorAreFromTheSavedNativeAnchorAndNoteWordCountsAreNotConflated() = runBlocking {
        val anchor = PdfSourceAnchor(3, 200, 500, PdfPagePoint(10, 20), PdfPagePoint(60, 20),
            listOf(PdfTextBounds(10.0, 10.0, 60.0, 30.0)))
        val records = StudyRecordsSnapshot(StudyExportBook("PDF", null, BookFormat.PDF, 10),
            notes = listOf(StudyNoteRecord("Sayfa notu", 3)), words = listOf(StudyWordRecord("leaf", "yaprak")),
            pdfAnnotations = listOf(StudyPdfAnnotationRecord(quote, anchor, PdfHighlightColor.BLUE, "Bağlı PDF notu")))
        val body = entries(renderObsidianBookStudyZip("pdf", hash, records)).values.single { "record_count: 3" in it }
        assertTrue(body.contains("PDF sayfası: 4")); assertTrue(body.contains("Mavi"))
        assertTrue(body.contains(quote)); assertTrue(body.contains("Bağlı PDF notu")); assertTrue(body.contains("leaf"))
    }

    @Test fun wordOnlyBooksUseActualTypedPairsAcrossSupportedFormats() = runBlocking {
        for (format in BookFormat.entries) {
            val count = if (format in setOf(BookFormat.PDF, BookFormat.CBZ)) 10 else null
            val records = StudyRecordsSnapshot(StudyExportBook("Kitap", null, format, count),
                words = listOf(StudyWordRecord("dependency injection", "bağımlılık enjeksiyonu")))
            val body = entries(renderObsidianBookStudyZip(format.name, hash, records)).values.single { "record_count: 1" in it }
            assertTrue(body.contains("dependency injection")); assertTrue(body.contains("bağımlılık enjeksiyonu"))
            assertTrue(body.contains("book_format: \"${format.name}\""))
        }
    }
    @Test fun hostileMetadataAndWordsRemainLiteralAndCannotChangeArchivePaths() = runBlocking<Unit> {
        val title = "../CON: [link](file:///x) ![[embed]]\n---\ntags: [injected]\n\"🌿\""
        val original = epub(title)
        val records = StudyRecordsSnapshot(original.book, original.notes,
            listOf(StudyWordRecord("![[leaf]]", "<b>yaprak</b>")), original.passages)
        val archive = renderObsidianBookStudyZip("../../outside", hash, records)
        val files = entries(archive)
        assertFalse(files.keys.any { ".." in it || ':' in it || '\\' in it })
        val body = files.values.single { "record_count: 3" in it }
        val header = body.substringBefore("\n---\n")
        val encoded = header.lineSequence().single { it.startsWith("title: ") }.removePrefix("title: ")
        assertEquals(title, JSONObject("{\"value\":$encoded}").getString("value"))
        assertFalse(header.lineSequence().any { it.startsWith("tags:") })
        assertTrue(body.contains("```\n![[leaf]]\n```")); assertTrue(body.contains("```\n<b>yaprak</b>\n```"))
        System.getProperty("yaprak.screenshotDir")?.let { screenshots ->
            java.io.File(java.io.File(screenshots).parentFile, "fixtures/obsidian-0.158.0-sample.zip")
                .apply { parentFile!!.mkdirs(); writeBytes(archive) }
        }
    }

    @Test fun sameBookRenamingAndContentUpdatesKeepItsSingleSafeFileIdentity() = runBlocking {
        val first = entries(renderObsidianBookStudyZip("book", hash, epub())).keys
        val changed = entries(renderObsidianBookStudyZip("book", "b".repeat(64), epub("Renamed"))).keys
        assertEquals(first, changed)
        assertNotEquals(first, entries(renderObsidianBookStudyZip("other", hash, epub())).keys)
    }

    @Test fun actualThousandWordRowsAreCompleteAndEmptyOrOversizedSourcesFail() = runBlocking {
        val records = StudyRecordsSnapshot(StudyExportBook("Words", null, BookFormat.TXT),
            words = List(1000) { StudyWordRecord("word-$it", "anlam-$it") })
        val body = entries(renderObsidianBookStudyZip("words", hash, records)).values.single { "record_count: 1000" in it }
        assertTrue("### Kelime 1000" in body); assertTrue("word-999" in body); assertTrue("anlam-999" in body)
        assertEquals(1000, Regex("(?m)^### Kelime ").findAll(body).count())
        assertEquals(StudyExportFailure.NO_RECORDS, failure {
            renderObsidianBookStudyZip("empty", hash, StudyRecordsSnapshot(records.book))
        })
        assertEquals(StudyExportFailure.TOO_LARGE, failure {
            renderObsidianBookStudyZip("large", hash, StudyRecordsSnapshot(records.book,
                notes = List(600) { StudyNoteRecord("雪".repeat(1000)) }))
        })
    }

    @Test fun invalidIdentityHashUnicodeAndCrossFormatSourceRowsRejectBeforeCreatingBytes() = runBlocking {
        for ((id, sha) in listOf("" to hash, "bad\u0000id" to hash, "bad\uD800" to hash, "book" to "legacy-hash", "book" to "A".repeat(64)))
            assertEquals(StudyExportFailure.IO, failure { renderObsidianBookStudyZip(id, sha, epub()) })
        val malformed = StudyRecordsSnapshot(epub().book, words = listOf(StudyWordRecord("two\nlines", "meaning")))
        assertEquals(StudyExportFailure.IO, failure { renderObsidianBookStudyZip("book", hash, malformed) })
        val wrong = StudyRecordsSnapshot(StudyExportBook("TXT", null, BookFormat.TXT), passages = epub().passages)
        assertEquals(StudyExportFailure.IO, failure { renderObsidianBookStudyZip("book", hash, wrong) })
    }

    private class Documents(private val onOpen: () -> Unit = {}) : ExportDocumentProvider {
        val bytes = ByteArrayOutputStream()
        var opens = 0; var deletes = 0; var closed = false; var deleteConfirmed = true
        var onWrite: () -> Unit = {}
        override fun accepts(uri: String) = uri == "content://owned/study"
        override fun open(uri: String): OutputStream {
            onOpen(); opens++
            return object : OutputStream() {
                override fun write(value: Int) { bytes.write(value); onWrite() }
                override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes.write(buffer, offset, length); onWrite() }
                override fun close() { closed = true }
            }
        }
        override fun delete(uri: String): Boolean { deletes++; return deleteConfirmed }
    }
    private fun source(records: StudyRecordsSnapshot) = object : BookStudySource {
        override suspend fun snapshot(bookId: String, contentSha256: String): BookStudySnapshot = error("No legacy TXT conversion")
        override suspend fun snapshotRecords(bookId: String, contentSha256: String) = records
    }

    @Test fun actualExporterCapturesTypedIdentityUnderMutexAndReleasesItBeforeProviderIo() = runBlocking {
        val mutex = Mutex(); val calls = mutableListOf<Pair<String, String>>()
        val records = epub()
        val source = object : BookStudySource {
            override suspend fun snapshot(bookId: String, contentSha256: String): BookStudySnapshot = error("No TXT fallback")
            override suspend fun snapshotRecords(bookId: String, contentSha256: String): StudyRecordsSnapshot {
                assertTrue(mutex.isLocked); calls += bookId to contentSha256; return records
            }
        }
        val docs = Documents { assertTrue(mutex.tryLock()); mutex.unlock() }
        val exporter = DefaultBookStudyExporter(source, docs, Dispatchers.Unconfined, mutex)
        assertEquals(StudyExportResult.Exported(2, 1, 1), exporter.export("book", hash, "content://owned/study", StudyExportFormat.OBSIDIAN_ZIP))
        assertEquals(listOf("book" to hash), calls); assertTrue(docs.closed); assertEquals(0, docs.deletes)
        assertEquals(2, entries(docs.bytes.toByteArray()).size)
        assertEquals("application/zip", StudyExportFormat.OBSIDIAN_ZIP.mimeType)
    }

    @Test fun legacyMissingAndChangedSourcesCannotOpenATargetOrInventTypedRecords() = runBlocking {
        val legacyDocs = Documents(); var legacyReads = 0
        val legacy = BookStudySource { _, _ -> legacyReads++; error("Never parse legacy TXT") }
        assertEquals(StudyExportResult.Failed(StudyExportFailure.UNSUPPORTED_FORMAT),
            DefaultBookStudyExporter(legacy, legacyDocs, Dispatchers.Unconfined)
                .export("book", hash, "content://owned/study", StudyExportFormat.OBSIDIAN_ZIP))
        assertEquals(0, legacyReads); assertEquals(0, legacyDocs.opens); assertEquals(1, legacyDocs.deletes)
        for (reason in listOf(StudyExportFailure.MISSING_BOOK, StudyExportFailure.CHANGED_BOOK)) {
            val docs = Documents()
            val broken = object : BookStudySource {
                override suspend fun snapshot(bookId: String, contentSha256: String): BookStudySnapshot = error("No fallback")
                override suspend fun snapshotRecords(bookId: String, contentSha256: String): StudyRecordsSnapshot = throw StudyExportException(reason)
            }
            assertEquals(StudyExportResult.Failed(reason), DefaultBookStudyExporter(broken, docs, Dispatchers.Unconfined)
                .export("book", hash, "content://owned/study", StudyExportFormat.OBSIDIAN_ZIP))
            assertEquals(0, docs.opens); assertEquals(1, docs.deletes)
        }
    }

    @Test fun providerFailureClosesItsPartialZipAndReportsConfirmedOrUnconfirmedRemoval() = runBlocking {
        for (confirmed in listOf(true, false)) {
            val docs = Documents().apply { deleteConfirmed = confirmed; onWrite = { throw java.io.IOException("Write failed") } }
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO, partialFileMayRemain = !confirmed),
                DefaultBookStudyExporter(source(epub()), docs, Dispatchers.Unconfined)
                    .export("book", hash, "content://owned/study", StudyExportFormat.OBSIDIAN_ZIP))
            assertTrue(docs.closed); assertEquals(1, docs.deletes); assertTrue(docs.bytes.size() > 0)
        }
    }

    @Test fun cancellationAfterWriteIsPropagatedAndCleansOnlyTheAcceptedCreatedTarget() = runTest {
        val docs = Documents(); var thrown: Throwable? = null; var success: StudyExportResult? = null
        val exporter = DefaultBookStudyExporter(source(epub()), docs, StandardTestDispatcher(testScheduler))
        val operation = launch {
            try { success = exporter.export("book", hash, "content://owned/study", StudyExportFormat.OBSIDIAN_ZIP) }
            catch (failure: Throwable) { thrown = failure }
        }
        docs.onWrite = { operation.cancel() }; runCurrent(); operation.join()
        assertTrue(thrown is CancellationException); assertNull(success); assertTrue(docs.closed); assertEquals(1, docs.deletes)
    }

    @Test fun alreadyCancelledRendererPreservesCancellationBeforeInvalidIdentityChecks() = runTest {
        var thrown: Throwable? = null
        val operation = launch {
            currentCoroutineContext().cancel()
            try { renderObsidianBookStudyZip("", "invalid", epub()) } catch (failure: Throwable) { thrown = failure }
        }
        runCurrent(); operation.join(); assertTrue(thrown is CancellationException)
    }

    @Test fun namedBookRequestCreatesTheActualLocalZipDocumentIntent() = runTest {
        val controller = com.yaprak.reader.feature.library.StudyExportController(
            DefaultBookStudyExporter(source(epub()), Documents(), Dispatchers.Unconfined), androidx.lifecycle.SavedStateHandle(), this)
        val book = Book("book", "İstanbul", "Deniz", BookFormat.EPUB, "../ unsafe:\u0000.epub", "book.epub", 1, hash, 1)
        val request = requireNotNull(controller.prepare(book, StudyExportFormat.OBSIDIAN_ZIP))
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = com.yaprak.reader.feature.library.CreateLocalBook(request.format.mimeType).createIntent(context, request.suggestedFileName)
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/zip", intent.type)
        assertEquals(request.suggestedFileName, intent.getStringExtra(android.content.Intent.EXTRA_TITLE))
        assertTrue(request.suggestedFileName.endsWith("-obsidian-calisma-kayitlari.zip"))
        assertTrue(intent.getBooleanExtra(android.content.Intent.EXTRA_LOCAL_ONLY, false))
        assertTrue(intent.categories.contains(android.content.Intent.CATEGORY_OPENABLE))
    }

    private suspend fun failure(block: suspend () -> Any?): StudyExportFailure? =
        (runCatching { block() }.exceptionOrNull() as? StudyExportException)?.reason
}

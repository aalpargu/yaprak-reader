package com.yaprak.reader.data

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.notes.*
import com.yaprak.reader.domain.passages.*
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.reader.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryNotesExportDataTest {
    private val uri = "content://study/library-notes"
    private val sha = "a".repeat(64)
    private fun note(id: String = "note", text: String = "  Not\n🌿  ") =
        LibraryNote(id, "book", sha, "İstanbul 🌿", "Çağrı Şen", BookFormat.EPUB, text, 1)
    private fun plain(id: String = "note", text: String = "  Not\n🌿  ") = LibraryNoteExportEntry(note(id, text), null)
    private fun pdfAnchor(page: Int = 3) = PdfSourceAnchor(page, 300, 600, PdfPagePoint(60, 100), PdfPagePoint(180, 100),
        listOf(PdfTextBounds(60.0, 90.0, 180.0, 110.0)))
    private fun epubEntry() = LibraryNoteExportEntry(note("epub").copy(source = LibraryNoteSource.EPUB_PASSAGE,
        sourceText = "quote", sourceAnchor = SourceTextAnchor("OPS/first.xhtml", 0, 5, "b".repeat(64)),
        highlightColor = EpubHighlightColor.PINK), null)
    private fun pdfEntry() = LibraryNoteExportEntry(note("pdf").copy(bookFormat = BookFormat.PDF, pdfPageIndex = 3,
        source = LibraryNoteSource.PDF_ANNOTATION, sourceText = "quote", pdfSourceAnchor = pdfAnchor(),
        pdfHighlightColor = PdfHighlightColor.BLUE), 10)

    @Test fun txtRetainsExactNotesMetadataExcerptsAndActualSourceWithoutExportingPrivateIdentitiesOrGeometry() {
        val records = listOf(plain(), epubEntry(), pdfEntry(),
            LibraryNoteExportEntry(note("txt").copy(bookFormat = BookFormat.TXT, bookAuthors = null), null),
            LibraryNoteExportEntry(note("cbz").copy(bookFormat = BookFormat.CBZ), 20))
        val output = renderLibraryNotesExport(LibraryNotesExportSnapshot("istanbul", records), LibraryNotesExportFormat.TEXT)
            .toString(Charsets.UTF_8)
        assertEquals(5, Regex("(?m)^Not [0-9]+$").findAll(output).count())
        assertEquals(5, Regex(Regex.escape("Not:\n  Not\n🌿  \n\n")).findAll(output).count())
        assertTrue(output.contains("Kaynak EPUB dosyası:\nOPS/first.xhtml\n\n"))
        assertTrue(output.contains("PDF sayfası:\n4\n\n")); assertTrue(output.contains("Vurgu rengi:\nMavi\n\n"))
        assertTrue(output.contains("Vurgu rengi:\nPembe\n\n")); assertTrue(output.contains("Yazar:\nBilinmiyor\n\n"))
        assertEquals(2, Regex("Kaynak alıntı:").findAll(output).count())
        assertFalse(output.contains(sha)); assertFalse(output.contains("b".repeat(64)))
        assertFalse(output.contains("pageWidth")); assertFalse(output.contains("createdAt"))
    }

    @Test fun markdownHostileMetadataQueryNoteAndExcerptRemainInsideIndependentCompleteLiteralFences() {
        val payload = "  # başlık\n``````\n<script>🌿</script>\t雪\r\n  "
        val entry = epubEntry().copy(note = epubEntry().note.copy(bookTitle = payload, bookAuthors = "*Yazar*", text = payload,
            sourceText = "quote"))
        val output = renderLibraryNotesExport(LibraryNotesExportSnapshot("# sorgu ```", listOf(entry)), LibraryNotesExportFormat.MARKDOWN)
            .toString(Charsets.UTF_8)
        val blocks = mutableListOf<String>(); val outside = StringBuilder(); val lines = output.split('\n'); var at = 0
        while (at < lines.size) {
            val line = lines[at++]
            if (line.length >= 3 && line.all { it == '`' }) {
                val body = mutableListOf<String>()
                while (at < lines.size && lines[at] != line) body.add(lines[at++])
                assertTrue("Literal fence must close", at < lines.size); at++
                blocks.add(body.joinToString("\n"))
            } else outside.append(line).append('\n')
        }
        assertEquals(listOf("# sorgu ```", payload, "*Yazar*", "EPUB", "OPS/first.xhtml", "Pembe", "quote", payload), blocks)
        assertFalse(outside.contains("<script>")); assertFalse(outside.contains("# başlık")); assertTrue(outside.contains("## Not 1"))
    }

    @Test fun emptyMalformedDuplicateMismatchedFormatAndWrongPageDetachedSnapshotsFailAsAWhole() = runBlocking {
        val bad = listOf(
            listOf(plain(text = "bad\u0000")), listOf(plain(), plain()),
            listOf(plain().copy(note = note().copy(contentSha256 = "bad"))),
            listOf(plain().copy(note = note().copy(sourceText = "unowned quote"))),
            listOf(epubEntry().copy(note = epubEntry().note.copy(bookFormat = BookFormat.TXT))),
            listOf(pdfEntry().copy(bookPageCount = 3)),
            listOf(pdfEntry().copy(note = pdfEntry().note.copy(pdfPageIndex = 2))),
            listOf(epubEntry().copy(note = epubEntry().note.copy(sourceAnchor = SourceTextAnchor("../bad.xhtml", 0, 5, "b".repeat(64)))))
        )
        for (entries in bad) assertFailure(StudyExportFailure.IO) {
            renderLibraryNotesExport(LibraryNotesExportSnapshot("", entries), LibraryNotesExportFormat.MARKDOWN)
        }
        assertFailure(StudyExportFailure.NO_RECORDS) {
            renderLibraryNotesExport(LibraryNotesExportSnapshot("", emptyList()), LibraryNotesExportFormat.TEXT)
        }
        assertFailure(StudyExportFailure.IO) { LibraryNotesExportBudget("bad\uD800") }
        assertFailure(StudyExportFailure.IO) { LibraryNotesExportBudget("x".repeat(201)) }
    }

    @Test fun exactThousandRecordAndUtf8CaptureBudgetsNeverSilentlyTruncateToTheUiLimit() = runBlocking {
        val entries = List(1000) { plain("note-$it", "value $it") }
        val output = renderLibraryNotesExport(LibraryNotesExportSnapshot("", entries), LibraryNotesExportFormat.TEXT).toString(Charsets.UTF_8)
        assertTrue(output.contains("Not 1000\n")); assertTrue(output.contains("value 999"))
        val budget = LibraryNotesExportBudget(""); entries.forEach(budget::add)
        assertFailure(StudyExportFailure.TOO_LARGE) { budget.add(plain("next")) }
        assertFailure(StudyExportFailure.TOO_LARGE) { LibraryNotesExportSnapshot("", entries + plain("next")) }
        assertFailure(StudyExportFailure.TOO_LARGE) {
            renderLibraryNotesExport(LibraryNotesExportSnapshot("", List(600) { plain("large-$it", "雪".repeat(1000)) }),
                LibraryNotesExportFormat.MARKDOWN)
        }
    }

    @Test fun exporterCapturesExactFreshQueryAndRejectsAResultForAnotherQueryBeforeOpeningTheProvider() = runBlocking {
        val mutex = Mutex(); val docs = Documents(onOpen = { assertTrue(mutex.tryLock()); mutex.unlock() })
        val calls = mutableListOf<String>()
        val source = LibraryNotesExportSource { query ->
            calls += query; mutex.lock()
            try { LibraryNotesExportSnapshot(query, listOf(plain(text = "fresh"))) } finally { mutex.unlock() }
        }
        val exporter = DefaultLibraryNotesExporter(source, docs, Dispatchers.Unconfined)
        assertEquals(StudyExportResult.Exported(1, 0, 0), exporter.export("istanbul", uri, LibraryNotesExportFormat.MARKDOWN))
        assertEquals(listOf("istanbul"), calls); assertTrue(docs.bytes.toString("UTF-8").contains("fresh")); assertEquals(0, docs.deletes)
        val wrong = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), DefaultLibraryNotesExporter(
            LibraryNotesExportSource { LibraryNotesExportSnapshot("other", listOf(plain())) }, wrong, Dispatchers.Unconfined)
            .export("istanbul", uri, LibraryNotesExportFormat.TEXT))
        assertEquals(0, wrong.opens); assertEquals(1, wrong.deletes)
    }

    @Test fun invalidSourceAndOpenWriteCloseFailureCleanOnlyAcceptedTargetsAndDiscloseUnconfirmedDeletion() = runBlocking {
        val source = LibraryNotesExportSource { query -> LibraryNotesExportSnapshot(query, listOf(plain())) }
        for (failure in listOf("open", "write", "close")) {
            val docs = Documents(failure, deleteConfirmed = false)
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO, partialFileMayRemain = true),
                DefaultLibraryNotesExporter(source, docs, Dispatchers.Unconfined).export("", uri, LibraryNotesExportFormat.TEXT))
            assertEquals(1, docs.opens); assertEquals(if (failure == "open") 0 else 1, docs.closes); assertEquals(1, docs.deletes)
        }
        val invalid = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.NO_RECORDS), DefaultLibraryNotesExporter(
            LibraryNotesExportSource { LibraryNotesExportSnapshot(it, emptyList()) }, invalid, Dispatchers.Unconfined)
            .export("", uri, LibraryNotesExportFormat.TEXT)); assertEquals(0, invalid.opens); assertEquals(1, invalid.deletes)
        val unaccepted = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.ACCESS), DefaultLibraryNotesExporter(
            LibraryNotesExportSource { error("No source read") }, unaccepted, Dispatchers.Unconfined)
            .export("", "file:///unaccepted", LibraryNotesExportFormat.TEXT))
        assertEquals(0, unaccepted.opens); assertEquals(0, unaccepted.deletes)
    }

    @Test fun cancellationInLateCaptureOrWriteCannotPublishSuccessAndClosesAndCleansTheAcceptedTarget() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val docs = Documents()
        val capture = launch {
            DefaultLibraryNotesExporter(LibraryNotesExportSource { query ->
                started.complete(Unit); withContext(NonCancellable) { finish.await() }
                LibraryNotesExportSnapshot(query, listOf(plain()))
            }, docs, Dispatchers.Unconfined).export("", uri, LibraryNotesExportFormat.TEXT)
            fail("Cancelled capture cannot succeed")
        }
        started.await(); capture.cancel(); finish.complete(Unit); capture.join()
        assertEquals(0, docs.opens); assertEquals(1, docs.deletes)
        val writing = Documents("cancel")
        val write = async { DefaultLibraryNotesExporter(LibraryNotesExportSource { LibraryNotesExportSnapshot(it, listOf(plain())) },
            writing, Dispatchers.Unconfined).export("", uri, LibraryNotesExportFormat.MARKDOWN) }
        assertTrue(runCatching { write.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, writing.opens); assertEquals(1, writing.closes); assertEquals(1, writing.deletes)
    }

    @Test fun actualRoomExportsAllThousandMatchesWhileUiRemainsNewest200AndNextMatchFailsBeforeProviderOpen() = runBlocking {
        database { db ->
            val book = book("book", BookFormat.EPUB, sha); db.books().insert(book)
            db.withTransaction { repeat(1000) { index -> db.bookNotes().add(BookNoteEntity("note-$index", book.id, sha, "needle $index", index.toLong())) } }
            val source = RoomLibraryNotesRepository(db)
            val ui = source.observe("needle").first(); assertEquals(1000, ui.totalCount); assertEquals(200, ui.entries.size)
            val snapshot = source.snapshot("needle"); assertEquals(1000, snapshot.entries.size)
            assertEquals("needle 999", snapshot.entries.first().note.text); assertEquals("needle 0", snapshot.entries.last().note.text)
            assertEquals(1, source.snapshot("needle 999").entries.size)
            val docs = Documents()
            assertEquals(StudyExportResult.Exported(1000, 0, 0), DefaultLibraryNotesExporter(source, docs, Dispatchers.Unconfined)
                .export("needle", uri, LibraryNotesExportFormat.MARKDOWN))
            assertTrue(docs.bytes.toString("UTF-8").contains("## Not 1000"))
            db.bookNotes().add(BookNoteEntity("extra", book.id, sha, "needle extra", 1001))
            val over = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.TOO_LARGE), DefaultLibraryNotesExporter(source, over, Dispatchers.Unconfined)
                .export("needle", uri, LibraryNotesExportFormat.TEXT)); assertEquals(0, over.opens); assertEquals(1, over.deletes)
            assertEquals(1, source.snapshot("extra").entries.size)
        }
    }

    @Test fun actualRoomMixedCurrentSourcesRetainMetadataAndTruePagesExcludeStaleAndPreserveReadingHistory() = runBlocking {
        database { db ->
            val epub = book("book", BookFormat.EPUB, sha); val pdf = book("pdf", BookFormat.PDF, "d".repeat(64))
            db.books().insert(epub); db.books().insert(pdf)
            db.bookNotes().add(BookNoteEntity("note", epub.id, sha, "own", 1))
            db.bookNotes().add(BookNoteEntity("old", epub.id, "c".repeat(64), "NOT CURRENT", 2))
            db.bookNotes().add(BookNoteEntity("page", pdf.id, pdf.sha256, "page note", 3, 9))
            db.savedWords().add(SavedWordEntity("word", epub.id, sha, "leaf", "yaprak", 4))
            db.epubPassages().add(EpubPassageEntity("linked", epub.id, sha, 1, "OPS/first.xhtml", "b".repeat(64),
                0, 5, "quote", 5, "PINK", "linked note", 2))
            db.epubPassages().add(EpubPassageEntity("plain-highlight", epub.id, sha, 1, "OPS/second.xhtml", "b".repeat(64),
                0, 5, "quote", 6, "BLUE", null, 2))
            RoomPdfAnnotationRepository(db, PdfAnnotationOriginalSource { _, _, _ -> }, Mutex())
                .create(pdf.id, pdf.sha256, "quote", pdfAnchor(), PdfHighlightColor.GREEN, "pdf linked")
            db.reading().save(ReadingProgressEntity(epub.id, sha, 1, "saved locator", "OPS/first.xhtml", 7))
            val resume = db.reading().resume(epub.id, sha)
            val source = RoomLibraryNotesRepository(db)
            val rows = source.snapshot("").entries
            assertEquals(setOf("own", "page note", "linked note", "pdf linked"), rows.map { it.note.text }.toSet())
            assertEquals(10, rows.single { it.note.text == "page note" }.bookPageCount)
            val attached = rows.single { it.note.source == LibraryNoteSource.PDF_ANNOTATION }
            assertEquals(3, attached.note.pdfPageIndex); assertEquals(10, attached.bookPageCount)
            assertEquals(4, source.snapshot("istanbul DENIZ").entries.size)
            db.openHelper.writableDatabase.execSQL("UPDATE books SET title = ? WHERE id = ?", arrayOf("Güncel", epub.id))
            assertEquals(setOf("Güncel"), source.snapshot("güncel").entries.map { it.note.bookTitle }.toSet())
            assertEquals(resume, db.reading().resume(epub.id, sha))
            for (table in listOf("reading_duration_receipts", "book_completion_events"))
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
        }
    }

    @Test fun actualRoomRecoveryIsRecheckedAfterMutexWaitAndCorruptOrClosedSourcesNeverBecomeEmptyExports() = runBlocking {
        database { db ->
            db.books().insert(book("book", BookFormat.EPUB, sha)); db.bookNotes().add(BookNoteEntity("note", "book", sha, "own", 1))
            val mutex = Mutex(locked = true); var ready = true; var guards = 0
            val source = RoomLibraryNotesRepository(db, Dispatchers.Unconfined, mutex, { guards++; check(ready) })
            val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { source.snapshot("") }.exceptionOrNull() }
            ready = false; mutex.unlock()
            assertTrue(queued.await() is LibraryNotesSourceException); assertEquals(1, guards)
            ready = true; assertEquals(1, source.snapshot("").entries.size)
            db.openHelper.writableDatabase.execSQL("UPDATE book_notes SET text = ?", arrayOf("bad\u0000"))
            val docs = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), DefaultLibraryNotesExporter(source, docs, Dispatchers.Unconfined)
                .export("", uri, LibraryNotesExportFormat.TEXT)); assertEquals(0, docs.opens)
            db.close()
            assertTrue(runCatching { source.snapshot("") }.exceptionOrNull() is LibraryNotesSourceException)
        }
    }

    private fun book(id: String, format: BookFormat, hash: String) = BookEntity(id, "İstanbul", "Deniz", format.name,
        "book.${format.extension}", "$id.${format.extension}", 100, hash, 1, if (format == BookFormat.PDF) 10 else null)
    private suspend fun database(block: suspend (LibraryDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), LibraryDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }
    private suspend fun assertFailure(expected: StudyExportFailure, block: suspend () -> Any) {
        val error = runCatching { block() }.exceptionOrNull()
        val reason = when (error) { is StudyExportException -> error.reason; is LibraryNotesExportException -> error.reason; else -> null }
        assertEquals(error.toString(), expected, reason)
    }
    private inner class Documents(val failure: String? = null, val deleteConfirmed: Boolean = true,
        val onOpen: () -> Unit = {}) : ExportDocumentProvider {
        val bytes = ByteArrayOutputStream(); var opens = 0; var closes = 0; var deletes = 0
        override fun accepts(uri: String) = uri == this@LibraryNotesExportDataTest.uri
        override fun open(uri: String): OutputStream {
            opens++; onOpen(); if (failure == "open") throw IOException("Open failed")
            return object : OutputStream() {
                override fun write(value: Int) { checkWrite(); bytes.write(value) }
                override fun write(buffer: ByteArray, offset: Int, length: Int) { checkWrite(); bytes.write(buffer, offset, length) }
                private fun checkWrite() { if (failure == "write") throw IOException("Write failed"); if (failure == "cancel") throw CancellationException() }
                override fun close() { closes++; if (failure == "close") throw IOException("Close failed") }
            }
        }
        override fun delete(uri: String): Boolean { deletes++; return deleteConfirmed }
    }
}

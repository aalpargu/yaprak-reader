package com.yaprak.reader.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.passages.*
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.reader.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Local CSV and saved-record proof; no live Notion import or original PDF decoding claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotionStudyCsvTest {
    private val uri = "content://study/notion"
    private val sha = "a".repeat(64)
    private val epub = StudyExportBook("İstanbul 🌿", "Çağrı Şen", BookFormat.EPUB)
    private fun anchor(text: String = "quote") = SourceTextAnchor("OPS/first.xhtml", 0, text.length, "b".repeat(64))
    private fun pdfAnchor(page: Int) = PdfSourceAnchor(page, 300, 600,
        PdfPagePoint(60, 100), PdfPagePoint(180, 100), listOf(PdfTextBounds(60.0, 90.0, 180.0, 110.0)))
    private val range = SourceRangeAnchor("OPS/range.xhtml", listOf(
        SourceRangeSegment("0/0", 0, 4, "b".repeat(64), "İğne"),
        SourceRangeSegment("1/0", 0, 4, "c".repeat(64), "🌿 x")))

    @Test fun epubRecordsBecomeOneRowEachWithExactTextAndAttachedNotesInTheSameRow() {
        val linked = "  bağlı\r\n🌿  "
        val records = StudyRecordsSnapshot(epub, listOf(StudyNoteRecord("  own\nnot  ")),
            listOf(StudyWordRecord("leaf", "yaprak")), listOf(
                StudyPassageRecord("quote", anchor(), EpubHighlightColor.PINK, linked),
                StudyPassageRecord("İğne\n🌿 x", range, null, "range note")))
        val rows = rows(renderNotionStudyCsv(records))
        assertEquals(listOf("Ad", "Kitap", "Yazar", "Biçim", "Kayıt türü", "Metin", "Anlam", "Bağlı not",
            "Kaynak", "PDF sayfası", "Vurgu rengi"), rows.first())
        val data = rows.drop(1)
        assertEquals(4, data.size); assertEquals(3, records.noteCount)
        assertEquals(listOf("Not 1", "Kelime 1", "EPUB pasajı 1", "EPUB pasajı 2"), data.map { it[0] })
        assertEquals(listOf("  own\nnot  ", "leaf", "quote", "İğne\n🌿 x"), data.map { it[5] })
        assertTrue(data.all { it.subList(1, 4) == listOf(epub.title, epub.authors, "EPUB") })
        assertEquals("yaprak", data[1][6]); assertEquals(linked, data[2][7])
        assertEquals("OPS/first.xhtml", data[2][8]); assertEquals("Pembe", data[2][10])
        assertEquals("OPS/range.xhtml", data[3][8]); assertEquals("", data[3][10])
        assertTrue(data.all { it[9].isEmpty() }); assertTrue(data.take(2).all { it[8].isEmpty() })
        assertFalse(renderNotionStudyCsv(records).toString(Charsets.UTF_8).contains("b".repeat(64)))
    }

    @Test fun pdfSourcesUseActualOneBasedPagesAllColorsAndNoInventedUnanchoredLocationOrAuthor() {
        val annotations = PdfHighlightColor.entries.mapIndexed { index, color ->
            StudyPdfAnnotationRecord("same", pdfAnchor(index), color, if (index == 0) "attached" else null)
        } + StudyPdfAnnotationRecord("note-only", pdfAnchor(4), null, "own note")
        val records = StudyRecordsSnapshot(StudyExportBook("PDF", null, BookFormat.PDF, 10),
            listOf(StudyNoteRecord("last page", 9), StudyNoteRecord("book note")), pdfAnnotations = annotations)
        val data = rows(renderNotionStudyCsv(records)).drop(1)
        assertEquals(7, data.size); assertEquals(4, records.noteCount); assertEquals(5, records.passageCount)
        assertTrue(data.all { it[2].isEmpty() })
        assertEquals(listOf("PDF sayfası 10", "10"), data[0].subList(8, 10))
        assertEquals(listOf("", ""), data[1].subList(8, 10))
        assertEquals(listOf("Sarı", "Yeşil", "Mavi", "Pembe", ""), data.drop(2).map { it[10] })
        assertEquals(listOf("1", "2", "3", "4", "5"), data.drop(2).map { it[9] })
        assertEquals("attached", data[2][7]); assertEquals("own note", data.last()[7])
    }

    @Test fun csvRoundTripPreservesQuotedMultilineUnicodeTabsAndLiteralFormulaLikeText() {
        val values = listOf("=SUM(1,2)", "#header\t,\"quote\"\r\nline\n", "  <script>🌿</script> 雪  ", "A\u2028B")
        for (format in listOf(BookFormat.EPUB, BookFormat.TXT, BookFormat.CBZ)) {
            val book = epub.copy(title = "=title,\"🌿\"", authors = "author\r\nnext", format = format,
                pageCount = if (format == BookFormat.CBZ) 2 else null)
            val bytes = renderNotionStudyCsv(StudyRecordsSnapshot(book, values.map { StudyNoteRecord(it) }))
            assertFalse(bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
            val data = rows(bytes).drop(1)
            assertEquals(values, data.map { it[5] }); assertTrue(data.all { it.size == 11 })
            assertTrue(data.all { it[1] == book.title && it[2] == book.authors && it[3] == format.name })
            assertTrue(data.all { it.subList(8, 11).all(String::isEmpty) })
        }
    }

    @Test fun emptyInvalidDetachedOrOutOfRangeSourcesAreRejectedAsAWhole() = runBlocking {
        val pdf = epub.copy(format = BookFormat.PDF, pageCount = 10)
        val bad = listOf(
            StudyRecordsSnapshot(epub, listOf(StudyNoteRecord("bad\uD800"))),
            StudyRecordsSnapshot(epub.copy(title = "bad\u0000"), listOf(StudyNoteRecord("note"))),
            StudyRecordsSnapshot(epub, words = listOf(StudyWordRecord("word\nsecond", "meaning"))),
            StudyRecordsSnapshot(epub, passages = listOf(StudyPassageRecord("quote", anchor().copy(resourceHref = "https://example.test/x")))),
            StudyRecordsSnapshot(epub, passages = listOf(StudyPassageRecord("quote", anchor().copy(end = 4)))),
            StudyRecordsSnapshot(pdf, listOf(StudyNoteRecord("wrong page", 10))),
            StudyRecordsSnapshot(epub, pdfAnnotations = listOf(StudyPdfAnnotationRecord("quote", pdfAnchor(1), PdfHighlightColor.BLUE, null))),
            StudyRecordsSnapshot(pdf, passages = listOf(StudyPassageRecord("quote", anchor()))))
        for (records in bad) assertFailure(StudyExportFailure.IO) { renderNotionStudyCsv(records) }
        assertFailure(StudyExportFailure.NO_RECORDS) { renderNotionStudyCsv(StudyRecordsSnapshot(epub)) }
    }

    @Test fun utf8SourceAndFiveMegabyteQuotedRepeatedMetadataOutputHaveIndependentWholeFileBounds() = runBlocking {
        assertFailure(StudyExportFailure.TOO_LARGE) {
            renderNotionStudyCsv(StudyRecordsSnapshot(epub, List(200) { StudyNoteRecord("雪".repeat(2000)) }))
        }
        val expanded = epub.copy(title = "\"".repeat(1000), authors = "\"".repeat(2000))
        val tooLarge = StudyRecordsSnapshot(expanded, List(1000) { StudyNoteRecord("n") })
        validateStudyRecordsSnapshot(tooLarge)
        assertFailure(StudyExportFailure.TOO_LARGE) { renderNotionStudyCsv(tooLarge) }
        val fits = renderNotionStudyCsv(StudyRecordsSnapshot(expanded, List(500) { StudyNoteRecord("雪🌿") }))
        assertTrue(fits.size < MAX_NOTION_STUDY_CSV_BYTES); assertEquals(501, rows(fits).size)
        assertEquals(1001, rows(renderNotionStudyCsv(StudyRecordsSnapshot(epub, List(1000) { StudyNoteRecord("n") }))).size)
        assertFailure(StudyExportFailure.TOO_LARGE) { StudyRecordsSnapshot(epub, List(1001) { StudyNoteRecord("n") }) }
        val bound = BoundedStudyText(9); bound.append("雪", "\"🌿\"")
        assertEquals(9, bound.bytes().size)
        assertFailure(StudyExportFailure.TOO_LARGE) { bound.append("x") }
    }

    @Test fun exporterUsesTypedSnapshotUnderMutexRendersBeforeProviderAndKeepsActualSavedCounts() = runBlocking {
        val mutex = Mutex()
        val records = StudyRecordsSnapshot(epub, words = listOf(StudyWordRecord("leaf", "yaprak")),
            passages = listOf(StudyPassageRecord("quote", anchor(), EpubHighlightColor.YELLOW, "note")))
        val docs = Documents(onOpen = { assertTrue(mutex.tryLock()); mutex.unlock() })
        assertEquals(StudyExportResult.Exported(1, 1, 1), exporter(source { assertTrue(mutex.isLocked); records }, docs, mutex)
            .export("book", sha, uri, StudyExportFormat.NOTION_CSV))
        assertArrayEquals(renderNotionStudyCsv(records), docs.bytes.toByteArray())
        assertEquals(1, docs.opens); assertEquals(1, docs.closes); assertEquals(0, docs.deletes)
        var legacyReads = 0
        val legacy = BookStudySource { _, _ -> legacyReads++; error("TXT cannot represent typed rows") }
        val unsupported = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.UNSUPPORTED_FORMAT), exporter(legacy, unsupported)
            .export("book", sha, uri, StudyExportFormat.NOTION_CSV))
        assertEquals(0, legacyReads); assertEquals(0, unsupported.opens); assertEquals(1, unsupported.deletes)
    }

    @Test fun invalidOrOversizedSourcesNeverOpenAndProviderFailuresCloseAndDiscloseUnconfirmedCleanup() = runBlocking {
        val invalid = listOf(
            StudyRecordsSnapshot(epub) to StudyExportFailure.NO_RECORDS,
            StudyRecordsSnapshot(epub, listOf(StudyNoteRecord("bad\u0000"))) to StudyExportFailure.IO,
            StudyRecordsSnapshot(epub.copy(title = "\"".repeat(1000), authors = "\"".repeat(2000)),
                List(1000) { StudyNoteRecord("n") }) to StudyExportFailure.TOO_LARGE)
        for ((records, failure) in invalid) {
            val docs = Documents()
            assertEquals(StudyExportResult.Failed(failure), exporter(source { records }, docs)
                .export("book", sha, uri, StudyExportFormat.NOTION_CSV))
            assertEquals(0, docs.opens); assertEquals(1, docs.deletes)
        }
        for (failure in listOf("open", "write", "close")) {
            val docs = Documents(failure = failure, deleteConfirmed = false)
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO, partialFileMayRemain = true),
                exporter(source { StudyRecordsSnapshot(epub, listOf(StudyNoteRecord("note"))) }, docs)
                    .export("book", sha, uri, StudyExportFormat.NOTION_CSV))
            assertEquals(1, docs.opens); assertEquals(if (failure == "open") 0 else 1, docs.closes)
            assertEquals(1, docs.deletes)
        }
    }

    @Test fun cancellationDuringTypedCaptureOrProviderWriteCleansAcceptedTargetAndCannotReportSuccess() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val docs = Documents()
        val capture = launch {
            exporter(source { started.complete(Unit); withContext(NonCancellable) { finish.await() }
                StudyRecordsSnapshot(epub, listOf(StudyNoteRecord("late"))) }, docs)
                .export("book", sha, uri, StudyExportFormat.NOTION_CSV)
            fail("Cancelled capture cannot succeed")
        }
        started.await(); capture.cancel(); finish.complete(Unit); capture.join()
        assertEquals(0, docs.opens); assertEquals(1, docs.deletes)
        val writing = Documents(failure = "cancel")
        val write = async {
            exporter(source { StudyRecordsSnapshot(epub, listOf(StudyNoteRecord("note"))) }, writing)
                .export("book", sha, uri, StudyExportFormat.NOTION_CSV)
        }
        assertTrue(runCatching { write.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, writing.opens); assertEquals(1, writing.closes); assertEquals(1, writing.deletes)
    }

    @Test fun actualRoomExportsOnlyCurrentHashAndSafeSourcesWithoutChangingReadingOrCompletionState() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), LibraryDatabase::class.java).build()
        val book = BookEntity("book", epub.title, epub.authors, "EPUB", "original.epub", "book.epub", 100, sha, 1, null)
        try {
            db.books().insert(book)
            db.bookNotes().add(BookNoteEntity("note", book.id, sha, "  own\n🌿  ", 1))
            db.savedWords().add(SavedWordEntity("word", book.id, sha, "leaf", "yaprak", 2))
            db.bookNotes().add(BookNoteEntity("stale", book.id, "c".repeat(64), "NOT CURRENT", 3))
            db.epubPassages().add(EpubPassageEntity("passage", book.id, sha, 1, "OPS/first.xhtml", "b".repeat(64),
                0, 5, "quote", 4, "PINK", "linked", 2))
            db.reading().save(ReadingProgressEntity(book.id, sha, 1, "saved locator", "OPS/first.xhtml", 3))
            val resume = db.reading().resume(book.id, sha)
            val before = bookState(db)
            val docs = Documents()
            assertEquals(StudyExportResult.Exported(2, 1, 1), exporter(RoomBookStudySource(db), docs)
                .export(book.id, sha, uri, StudyExportFormat.NOTION_CSV))
            val data = rows(docs.bytes.toByteArray()).drop(1)
            assertEquals(setOf("  own\n🌿  ", "leaf", "quote"), data.map { it[5] }.toSet())
            assertTrue(data.all { it[1] == epub.title && it[2] == epub.authors })
            assertFalse(docs.bytes.toString("UTF-8").contains("NOT CURRENT")); assertEquals(before, bookState(db))
            assertEquals(resume, db.reading().resume(book.id, sha))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM book_completion_events").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(0L, cursor.getLong(0))
            }
            db.openHelper.writableDatabase.execSQL("UPDATE epub_passages SET resourceHref = ?", arrayOf("../unsafe.xhtml"))
            val corrupt = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), exporter(RoomBookStudySource(db), corrupt)
                .export(book.id, sha, uri, StudyExportFormat.NOTION_CSV)); assertEquals(0, corrupt.opens)
            db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ?", arrayOf("d".repeat(64)))
            val changed = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.CHANGED_BOOK), exporter(RoomBookStudySource(db), changed)
                .export(book.id, sha, uri, StudyExportFormat.NOTION_CSV)); assertEquals(0, changed.opens)
        } finally { db.close() }
    }

    private fun bookState(db: LibraryDatabase): List<String?> = db.openHelper.readableDatabase.query("SELECT * FROM books").use { cursor ->
        assertTrue(cursor.moveToFirst()); (0 until cursor.columnCount).map { cursor.getString(it) }
    }
    private fun source(read: suspend () -> StudyRecordsSnapshot) = object : BookStudySource {
        override suspend fun snapshot(bookId: String, contentSha256: String): BookStudySnapshot = error("Never parse TXT")
        override suspend fun snapshotRecords(bookId: String, contentSha256: String) = read()
    }
    private fun exporter(source: BookStudySource, docs: Documents, mutex: Mutex = Mutex()) =
        DefaultBookStudyExporter(source, docs, Dispatchers.Unconfined, mutex)
    private suspend fun assertFailure(expected: StudyExportFailure, block: suspend () -> Any) {
        val error = runCatching { block() }.exceptionOrNull(); assertTrue(error.toString(), error is StudyExportException)
        assertEquals(expected, (error as StudyExportException).reason)
    }
    private inner class Documents(val failure: String? = null, val deleteConfirmed: Boolean = true,
        val onOpen: () -> Unit = {}) : ExportDocumentProvider {
        val bytes = ByteArrayOutputStream(); var opens = 0; var closes = 0; var deletes = 0
        override fun accepts(uri: String) = uri == this@NotionStudyCsvTest.uri
        override fun open(uri: String): OutputStream {
            opens++; onOpen(); if (failure == "open") throw IOException("Open failed")
            return object : OutputStream() {
                override fun write(value: Int) { checkWrite(); bytes.write(value) }
                override fun write(buffer: ByteArray, offset: Int, length: Int) { checkWrite(); bytes.write(buffer, offset, length) }
                private fun checkWrite() {
                    if (failure == "write") throw IOException("Write failed")
                    if (failure == "cancel") throw CancellationException("Cancelled write")
                }
                override fun close() { closes++; if (failure == "close") throw IOException("Close failed") }
            }
        }
        override fun delete(uri: String): Boolean { deletes++; return deleteConfirmed }
    }
    /** Independent quoted CSV parser accepting commas, doubled quotes and literal CR/LF inside fields. */
    private fun rows(bytes: ByteArray): List<List<String>> {
        val text = bytes.toString(Charsets.UTF_8); var offset = 0; val rows = mutableListOf<List<String>>()
        while (offset < text.length) {
            val fields = mutableListOf<String>()
            while (true) {
                assertEquals('"', text[offset++]); val field = StringBuilder(); var closed = false
                while (offset < text.length) {
                    val char = text[offset++]
                    if (char != '"') field.append(char)
                    else if (offset < text.length && text[offset] == '"') { field.append('"'); offset++ }
                    else { closed = true; break }
                }
                assertTrue("Field must close", closed); fields.add(field.toString())
                when (text[offset++]) {
                    ',' -> continue
                    '\r' -> { assertEquals('\n', text[offset++]); rows.add(fields); break }
                    else -> fail("Unexpected delimiter")
                }
            }
        }
        assertTrue(rows.all { it.size == 11 }); return rows
    }
}

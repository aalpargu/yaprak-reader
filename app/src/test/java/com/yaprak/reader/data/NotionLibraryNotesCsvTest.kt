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
class NotionLibraryNotesCsvTest {
    private val uri = "content://notes/csv"
    private val sha = "a".repeat(64)
    private fun note(id: String = "private-note", text: String = "  Not,\t\"🌿\"\r\n雪\n  ") =
        LibraryNote(id, "private-book", sha, "=Kitap,\"🌿\"", "Çağrı\r\nŞen", BookFormat.EPUB, text, 1)
    private fun plain(id: String = "private-note", text: String = note().text) = LibraryNoteExportEntry(note(id, text), null)
    private val quote = "exact,\t\"quote\"\r\nGüneş 🌿"
    private fun epub(color: EpubHighlightColor = EpubHighlightColor.PINK) = LibraryNoteExportEntry(
        note("private-epub").copy(source = LibraryNoteSource.EPUB_PASSAGE, sourceText = quote,
            sourceAnchor = SourceTextAnchor("OPS/first.xhtml", 0, quote.length, "b".repeat(64)), highlightColor = color), null)
    private fun pdf(color: PdfHighlightColor = PdfHighlightColor.BLUE) = LibraryNoteExportEntry(
        note("private-pdf").copy(bookFormat = BookFormat.PDF, pdfPageIndex = 3, source = LibraryNoteSource.PDF_ANNOTATION,
            sourceText = quote, pdfSourceAnchor = PdfSourceAnchor(3, 300, 600, PdfPagePoint(60,100), PdfPagePoint(180,100),
                listOf(PdfTextBounds(60.0,90.0,180.0,110.0))), pdfHighlightColor = color), 10)

    @Test fun mixedActualNotesKeepOneRowPerNoteAndLiteralMetadataQuotesPagesAndAttachedText() {
        val records = listOf(plain(), epub(), pdf(),
            plain("txt").copy(note = note("txt").copy(bookFormat = BookFormat.TXT, bookAuthors = null)),
            plain("comic").copy(note = note("comic").copy(bookFormat = BookFormat.CBZ), bookPageCount = 20),
            plain("page").copy(note = note("page").copy(bookFormat = BookFormat.PDF, pdfPageIndex = 9), bookPageCount = 10))
        val bytes = renderNotionLibraryNotesCsv(LibraryNotesExportSnapshot("istanbul", records)); val rows = rows(bytes)
        assertEquals(listOf("Ad","Kitap","Yazar","Biçim","Kayıt türü","Metin","Anlam","Bağlı not","Kaynak","PDF sayfası","Vurgu rengi"), rows.first())
        val data = rows.drop(1); assertEquals(6, data.size)
        assertEquals(records.map { it.note.bookTitle }, data.map { it[1] })
        assertEquals(records.map { it.note.bookAuthors ?: "" }, data.map { it[2] })
        assertEquals(listOf(note().text,quote,quote,note().text,note().text,note().text), data.map { it[5] })
        assertEquals(listOf("",note().text,note().text,"","",""), data.map { it[7] })
        assertEquals(listOf("","OPS/first.xhtml","PDF sayfası 4","","","PDF sayfası 10"), data.map { it[8] })
        assertEquals(listOf("","","4","","","10"), data.map { it[9] })
        assertEquals(listOf("","Pembe","Mavi","","",""), data.map { it[10] })
        assertTrue(data.all { it[6].isEmpty() }); assertFalse(bytes.take(3) == listOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte()))
        val raw = bytes.toString(Charsets.UTF_8)
        for (private in listOf("private-note","private-book",sha,"b".repeat(64),"pageWidth","createdAt")) assertFalse(raw.contains(private))
    }

    @Test fun allSavedColorsRemainLiteralForBothAttachedSourceKinds() {
        val expected = listOf("Sarı","Yeşil","Mavi","Pembe")
        assertEquals(expected, EpubHighlightColor.entries.map { rows(renderNotionLibraryNotesCsv(
            LibraryNotesExportSnapshot("", listOf(epub(it))))).last()[10] })
        assertEquals(expected, PdfHighlightColor.entries.map { rows(renderNotionLibraryNotesCsv(
            LibraryNotesExportSnapshot("", listOf(pdf(it))))).last()[10] })
    }

    @Test fun fullThousandRowsAndSourceBoundsRejectAnyTruncatedMalformedOrDuplicateInput() = runBlocking {
        val records = List(1000) { plain("note-$it", "value $it") }
        val full = rows(renderNotionLibraryNotesCsv(LibraryNotesExportSnapshot("", records)))
        assertEquals(1001, full.size); assertEquals("value 999", full.last()[5])
        bad(StudyExportFailure.TOO_LARGE) { LibraryNotesExportSnapshot("", records + plain("extra")) }
        bad(StudyExportFailure.TOO_LARGE) { renderNotionLibraryNotesCsv(LibraryNotesExportSnapshot("",
            List(600) { plain("big-$it", "雪".repeat(1000)) })) }
        for (entries in listOf(listOf(plain(text = "bad\u0000")),listOf(plain(text = "bad\uD800")),listOf(plain(),plain()),
            listOf(pdf().copy(bookPageCount = 3)), listOf(epub().copy(note = epub().note.copy(bookFormat = BookFormat.TXT))))) {
            bad(StudyExportFailure.IO) { renderNotionLibraryNotesCsv(LibraryNotesExportSnapshot("",entries)) }
        }
        bad(StudyExportFailure.NO_RECORDS) { renderNotionLibraryNotesCsv(LibraryNotesExportSnapshot("", emptyList())) }
    }

    @Test fun freshQuerySnapshotFinishesItsMutexBeforeCsvRenderingAndProviderOpen() = runBlocking {
        val mutex = Mutex(); var captures = 0
        val snapshot = LibraryNotesExportSnapshot("istanbul", listOf(plain(),epub(),pdf()))
        val source = LibraryNotesExportSource { query ->
            captures++; assertEquals(snapshot.query,query); mutex.lock()
            try { snapshot } finally { mutex.unlock() }
        }
        val docs = Documents(onOpen = { assertTrue(mutex.tryLock()); mutex.unlock() })
        assertEquals(StudyExportResult.Exported(3,0,0), exporter(source,docs).export(snapshot.query,uri,LibraryNotesExportFormat.NOTION_CSV))
        assertArrayEquals(renderNotionLibraryNotesCsv(snapshot),docs.bytes.toByteArray())
        assertEquals(1,captures); assertEquals(1,docs.opens); assertEquals(1,docs.closes); assertEquals(0,docs.deletes)
        bad(StudyExportFailure.UNSUPPORTED_FORMAT) { renderLibraryNotesExport(snapshot,LibraryNotesExportFormat.NOTION_CSV) }
    }

    @Test fun wrongQueryInvalidSourceAndUnacceptedTargetNeverOpenCsvProvider() = runBlocking {
        for (snapshot in listOf(LibraryNotesExportSnapshot("other",listOf(plain())),
            LibraryNotesExportSnapshot("",listOf(plain(text = "bad\u0000"))), LibraryNotesExportSnapshot("",emptyList()))) {
            val docs = Documents()
            assertTrue(exporter(LibraryNotesExportSource { snapshot },docs).export("",uri,LibraryNotesExportFormat.NOTION_CSV) is StudyExportResult.Failed)
            assertEquals(0,docs.opens); assertEquals(1,docs.deletes)
        }
        val docs = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.ACCESS),exporter(LibraryNotesExportSource { error("No source") },docs)
            .export("","file:///unaccepted",LibraryNotesExportFormat.NOTION_CSV))
        assertEquals(0,docs.opens); assertEquals(0,docs.deletes)
    }

    @Test fun providerFailuresAndRealCancellationCloseAndCleanOnlyTheAcceptedTarget() = runBlocking {
        val source = LibraryNotesExportSource { LibraryNotesExportSnapshot(it,listOf(plain())) }
        for (failure in listOf("open","write","close")) {
            val docs = Documents(failure,deleteConfirmed = false)
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO,partialFileMayRemain = true),
                exporter(source,docs).export("",uri,LibraryNotesExportFormat.NOTION_CSV))
            assertEquals(1,docs.opens); assertEquals(if (failure == "open") 0 else 1,docs.closes); assertEquals(1,docs.deletes)
        }
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val docs = Documents()
        val operation = launch {
            exporter(LibraryNotesExportSource { query -> entered.complete(Unit); withContext(NonCancellable) { finish.await() }
                LibraryNotesExportSnapshot(query,listOf(plain())) },docs).export("",uri,LibraryNotesExportFormat.NOTION_CSV)
            fail("Cancelled capture cannot succeed")
        }
        entered.await(); operation.cancel(); finish.complete(Unit); operation.join()
        assertEquals(0,docs.opens); assertEquals(1,docs.deletes)
        val writing = Documents("cancel")
        val write = async { exporter(source,writing).export("",uri,LibraryNotesExportFormat.NOTION_CSV) }
        assertTrue(runCatching { write.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1,writing.opens); assertEquals(1,writing.closes); assertEquals(1,writing.deletes)
    }

    @Test fun actualRoomWholeQueryExportsBeyondNewest200AndCurrentMetadataWithoutReadingWrites() = runBlocking {
        database { db ->
            val book = BookEntity("book","İstanbul","Deniz","EPUB","original.epub","book.epub",100,sha,1,null)
            db.books().insert(book)
            db.withTransaction { repeat(230) { db.bookNotes().add(BookNoteEntity("note-$it",book.id,sha,"needle $it",it.toLong())) } }
            db.bookNotes().add(BookNoteEntity("stale",book.id,"c".repeat(64),"NOT CURRENT needle",999))
            db.savedWords().add(SavedWordEntity("word",book.id,sha,"leaf","yaprak",1000))
            db.reading().save(ReadingProgressEntity(book.id,sha,1,"saved locator","OPS/first.xhtml",7))
            val resume = db.reading().resume(book.id,sha)
            val source = RoomLibraryNotesRepository(db); val ui = source.observe("needle").first()
            assertEquals(230,ui.totalCount); assertEquals(200,ui.entries.size)
            val docs = Documents()
            assertEquals(StudyExportResult.Exported(230,0,0),exporter(source,docs).export("needle",uri,LibraryNotesExportFormat.NOTION_CSV))
            val data = rows(docs.bytes.toByteArray()).drop(1)
            assertEquals(230,data.size); assertEquals((0 until 230).map { "needle $it" }.toSet(),data.map { it[5] }.toSet())
            assertFalse(docs.bytes.toString("UTF-8").contains("NOT CURRENT"))
            assertEquals(resume,db.reading().resume(book.id,sha)); assertTrue(db.reading().observed(book.id,sha).isEmpty())
            for (table in listOf("reading_duration_receipts","book_completion_events"))
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0L,it.getLong(0)) }
            db.openHelper.writableDatabase.execSQL("UPDATE books SET title = ?",arrayOf("Güncel"))
            val current = Documents()
            assertEquals(StudyExportResult.Exported(230,0,0),exporter(source,current).export("güncel",uri,LibraryNotesExportFormat.NOTION_CSV))
            assertTrue(rows(current.bytes.toByteArray()).drop(1).all { it[1] == "Güncel" })
        }
    }

    @Test fun actualRoomCorruptSourceCannotProducePartialCsvAndClosedDatabaseCannotLookEmpty() = runBlocking {
        database { db ->
            db.books().insert(BookEntity("book","Book",null,"EPUB","original.epub","book.epub",100,sha,1,null))
            db.bookNotes().add(BookNoteEntity("note","book",sha,"valid",1))
            db.epubPassages().add(EpubPassageEntity("linked","book",sha,1,"OPS/first.xhtml","b".repeat(64),0,5,"quote",2,"PINK","attached",2))
            val pdfBook = BookEntity("pdf", "PDF book", null, "PDF", "original.pdf", "book.pdf", 100, "d".repeat(64), 1, 10)
            db.books().insert(pdfBook)
            db.bookNotes().add(BookNoteEntity("page", pdfBook.id, pdfBook.sha256, "page note", 3, 9))
            RoomPdfAnnotationRepository(db, PdfAnnotationOriginalSource { _, _, _ -> }, Mutex())
                .create(pdfBook.id, pdfBook.sha256, quote, requireNotNull(pdf().note.pdfSourceAnchor), PdfHighlightColor.GREEN, "PDF attached")
            val source = RoomLibraryNotesRepository(db); val docs = Documents()
            assertEquals(StudyExportResult.Exported(4,0,0),exporter(source,docs).export("",uri,LibraryNotesExportFormat.NOTION_CSV))
            val data = rows(docs.bytes.toByteArray()).drop(1)
            assertEquals(setOf("valid","quote",quote,"page note"),data.map { it[5] }.toSet())
            assertEquals("4", data.single { it[7] == "PDF attached" }[9])
            assertEquals("Yeşil", data.single { it[7] == "PDF attached" }[10])
            assertEquals("10", data.single { it[5] == "page note" }[9])
            db.openHelper.writableDatabase.execSQL("UPDATE epub_passages SET resourceHref = ?",arrayOf("../bad.xhtml"))
            val invalid = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO),exporter(source,invalid).export("",uri,LibraryNotesExportFormat.NOTION_CSV))
            assertEquals(0,invalid.opens)
            db.close(); val closed = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO),exporter(source,closed).export("",uri,LibraryNotesExportFormat.NOTION_CSV))
            assertEquals(0,closed.opens)
        }
    }

    private fun exporter(source: LibraryNotesExportSource, docs: Documents) = DefaultLibraryNotesExporter(source,docs,Dispatchers.Unconfined)
    private suspend fun database(block: suspend (LibraryDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(),LibraryDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }
    private suspend fun bad(reason: StudyExportFailure, block: suspend () -> Any) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertEquals(failure.toString(),reason,when(failure) { is StudyExportException -> failure.reason; is LibraryNotesExportException -> failure.reason; else -> null })
    }
    private inner class Documents(val failure: String? = null,val deleteConfirmed: Boolean = true,val onOpen: () -> Unit = {}) : ExportDocumentProvider {
        val bytes = ByteArrayOutputStream(); var opens = 0; var closes = 0; var deletes = 0
        override fun accepts(uri: String) = uri == this@NotionLibraryNotesCsvTest.uri
        override fun open(uri: String): OutputStream {
            opens++; onOpen(); if (failure == "open") throw IOException("Open failed")
            return object : OutputStream() {
                override fun write(value: Int) { checkWrite(); bytes.write(value) }
                override fun write(buffer: ByteArray,offset: Int,length: Int) { checkWrite(); bytes.write(buffer,offset,length) }
                private fun checkWrite() { if (failure == "write") throw IOException("Write failed"); if (failure == "cancel") throw CancellationException() }
                override fun close() { closes++; if (failure == "close") throw IOException("Close failed") }
            }
        }
        override fun delete(uri: String): Boolean { deletes++; return deleteConfirmed }
    }
    /** Independent parser. Embedded CR/LF, commas and doubled quotes are decoded literally. */
    private fun rows(bytes: ByteArray): List<List<String>> {
        val text = bytes.toString(Charsets.UTF_8); var offset = 0; val rows = mutableListOf<List<String>>()
        while(offset < text.length) {
            val fields = mutableListOf<String>()
            while(true) {
                assertEquals('"',text[offset++]); val field = StringBuilder(); var closed = false
                while(offset < text.length) {
                    val char = text[offset++]
                    if(char != '"') field.append(char)
                    else if(offset < text.length && text[offset] == '"') { field.append('"'); offset++ }
                    else { closed = true; break }
                }
                assertTrue(closed); fields.add(field.toString())
                when(text[offset++]) { ',' -> continue; '\r' -> { assertEquals('\n',text[offset++]); rows.add(fields); break }; else -> fail("Delimiter") }
            }
        }
        assertTrue(rows.all { it.size == 11 }); return rows
    }
}

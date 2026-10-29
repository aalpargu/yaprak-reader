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
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnkiLibraryNotesTest {
    private val uri = "content://notes/anki"
    private val sha = "a".repeat(64)
    private val quote = "exact,\t\"quote\"\r\nGüneş 🌿"
    private fun note(id: String = "private-note", text: String = "  Not,\t\"🌿\"\r\n雪\n  ") =
        LibraryNote(id, "private-book", sha, "=Kitap,\"🌿\"", "Çağrı\r\nŞen", BookFormat.EPUB, text, 1)
    private fun plain(id: String = "private-note", text: String = note().text) = LibraryNoteExportEntry(note(id, text), null)
    private fun epub(color: EpubHighlightColor = EpubHighlightColor.PINK) = LibraryNoteExportEntry(
        note("private-epub").copy(source = LibraryNoteSource.EPUB_PASSAGE, sourceText = quote,
            sourceAnchor = SourceTextAnchor("OPS/first.xhtml", 0, quote.length, "b".repeat(64)), highlightColor = color), null)
    private fun pdf(color: PdfHighlightColor = PdfHighlightColor.BLUE) = LibraryNoteExportEntry(
        note("private-pdf").copy(bookFormat = BookFormat.PDF, pdfPageIndex = 3, source = LibraryNoteSource.PDF_ANNOTATION,
            sourceText = quote, pdfSourceAnchor = PdfSourceAnchor(3, 300, 600, PdfPagePoint(60,100), PdfPagePoint(180,100),
                listOf(PdfTextBounds(60.0,90.0,180.0,110.0))), pdfHighlightColor = color), 10)
    private val header = "#separator:Tab\n#html:false\n#columns:Front\tBack\n"

    @Test fun mixedNotesKeepTheirActualFrontBackMetadataAndPageWithoutLeakingPrivateRecords() = runBlocking {
        val records = listOf(plain(), epub(), pdf(),
            plain("txt").copy(note = note("txt").copy(bookFormat = BookFormat.TXT, bookAuthors = null)),
            plain("comic").copy(note = note("comic").copy(bookFormat = BookFormat.CBZ), bookPageCount = 20),
            plain("page").copy(note = note("page").copy(bookFormat = BookFormat.PDF, pdfPageIndex = 9), bookPageCount = 10))
        val bytes = renderAnkiLibraryNotes(LibraryNotesExportSnapshot("istanbul", records))
        val data = rows(bytes); assertEquals(6, data.size)
        assertEquals(listOf(note().text,quote,quote,note().text,note().text,note().text), data.map { it[0] })
        assertEquals("${note().text}\n\nKitap: ${note().bookTitle}\nYazar: ${note().bookAuthors}\nBiçim: EPUB\nKaynak EPUB dosyası: OPS/first.xhtml\nVurgu rengi: Pembe", data[1][1])
        assertTrue(data[2][1].startsWith(note().text + "\n\n")); assertTrue(data[2][1].endsWith("PDF sayfası: 4\nVurgu rengi: Mavi"))
        assertTrue(data[3][1].contains("Yazar: Bilinmiyor\nBiçim: TXT"))
        assertFalse(data[4][1].contains("PDF sayfası")); assertTrue(data[5][1].endsWith("PDF sayfası: 10"))
        val raw = bytes.toString(Charsets.UTF_8)
        for (private in listOf("private-note","private-book",sha,"b".repeat(64),"pageWidth","createdAt")) assertFalse(raw.contains(private))
        assertFalse(bytes.take(3) == listOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte()))
    }

    @Test fun allFourColorsStayWithTheStoredSourceQuoteAndItsOwnAttachedAnswer() = runBlocking {
        val expected = listOf("Sarı","Yeşil","Mavi","Pembe")
        for ((index, color) in EpubHighlightColor.entries.withIndex()) {
            val row = rows(renderAnkiLibraryNotes(LibraryNotesExportSnapshot("", listOf(epub(color))))).single()
            assertEquals(quote, row[0]); assertTrue(row[1].startsWith(note().text + "\n\n"))
            assertTrue(row[1].endsWith("Vurgu rengi: ${expected[index]}"))
        }
        for ((index, color) in PdfHighlightColor.entries.withIndex()) {
            val row = rows(renderAnkiLibraryNotes(LibraryNotesExportSnapshot("", listOf(pdf(color))))).single()
            assertEquals(quote, row[0]); assertTrue(row[1].contains("PDF sayfası: 4"))
            assertTrue(row[1].endsWith("Vurgu rengi: ${expected[index]}"))
        }
    }

    @Test fun quotedMultilineTabsUnicodeAndLiteralHtmlMediaOrHeaderTextRoundTripAsTwoFields() = runBlocking {
        val texts = listOf("#separator:Comma\r\n#html:true", "<script>\"x\"</script> & <img src=\"x\"> [sound:x.mp3]",
            "\t\"quoted\"\r\n雪🌿İstanbul\n last  ")
        val data = rows(renderAnkiLibraryNotes(LibraryNotesExportSnapshot("", texts.mapIndexed { i, text -> plain("note-$i", text) })))
        assertEquals(texts, data.map { it[0] }); assertTrue(data.all { it.size == 2 })
    }

    @Test fun fullThousandRecordsAreNotUiTruncatedAndInvalidOrOversizedSourcesRefuseTheWholeFile() = runBlocking {
        val entries = List(1000) { plain("note-$it", "value $it") }
        val data = rows(renderAnkiLibraryNotes(LibraryNotesExportSnapshot("", entries)))
        assertEquals(1000, data.size); assertEquals("value 999", data.last()[0])
        bad(StudyExportFailure.TOO_LARGE) { LibraryNotesExportSnapshot("", entries + plain("extra")) }
        bad(StudyExportFailure.TOO_LARGE) { renderAnkiLibraryNotes(LibraryNotesExportSnapshot("", List(600) { plain("big-$it", "雪".repeat(1000)) })) }
        for (invalid in listOf(listOf(plain(text = "bad\u0000")), listOf(plain(text = "bad\uD800")), listOf(plain(),plain()),
            listOf(pdf().copy(bookPageCount = 3)), listOf(epub().copy(note = epub().note.copy(bookFormat = BookFormat.PDF))))) {
            bad(StudyExportFailure.IO) { renderAnkiLibraryNotes(LibraryNotesExportSnapshot("", invalid)) }
        }
        bad(StudyExportFailure.NO_RECORDS) { renderAnkiLibraryNotes(LibraryNotesExportSnapshot("", emptyList())) }
    }

    @Test fun freshExactQueryCaptureReleasesItsMutexBeforeProviderIoAndWrongQueryCannotWrite() = runBlocking {
        val mutex = Mutex(); var captures = 0
        val source = LibraryNotesExportSource { query -> mutex.withLock { captures++; LibraryNotesExportSnapshot(query,listOf(plain(),epub(),pdf())) } }
        val docs = Documents(onOpen = { assertTrue(mutex.tryLock()); mutex.unlock() })
        assertEquals(StudyExportResult.Exported(3,0,0), exporter(source,docs).export("istanbul",uri,LibraryNotesExportFormat.ANKI_TSV))
        assertEquals(1,captures); assertEquals(3, rows(docs.bytes.toByteArray()).size); assertEquals(1,docs.closes)
        val wrong = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), exporter(LibraryNotesExportSource {
            LibraryNotesExportSnapshot("different", listOf(plain())) },wrong).export("istanbul",uri,LibraryNotesExportFormat.ANKI_TSV))
        assertEquals(0,wrong.opens); assertEquals(1,wrong.deletes)
    }

    @Test fun providerFailuresAndCaptureOrWriteCancellationCleanOnlyTheAcceptedTarget() = runBlocking {
        val source = LibraryNotesExportSource { query -> LibraryNotesExportSnapshot(query,listOf(plain())) }
        for (failure in listOf("open","write","close")) {
            val docs = Documents(failure, deleteConfirmed = false)
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO, partialFileMayRemain = true),
                exporter(source,docs).export("",uri,LibraryNotesExportFormat.ANKI_TSV))
            assertEquals(1,docs.opens); assertEquals(if (failure == "open") 0 else 1, docs.closes); assertEquals(1,docs.deletes)
        }
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val docs = Documents()
        val operation = launch {
            exporter(LibraryNotesExportSource { query -> entered.complete(Unit); withContext(NonCancellable) { finish.await() }
                LibraryNotesExportSnapshot(query,listOf(plain())) },docs).export("",uri,LibraryNotesExportFormat.ANKI_TSV)
            fail("Cancelled capture cannot succeed")
        }
        entered.await(); operation.cancel(); finish.complete(Unit); operation.join()
        assertEquals(0,docs.opens); assertEquals(1,docs.deletes)
        val writing = Documents("cancel")
        val write = async { exporter(source,writing).export("",uri,LibraryNotesExportFormat.ANKI_TSV) }
        assertTrue(runCatching { write.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1,writing.closes); assertEquals(1,writing.deletes)
        val inaccessible = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.ACCESS), exporter(source,inaccessible).export("","https://wrong",LibraryNotesExportFormat.ANKI_TSV))
        assertEquals(0,inaccessible.opens); assertEquals(0,inaccessible.deletes)
    }

    @Test fun actualRoomExportsAll230QueryMatchesWithCurrentMetadataAndIgnoresStaleSourceHashes() = runBlocking {
        database { db ->
            val book = BookEntity("book","İstanbul","Deniz","EPUB","original.epub","book.epub",100,sha,1,null)
            db.books().insert(book)
            db.withTransaction { repeat(230) { db.bookNotes().add(BookNoteEntity("note-$it",book.id,sha,"needle $it",it.toLong())) } }
            db.bookNotes().add(BookNoteEntity("stale",book.id,"c".repeat(64),"NOT CURRENT needle",999))
            val source = RoomLibraryNotesRepository(db)
            assertEquals(200, source.observe("needle").first().entries.size)
            db.openHelper.writableDatabase.execSQL("UPDATE books SET title = 'New title', authors = 'New author'")
            val docs = Documents()
            assertEquals(StudyExportResult.Exported(230,0,0), exporter(source,docs).export("needle",uri,LibraryNotesExportFormat.ANKI_TSV))
            val data = rows(docs.bytes.toByteArray()); assertEquals(230,data.size)
            assertEquals((0 until 230).map { "needle $it" }.toSet(), data.map { it[0] }.toSet())
            assertTrue(data.all { it[1].contains("Kitap: New title\nYazar: New author") })
            assertNull(db.reading().resume(book.id,sha)); assertTrue(db.reading().observed(book.id,sha).isEmpty())
        }
    }

    @Test fun currentRoomAttachedEpubAndPdfNotesKeepTheirRealSourcesAndCorruptionCannotYieldPartialAnkiText() = runBlocking {
        database { db ->
            db.books().insert(BookEntity("book","Book",null,"EPUB","original.epub","book.epub",100,sha,1,null))
            db.bookNotes().add(BookNoteEntity("note","book",sha,"valid",1))
            db.epubPassages().add(EpubPassageEntity("linked","book",sha,1,"OPS/first.xhtml","b".repeat(64),0,5,"quote",2,"PINK","attached",2))
            val pdfBook = BookEntity("pdf", "PDF book", null, "PDF", "original.pdf", "pdf.pdf", 100, "d".repeat(64), 1, 10)
            db.books().insert(pdfBook)
            RoomPdfAnnotationRepository(db, PdfAnnotationOriginalSource { _, _, _ -> }, Mutex())
                .create(pdfBook.id,pdfBook.sha256,quote, requireNotNull(pdf().note.pdfSourceAnchor),PdfHighlightColor.GREEN,"PDF attached")
            val source = RoomLibraryNotesRepository(db); val docs = Documents()
            assertEquals(StudyExportResult.Exported(3,0,0), exporter(source,docs).export("",uri,LibraryNotesExportFormat.ANKI_TSV))
            val data = rows(docs.bytes.toByteArray())
            assertEquals(setOf("valid","quote",quote),data.map { it[0] }.toSet())
            assertTrue(data.single { it[0] == quote }[1].endsWith("PDF sayfası: 4\nVurgu rengi: Yeşil"))
            db.openHelper.writableDatabase.execSQL("UPDATE epub_passages SET resourceHref = ?",arrayOf("../bad.xhtml"))
            val invalid = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), exporter(source,invalid).export("",uri,LibraryNotesExportFormat.ANKI_TSV))
            assertEquals(0,invalid.opens)
            db.close(); val closed = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), exporter(source,closed).export("",uri,LibraryNotesExportFormat.ANKI_TSV))
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
        assertEquals(failure.toString(), reason, when(failure) { is StudyExportException -> failure.reason; is LibraryNotesExportException -> failure.reason; else -> null })
    }
    private inner class Documents(val failure: String? = null,val deleteConfirmed: Boolean = true,val onOpen: () -> Unit = {}) : ExportDocumentProvider {
        val bytes = ByteArrayOutputStream(); var opens = 0; var closes = 0; var deletes = 0
        override fun accepts(uri: String) = uri == this@AnkiLibraryNotesTest.uri
        override fun open(uri: String): OutputStream {
            opens++; onOpen(); if (failure == "open") throw IOException("Open failed")
            return object : OutputStream() {
                override fun write(value: Int) { checkWrite(); bytes.write(value) }
                override fun write(buffer: ByteArray,offset: Int,length: Int) { checkWrite(); bytes.write(buffer,offset,length) }
                private fun checkWrite() { if (failure == "write") throw IOException("Write failed"); if (failure == "cancel") throw CancellationException() }
                override fun close() { closes++; if (failure == "close") throw IOException("Close failed") }
            }
        }
        override fun delete(uri: String): Boolean { deletes++; if (deleteConfirmed) bytes.reset(); return deleteConfirmed }
    }
    /** Import grammar: comments then two quoted fields, escaped quotes, tabs/newlines retained inside quotes. */
    private fun rows(bytes: ByteArray): List<List<String>> {
        val text = bytes.toString(Charsets.UTF_8); assertTrue(text.startsWith(header))
        var cursor = header.length
        return buildList {
            while (cursor < text.length) {
                val row = mutableListOf<String>()
                repeat(2) { column ->
                    assertEquals('"',text[cursor++])
                    row += buildString {
                        while (true) {
                            val ch = text[cursor++]
                            if (ch == '"') {
                                if (cursor < text.length && text[cursor] == '"') { append('"'); cursor++ } else break
                            } else append(ch)
                        }
                    }
                    assertEquals(if (column == 0) '\t' else '\n',text[cursor++])
                }
                add(row)
            }
        }
    }
}

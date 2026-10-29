package com.yaprak.reader.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.notes.*
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfAnnotationStudyTest {
    private val book = Book("22222222-2222-4222-8222-222222222222", "İğne PDF", "Çağrı", BookFormat.PDF,
        "source.pdf", "source.pdf", 100, "a".repeat(64), 1, pageCount = 12)
    private val quote = "  dependency injection 🌿\nIŞIK  "
    private val note = "  gece\n```\n<img src=x> [link](https://example.invalid)\t漢字 🌿  "
    private fun anchor(page: Int = 3) = PdfSourceAnchor(page, 300, 600, PdfPagePoint(60, 100), PdfPagePoint(180, 100),
        listOf(PdfTextBounds(60.0, 90.0, 180.0, 110.0)))
    private suspend fun fixture(block: suspend (LibraryDatabase, RoomPdfAnnotationRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try { db.books().insert(book.toEntity()); block(db, RoomPdfAnnotationRepository(db, PdfAnnotationOriginalSource { _, _, _ -> }, Mutex())) }
        finally { db.close() }
    }

    @Test fun librarySearchIncludesExactAttachedPdfNoteSourceTextMetadataAndTypedIdentity() = runBlocking {
        fixture { db, repo ->
            val saved = repo.create(book.id, book.sha256, quote, anchor(), PdfHighlightColor.BLUE, note)
            repo.create(book.id, book.sha256, "plain highlight", anchor(4), PdfHighlightColor.YELLOW, null)
            val search = RoomLibraryNotesRepository(db)
            val actual = search.observe("dependency gece çağrı 🌿").first().entries.single()
            assertEquals(LibraryNoteSource.PDF_ANNOTATION, actual.source); assertEquals(saved.id, actual.id)
            assertEquals(note, actual.text); assertEquals(quote, actual.sourceText); assertEquals(anchor(), actual.pdfSourceAnchor)
            assertEquals(3, actual.pdfPageIndex); assertEquals(PdfHighlightColor.BLUE, actual.pdfHighlightColor)
            assertNull(actual.sourceAnchor); assertNull(actual.highlightColor)
            assertEquals(actual, search.find(book.id, book.sha256, saved.id, LibraryNoteSource.PDF_ANNOTATION))
            assertNull(search.find(book.id, book.sha256, saved.id, LibraryNoteSource.BOOK_NOTE))
            assertEquals(0, search.observe("plain highlight").first().totalCount)
        }
    }

    @Test fun annotationTableInvalidationRefreshesAnActiveGlobalNoteSearch() = runBlocking {
        fixture { db, repo ->
            val saved = repo.create(book.id, book.sha256, quote, anchor(), PdfHighlightColor.BLUE, "before")
            withContext(Dispatchers.IO) {
                val first = CompletableDeferred<Unit>()
                val collection = async { withTimeout(5000) {
                    RoomLibraryNotesRepository(db).observe("").onEach { first.complete(Unit) }.take(2).toList()
                } }
                first.await(); val changed = repo.update(book.id, book.sha256, saved, PdfHighlightColor.GREEN, "after")
                val entries = collection.await().map { it.entries.single() }
                assertEquals(listOf("before", "after"), entries.map { it.text })
                assertEquals(changed.revision, entries.last().sourceRevision)
            }
        }
    }

    @Test fun currentMalformedRowsNeverBecomeSuccessfulPartialSearchOrExportResults() = runBlocking {
        fixture { db, repo ->
            repo.create(book.id, book.sha256, quote, anchor(), PdfHighlightColor.GREEN, note)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE pdf_annotations SET selectionKey = ?", arrayOf("b".repeat(64)))
            assertTrue(runCatching { RoomLibraryNotesRepository(db).observe("").first() }.exceptionOrNull() is LibraryNotesSourceException)
            for (format in listOf(StudyExportFormat.TEXT, StudyExportFormat.MARKDOWN))
                assertEquals(StudyExportFailure.IO, (runCatching { RoomBookStudySource(db).snapshot(book.id, book.sha256, format) }.exceptionOrNull() as StudyExportException).reason)
            assertEquals(StudyExportFailure.IO, (runCatching { RoomBookStudySource(db).snapshotRecords(book.id, book.sha256) }.exceptionOrNull() as StudyExportException).reason)
        }
    }

    @Test fun textMarkdownAndTypedPdfSnapshotsRetainAllColorsSourcePagesAndExactUnicode() = runBlocking {
        fixture { db, repo ->
            for ((page, color) in PdfHighlightColor.entries.withIndex()) repo.create(book.id, book.sha256, quote, anchor(page), color, if(page==0) note else null)
            repo.create(book.id, book.sha256, quote, anchor(4), null, note)
            val source = RoomBookStudySource(db)
            for (format in listOf(StudyExportFormat.TEXT, StudyExportFormat.MARKDOWN)) {
                val actual = source.snapshot(book.id, book.sha256, format)
                assertEquals(2, actual.noteCount); assertEquals(5, actual.passageCount); assertEquals(0, actual.wordCount)
                val text = actual.utf8.toString(Charsets.UTF_8)
                assertEquals(5, Regex(Regex.escape(quote)).findAll(text).count())
                assertEquals(2, Regex(Regex.escape(note)).findAll(text).count())
                for (page in 1..5) assertTrue(text.contains("PDF sayfası: $page"))
                for (color in listOf("Sarı", "Yeşil", "Mavi", "Pembe")) assertTrue(text.contains("Vurgu rengi: $color"))
                if (format == StudyExportFormat.MARKDOWN) assertTrue(text.contains("````\n$note\n````"))
            }
            val records = source.snapshotRecords(book.id, book.sha256)
            assertTrue(records.passages.isEmpty()); assertEquals(5, records.pdfAnnotations.size)
            assertEquals(2, records.noteCount); assertEquals(5, records.passageCount); assertEquals(5, records.recordCount)
            assertEquals((0..4).toSet(), records.pdfAnnotations.map { it.anchor.pageIndex }.toSet())
            validateStudyRecordsSnapshot(records)
            val blocks = studyPdfBlocks(records)
            assertEquals(5, blocks.count { it.text == quote }); assertEquals(2, blocks.count { it.text == note })
            assertEquals(PdfHighlightColor.entries.toSet(), blocks.mapNotNull { it.pdfHighlightColor }.toSet())
        }
    }

    @Test fun replacingTheBookHashHidesOldPdfNotesAndExportsAsChangedBook() = runBlocking {
        fixture { db, repo ->
            repo.create(book.id, book.sha256, quote, anchor(), PdfHighlightColor.BLUE, note)
            db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ?", arrayOf("b".repeat(64)))
            assertEquals(0, RoomLibraryNotesRepository(db).observe("").first().totalCount)
            assertNull(RoomLibraryNotesRepository(db).find(book.id, book.sha256, repoId(db), LibraryNoteSource.PDF_ANNOTATION))
            val error = runCatching { RoomBookStudySource(db).snapshotRecords(book.id, book.sha256) }.exceptionOrNull() as StudyExportException
            assertEquals(StudyExportFailure.CHANGED_BOOK, error.reason)
        }
    }

    @Test fun ankiRetainsItsCurrentExplicitWordPairContractRatherThanSilentlyReportingPdfNotesExported() = runBlocking {
        fixture { db, repo ->
            repo.create(book.id, book.sha256, quote, anchor(), PdfHighlightColor.YELLOW, note)
            val failure = runCatching { RoomBookStudySource(db).snapshot(book.id, book.sha256, StudyExportFormat.ANKI_TSV) }.exceptionOrNull() as StudyExportException
            assertEquals(StudyExportFailure.NO_RECORDS, failure.reason)
        }
    }

    @Test fun detachedPdfRecordsAreImmutableAndCountEveryAnnotationAtTheSharedRecordBoundary() {
        val original = mutableListOf(StudyPdfAnnotationRecord(quote, anchor(), PdfHighlightColor.PINK, note))
        val snapshot = StudyRecordsSnapshot(StudyExportBook(book.title, book.authors, BookFormat.PDF, 12), pdfAnnotations = original)
        original.clear(); assertEquals(1, snapshot.recordCount)
        assertTrue(runCatching { (snapshot.pdfAnnotations as MutableList).clear() }.isFailure)
        val repeated = List(1001) { snapshot.pdfAnnotations.single() }
        assertEquals(StudyExportFailure.TOO_LARGE, (runCatching { StudyRecordsSnapshot(snapshot.book, pdfAnnotations = repeated) }.exceptionOrNull() as StudyExportException).reason)
        val hundredGeometryLines = PdfSourceAnchor(3,300,600,PdfPagePoint(60,100),PdfPagePoint(180,100), List(256) { PdfTextBounds(60.0,90.0,180.0,110.0) })
        val geometryHeavy = List(200) { snapshot.pdfAnnotations.single().copy(anchor=hundredGeometryLines) }
        assertEquals(StudyExportFailure.TOO_LARGE, (runCatching { validateStudyRecordsSnapshot(StudyRecordsSnapshot(snapshot.book,pdfAnnotations=geometryHeavy)) }.exceptionOrNull() as StudyExportException).reason)
    }

    @Test fun rendererBoundaryRejectsWrongFormatPagesAndEmptyAnnotationsBeforeDrawing() {
        val record = StudyPdfAnnotationRecord(quote, anchor(), PdfHighlightColor.YELLOW, note)
        val invalid = listOf(
            StudyRecordsSnapshot(StudyExportBook("EPUB",null,BookFormat.EPUB),pdfAnnotations=listOf(record)),
            StudyRecordsSnapshot(StudyExportBook("PDF",null,BookFormat.PDF,3),pdfAnnotations=listOf(record)),
            StudyRecordsSnapshot(StudyExportBook("PDF",null,BookFormat.PDF,12),pdfAnnotations=listOf(record.copy(highlightColor=null,attachedNote=null))),
            StudyRecordsSnapshot(StudyExportBook("PDF",null,BookFormat.PDF,12),pdfAnnotations=listOf(record.copy(text="bad\u0000text"))),
            StudyRecordsSnapshot(StudyExportBook("PDF",null,BookFormat.PDF,12),pdfAnnotations=listOf(record.copy(revision=-1))),
        )
        for (snapshot in invalid) assertEquals(StudyExportFailure.IO, (runCatching { validateStudyRecordsSnapshot(snapshot) }.exceptionOrNull() as StudyExportException).reason)
    }
    private fun repoId(db: LibraryDatabase) = db.openHelper.readableDatabase.query("SELECT id FROM pdf_annotations").use { it.moveToFirst(); it.getString(0) }
}

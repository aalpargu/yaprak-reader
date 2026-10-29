package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.exporting.DefaultBookStudyExporter
import com.yaprak.reader.data.exporting.ExportDocumentProvider
import com.yaprak.reader.data.exporting.RoomBookStudySource
import com.yaprak.reader.data.persistence.BookEntity
import com.yaprak.reader.data.persistence.BookNoteEntity
import com.yaprak.reader.data.persistence.EpubPassageEntity
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.SavedWordEntity
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.library.StudyExportResult
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RoomBookStudySourceTest {
    private val book = BookEntity("book", "İstanbul 🌿", "Çağrı Şen", "EPUB", "original.epub", "missing.epub",
        100, "a".repeat(64), 1, null)
    private val destination = "content://documents/study.txt"

    @Test fun `actual annotated rows export color exact linked note source and include attached note count`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book)
            db.bookNotes().add(BookNoteEntity("ordinary", book.id, book.sha256, "Ordinary", 1))
            val linked = "  ## Not\n`````\n<img src=\"https://example.test\">\n🌿  "
            db.epubPassages().add(passage("linked", "Source 🌿").copy(highlightColor = "BLUE", attachedNote = linked, revision = 9))
            db.epubPassages().add(passage("color-only", "Other quote").copy(highlightColor = "GREEN", revision = 1))
            val before = counts(db)
            for (format in listOf(StudyExportFormat.TEXT, StudyExportFormat.MARKDOWN)) {
                val source = RoomBookStudySource(db).snapshot(book.id, book.sha256, format)
                assertEquals(2, source.noteCount)
                assertEquals(2, source.passageCount)
                assertEquals(0, source.wordCount)
                val output = source.utf8.toString(Charsets.UTF_8)
                assertTrue(output.contains(linked))
                assertTrue(output.contains("OPS/linked.xhtml"))
                assertTrue(output.contains("Vurgu rengi: Mavi"))
                assertTrue(output.contains("Vurgu rengi: Yeşil"))
                assertEquals(before, counts(db))
            }
            val noWords = runCatching { RoomBookStudySource(db).snapshot(book.id, book.sha256, StudyExportFormat.ANKI_TSV) }.exceptionOrNull()
            assertTrue(noWords is com.yaprak.reader.data.exporting.StudyExportException)
            assertEquals(StudyExportFailure.NO_RECORDS, (noWords as com.yaprak.reader.data.exporting.StudyExportException).reason)
            assertEquals(linked, db.epubPassages().observe(book.id, book.sha256).first().single { it.id == "linked" }.attachedNote)
        }
    }

    @Test fun `new annotation fields reject corrupt SQL values before opening TXT or Markdown target`() = runBlocking {
        for ((field, value) in listOf("highlightColor" to "'UNKNOWN'", "highlightColor" to "CAST('BLUE' AS BLOB)",
            "attachedNote" to "''", "attachedNote" to "CAST('note' AS BLOB)", "revision" to "0.5", "revision" to "-1")) {
            withDatabase { db ->
                db.books().insert(book)
                db.epubPassages().add(passage("bad", "Valid quote"))
                db.openHelper.writableDatabase.execSQL("UPDATE epub_passages SET $field = $value WHERE id = 'bad'")
                for (format in listOf(StudyExportFormat.TEXT, StudyExportFormat.MARKDOWN)) {
                    val target = Documents()
                    assertEquals(field, StudyExportResult.Failed(StudyExportFailure.IO), export(db, target, format = format))
                    assertEquals(0, target.opens)
                    assertEquals(1, target.deletes)
                }
            }
        }
    }

    @Test fun `note-only actual passage exports source and note while Anki stays words only`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book)
            db.epubPassages().add(passage("only-note", "Quoted text").copy(attachedNote = "Only note", revision = 2))
            db.savedWords().add(SavedWordEntity("word", book.id, book.sha256, "leaf", "yaprak", 3))
            val plain = RoomBookStudySource(db).snapshot(book.id, book.sha256)
            assertEquals(1, plain.noteCount)
            assertTrue(plain.utf8.toString(Charsets.UTF_8).endsWith("Kaynak: OPS/only-note.xhtml\nBağlı not:\nOnly note\n\n"))
            assertFalse(plain.utf8.toString(Charsets.UTF_8).contains("Vurgu rengi:"))
            val anki = RoomBookStudySource(db).snapshot(book.id, book.sha256, StudyExportFormat.ANKI_TSV)
            assertEquals(0, anki.noteCount); assertEquals(0, anki.passageCount); assertEquals(1, anki.wordCount)
            assertFalse(anki.utf8.toString(Charsets.UTF_8).contains("Only note"))
        }
    }

    @Test fun `current scoped records retain exact Unicode multiline text and DAO order without writes`() = runBlocking {
        withDatabase { db ->
            val other = book.copy(id = "other", sha256 = "b".repeat(64))
            db.books().insert(book)
            db.books().insert(other)
            db.bookNotes().add(BookNoteEntity("note-a", book.id, book.sha256, "eski not", 7))
            db.bookNotes().add(BookNoteEntity("note-b", book.id, book.sha256, " yeni not\nçığ 🌿\n ", 7))
            db.bookNotes().add(BookNoteEntity("note-stale", book.id, "old-hash", "STALENOTE", 99))
            db.bookNotes().add(BookNoteEntity("note-other", other.id, other.sha256, "OTHERNOTE", 99))
            db.savedWords().add(SavedWordEntity("word-a", book.id, book.sha256, "leaf", "yaprak", 8))
            db.savedWords().add(SavedWordEntity("word-b", book.id, book.sha256, "snow", "kar ❄", 8))
            db.savedWords().add(SavedWordEntity("word-stale", book.id, "old-hash", "STALEWORD", "eski", 99))
            db.savedWords().add(SavedWordEntity("word-other", other.id, other.sha256, "OTHERWORD", "başka", 99))
            db.epubPassages().add(passage("passage-a", "eski pasaj"))
            db.epubPassages().add(passage("passage-b", "  yeni pasaj\nİğne 🌿  "))
            db.epubPassages().add(passage("passage-stale", "STALEPASSAGE").copy(contentSha256 = "old-hash"))
            db.epubPassages().add(passage("passage-other", "OTHERPASSAGE").copy(bookId = other.id, contentSha256 = other.sha256))
            db.epubPassages().add(passage("passage-future", "FUTUREPASSAGE").copy(anchorVersion = 2))
            val before = counts(db)
            val target = Documents()
            assertEquals(StudyExportResult.Exported(2, 2, 2), export(db, target))
            val txt = target.bytes.toString(Charsets.UTF_8.name())
            assertTrue(txt.contains("Başlık: İstanbul 🌿\nYazar: Çağrı Şen"))
            assertTrue(txt.contains(" yeni not\nçığ 🌿\n "))
            assertTrue(txt.contains("  yeni pasaj\nİğne 🌿  "))
            assertTrue(txt.indexOf("yeni not") < txt.indexOf("eski not"))
            assertTrue(txt.indexOf("İngilizce: snow\nTürkçe: kar ❄") < txt.indexOf("İngilizce: leaf"))
            assertTrue(txt.indexOf("yeni pasaj") < txt.indexOf("eski pasaj"))
            listOf("STALENOTE", "OTHERNOTE", "STALEWORD", "OTHERWORD", "STALEPASSAGE", "OTHERPASSAGE", "FUTUREPASSAGE")
                .forEach { assertFalse(it, txt.contains(it)) }
            assertEquals(before, counts(db))
            assertEquals(book, db.books().findById(book.id))
            assertEquals(0, target.deletes)
        }
    }

    @Test fun `PDF page note exports one based page and never EPUB passage rows`() = runBlocking {
        withDatabase { db ->
            val pdf = book.copy(format = "PDF", pageCount = 10, authors = null)
            db.books().insert(pdf)
            db.bookNotes().add(BookNoteEntity("page-note", book.id, book.sha256, "Sayfa 5\nnotu", 1, 4))
            db.epubPassages().add(passage("wrong-format", "WRONGFORMAT"))
            val target = Documents()
            assertEquals(StudyExportResult.Exported(1, 0, 0), export(db, target))
            val txt = target.bytes.toString(Charsets.UTF_8.name())
            assertTrue(txt.contains("Yazar: Bilinmiyor"))
            assertTrue(txt.contains("PDF sayfası: 5\nSayfa 5\nnotu"))
            assertFalse(txt.contains("WRONGFORMAT"))
        }
    }

    @Test fun `missing changed SQL-looking identity and empty book reject entire export before opening`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book)
            val cases = listOf(
                Triple("missing", book.sha256, StudyExportFailure.MISSING_BOOK),
                Triple("book' OR 1=1 --", book.sha256, StudyExportFailure.MISSING_BOOK),
                Triple(book.id, "changed-hash", StudyExportFailure.CHANGED_BOOK),
                Triple(book.id, book.sha256, StudyExportFailure.NO_RECORDS),
            )
            for ((id, hash, reason) in cases) {
                val target = Documents()
                assertEquals(StudyExportResult.Failed(reason), export(db, target, id, hash))
                assertEquals(0, target.opens)
                assertEquals(1, target.deletes)
            }
        }
    }

    @Test fun `invalid current note word or passage fails without silently skipping rows`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book)
            db.bookNotes().add(BookNoteEntity("bad-note", book.id, book.sha256, "x".repeat(2001), 1))
            assertInvalid(db)
            db.bookNotes().remove(book.id, book.sha256, "bad-note")
            db.savedWords().add(SavedWordEntity("bad-word", book.id, book.sha256, "bad\nword", "anlam", 1))
            assertInvalid(db)
            db.savedWords().remove(book.id, book.sha256, "bad-word")
            db.epubPassages().add(passage("bad-passage", "text").copy(endOffset = 4097))
            assertInvalid(db)
            assertEquals(1L, db.openHelper.writableDatabase.compileStatement("SELECT count(*) FROM epub_passages").simpleQueryForLong())
        }
    }

    @Test fun `multibyte saved notes exceeding eight MiB reject before provider open`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book)
            val text = "漢".repeat(2000) // 2,000 UTF-16 units but 6,000 UTF-8 bytes per saved note.
            db.withTransaction {
                repeat(1400) { db.bookNotes().add(BookNoteEntity("note-$it", book.id, book.sha256, text, it.toLong())) }
            }
            for (format in listOf(StudyExportFormat.TEXT, StudyExportFormat.MARKDOWN)) {
                val target = Documents()
                assertEquals(StudyExportResult.Failed(StudyExportFailure.TOO_LARGE), export(db, target, format = format))
                assertEquals(0, target.opens)
                assertEquals(1, target.deletes)
            }
            assertEquals(1400L, db.openHelper.writableDatabase.compileStatement("SELECT count(*) FROM book_notes").simpleQueryForLong())
        }
    }

    @Test fun `legacy Room TXT snapshot remains exact byte golden from real rows`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book)
            db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, " note\nNotlar\n------\n ", 1))
            db.savedWords().add(SavedWordEntity("word", book.id, book.sha256, "snow", "kar ❄", 1))
            db.epubPassages().add(passage("passage", "雪 🌿"))
            val expected = "Yaprak — Kitap çalışma kayıtları\nBaşlık: İstanbul 🌿\nYazar: Çağrı Şen\nBiçim: EPUB\n\n" +
                "Notlar\n------\n[1]\n note\nNotlar\n------\n \n\n" +
                "Kelimeler\n---------\n[1]\nİngilizce: snow\nTürkçe: kar ❄\n\n" +
                "Kayıtlı EPUB pasajları\n---------------------\n[1]\n雪 🌿\n\n"
            val source = RoomBookStudySource(db)
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), source.snapshot(book.id, book.sha256).utf8)
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), source.snapshot(book.id, book.sha256, StudyExportFormat.TEXT).utf8)
        }
    }

    @Test fun `Markdown current real scoped rows keep metadata delimiters href and note literal without writes`() = runBlocking {
        withDatabase { db ->
            val current = book.copy(title = "# title\n<img src=\"https://example.test\">", authors = "![author](https://example.test)")
            val other = book.copy(id = "other", sha256 = "b".repeat(64))
            db.books().insert(current)
            db.books().insert(other)
            val note = "## Notlar\n------\n````\n雪 🌿\n"
            db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, note, 1))
            db.bookNotes().add(BookNoteEntity("stale", book.id, "old-hash", "STALE", 99))
            db.bookNotes().add(BookNoteEntity("other", other.id, other.sha256, "OTHER", 99))
            db.savedWords().add(SavedWordEntity("word", book.id, book.sha256, "#html:true", "<b>yaprak</b>", 1))
            db.savedWords().add(SavedWordEntity("stale", book.id, "old-hash", "STALEWORD", "old", 99))
            db.epubPassages().add(passage("passage", "pasaj\n```\n漢字"))
            db.epubPassages().add(passage("stale", "STALEPASSAGE").copy(contentSha256 = "old-hash"))
            db.epubPassages().add(passage("future", "FUTUREPASSAGE").copy(anchorVersion = 2))
            val before = counts(db)
            val target = Documents()
            assertEquals(StudyExportResult.Exported(1, 1, 1), export(db, target, format = StudyExportFormat.MARKDOWN))
            val markdown = target.bytes.toString(Charsets.UTF_8.name())
            assertTrue(markdown.contains("```\n${current.title}\n```"))
            assertTrue(markdown.contains("```\n${current.authors}\n```"))
            assertTrue(markdown.contains("`````\n$note\n`````"))
            assertTrue(markdown.contains("```\nOPS/passage.xhtml\n```"))
            assertTrue(markdown.contains("````\npasaj\n```\n漢字\n````"))
            listOf("STALE", "OTHER", "STALEWORD", "STALEPASSAGE", "FUTUREPASSAGE").forEach {
                assertFalse(it, markdown.contains(it))
            }
            assertEquals(before, counts(db))
            assertEquals(current, db.books().findById(book.id))
            assertEquals(0, target.deletes)
        }
    }

    @Test fun `Anki contains only current saved pairs in DAO order no notes passages metadata or stale fields`() = runBlocking {
        withDatabase { db ->
            val other = book.copy(id = "other", sha256 = "b".repeat(64))
            db.books().insert(book)
            db.books().insert(other)
            db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, "NOTEONLY", 1))
            db.epubPassages().add(passage("passage", "PASSAGEONLY"))
            db.savedWords().add(SavedWordEntity("word-a", book.id, book.sha256, "#html:true", "<b>kar</b>", 1))
            db.savedWords().add(SavedWordEntity("word-b", book.id, book.sha256, "a\t\"b\"", "çığ\t雪 🌿", 1))
            db.savedWords().add(SavedWordEntity("stale", book.id, "old-hash", "STALE", "old", 99))
            db.savedWords().add(SavedWordEntity("other", other.id, other.sha256, "OTHER", "other", 99))
            val before = counts(db)
            val target = Documents()
            assertEquals(StudyExportResult.Exported(0, 2, 0), export(db, target, format = StudyExportFormat.ANKI_TSV))
            val expected = "#separator:Tab\n#html:false\n#columns:Front\tBack\n" +
                "\"a\t\"\"b\"\"\"\t\"çığ\t雪 🌿\"\n" +
                "\"#html:true\"\t\"<b>kar</b>\"\n"
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), target.bytes.toByteArray())
            assertEquals(before, counts(db))
            assertEquals(0, target.deletes)
        }
    }

    @Test fun `Anki notes-only and passages-only books fail NO_RECORDS and clean created target`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book)
            db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, "note", 1))
            db.epubPassages().add(passage("passage", "pasaj"))
            val target = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.NO_RECORDS), export(db, target, format = StudyExportFormat.ANKI_TSV))
            assertEquals(0, target.opens)
            assertEquals(1, target.deletes)
            for (format in listOf(StudyExportFormat.TEXT, StudyExportFormat.MARKDOWN)) {
                assertEquals(StudyExportResult.Exported(1, 0, 1), export(db, Documents(), format = format))
            }
        }
    }

    @Test fun `new formats preserve missing changed SQL identity and empty book failures before provider open`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book)
            for (format in listOf(StudyExportFormat.MARKDOWN, StudyExportFormat.ANKI_TSV)) {
                val cases = listOf(
                    Triple("missing", book.sha256, StudyExportFailure.MISSING_BOOK),
                    Triple("book' OR 1=1 --", book.sha256, StudyExportFailure.MISSING_BOOK),
                    Triple(book.id, "changed", StudyExportFailure.CHANGED_BOOK),
                    Triple(book.id, book.sha256, StudyExportFailure.NO_RECORDS),
                )
                for ((id, hash, failure) in cases) {
                    val target = Documents()
                    assertEquals(StudyExportResult.Failed(failure), export(db, target, id, hash, format))
                    assertEquals(0, target.opens)
                    assertEquals(1, target.deletes)
                }
            }
        }
    }

    @Test fun `Markdown PDF current note exports page and malformed current page never writes`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book.copy(format = "PDF", pageCount = 10, authors = null))
            db.bookNotes().add(BookNoteEntity("page-note", book.id, book.sha256, "Sayfa 5\nnotu", 1, 4))
            db.epubPassages().add(passage("wrong-format", "WRONGFORMAT"))
            val target = Documents()
            assertEquals(StudyExportResult.Exported(1, 0, 0), export(db, target, format = StudyExportFormat.MARKDOWN))
            val markdown = target.bytes.toString(Charsets.UTF_8.name())
            assertTrue(markdown.contains("PDF sayfası: 5\n\n```\nSayfa 5\nnotu\n```"))
            assertFalse(markdown.contains("WRONGFORMAT"))
            db.bookNotes().add(BookNoteEntity("bad-page", book.id, book.sha256, "outside page", 2, 10))
            val failed = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), export(db, failed, format = StudyExportFormat.MARKDOWN))
            assertEquals(0, failed.opens)
            assertEquals(1, failed.deletes)
        }
    }

    @Test fun `new formats reject malformed current word and Markdown rejects malformed current note anchor and href`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book)
            db.savedWords().add(SavedWordEntity("word", book.id, book.sha256, "word\nline", "meaning", 1))
            for (format in listOf(StudyExportFormat.MARKDOWN, StudyExportFormat.ANKI_TSV)) {
                val target = Documents()
                assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), export(db, target, format = format))
                assertEquals(0, target.opens)
                assertEquals(1, target.deletes)
            }
            db.savedWords().remove(book.id, book.sha256, "word")
            db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, " ", 1))
            val badNote = Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), export(db, badNote, format = StudyExportFormat.MARKDOWN))
            assertEquals(0, badNote.opens)
            db.bookNotes().remove(book.id, book.sha256, "note")
            for (invalid in listOf(
                passage("bad", "text").copy(endOffset = 4097),
                passage("bad", "text").copy(resourceHref = "https://example.test/book.xhtml"),
                passage("bad", "text").copy(resourceHref = "OPS/../book.xhtml"),
                passage("bad", "text").copy(nodeTextSha256 = "bad-hash"),
            )) {
                db.epubPassages().add(invalid)
                val target = Documents()
                assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), export(db, target, format = StudyExportFormat.MARKDOWN))
                assertEquals(0, target.opens)
                assertEquals(1, target.deletes)
                db.epubPassages().remove(book.id, book.sha256, "bad")
            }
        }
    }

    @Test fun `Markdown rejects fractional SQL page metadata and passage offsets instead of truncating attribution`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book.copy(format = "PDF", pageCount = 10))
            db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, "note", 1, 4))
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE book_notes SET pdfPageIndex = 4.5 WHERE id = 'note'")
            sql.query("SELECT pdfPageIndex FROM book_notes WHERE id = 'note'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(Cursor.FIELD_TYPE_FLOAT, cursor.getType(0))
            }
            assertMarkdownInvalid(db)
            sql.execSQL("UPDATE book_notes SET pdfPageIndex = 4 WHERE id = 'note'")
            sql.execSQL("UPDATE books SET pageCount = 10.5 WHERE id = 'book'")
            assertMarkdownInvalid(db)
            db.bookNotes().remove(book.id, book.sha256, "note")
            sql.execSQL("UPDATE books SET format = 'EPUB', pageCount = NULL WHERE id = 'book'")
            db.epubPassages().add(passage("passage", "text"))
            sql.execSQL("UPDATE epub_passages SET startOffset = 0.5, endOffset = 4.5 WHERE id = 'passage'")
            sql.query("SELECT startOffset, endOffset FROM epub_passages WHERE id = 'passage'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(Cursor.FIELD_TYPE_FLOAT, cursor.getType(0))
                assertEquals(Cursor.FIELD_TYPE_FLOAT, cursor.getType(1))
            }
            assertMarkdownInvalid(db)
            assertEquals(1L, sql.compileStatement("SELECT count(*) FROM epub_passages").simpleQueryForLong())
        }
    }

    @Test fun `new format saved text fields reject SQL BLOB rather than stringify malformed source`() = runBlocking {
        withDatabase { db ->
            db.books().insert(book)
            db.savedWords().add(SavedWordEntity("word", book.id, book.sha256, "leaf", "yaprak", 1))
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE saved_words SET english = CAST('leaf' AS BLOB) WHERE id = 'word'")
            sql.query("SELECT english FROM saved_words WHERE id = 'word'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(Cursor.FIELD_TYPE_BLOB, cursor.getType(0))
            }
            for (format in listOf(StudyExportFormat.MARKDOWN, StudyExportFormat.ANKI_TSV)) {
                val target = Documents()
                assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), export(db, target, format = format))
                assertEquals(0, target.opens)
                assertEquals(1, target.deletes)
            }
            db.savedWords().remove(book.id, book.sha256, "word")
            db.bookNotes().add(BookNoteEntity("note", book.id, book.sha256, "note", 1))
            sql.execSQL("UPDATE book_notes SET text = CAST('note' AS BLOB) WHERE id = 'note'")
            assertMarkdownInvalid(db)
        }
    }

    private suspend fun assertInvalid(db: LibraryDatabase) {
        val target = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), export(db, target))
        assertEquals(0, target.opens)
        assertEquals(1, target.deletes)
    }

    private suspend fun assertMarkdownInvalid(db: LibraryDatabase) {
        val target = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), export(db, target, format = StudyExportFormat.MARKDOWN))
        assertEquals(0, target.opens)
        assertEquals(1, target.deletes)
    }

    private fun passage(id: String, text: String) = EpubPassageEntity(id, book.id, book.sha256, 1,
        "OPS/$id.xhtml", "c".repeat(64), 0, text.length, text, 9)

    private suspend fun export(
        db: LibraryDatabase,
        target: Documents,
        id: String = book.id,
        hash: String = book.sha256,
        format: StudyExportFormat = StudyExportFormat.TEXT,
    ) = DefaultBookStudyExporter(RoomBookStudySource(db), target, Dispatchers.Unconfined).export(id, hash, destination, format)

    private fun counts(db: LibraryDatabase) = listOf("books", "book_notes", "saved_words", "epub_passages").map {
        db.openHelper.writableDatabase.compileStatement("SELECT count(*) FROM $it").simpleQueryForLong()
    }

    private suspend fun withDatabase(block: suspend (LibraryDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }

    private class Documents : ExportDocumentProvider {
        val bytes = ByteArrayOutputStream()
        var opens = 0
        var deletes = 0
        override fun accepts(uri: String) = uri.startsWith("content://")
        override fun open(uri: String): OutputStream { opens++; return bytes }
        override fun delete(uri: String): Boolean { deletes++; return true }
    }
}

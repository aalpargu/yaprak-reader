package com.yaprak.reader.data

import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.reader.SourceTextAnchor
import org.junit.Assert.*
import org.junit.Test

class StudyRecordsSnapshotTest {
    private val book = StudyExportBook("Kitap", null, BookFormat.EPUB)
    private val passage = StudyPassageRecord("İ🌿", SourceTextAnchor("OPS/chapter.xhtml", 2, 5, "a".repeat(64)),
        EpubHighlightColor.BLUE, "  Bağlı not\n漢字  ", 7)

    @Test fun `snapshots detach lists expose unmodifiable contents and count linked notes once`() {
        val notes = mutableListOf(StudyNoteRecord("  Not\n🌿  "))
        val words = mutableListOf(StudyWordRecord("leaf\t🌿", "yaprak 漢字"))
        val passages = mutableListOf(passage)
        val snapshot = StudyRecordsSnapshot(book, notes, words, passages)
        notes.clear(); words.clear(); passages.clear()
        assertEquals(listOf(StudyNoteRecord("  Not\n🌿  ")), snapshot.notes)
        assertEquals(listOf(StudyWordRecord("leaf\t🌿", "yaprak 漢字")), snapshot.words)
        assertEquals(listOf(passage), snapshot.passages)
        assertEquals(2, snapshot.noteCount); assertEquals(1, snapshot.wordCount)
        assertEquals(1, snapshot.passageCount); assertEquals(3, snapshot.recordCount)
        assertTrue(runCatching { (snapshot.notes as MutableList<StudyNoteRecord>).add(StudyNoteRecord("changed")) }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (snapshot.words as MutableList<StudyWordRecord>).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (snapshot.passages as MutableList<StudyPassageRecord>).removeAt(0) }.exceptionOrNull() is UnsupportedOperationException)
        validateStudyRecordsSnapshot(snapshot)
    }

    @Test fun `record boundary accepts exactly one thousand and rejects before copying an oversized list`() {
        val snapshot = StudyRecordsSnapshot(book, List(999) { StudyNoteRecord("Not $it") }, passages = listOf(passage))
        validateStudyRecordsSnapshot(snapshot)
        assertEquals(1000, snapshot.recordCount)
        assertEquals(1000, snapshot.noteCount)
        assertFailure(StudyExportFailure.TOO_LARGE) {
            StudyRecordsSnapshot(book, List(1001) { StudyNoteRecord("Not $it") })
        }
    }

    @Test fun `exact UTF8 source boundary includes metadata and one extra byte rejects`() {
        val bytes = MAX_STUDY_PDF_SOURCE_BYTES - book.title.toByteArray(Charsets.UTF_8).size - book.format.name.length
        val notes = buildList {
            repeat(bytes / 2000) { add(StudyNoteRecord("x".repeat(2000))) }
            if (bytes % 2000 != 0) add(StudyNoteRecord("x".repeat(bytes % 2000)))
        }
        validateStudyRecordsSnapshot(StudyRecordsSnapshot(book, notes))
        assertFailure(StudyExportFailure.TOO_LARGE) {
            validateStudyRecordsSnapshot(StudyRecordsSnapshot(book, notes + StudyNoteRecord("x")))
        }
    }

    @Test fun `multibyte payload uses bytes and source hash href color and attached note count in budget`() {
        assertFailure(StudyExportFailure.TOO_LARGE) {
            validateStudyRecordsSnapshot(StudyRecordsSnapshot(book, List(175) { StudyNoteRecord("雪".repeat(2000)) }))
        }
        val raw = "x".repeat(4096)
        val longSource = passage.copy(text = raw, anchor = (passage.anchor as SourceTextAnchor).copy(start = 0, end = raw.length),
            attachedNote = "n".repeat(2000))
        assertFailure(StudyExportFailure.TOO_LARGE) {
            validateStudyRecordsSnapshot(StudyRecordsSnapshot(book, passages = List(175) { longSource }))
        }
    }

    @Test fun `every text field rejects malformed UTF16 without replacement or silent truncation`() {
        for (bad in listOf("\uD800", "x\uDC00", "\uD800\uD800")) {
            val cases = listOf(
                StudyRecordsSnapshot(book.copy(title = bad), notes = listOf(StudyNoteRecord("Not"))),
                StudyRecordsSnapshot(book.copy(authors = bad), notes = listOf(StudyNoteRecord("Not"))),
                StudyRecordsSnapshot(book, notes = listOf(StudyNoteRecord(bad))),
                StudyRecordsSnapshot(book, words = listOf(StudyWordRecord(bad, "yaprak"))),
                StudyRecordsSnapshot(book, words = listOf(StudyWordRecord("leaf", bad))),
                StudyRecordsSnapshot(book, passages = listOf(passage.copy(text = bad, anchor = (passage.anchor as SourceTextAnchor).copy(start = 0, end = bad.length)))),
                StudyRecordsSnapshot(book, passages = listOf(passage.copy(attachedNote = bad))),
            )
            cases.forEach { snapshot -> assertFailure(StudyExportFailure.IO) { validateStudyRecordsSnapshot(snapshot) } }
        }
    }

    @Test fun `PDF source pages are actual bounded indices and EPUB cannot claim pages or PDF passages`() {
        val pdf = book.copy(format = BookFormat.PDF, pageCount = 8)
        validateStudyRecordsSnapshot(StudyRecordsSnapshot(pdf, listOf(StudyNoteRecord("Page", 7), StudyNoteRecord("General"))))
        val invalid = listOf(
            StudyRecordsSnapshot(pdf, listOf(StudyNoteRecord("Page", 8))),
            StudyRecordsSnapshot(pdf, listOf(StudyNoteRecord("Page", -1))),
            StudyRecordsSnapshot(pdf.copy(pageCount = null), listOf(StudyNoteRecord("Page"))),
            StudyRecordsSnapshot(pdf, passages = listOf(passage)),
            StudyRecordsSnapshot(book, listOf(StudyNoteRecord("Page", 0))),
            StudyRecordsSnapshot(book.copy(pageCount = 8), listOf(StudyNoteRecord("Page"))),
        )
        invalid.forEach { assertFailure(StudyExportFailure.IO) { validateStudyRecordsSnapshot(it) } }
    }

    @Test fun `source anchor and all individual field bounds fail rather than create partial records`() {
        val invalidPassages = listOf(passage.copy(anchor = (passage.anchor as SourceTextAnchor).copy(start = -1)),
            passage.copy(anchor = (passage.anchor as SourceTextAnchor).copy(end = 4097)), passage.copy(anchor = (passage.anchor as SourceTextAnchor).copy(end = 6)),
            passage.copy(anchor = (passage.anchor as SourceTextAnchor).copy(nodeTextSha256 = "A".repeat(64))),
            passage.copy(anchor = (passage.anchor as SourceTextAnchor).copy(resourceHref = "../chapter.xhtml")),
            passage.copy(anchor = (passage.anchor as SourceTextAnchor).copy(resourceHref = "https://example.test/chapter.xhtml")),
            passage.copy(anchor = (passage.anchor as SourceTextAnchor).copy(resourceHref = "OPS%2fchapter.xhtml")),
            passage.copy(revision = -1), passage.copy(attachedNote = " "), passage.copy(attachedNote = "n".repeat(2001)))
        invalidPassages.forEach { assertFailure(StudyExportFailure.IO) {
            validateStudyRecordsSnapshot(StudyRecordsSnapshot(book, passages = listOf(it)))
        } }
        assertFailure(StudyExportFailure.IO) { validateStudyRecordsSnapshot(StudyRecordsSnapshot(book, words = listOf(StudyWordRecord("a\nb", "meaning")))) }
        assertFailure(StudyExportFailure.IO) { validateStudyRecordsSnapshot(StudyRecordsSnapshot(book, notes = listOf(StudyNoteRecord("n".repeat(2001))))) }
    }

    @Test fun `empty detached records are no records and legacy SAM does not invent structured data`() {
        assertFailure(StudyExportFailure.NO_RECORDS) { validateStudyRecordsSnapshot(StudyRecordsSnapshot(book)) }
        kotlinx.coroutines.runBlocking {
            val source = BookStudySource { _, _ -> BookStudySnapshot("Pretend TXT".toByteArray(), 1, 0, 0) }
            val failure = runCatching { source.snapshotRecords("book", "hash") }.exceptionOrNull()
            assertTrue(failure is StudyExportException)
            assertEquals(StudyExportFailure.UNSUPPORTED_FORMAT, (failure as StudyExportException).reason)
        }
    }

    private fun assertFailure(reason: StudyExportFailure, work: () -> Unit) {
        val failure = runCatching(work).exceptionOrNull()
        assertTrue("Expected $reason, got $failure", failure is StudyExportException)
        assertEquals(reason, (failure as StudyExportException).reason)
    }
}

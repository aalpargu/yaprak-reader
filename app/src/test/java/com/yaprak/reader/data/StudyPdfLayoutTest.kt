package com.yaprak.reader.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.reader.SourceTextAnchor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Android native text layout and Bitmap paint; this does not prove a PDF container. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudyPdfLayoutTest {
    private val book = StudyExportBook("İstanbul — Çalışma kitabı", "Çığ Öztürk", BookFormat.EPUB)
    private fun passage(color: EpubHighlightColor? = null, note: String? = null): StudyPassageRecord {
        val text = "Kesin seçili metin — İstanbul 🌿"
        return StudyPassageRecord(text, SourceTextAnchor("OPS/chapter.xhtml", 0, text.length, "a".repeat(64)), color, note, 4)
    }
    private fun mixed() = StudyRecordsSnapshot(book,
        notes = listOf(StudyNoteRecord("  Literal <script> \"[link](url)\"\nİğışÇöü 😀\tson\n  ")),
        words = listOf(StudyWordRecord("dependency injection", "bağımlılık enjeksiyonu")),
        passages = listOf(passage(EpubHighlightColor.YELLOW, "  Bağlı not\nالعربية\n🌿  ")))

    @Test fun `every native line and UTF16 source offset appears exactly once across pages`() = runBlocking {
        val long = ("İstanbul 🌿 العربية uzun satır. ".repeat(45)).take(1950) + "\nSON😀"
        val snapshot = StudyRecordsSnapshot(book, notes = List(8) { StudyNoteRecord("$it\n$long") })
        val pages = StudyPdfPagination.pages(snapshot)
        assertTrue(pages.size > 1); assertTrue(pages.size <= MAX_STUDY_PDF_PAGES)
        val fragments = pages.flatMap { it.fragments }
        for ((layout, pieces) in fragments.groupBy { it.layout }) {
            var line = 0; val restored = StringBuilder()
            for (piece in pieces) {
                assertEquals(line, piece.firstLine); assertTrue(piece.endLine > piece.firstLine)
                assertTrue(piece.y >= STUDY_PDF_MARGIN); assertTrue(piece.y + piece.height <= STUDY_PDF_BOTTOM)
                assertEquals(0, layout.getEllipsisCount(piece.firstLine))
                val source = layout.text.toString()
                for (offset in listOf(piece.startOffset, piece.endOffset)) {
                    assertFalse(offset > 0 && offset < source.length && source[offset - 1].isHighSurrogate() && source[offset].isLowSurrogate())
                }
                restored.append(source.substring(piece.startOffset, piece.endOffset)); line = piece.endLine
            }
            assertEquals(layout.lineCount, line); assertEquals(layout.text.toString(), restored.toString())
        }
        assertTrue(fragments.any { it.startOffset > 0 })
    }

    @Test fun `book note word quote source and linked note strings remain literal in their own blocks`() {
        val snapshot = mixed(); val blocks = studyPdfBlocks(snapshot)
        val strings = blocks.map { it.text }
        for (expected in listOf(snapshot.book.title, snapshot.book.authors!!, snapshot.notes.single().text,
            snapshot.words.single().english, snapshot.words.single().turkish, snapshot.passages.single().text,
            snapshot.passages.single().anchor.resourceHref, snapshot.passages.single().attachedNote!!))
            assertTrue("Missing literal field", strings.contains(expected))
        assertTrue(strings.contains("Vurgu rengi: Sarı")); assertTrue(strings.contains("Bağlı not:"))
        assertTrue(strings.contains("2 not · 1 kelime · 1 pasaj"))
        assertEquals(snapshot.notes.single().text, blocks.single { it.text.startsWith("  Literal") }.text)
        assertEquals(StudyPdfTextStyle.BODY, blocks.single { it.text == snapshot.passages.single().text }.style)
    }

    @Test fun `actual PDF page note label uses verified one based source page`() {
        val snapshot = StudyRecordsSnapshot(StudyExportBook("PDF kitabı", null, BookFormat.PDF, 12),
            notes = listOf(StudyNoteRecord("Page note", 11), StudyNoteRecord("General note")))
        val text = studyPdfBlocks(snapshot).map { it.text }
        assertTrue(text.contains("Not 1 · PDF sayfası: 12")); assertTrue(text.contains("Not 2"))
        assertFalse(text.any { it.contains("PDF sayfası: 13") })
        assertTrue(text.contains("Bilinmiyor"))
    }

    @Test fun `native RTL paragraphs keep direction while following Latin paragraphs use LTR`() {
        val layout = studyPdfLayout(StudyPdfBlock("العربية بالعربية\nLatin paragraph\nעברית"))
        assertEquals(Layout.DIR_RIGHT_TO_LEFT, layout.getParagraphDirection(0))
        val latin = layout.getLineForOffset(layout.text.toString().indexOf("Latin"))
        assertEquals(Layout.DIR_LEFT_TO_RIGHT, layout.getParagraphDirection(latin))
        val hebrew = layout.getLineForOffset(layout.text.toString().indexOf("עברית"))
        assertEquals(Layout.DIR_RIGHT_TO_LEFT, layout.getParagraphDirection(hebrew))
        assertEquals(12f, layout.paint.textSize, 0f)
        assertTrue(layout.lineCount >= 3); assertEquals(layout.text.length, layout.getLineStart(layout.lineCount))
    }

    @Test fun `fixed point sizes and pagination ignore device font scale`() = runBlocking {
        val snapshot = mixed()
        RuntimeEnvironment.setFontScale(1f)
        val before = StudyPdfPagination.pages(snapshot).flatMap { it.fragments }.map { listOf(it.firstLine, it.endLine, it.y, it.height) }
        try {
            RuntimeEnvironment.setFontScale(2f)
            val after = StudyPdfPagination.pages(snapshot).flatMap { it.fragments }.map { listOf(it.firstLine, it.endLine, it.y, it.height) }
            assertEquals(before, after)
            assertEquals(12f, studyPdfLayout(StudyPdfBlock("İstanbul 😀")).paint.textSize, 0f)
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun `long literal word wraps without ellipsis and remains within page content width`() {
        val text = "Uzun" + "x".repeat(1990) + "🌿"
        val layout = studyPdfLayout(StudyPdfBlock(text))
        assertTrue(layout.lineCount > 1)
        assertEquals(text.length, layout.getLineStart(layout.lineCount))
        for (line in 0 until layout.lineCount) {
            assertEquals(0, layout.getEllipsisCount(line))
            assertTrue(layout.getLineLeft(line) >= -1f)
            assertTrue(layout.getLineRight(line) <= STUDY_PDF_WIDTH - STUDY_PDF_MARGIN * 2 + 1f)
        }
    }

    @Test fun `native page pixels paint literal text four colors and numbered footer without margin clipping`() = runBlocking {
        val snapshot = StudyRecordsSnapshot(book, notes = listOf(StudyNoteRecord("İğışÇöü Latin text")),
            passages = EpubHighlightColor.entries.map { passage(it, "Bağlı not ${it.name}") })
        val pages = StudyPdfPagination.pages(snapshot)
        val colorCounts = EpubHighlightColor.entries.associateWith { 0 }.toMutableMap()
        var textPixels = 0; var footerPixels = 0
        for ((index, page) in pages.withIndex()) {
            val bitmap = Bitmap.createBitmap(STUDY_PDF_WIDTH, STUDY_PDF_HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                page.draw(Canvas(bitmap), index + 1)
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val color = bitmap.getPixel(x, y)
                    if (x < STUDY_PDF_MARGIN || x >= STUDY_PDF_WIDTH - STUDY_PDF_MARGIN)
                        assertEquals("Outside horizontal margin at $x,$y", Color.WHITE, color)
                    if (y >= STUDY_PDF_MARGIN && y < STUDY_PDF_BOTTOM && Color.red(color) < 100 && Color.green(color) < 100 && Color.blue(color) < 100) textPixels++
                    if (y > STUDY_PDF_BOTTOM && color != Color.WHITE) footerPixels++
                    for (highlight in EpubHighlightColor.entries) if (color == highlight.pdfColor()) colorCounts[highlight] = colorCounts.getValue(highlight) + 1
                }
                if (index == 0) saveEvidence(bitmap, "study-pdf-layout-page1.png")
            } finally { bitmap.recycle() }
        }
        assertTrue(textPixels > 100); assertTrue(footerPixels > 5)
        EpubHighlightColor.entries.forEach { assertTrue("Missing native ${it.name} tint", colorCounts.getValue(it) > 100) }
    }

    @Test fun `page cap rejects complete content rather than returning a truncated document`() = runBlocking {
        val manyLines = "L\n".repeat(999) + "Z"
        val snapshot = StudyRecordsSnapshot(book, notes = List(12) { StudyNoteRecord(manyLines) })
        try { StudyPdfPagination.pages(snapshot); fail("Page budget must fail") }
        catch (failure: StudyExportException) { assertEquals(StudyExportFailure.TOO_LARGE, failure.reason) }
    }

    @Test @Config(sdk = [28]) fun `API28 fallback layout path preserves complete emoji and mixed script offsets`() {
        val text = "İstanbul 🌿\nالعربية\n漢字"
        val layout = studyPdfLayout(StudyPdfBlock(text))
        assertEquals(text.length, layout.getLineStart(layout.lineCount))
        assertTrue(layout.height > 0); assertTrue(layout.lineCount >= 3)
        assertEquals(12f, layout.paint.textSize, 0f)
        // Glyph availability remains a device/font acceptance item, not inferred from these offsets.
    }

    private fun saveEvidence(bitmap: Bitmap, name: String) {
        val directory = File(checkNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}

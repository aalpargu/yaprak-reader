package com.yaprak.reader.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.notes.*
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.reader.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real native text layout/Canvas and controlled document orchestration, not native PDF-container evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryNotesPdfExportTest {
    private val uri = "content://study/library-pdf"
    // Deliberately a transport fixture only: a PDF signature does not make these bytes a valid PDF container.
    private val fixtureBytes = "%PDF-1.7\nTRANSPORT FIXTURE ONLY".toByteArray(Charsets.US_ASCII)
    private fun plain(id: String = "note", text: String = "  Not\n🌿  ") = LibraryNoteExportEntry(
        LibraryNote(id, "book", "a".repeat(64), "İstanbul — Kitap", "Çığ Öztürk", BookFormat.EPUB, text, 1), null)
    private fun epub(color: EpubHighlightColor? = EpubHighlightColor.GREEN) = plain("epub").let {
        val quote = "Kaynak alıntı — İstanbul 🌿"
        it.copy(note = it.note.copy(source = LibraryNoteSource.EPUB_PASSAGE, sourceText = quote,
            sourceAnchor = SourceTextAnchor("OPS/first.xhtml", 0, quote.length, "b".repeat(64)), highlightColor = color))
    }
    private fun pdf(color: PdfHighlightColor? = PdfHighlightColor.PINK) = plain("pdf").let {
        it.copy(bookPageCount = 20, note = it.note.copy(bookId = "pdf-book", contentSha256 = "d".repeat(64),
            bookTitle = "Fizik kitabı", bookAuthors = null, bookFormat = BookFormat.PDF, pdfPageIndex = 3,
            source = LibraryNoteSource.PDF_ANNOTATION, sourceText = "Kaynak PDF alıntısı 🌿",
            pdfSourceAnchor = PdfSourceAnchor(3, 300, 600, PdfPagePoint(60, 100), PdfPagePoint(180, 100),
                listOf(PdfTextBounds(60.0, 90.0, 180.0, 110.0))), pdfHighlightColor = color))
    }
    private fun snapshot(entries: List<LibraryNoteExportEntry> = listOf(plain(), epub(), pdf()), query: String = "istanbul") =
        LibraryNotesExportSnapshot(query, entries)

    @Test fun actualMultiBookBlocksKeepLiteralMetadataNotesQuotesTrueSourcesAndColorsWithoutInventedBookOrPrivateFields() {
        val records = snapshot(listOf(plain(text = "  <script>雪</script>\r\n```\t🌿  "), epub(), pdf()))
        val blocks = libraryNotesPdfBlocks(records); val text = blocks.map { it.text }
        assertTrue(text.contains(records.query)); assertTrue(text.contains("3 not"))
        records.entries.forEach { entry ->
            assertTrue(text.contains(entry.note.bookTitle)); assertTrue(text.contains(entry.note.text))
            entry.note.sourceText?.let { assertTrue(text.contains(it)) }
        }
        assertTrue(text.contains("OPS/first.xhtml")); assertTrue(text.contains("PDF sayfası: 4"))
        assertTrue(text.contains("Bilinmiyor")); assertTrue(text.contains("Vurgu rengi: Yeşil")); assertTrue(text.contains("Vurgu rengi: Pembe"))
        assertFalse(text.any { it.contains("a".repeat(64)) || it.contains("d".repeat(64)) || it.contains("pageWidth") })
        assertEquals(1, blocks.count { it.highlightColor == EpubHighlightColor.GREEN })
        assertEquals(1, blocks.count { it.pdfHighlightColor == PdfHighlightColor.PINK })
        assertTrue(blocks.filter { it.highlightColor != null || it.pdfHighlightColor != null }.all { it.style == StudyPdfTextStyle.BODY })
    }

    @Test fun everyNativeLineAndUtf16OffsetAppearsExactlyOnceAcrossActualPagesIndependentOfSystemFontScale() = runBlocking {
        val long = ("İstanbul 🌿 العربية 雪 uzun satır. ".repeat(80)).take(1900) + "\nSON😀"
        val records = snapshot(List(5) { index -> epub().copy(note = epub().note.copy(id = "note-$index", text = "$index\n$long")) })
        val pages = StudyPdfPagination.pagesFromBlocks(libraryNotesPdfBlocks(records))
        assertTrue(pages.size > 1); assertTrue(pages.size <= MAX_STUDY_PDF_PAGES)
        for ((layout, fragments) in pages.flatMap { it.fragments }.groupBy { it.layout }) {
            var line = 0; val restored = StringBuilder()
            for (fragment in fragments) {
                assertEquals(line, fragment.firstLine); assertTrue(fragment.endLine > fragment.firstLine)
                assertTrue(fragment.y >= STUDY_PDF_MARGIN && fragment.y + fragment.height <= STUDY_PDF_BOTTOM)
                for (offset in listOf(fragment.startOffset, fragment.endOffset)) {
                    val source = layout.text.toString()
                    assertFalse(offset > 0 && offset < source.length && source[offset - 1].isHighSurrogate() && source[offset].isLowSurrogate())
                }
                for (index in fragment.firstLine until fragment.endLine) assertEquals(0, layout.getEllipsisCount(index))
                restored.append(layout.text.toString().substring(fragment.startOffset, fragment.endOffset)); line = fragment.endLine
            }
            assertEquals(layout.lineCount, line); assertEquals(layout.text.toString(), restored.toString())
        }
        try {
            RuntimeEnvironment.setFontScale(2f)
            val large = StudyPdfPagination.pagesFromBlocks(libraryNotesPdfBlocks(records))
            assertEquals(pages.map { it.fragments.map { piece -> Triple(piece.startOffset, piece.endOffset, piece.height) } },
                large.map { it.fragments.map { piece -> Triple(piece.startOffset, piece.endOffset, piece.height) } })
        } finally { RuntimeEnvironment.setFontScale(1f) }
        save(pages.first(), 1, "first-page"); save(pages.last(), pages.size, "last-page")
    }

    @Test fun allFourEpubAndPdfQuoteColorsPaintRealNativeBackgroundWhileNoteOnlyQuotesRemainWhite() = runBlocking {
        val cases = EpubHighlightColor.entries.map { epub(it) } + PdfHighlightColor.entries.map { pdf(it) } + pdf(null)
        for (entry in cases) {
            val pages = StudyPdfPagination.pagesFromBlocks(libraryNotesPdfBlocks(snapshot(listOf(entry))))
            val pair = pages.mapNotNull { page -> page.fragments.singleOrNull { it.block.text == entry.note.sourceText }?.let { page to it } }.single()
            val bitmap = Bitmap.createBitmap(STUDY_PDF_WIDTH, STUDY_PDF_HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                pair.first.draw(Canvas(bitmap), 1)
                val expected = entry.note.highlightColor?.pdfColor() ?: entry.note.pdfHighlightColor?.pdfColor() ?: Color.WHITE
                assertEquals(expected, bitmap.getPixel(STUDY_PDF_WIDTH - STUDY_PDF_MARGIN - 2, pair.second.y + 1))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun invalidEmptySourceAndMoreThanTwoHundredNativePagesFailBeforeCreatingADocument() = runBlocking {
        var creates = 0
        val renderer = AndroidLibraryNotesPdfRenderer(AndroidStudyPdfRenderer({ creates++; Document() }, Dispatchers.Unconfined))
        assertFailure(StudyExportFailure.NO_RECORDS) { renderer.render(snapshot(emptyList())) }
        assertFailure(StudyExportFailure.IO) { renderer.render(snapshot(listOf(pdf().copy(bookPageCount = 3)))) }
        val big = snapshot(List(700) { plain("note-$it", "n".repeat(1000)) })
        validateLibraryNotesExportSnapshot(big)
        assertFailure(StudyExportFailure.TOO_LARGE) { renderer.render(big) }
        assertEquals(0, creates)
    }

    @Test fun sharedNativePageLifecycleFinishesEveryStartedPageAndClosesOnSuccessAndCancellationWithoutSerializingLate() = runBlocking {
        val document = Document()
        val renderer = AndroidLibraryNotesPdfRenderer(AndroidStudyPdfRenderer({ document }, Dispatchers.Unconfined))
        assertArrayEquals(fixtureBytes, renderer.render(snapshot(listOf(plain()))))
        assertEquals(listOf("start:1", "finish", "write", "close"), document.events)
        val job = Job(); val cancelled = Document(onStart = { job.cancel() })
        val operation = async(job) { AndroidLibraryNotesPdfRenderer(AndroidStudyPdfRenderer({ cancelled }, Dispatchers.Unconfined))
            .render(snapshot(listOf(plain()))) }
        assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("start:1", "finish", "close"), cancelled.events)
    }

    @Test fun exporterRendersTheExactCompleteQuerySnapshotAfterSourceLockAndWritesOnlyExplicitPdfTransportBytes() = runBlocking {
        val mutex = Mutex(); val records = snapshot(); var reads = 0; var renders = 0
        val source = LibraryNotesExportSource { query -> reads++; assertEquals(records.query, query); mutex.lock()
            try { records } finally { mutex.unlock() } }
        val docs = Documents(onOpen = { assertTrue(mutex.tryLock()); mutex.unlock() })
        val renderer = LibraryNotesPdfRenderer { current -> renders++; assertSame(records, current)
            assertTrue(mutex.tryLock()); mutex.unlock(); fixtureBytes }
        assertEquals(StudyExportResult.Exported(3, 0, 0), DefaultLibraryNotesExporter(source, docs, Dispatchers.Unconfined, renderer)
            .export(records.query, uri, LibraryNotesExportFormat.PDF))
        assertEquals(1, reads); assertEquals(1, renders); assertArrayEquals(fixtureBytes, docs.bytes.toByteArray())
        assertEquals(1, docs.opens); assertEquals(1, docs.closes); assertEquals(0, docs.deletes)
    }

    @Test fun unavailableRendererInvalidSourceAndInvalidOrOversizedRenderedOutputNeverOpenAProviderOrBecomeTextPdf() = runBlocking {
        val noRenderer = Documents(); var reads = 0
        assertEquals(StudyExportResult.Failed(StudyExportFailure.UNSUPPORTED_FORMAT), DefaultLibraryNotesExporter(
            LibraryNotesExportSource { reads++; snapshot() }, noRenderer, Dispatchers.Unconfined)
            .export("istanbul", uri, LibraryNotesExportFormat.PDF))
        assertEquals(0, reads); assertEquals(0, noRenderer.opens); assertEquals(1, noRenderer.deletes)
        for ((bytes, failure) in listOf(ByteArray(0) to StudyExportFailure.IO, "not a pdf".toByteArray() to StudyExportFailure.IO,
            ByteArray(MAX_STUDY_EXPORT_BYTES + 1) to StudyExportFailure.TOO_LARGE)) {
            val docs = Documents()
            assertEquals(StudyExportResult.Failed(failure), DefaultLibraryNotesExporter(LibraryNotesExportSource { snapshot() }, docs,
                Dispatchers.Unconfined, LibraryNotesPdfRenderer { bytes }).export("istanbul", uri, LibraryNotesExportFormat.PDF))
            assertEquals(0, docs.opens); assertEquals(1, docs.deletes)
        }
        var renders = 0; val invalid = Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), DefaultLibraryNotesExporter(
            LibraryNotesExportSource { snapshot(listOf(plain(text = "bad\u0000"))) }, invalid, Dispatchers.Unconfined,
            LibraryNotesPdfRenderer { renders++; fixtureBytes }).export("istanbul", uri, LibraryNotesExportFormat.PDF))
        assertEquals(0, renders); assertEquals(0, invalid.opens)
        assertFailure(StudyExportFailure.UNSUPPORTED_FORMAT) { renderLibraryNotesExport(snapshot(), LibraryNotesExportFormat.PDF) }
    }

    @Test fun lateCancelledPdfRenderingAndRendererOrProviderFailureCleanTheAcceptedTargetWithoutPartialSuccess() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val docs = Documents()
        val operation = launch { DefaultLibraryNotesExporter(LibraryNotesExportSource { snapshot() }, docs, Dispatchers.Unconfined,
            LibraryNotesPdfRenderer { started.complete(Unit); withContext(NonCancellable) { finish.await() }; fixtureBytes })
            .export("istanbul", uri, LibraryNotesExportFormat.PDF); fail("Cancelled render cannot succeed") }
        started.await(); operation.cancel(); finish.complete(Unit); operation.join()
        assertEquals(0, docs.opens); assertEquals(1, docs.deletes)
        for (failure in listOf("render", "write", "close")) {
            val failed = Documents(failure, deleteConfirmed = false)
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO, partialFileMayRemain = true),
                DefaultLibraryNotesExporter(LibraryNotesExportSource { snapshot() }, failed, Dispatchers.Unconfined,
                    LibraryNotesPdfRenderer { if (failure == "render") throw IOException("Render failed"); fixtureBytes })
                    .export("istanbul", uri, LibraryNotesExportFormat.PDF))
            assertEquals(if (failure == "render") 0 else 1, failed.opens)
            assertEquals(if (failure == "render") 0 else 1, failed.closes); assertEquals(1, failed.deletes)
        }
    }

    private suspend fun save(page: StudyPdfPage, number: Int, name: String) {
        val bitmap = Bitmap.createBitmap(STUDY_PDF_WIDTH, STUDY_PDF_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            page.draw(Canvas(bitmap), number)
            val directory = File(System.getProperty("yaprak.screenshotDir"), "library-notes-pdf-0.134.0").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
    private suspend fun assertFailure(expected: StudyExportFailure, block: suspend () -> Any) {
        val error = runCatching { block() }.exceptionOrNull()
        val reason = when (error) { is LibraryNotesExportException -> error.reason; is StudyExportException -> error.reason; else -> null }
        assertEquals(error.toString(), expected, reason)
    }
    private inner class Document(val onStart: () -> Unit = {}) : StudyPdfDocument {
        val events = mutableListOf<String>(); private var bitmap: Bitmap? = null
        override fun startPage(number: Int): Canvas {
            check(bitmap == null); events += "start:$number"
            bitmap = Bitmap.createBitmap(STUDY_PDF_WIDTH, STUDY_PDF_HEIGHT, Bitmap.Config.ARGB_8888)
            onStart(); return Canvas(bitmap!!)
        }
        override fun finishPage() { events += "finish"; checkNotNull(bitmap).recycle(); bitmap = null }
        override fun writeTo(output: OutputStream) { check(bitmap == null); events += "write"; output.write(fixtureBytes) }
        override fun close() { events += "close"; bitmap?.recycle(); bitmap = null }
    }
    private inner class Documents(val failure: String? = null, val deleteConfirmed: Boolean = true,
        val onOpen: () -> Unit = {}) : ExportDocumentProvider {
        val bytes = ByteArrayOutputStream(); var opens = 0; var closes = 0; var deletes = 0
        override fun accepts(uri: String) = uri == this@LibraryNotesPdfExportTest.uri
        override fun open(uri: String): OutputStream {
            opens++; onOpen()
            return object : OutputStream() {
                override fun write(value: Int) { if (failure == "write") throw IOException("Write failed"); bytes.write(value) }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    if (failure == "write") throw IOException("Write failed"); bytes.write(buffer, offset, length)
                }
                override fun close() { closes++; if (failure == "close") throw IOException("Close failed") }
            }
        }
        override fun delete(uri: String): Boolean { deletes++; return deleteConfirmed }
    }
}

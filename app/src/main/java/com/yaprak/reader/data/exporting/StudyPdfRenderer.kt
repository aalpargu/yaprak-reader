package com.yaprak.reader.data.exporting

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.yaprak.reader.domain.library.MAX_STUDY_EXPORT_BYTES
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.notes.LibraryNotesExportSnapshot
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Detached records only. A renderer must never read books, Room or document providers. */
fun interface StudyPdfRenderer {
    suspend fun render(snapshot: StudyRecordsSnapshot): ByteArray
}

/** A4 native vector/text pages; fixed point sizes do not inherit the device's display font scale. */
class AndroidStudyPdfRenderer internal constructor(
    private val documents: () -> StudyPdfDocument,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : StudyPdfRenderer {
    constructor() : this({ NativeStudyPdfDocument() })

    override suspend fun render(snapshot: StudyRecordsSnapshot): ByteArray = withContext(worker) {
        currentCoroutineContext().ensureActive()
        validateStudyRecordsSnapshot(snapshot)
        val pages = StudyPdfPagination.pages(snapshot)
        currentCoroutineContext().ensureActive()
        renderPages(pages)
    }

    internal suspend fun renderLibraryNotes(snapshot: LibraryNotesExportSnapshot): ByteArray = withContext(worker) {
        currentCoroutineContext().ensureActive()
        validateLibraryNotesExportSnapshot(snapshot)
        val pages = StudyPdfPagination.pagesFromBlocks(libraryNotesPdfBlocks(snapshot))
        currentCoroutineContext().ensureActive()
        renderPages(pages)
    }

    private suspend fun renderPages(pages: List<StudyPdfPage>): ByteArray {
        return documents().use { document ->
            for ((index, page) in pages.withIndex()) {
                currentCoroutineContext().ensureActive()
                val canvas = document.startPage(index + 1)
                var drawingFailure: Throwable? = null
                try {
                    page.draw(canvas, index + 1)
                    currentCoroutineContext().ensureActive()
                } catch (failure: Throwable) {
                    drawingFailure = failure
                    throw failure
                } finally {
                    // PdfDocument requires the last started page to be finished before close.
                    try { document.finishPage() }
                    catch (cleanup: Throwable) {
                        val primary = drawingFailure
                        if (primary == null) throw cleanup
                        primary.addSuppressed(cleanup)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val output = BoundedPdfOutput(currentCoroutineContext())
            try { document.writeTo(output) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                // A native writer may wrap a Java stream callback exception as IOException.
                output.rethrowLimitFailure()
                throw failure
            }
            currentCoroutineContext().ensureActive()
            output.bytes()
        }
    }
}

/** Test seam for native-page lifecycle only; fake documents are never PDF-container evidence. */
internal interface StudyPdfDocument : Closeable {
    fun startPage(number: Int): Canvas
    fun finishPage()
    fun writeTo(output: OutputStream)
}

private class NativeStudyPdfDocument : StudyPdfDocument {
    private val document = PdfDocument()
    private var page: PdfDocument.Page? = null
    override fun startPage(number: Int): Canvas {
        check(page == null)
        return document.startPage(PdfDocument.PageInfo.Builder(STUDY_PDF_WIDTH, STUDY_PDF_HEIGHT, number)
            .create()).also { page = it }.canvas
    }
    override fun finishPage() {
        val started = checkNotNull(page)
        document.finishPage(started)
        page = null
    }
    override fun writeTo(output: OutputStream) {
        check(page == null)
        document.writeTo(output)
    }
    override fun close() {
        try { if (page != null) finishPage() }
        finally { document.close() }
    }
}

internal const val STUDY_PDF_WIDTH = 595
internal const val STUDY_PDF_HEIGHT = 842
internal const val STUDY_PDF_MARGIN = 42
internal const val STUDY_PDF_BOTTOM = 784
internal const val MAX_STUDY_PDF_PAGES = 200
private const val CONTENT_WIDTH = STUDY_PDF_WIDTH - STUDY_PDF_MARGIN * 2
private const val BLOCK_GAP = 8

internal enum class StudyPdfTextStyle(val points: Float, val bold: Boolean) {
    TITLE(18f, true), HEADING(14f, true), BODY(12f, false), SOURCE(10f, false)
}

internal data class StudyPdfBlock(val text: String, val style: StudyPdfTextStyle = StudyPdfTextStyle.BODY,
    val keepWithNext: Boolean = false, val highlightColor: EpubHighlightColor? = null,
    val pdfHighlightColor: PdfHighlightColor? = null)

/** Every string is plain text. No HTML, Markdown, URLs, links or executable PDF actions are parsed. */
internal fun studyPdfBlocks(snapshot: StudyRecordsSnapshot): List<StudyPdfBlock> = buildList {
    fun heading(text: String) { add(StudyPdfBlock(text, StudyPdfTextStyle.HEADING, keepWithNext = true)) }
    fun source(text: String, keep: Boolean = true) { add(StudyPdfBlock(text, StudyPdfTextStyle.SOURCE, keepWithNext = keep)) }
    add(StudyPdfBlock("Yaprak — Çalışma kayıtları", StudyPdfTextStyle.TITLE, keepWithNext = true))
    source("Başlık:"); add(StudyPdfBlock(snapshot.book.title))
    source("Yazar:"); add(StudyPdfBlock(snapshot.book.authors ?: "Bilinmiyor"))
    source("Biçim: ${snapshot.book.format.name}", keep = false)
    source("${snapshot.noteCount} not · ${snapshot.wordCount} kelime · ${snapshot.passageCount} pasaj", keep = false)
    heading("Notlar")
    if (snapshot.notes.isEmpty()) add(StudyPdfBlock("Kayıt yok."))
    snapshot.notes.forEachIndexed { index, note ->
        source("Not ${index + 1}" + (note.pdfPageIndex?.let { " · PDF sayfası: ${it + 1}" } ?: ""))
        add(StudyPdfBlock(note.text))
    }
    heading("Kelimeler")
    if (snapshot.words.isEmpty()) add(StudyPdfBlock("Kayıt yok."))
    snapshot.words.forEachIndexed { index, word ->
        source("Kelime ${index + 1} · İngilizce:"); add(StudyPdfBlock(word.english))
        source("Türkçe:"); add(StudyPdfBlock(word.turkish))
    }
    heading("Kayıtlı EPUB pasajları")
    if (snapshot.passages.isEmpty()) add(StudyPdfBlock("Kayıt yok."))
    snapshot.passages.forEachIndexed { index, passage ->
        source("Pasaj ${index + 1} · Kaynak EPUB dosyası:")
        source(passage.anchor.resourceHref)
        passage.highlightColor?.let { source("Vurgu rengi: ${it.pdfLabel()}") }
        add(StudyPdfBlock(passage.text, highlightColor = passage.highlightColor))
        passage.attachedNote?.let { note -> source("Bağlı not:"); add(StudyPdfBlock(note)) }
    }
    if (snapshot.pdfAnnotations.isNotEmpty()) heading("PDF vurguları ve bağlı notlar")
    snapshot.pdfAnnotations.forEachIndexed { index, annotation ->
        source("PDF kaydı ${index + 1} · PDF sayfası: ${annotation.anchor.pageIndex.toLong() + 1}")
        annotation.highlightColor?.let { source("Vurgu rengi: ${it.pdfLabel()}") }
        add(StudyPdfBlock(annotation.text, pdfHighlightColor = annotation.highlightColor))
        annotation.attachedNote?.let { source("Bağlı not:"); add(StudyPdfBlock(it)) }
    }
}

private fun PdfHighlightColor.pdfLabel() = when (this) {
    PdfHighlightColor.YELLOW -> "Sarı"
    PdfHighlightColor.GREEN -> "Yeşil"
    PdfHighlightColor.BLUE -> "Mavi"
    PdfHighlightColor.PINK -> "Pembe"
}
internal fun PdfHighlightColor.pdfColor(): Int = when (this) {
    PdfHighlightColor.YELLOW -> Color.rgb(255, 248, 190)
    PdfHighlightColor.GREEN -> Color.rgb(212, 245, 220)
    PdfHighlightColor.BLUE -> Color.rgb(215, 235, 255)
    PdfHighlightColor.PINK -> Color.rgb(255, 220, 235)
}

private fun EpubHighlightColor.pdfLabel(): String = when (this) {
    EpubHighlightColor.YELLOW -> "Sarı"
    EpubHighlightColor.GREEN -> "Yeşil"
    EpubHighlightColor.BLUE -> "Mavi"
    EpubHighlightColor.PINK -> "Pembe"
}

internal fun EpubHighlightColor.pdfColor(): Int = when (this) {
    EpubHighlightColor.YELLOW -> Color.rgb(255, 248, 190)
    EpubHighlightColor.GREEN -> Color.rgb(212, 245, 220)
    EpubHighlightColor.BLUE -> Color.rgb(215, 235, 255)
    EpubHighlightColor.PINK -> Color.rgb(255, 220, 235)
}

internal fun studyPdfLayout(block: StudyPdfBlock): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (block.style == StudyPdfTextStyle.SOURCE) Color.rgb(55, 55, 55) else Color.BLACK
        textSize = block.style.points
        typeface = Typeface.create("sans-serif", if (block.style.bold) Typeface.BOLD else Typeface.NORMAL)
    }
    val builder = StaticLayout.Builder.obtain(block.text, 0, block.text.length, paint, CONTENT_WIDTH)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setIncludePad(true)
        .setLineSpacing(2f, 1.15f)
        // API36's IntDef names LineBreaker. This compile-time constant inlines to the
        // same value as Layout.BREAK_STRATEGY_HIGH_QUALITY on supported older devices.
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUseLineSpacingFromFallbacks(true)
    // No ellipsize/maxLines: pagination consumes every native line and source offset.
    return builder.build()
}

internal data class StudyPdfFragment(val block: StudyPdfBlock, val layout: StaticLayout,
    val firstLine: Int, val endLine: Int, val y: Int) {
    val height: Int get() = layout.getLineTop(endLine) - layout.getLineTop(firstLine)
    val startOffset: Int get() = layout.getLineStart(firstLine)
    val endOffset: Int get() = layout.getLineStart(endLine)
    fun draw(canvas: Canvas) {
        val saved = canvas.save()
        try {
            canvas.clipRect(STUDY_PDF_MARGIN, y, STUDY_PDF_WIDTH - STUDY_PDF_MARGIN, y + height)
            (block.highlightColor?.pdfColor() ?: block.pdfHighlightColor?.pdfColor())?.let { color -> canvas.drawRect(STUDY_PDF_MARGIN.toFloat(), y.toFloat(),
                (STUDY_PDF_WIDTH - STUDY_PDF_MARGIN).toFloat(), (y + height).toFloat(),
                Paint().apply { this.color = color }) }
            canvas.translate(STUDY_PDF_MARGIN.toFloat(), (y - layout.getLineTop(firstLine)).toFloat())
            layout.draw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }
}

internal data class StudyPdfPage(val fragments: List<StudyPdfFragment>) {
    suspend fun draw(canvas: Canvas, number: Int) {
        canvas.drawColor(Color.WHITE)
        for (fragment in fragments) {
            currentCoroutineContext().ensureActive()
            fragment.draw(canvas)
        }
        currentCoroutineContext().ensureActive()
        val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.DKGRAY }
        val label = "Yaprak · $number"
        canvas.drawText(label, (STUDY_PDF_WIDTH - footer.measureText(label)) / 2,
            (STUDY_PDF_HEIGHT - 26).toFloat(), footer)
    }
}

/** Line boundaries come from the real native layout, including RTL and font fallback. */
internal object StudyPdfPagination {
    suspend fun pages(snapshot: StudyRecordsSnapshot): List<StudyPdfPage> {
        validateStudyRecordsSnapshot(snapshot)
        return pagesFromBlocks(studyPdfBlocks(snapshot))
    }

    internal suspend fun pagesFromBlocks(blocks: List<StudyPdfBlock>): List<StudyPdfPage> {
        if (blocks.isEmpty()) throw StudyExportException(StudyExportFailure.IO)
        val layouts = blocks.map { block ->
            currentCoroutineContext().ensureActive()
            block to studyPdfLayout(block)
        }
        val pages = mutableListOf<StudyPdfPage>()
        var fragments = mutableListOf<StudyPdfFragment>()
        var y = STUDY_PDF_MARGIN
        fun nextPage() {
            if (fragments.isNotEmpty()) { pages += StudyPdfPage(fragments.toList()); fragments = mutableListOf() }
            if (pages.size >= MAX_STUDY_PDF_PAGES) throw StudyExportException(StudyExportFailure.TOO_LARGE)
            y = STUDY_PDF_MARGIN
        }
        for ((index, pair) in layouts.withIndex()) {
            currentCoroutineContext().ensureActive()
            val (block, layout) = pair
            if (block.keepWithNext && index < layouts.lastIndex && fragments.isNotEmpty()) {
                // Keep a short heading/source-label chain with the first two actual body lines.
                // A long literal filename can span pages and is never truncated to enforce this.
                var following = index
                var need = 0
                while (following < layouts.size && layouts[following].first.keepWithNext) {
                    need += layouts[following].second.height + BLOCK_GAP
                    if (need > STUDY_PDF_BOTTOM - STUDY_PDF_MARGIN) break
                    following++
                }
                if (following < layouts.size && need <= STUDY_PDF_BOTTOM - STUDY_PDF_MARGIN) {
                    val body = layouts[following].second
                    need += body.getLineTop(minOf(2, body.lineCount))
                }
                if (need <= STUDY_PDF_BOTTOM - STUDY_PDF_MARGIN && need > STUDY_PDF_BOTTOM - y) nextPage()
            }
            var firstLine = 0
            while (firstLine < layout.lineCount) {
                currentCoroutineContext().ensureActive()
                var endLine = firstLine
                while (endLine < layout.lineCount &&
                    layout.getLineTop(endLine + 1) - layout.getLineTop(firstLine) <= STUDY_PDF_BOTTOM - y) endLine++
                if (endLine == firstLine) {
                    if (fragments.isEmpty()) throw StudyExportException(StudyExportFailure.IO)
                    nextPage(); continue
                }
                val fragment = StudyPdfFragment(block, layout, firstLine, endLine, y)
                fragments += fragment; y += fragment.height
                firstLine = endLine
                if (firstLine < layout.lineCount) nextPage()
            }
            y += BLOCK_GAP
        }
        if (fragments.isNotEmpty()) pages += StudyPdfPage(fragments.toList())
        currentCoroutineContext().ensureActive()
        return pages
    }
}

/** Native PDF serialization can write any chunk size; all callback bytes share the same bound. */
internal class BoundedPdfOutput(private val context: CoroutineContext) : OutputStream() {
    private val output = ByteArrayOutputStream()
    private var limitFailure: StudyExportException? = null
    override fun write(value: Int) {
        context.ensureActive()
        checkLimit(1); output.write(value)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        context.ensureActive()
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        checkLimit(length); output.write(bytes, offset, length)
    }
    private fun checkLimit(length: Int) {
        limitFailure?.let { throw it }
        if (length > MAX_STUDY_EXPORT_BYTES - output.size()) {
            val failure = StudyExportException(StudyExportFailure.TOO_LARGE)
            limitFailure = failure; throw failure
        }
    }
    fun bytes(): ByteArray {
        context.ensureActive()
        rethrowLimitFailure()
        if (output.size() == 0) throw StudyExportException(StudyExportFailure.IO)
        return output.toByteArray()
    }
    fun rethrowLimitFailure() { limitFailure?.let { throw it } }
}

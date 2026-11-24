package com.yaprak.reader.domain.pdfink

import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import kotlinx.coroutines.flow.Flow

/**
 * Highlighter ink drawn over a PDF page. Geometry is stored in page units, never screen pixels:
 * x is a fraction of the page width and y of the page height, both scaled to [0, PDF_INK_UNIT].
 * A segment's width is a fraction of the page height (same scale). Zoom, pan, crop, render
 * resolution and scroll mode therefore never change where a stroke sits on the page.
 */
const val PDF_INK_UNIT = 100_000
const val MAX_PDF_INK_SEGMENTS = 64
const val MAX_PDF_INK_POINTS = 2_000
const val MAX_PDF_INK_STROKES_PER_BOOK = 5_000
const val MIN_PDF_INK_WIDTH = 50        // 0.05% of the page height
const val MAX_PDF_INK_WIDTH = 15_000    // 15% of the page height
/** No text under the finger (scanned page, margin): about a 13 pt line on a Letter/A4 page. */
const val DEFAULT_PDF_INK_WIDTH = 1_700

enum class PdfInkMode { FREEHAND, ALIGNED }

data class PdfInkPoint(val x: Int, val y: Int)

/** One continuous painted band. FREEHAND has one; ALIGNED has one straight band per text line. */
data class PdfInkSegment(val width: Int, val points: List<PdfInkPoint>)

data class PdfInkStroke(
    val id: String,
    val pageIndex: Int,
    val color: PdfHighlightColor,
    val mode: PdfInkMode,
    val segments: List<PdfInkSegment>,
    val createdAt: Long,
)

class PdfInkLimitException : Exception("PDF highlighter limit reached")

interface PdfInkRepository {
    fun observe(bookId: String, contentSha256: String): Flow<List<PdfInkStroke>>
    suspend fun add(bookId: String, contentSha256: String, stroke: PdfInkStroke)
    suspend fun remove(bookId: String, contentSha256: String, strokeId: String)
}

fun validatePdfInkSegments(segments: List<PdfInkSegment>) {
    require(segments.size in 1..MAX_PDF_INK_SEGMENTS)
    var total = 0
    segments.forEach { segment ->
        require(segment.width in MIN_PDF_INK_WIDTH..MAX_PDF_INK_WIDTH)
        require(segment.points.isNotEmpty())
        total += segment.points.size
        segment.points.forEach { require(it.x in 0..PDF_INK_UNIT && it.y in 0..PDF_INK_UNIT) }
    }
    require(total <= MAX_PDF_INK_POINTS)
}

fun validatePdfInkStroke(stroke: PdfInkStroke) {
    require(stroke.id.isNotBlank() && stroke.id.length <= 64)
    require(stroke.pageIndex >= 0 && stroke.createdAt >= 0)
    validatePdfInkSegments(stroke.segments)
}

/**
 * Compact, strict text form: `1;w,x,y,x,y;w,x,y` (version, then one group per segment).
 * Kept free of JSON so the same codec runs in Room, backup validation and plain JVM tests.
 */
object PdfInkGeometryCodec {
    private const val VERSION = "1"

    fun encode(segments: List<PdfInkSegment>): String {
        validatePdfInkSegments(segments)
        return buildString {
            append(VERSION)
            segments.forEach { segment ->
                append(';').append(segment.width)
                segment.points.forEach { append(',').append(it.x).append(',').append(it.y) }
            }
        }
    }

    fun decode(text: String): List<PdfInkSegment> {
        require(text.length <= 32_000)
        val groups = text.split(';')
        require(groups.first() == VERSION && groups.size >= 2)
        val segments = groups.drop(1).map { group ->
            val numbers = group.split(',').map { token ->
                require(token.isNotEmpty() && token.length <= 6 && token.all { it in '0'..'9' })
                token.toInt()
            }
            require(numbers.size >= 3 && numbers.size % 2 == 1)
            PdfInkSegment(numbers[0], (1 until numbers.size step 2).map { PdfInkPoint(numbers[it], numbers[it + 1]) })
        }
        validatePdfInkSegments(segments)
        // Canonical form only: a re-encoded value must be byte-identical (no leading zeros etc.).
        require(encode(segments) == text)
        return segments
    }
}

/** A text rectangle as fractions of the page (0..1), from the PDF's own text geometry. */
data class PdfInkTextRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val height get() = bottom - top
}

private fun unit(fraction: Double): Int = (fraction * PDF_INK_UNIT).let { if (it.isFinite()) Math.round(it) else 0L }
    .coerceIn(0L, PDF_INK_UNIT.toLong()).toInt()

/** Band thickness for one line of text: its glyph-box height, kept inside sane bounds. */
fun pdfInkWidthForLine(lineHeight: Double): Int =
    if (!lineHeight.isFinite() || lineHeight <= 0.0) DEFAULT_PDF_INK_WIDTH
    else unit(lineHeight).coerceIn(MIN_PDF_INK_WIDTH, MAX_PDF_INK_WIDTH)

/**
 * Groups text rectangles into lines (rectangles that overlap vertically by at least half of the
 * shorter one) and returns one straight band per line spanning the line's words, ordered top to
 * bottom. The band's thickness is that line's own height, so a heading gets a thicker band than
 * footnote text.
 */
fun pdfInkAlignedSegments(rects: List<PdfInkTextRect>): List<PdfInkSegment> {
    val usable = rects.filter { it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
        it.right > it.left && it.bottom > it.top }
        .sortedWith(compareBy({ (it.top + it.bottom) / 2 }, { it.left }))
    val lines = ArrayList<MutableList<PdfInkTextRect>>()
    usable.forEach { rect ->
        val line = lines.lastOrNull { line ->
            val top = line.minOf { it.top }; val bottom = line.maxOf { it.bottom }
            val overlap = minOf(bottom, rect.bottom) - maxOf(top, rect.top)
            overlap >= 0.5 * minOf(bottom - top, rect.height)
        }
        if (line == null) lines += mutableListOf(rect) else line += rect
    }
    return lines.take(MAX_PDF_INK_SEGMENTS).map { line ->
        // The median height ignores a stray superscript or drop cap on the same line.
        val heights = line.map { it.height }.sorted()
        val height = heights[heights.size / 2]
        val center = line.map { (it.top + it.bottom) / 2 }.sorted()[line.size / 2]
        val y = unit(center)
        PdfInkSegment(pdfInkWidthForLine(height),
            listOf(PdfInkPoint(unit(line.minOf { it.left }), y), PdfInkPoint(unit(line.maxOf { it.right }), y)))
    }
}

/**
 * Drops points closer than [minStep] page units to the previously kept point (the last one is
 * always kept) and caps the count, so a long slow stroke stays small in the database and backup.
 */
fun simplifyPdfInkPoints(points: List<PdfInkPoint>, minStep: Int = 250): List<PdfInkPoint> {
    if (points.size <= 2) return points
    val kept = ArrayList<PdfInkPoint>(points.size)
    kept += points.first()
    for (index in 1 until points.size - 1) {
        val last = kept.last(); val point = points[index]
        val dx = (point.x - last.x).toLong(); val dy = (point.y - last.y).toLong()
        if (dx * dx + dy * dy >= minStep.toLong() * minStep) kept += point
    }
    kept += points.last()
    if (kept.size <= MAX_PDF_INK_POINTS) return kept
    val step = kept.size.toDouble() / (MAX_PDF_INK_POINTS - 1)
    return (0 until MAX_PDF_INK_POINTS - 1).map { kept[(it * step).toInt()] } + kept.last()
}

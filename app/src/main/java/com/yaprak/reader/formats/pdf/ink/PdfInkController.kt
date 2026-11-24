package com.yaprak.reader.formats.pdf.ink

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.pdfink.DEFAULT_PDF_INK_WIDTH
import com.yaprak.reader.domain.pdfink.PdfInkLimitException
import com.yaprak.reader.domain.pdfink.PdfInkMode
import com.yaprak.reader.domain.pdfink.PdfInkPoint
import com.yaprak.reader.domain.pdfink.PdfInkRepository
import com.yaprak.reader.domain.pdfink.PdfInkSegment
import com.yaprak.reader.domain.pdfink.PdfInkStroke
import com.yaprak.reader.domain.pdfink.PdfInkTextRect
import com.yaprak.reader.domain.pdfink.pdfInkAlignedSegments
import com.yaprak.reader.domain.pdfink.pdfInkWidthForLine
import com.yaprak.reader.domain.pdfink.simplifyPdfInkPoints
import com.yaprak.reader.reader.PdfInkTool
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** PDF text geometry near a point or between two points, as page fractions; null when there is none. */
internal fun interface PdfInkTextLookup {
    suspend fun rects(page: Int, start: PdfInkPoint, stop: PdfInkPoint): List<PdfInkTextRect>?
}

internal data class PdfInkToolState(
    val color: PdfHighlightColor = PdfHighlightColor.YELLOW,
    val mode: PdfInkMode = PdfInkMode.ALIGNED,
    val eraser: Boolean = false,
)

/** The stroke under the finger, painted live and saved when the finger lifts. */
internal data class PdfInkDraft(val page: Int, val color: PdfHighlightColor, val mode: PdfInkMode,
    val segments: List<PdfInkSegment>)

private sealed interface PdfInkUndo {
    data class Added(val stroke: PdfInkStroke) : PdfInkUndo
    data class Erased(val stroke: PdfInkStroke) : PdfInkUndo
}

/**
 * Main-thread owner of the highlighter. Gestures report page-unit points; this class decides the
 * band thickness from the PDF's text (line height), keeps the live draft and writes Room.
 * Text lookups are best effort: with no text the pen still works with a default thickness.
 */
internal class PdfInkController(
    private val book: Book,
    private val repository: PdfInkRepository,
    private val text: PdfInkTextLookup?,
    private val canEnter: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : PdfInkTool {
    private val activeState = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = activeState.asStateFlow()
    private val toolState = MutableStateFlow(PdfInkToolState(mode = if (text != null) PdfInkMode.ALIGNED else PdfInkMode.FREEHAND))
    val tool: StateFlow<PdfInkToolState> = toolState.asStateFlow()
    val textAvailable get() = text != null

    private val stored = MutableStateFlow<List<PdfInkStroke>>(emptyList())
    private val pending = MutableStateFlow<List<PdfInkStroke>>(emptyList())
    private val hidden = MutableStateFlow<Set<String>>(emptySet())
    private val visible = MutableStateFlow<List<PdfInkStroke>>(emptyList())
    /** Saved strokes plus ones still being written, minus ones being erased. */
    val strokes: StateFlow<List<PdfInkStroke>> = visible.asStateFlow()
    private val draftState = MutableStateFlow<PdfInkDraft?>(null)
    val draft: StateFlow<PdfInkDraft?> = draftState.asStateFlow()
    private val undoStack = ArrayDeque<PdfInkUndo>()
    private val undoAvailable = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = undoAvailable.asStateFlow()
    private val noticeState = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = noticeState.asStateFlow()

    private var scope: CoroutineScope? = null
    private var observer: Job? = null
    private var lookup: Job? = null
    private var widthLookup: Deferred<Int?>? = null
    private var gesture = 0L
    private var points = ArrayList<PdfInkPoint>()
    private var width = DEFAULT_PDF_INK_WIDTH
    private var aligned: List<PdfInkSegment>? = null
    private var alignedFor: PdfInkPoint? = null
    private val pageWidths = HashMap<Int, Int>()

    fun attach(owner: CoroutineScope) {
        detach()
        scope = owner
        observer = owner.launch {
            launch { combine(stored, pending, hidden) { saved, writing, erased ->
                (saved + writing.filter { item -> saved.none { it.id == item.id } }).filter { it.id !in erased }
            }.collect { visible.value = it } }
            try {
                repository.observe(book.id, book.sha256).collect { saved ->
                    stored.value = saved
                    val ids = saved.mapTo(HashSet()) { it.id }
                    pending.update { list -> list.filterNot { it.id in ids } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { noticeState.value = "Fosforlu kalem çizimleri okunamadı." }
        }
    }

    fun detach() {
        cancel(); observer?.cancel(); observer = null; scope = null
        activeState.value = false
    }

    override fun enter(): Boolean {
        if (scope == null || !canEnter()) return false
        activeState.value = true
        return true
    }
    override fun exit() { cancel(); activeState.value = false; toolState.update { it.copy(eraser = false) } }

    fun select(color: PdfHighlightColor) = toolState.update { it.copy(color = color, eraser = false) }
    fun select(mode: PdfInkMode) = toolState.update { it.copy(mode = mode, eraser = false) }
    fun toggleEraser() = toolState.update { it.copy(eraser = !it.eraser) }
    fun dismissNotice() { noticeState.value = null }

    fun begin(page: Int, point: PdfInkPoint) {
        cancel()
        if (!activeState.value || toolState.value.eraser) return
        val id = ++gesture
        val tool = toolState.value
        points = arrayListOf(point)
        width = pageWidths[page] ?: DEFAULT_PDF_INK_WIDTH
        aligned = null; alignedFor = null
        widthLookup = null
        publish(page)
        val source = text ?: return
        if (tool.mode == PdfInkMode.FREEHAND) {
            // The word under the first touch gives the line height for the whole stroke.
            widthLookup = scope?.async {
                val rects = safeRects(source, page, point, point) ?: return@async null
                val found = pdfInkWidthForLine(rects.map { it.height }.sorted().let { it[it.size / 2] })
                pageWidths[page] = found
                if (id == gesture) { width = found; publish(page) }
                found
            }
        }
    }

    fun extend(page: Int, point: PdfInkPoint) {
        val draft = draftState.value ?: return
        if (draft.page != page) return
        if (draft.mode == PdfInkMode.FREEHAND) {
            val last = points.last()
            val dx = (point.x - last.x).toLong(); val dy = (point.y - last.y).toLong()
            if (dx * dx + dy * dy < 40L * 40L || points.size >= 6_000) return
            points += point
        } else {
            if (points.size == 1) points += point else points[points.lastIndex] = point
            requestAligned(page, gesture)
        }
        publish(page)
    }

    /**
     * Commits the draft: it is shown at once as a pending stroke, so starting the next stroke right
     * away never loses it. Aligned strokes then snap (briefly waiting) to the final word geometry.
     */
    fun finish() {
        val draft = draftState.value ?: return
        val owner = scope ?: return cancel()
        val captured = points.toList()
        val bandWidth = width
        val freehandWidth = widthLookup
        widthLookup = null
        cancel() // Ends this gesture; the lookups below belong to the committed stroke only.
        val stroke = PdfInkStroke(newId(), draft.page, draft.color, draft.mode,
            if (draft.mode == PdfInkMode.FREEHAND) listOf(PdfInkSegment(bandWidth, simplifyPdfInkPoints(captured)))
            else draft.segments, clock())
        pending.update { it + stroke }
        owner.launch {
            val segments = if (draft.mode == PdfInkMode.ALIGNED) {
                val start = captured.first(); val stop = captured.last()
                val exact = text?.let { source ->
                    withTimeoutOrNull(1_500) { safeRects(source, draft.page, start, stop) }
                }?.let(::pdfInkAlignedSegments)?.takeIf { it.isNotEmpty() }
                exact?.also { found -> pageWidths[draft.page] = found.first().width }
                    ?: listOf(PdfInkSegment(bandWidth, listOf(start, PdfInkPoint(stop.x, start.y))))
            } else {
                val measured = freehandWidth?.let { withTimeoutOrNull(800) { it.await() } } ?: bandWidth
                listOf(PdfInkSegment(measured, simplifyPdfInkPoints(captured)))
            }
            val final = stroke.copy(segments = segments)
            save(final, PdfInkUndo.Added(final))
        }
    }

    fun cancel() {
        gesture++
        lookup?.cancel(); lookup = null
        widthLookup?.cancel(); widthLookup = null
        draftState.value = null
    }

    fun erase(stroke: PdfInkStroke) {
        if (stroke.id in hidden.value) return
        val owner = scope ?: return
        hidden.update { it + stroke.id }
        owner.launch {
            try {
                repository.remove(book.id, book.sha256, stroke.id)
                pending.update { list -> list.filterNot { it.id == stroke.id } }
                push(PdfInkUndo.Erased(stroke))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                hidden.update { it - stroke.id }
                noticeState.value = "Çizim silinemedi. Yeniden dene."
            }
        }
    }

    fun undo() {
        val last = undoStack.removeLastOrNull() ?: return
        undoAvailable.value = undoStack.isNotEmpty()
        val owner = scope ?: return
        when (last) {
            is PdfInkUndo.Added -> {
                hidden.update { it + last.stroke.id }
                owner.launch {
                    try { repository.remove(book.id, book.sha256, last.stroke.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        hidden.update { it - last.stroke.id }
                        noticeState.value = "Geri alınamadı. Yeniden dene."
                    }
                }
            }
            is PdfInkUndo.Erased -> {
                hidden.update { it - last.stroke.id }
                owner.launch { save(last.stroke, null) }
            }
        }
    }

    private suspend fun save(stroke: PdfInkStroke, undo: PdfInkUndo?) {
        pending.update { list -> list.filterNot { it.id == stroke.id } + stroke }
        // On success the stroke stays pending until Room's own emission contains it (no flicker).
        fun drop() = pending.update { list -> list.filterNot { it.id == stroke.id } }
        try {
            repository.add(book.id, book.sha256, stroke)
            undo?.let(::push)
        } catch (cancelled: CancellationException) { drop(); throw cancelled }
        catch (_: PdfInkLimitException) { drop(); noticeState.value = "Bu kitapta en fazla 5000 çizim saklanabilir." }
        catch (_: Exception) { drop(); noticeState.value = "Çizim kaydedilemedi." }
    }

    private fun push(entry: PdfInkUndo) {
        undoStack.addLast(entry)
        while (undoStack.size > 100) undoStack.removeFirst()
        undoAvailable.value = true
    }

    private fun requestAligned(page: Int, id: Long) {
        val source = text ?: return
        if (lookup?.isActive == true) return // The running lookup re-reads the latest point when it ends.
        lookup = scope?.launch {
            while (id == gesture) {
                val stop = points.last()
                if (stop == alignedFor) return@launch
                val segments = safeRects(source, page, points.first(), stop)?.let(::pdfInkAlignedSegments)
                if (id != gesture) return@launch
                aligned = segments?.takeIf { it.isNotEmpty() }
                alignedFor = stop
                aligned?.firstOrNull()?.let { width = it.width }
                publish(page)
            }
        }
    }

    private fun straightBand(start: PdfInkPoint, stop: PdfInkPoint) =
        listOf(PdfInkSegment(width, listOf(start, PdfInkPoint(stop.x, start.y))))

    private fun publish(page: Int) {
        val tool = toolState.value
        val segments = if (tool.mode == PdfInkMode.ALIGNED) aligned ?: straightBand(points.first(), points.last())
            else listOf(PdfInkSegment(width, points.toList()))
        draftState.value = PdfInkDraft(page, tool.color, tool.mode, segments)
    }

    private suspend fun safeRects(source: PdfInkTextLookup, page: Int, start: PdfInkPoint, stop: PdfInkPoint) = try {
        source.rects(page, start, stop)?.filter { it.right > it.left && it.bottom > it.top }?.takeIf { it.isNotEmpty() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}

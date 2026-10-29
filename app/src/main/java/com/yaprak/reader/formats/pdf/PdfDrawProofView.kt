package com.yaprak.reader.formats.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.IntSize
import com.yaprak.reader.reader.PdfPageCrop

/** Confirms after the real Compose child draws, including replay of its cached native layers. */
@SuppressLint("ViewConstructor") // Programmatic fragment child requires the current status and owner; never inflated from XML.
internal class PdfDrawProofView(
    context: Context,
    private val status: PdfRenderStatus,
    private val active: () -> Boolean,
) : FrameLayout(context) {
    private val witnesses = mutableMapOf<Int, PdfPageDrawWitness>()
    private val localVisible = Rect()
    private val globalVisible = Rect()
    private val location = IntArray(2)
    private val nativeBounds = RectF()
    private var readingLineWindowY: Float? = null
    fun updateReadingLine(windowY: Float?) {
        val valid = windowY?.takeIf { it.isFinite() }
        if (readingLineWindowY == valid) return
        readingLineWindowY = valid
        status.clearDraw(); invalidate()
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (!primaryVisible()) status.clearDraw()
        true
    }

    fun register(page: Int, witness: PdfPageDrawWitness) {
        witnesses[page] = witness
        if (page == status.requestedPage.value) status.clearDraw()
        invalidate()
    }

    fun unregister(page: Int, witness: PdfPageDrawWitness) {
        if (witnesses[page] !== witness) return
        witnesses.remove(page)
        if (page == status.requestedPage.value) status.clearDraw()
    }

    fun revoke() { status.clearDraw() }

    private fun hostVisible(): Boolean {
        if (!active() || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE || width <= 0 || height <= 0) return false
        var ancestor: View? = this
        while (ancestor != null) {
            if (!ancestor.alpha.isFinite() || ancestor.alpha <= 0f) return false
            ancestor = ancestor.parent as? View
        }
        return getLocalVisibleRect(localVisible) && !localVisible.isEmpty &&
            getGlobalVisibleRect(globalVisible) && !globalVisible.isEmpty
    }

    fun primaryVisible(): Boolean {
        if (!hostVisible()) return false
        val page = status.requestedPage.value
        val witness = witnesses[page] ?: return false
        return currentPointTransform(page, witness) && visibleBounds(witness)
    }

    /** Admission for focusing a visible neighbor; its preparation alone is insufficient. */
    fun pageVisible(page: Int): Boolean {
        val witness = witnesses[page] ?: return false
        val ticket = witness.paintedTicket ?: return false
        return hostVisible() && currentPointTransform(page, witness) && status.preparedTicket(page, ticket) && visibleBounds(witness)
    }

    private fun currentPointTransform(page: Int, witness: PdfPageDrawWitness): Boolean =
        !status.scrolling || witness.pointNavigationRevision == status.scrollNavigation.value.pointRevision(page)

    private fun visibleBounds(witness: PdfPageDrawWitness): Boolean {
        if (!witness.visibleWindowBounds(nativeBounds)) return false
        getLocationInWindow(location)
        nativeBounds.offset(-location[0].toFloat(), -location[1].toFloat())
        return nativeBounds.intersect(localVisible.left.toFloat(), localVisible.top.toFloat(),
            localVisible.right.toFloat(), localVisible.bottom.toFloat()) && !nativeBounds.isEmpty
    }

    @Suppress("DEPRECATION") // API26 uses the original Canvas overload; the four-float overload requires API30.
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val page = status.requestedPage.value
        val witness = witnesses[page]
        if (witness == null || !hostVisible() || !currentPointTransform(page, witness) || !visibleBounds(witness) ||
            canvas.quickReject(nativeBounds, Canvas.EdgeType.AA)) {
            status.clearDraw()
            return
        }
        val ticket = witness.paintedTicket ?: run { status.clearDraw(); return }
        val anchor = if (status.scrolling) readingLineWindowY?.let(witness::resumeAnchorY) else null
        status.finishDraw(page, ticket, anchor, !status.scrolling || readingLineWindowY != null,
            pointRequiresBaselineReset = witness.pointRequiresBaselineReset)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        status.clearDraw()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) status.clearDraw()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow && visibility != VISIBLE) status.clearDraw()
    }
}

/** Geometry and painter acknowledgement are prerequisites, never visibility evidence by themselves. */
internal class PdfPageDrawWitness(private val changed: () -> Unit) {
    private var bitmap: Bitmap? = null
    private var ticket: Long? = null
    private var geometry: PdfDrawGeometry? = null
    private var painted = false
    private var coordinates: LayoutCoordinates? = null
    private var previousBounds: androidx.compose.ui.geometry.Rect? = null
    private var previousOrigin: Offset? = null
    private val imageBounds = RectF()
    private var validGeometry = false
    val paintedTicket: Long? get() = ticket.takeIf { painted && bitmap?.isRecycled == false }
    val pointNavigationRevision: Long? get() = geometry?.pointNavigationRevision
    val pointRequiresBaselineReset: Boolean get() = geometry?.let { it.zoom != 1f || it.pan != Offset.Zero } == true

    fun update(image: Bitmap?, currentTicket: Long?, currentGeometry: PdfDrawGeometry?) {
        if (bitmap === image && ticket == currentTicket && geometry == currentGeometry) return
        bitmap = image; ticket = currentTicket; geometry = currentGeometry; painted = false
        validGeometry = currentGeometry?.destination(imageBounds) == true
        changed()
    }

    fun position(value: LayoutCoordinates) {
        coordinates = value
        val bounds = if (value.isAttached) value.boundsInWindow() else null
        // A long page can move behind the same clipped viewport. Its reading line
        // still changes, even when Compose reuses the cached child drawing.
        val origin = if (value.isAttached) value.localToWindow(Offset.Zero) else null
        if (bounds != previousBounds || origin != previousOrigin) {
            previousBounds = bounds; previousOrigin = origin; changed()
        }
    }

    fun painted(image: Bitmap, currentTicket: Long?, currentGeometry: PdfDrawGeometry) {
        if (bitmap === image && !image.isRecycled && currentTicket != null && ticket == currentTicket && geometry == currentGeometry)
            painted = true
    }

    fun visibleWindowBounds(out: RectF): Boolean {
        val layout = coordinates ?: return false
        val value = geometry ?: return false
        if (!layout.isAttached || bitmap?.isRecycled != false || layout.size != value.viewport || paintedTicket == null) return false
        if (!validGeometry) return false
        val start = layout.localToWindow(Offset(imageBounds.left, imageBounds.top))
        val end = layout.localToWindow(Offset(imageBounds.right, imageBounds.bottom))
        if (!start.x.isFinite() || !start.y.isFinite() || !end.x.isFinite() || !end.y.isFinite()) return false
        out.set(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))
        val visible = layout.boundsInWindow()
        return out.intersect(visible.left, visible.top, visible.right, visible.bottom) && !out.isEmpty
    }

    /** Only the exact painted witness's visible geometry can supply a point for persistence. */
    fun resumeAnchorY(windowY: Float): Int? {
        val layout = coordinates ?: return null
        val value = geometry ?: return null
        val visible = RectF()
        if (!windowY.isFinite() || !visibleWindowBounds(visible)) return null
        val point = layout.windowToLocal(Offset(visible.centerX(), windowY.coerceIn(visible.top, visible.bottom)))
        return value.resumeAnchorY(point.y)
    }
}

internal data class PdfDrawGeometry(
    val viewport: IntSize,
    val source: IntSize,
    val crop: PdfPageCrop,
    val fit: PdfFitMode,
    val zoom: Float,
    val pan: Offset,
    val pointNavigationRevision: Long = 0L,
) {
    fun destination(out: RectF): Boolean {
        if (viewport.width <= 0 || viewport.height <= 0 || source.width <= 0 || source.height <= 0 ||
            !zoom.isFinite() || zoom < 1f || !pan.x.isFinite() || !pan.y.isFinite()) return false
        val area = crop.bounds(source.width, source.height)
        val scale = if (fit == PdfFitMode.WIDTH) viewport.width.toFloat() / area.width
            else minOf(viewport.width.toFloat() / area.width, viewport.height.toFloat() / area.height)
        val width = area.width * scale
        val height = area.height * scale
        val left = (viewport.width - width) / 2
        val top = if (fit == PdfFitMode.WIDTH) 0f else (viewport.height - height) / 2
        val centerX = viewport.width / 2f; val centerY = viewport.height / 2f
        out.set((left - centerX) * zoom + centerX + pan.x, (top - centerY) * zoom + centerY + pan.y,
            (left + width - centerX) * zoom + centerX + pan.x, (top + height - centerY) * zoom + centerY + pan.y)
        return out.left.isFinite() && out.top.isFinite() && out.right.isFinite() && out.bottom.isFinite() &&
            out.width().isFinite() && out.height().isFinite() && !out.isEmpty
    }
}

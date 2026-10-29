package com.yaprak.reader.formats.cbz

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewTreeObserver
import com.yaprak.reader.reader.ReaderTap

/** Owns one bounded frame of real originals. Only a visible primary draw grants an anchor. */
internal class CbzPageView @JvmOverloads constructor(context: Context, private val active: () -> Boolean = { false },
    private val onTap: () -> Unit = {}, private val customTap: (ReaderTap) -> Boolean = { false },
) : View(context) {
    var frameDrawn: ((Int?) -> Unit)? = null
    var viewportChanged: ((Int, Int) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val imageBounds = RectF()
    private val visibleBounds = RectF()
    private val visible = Rect()
    private val global = Rect()
    private var pendingTap: ReaderTap? = null
    private var frame: CbzFrame? = null
    private val page get() = frame?.anchor
    private var zoom = 1f; private var panX = 0f; private var panY = 0f
    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        if (!frameVisible()) frameDrawn?.invoke(null)
        true
    }
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (active() && page != null) {
                pendingTap = ReaderTap.fromPixels(event.x, event.y, width, height)
                try { performClick() } finally { pendingTap = null }
            }
            return true
        }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (active() && frame != null) { zoom = if (zoom > 1f) 1f else 3f; panX = 0f; panY = 0f; invalidate() }
            return true
        }
        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (active() && zoom > 1f && !scaleDetector.isInProgress) {
                panX -= distanceX; panY -= distanceY; constrainPan(); invalidate()
            }
            return true
        }
    })
    private val scaleDetector: ScaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!active() || frame == null || !detector.scaleFactor.isFinite()) return false
            val old = zoom; zoom = (zoom * detector.scaleFactor).coerceIn(1f, 6f); val factor = zoom / old
            panX = detector.focusX - width / 2f - (detector.focusX - width / 2f - panX) * factor
            panY = detector.focusY - height / 2f - (detector.focusY - height / 2f - panY) * factor
            constrainPan(); invalidate(); return true
        }
    })
    init { isClickable = true; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun install(index: Int, image: Bitmap, label: String) {
        install(CbzFrame(index, listOf(CbzRenderedPage(index, image))), label)
    }
    fun install(value: CbzFrame, label: String) {
        clear(); frame = value; contentDescription = label; invalidate()
    }
    fun clear() {
        frameDrawn?.invoke(null)
        val old = frame; frame = null; old?.close()
        zoom = 1f; panX = 0f; panY = 0f; invalidate()
    }
    private fun fittedHeight(value: CbzFrame) = minOf(width / value.aspectRatio, height.toFloat()) * zoom
    private fun constrainPan() {
        val value = frame ?: return
        val fitted = fittedHeight(value)
        val dx = ((value.aspectRatio * fitted - width) / 2).coerceAtLeast(0f)
        val dy = ((fitted - height) / 2).coerceAtLeast(0f)
        panX = panX.coerceIn(-dx, dx); panY = panY.coerceIn(-dy, dy)
    }
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        zoom = 1f; panX = 0f; panY = 0f; frameDrawn?.invoke(null)
        viewportChanged?.invoke(width, height)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.BLACK)
        val value = frame
        if (value == null || value.pages.any { it.bitmap.isRecycled } || width <= 0 || height <= 0) { frameDrawn?.invoke(null); return }
        val fitted = fittedHeight(value)
        var x = (width - value.aspectRatio * fitted) / 2 + panX
        val y = (height - fitted) / 2 + panY
        for (member in value.pages) {
            val image = member.bitmap
            val pageWidth = image.width.toFloat() / image.height * fitted
            destination.set(x, y, x + pageWidth, y + fitted)
            canvas.drawBitmap(image, null, destination, paint)
            x += pageWidth
        }
        frameDrawn?.invoke(value.anchor.takeIf { frameVisible() })
    }
    private fun frameVisible(): Boolean {
        val value = frame ?: return false
        if (value.pages.any { it.bitmap.isRecycled } || width <= 0 || height <= 0 || !active() || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE) return false
        var ancestor: View? = this
        while (ancestor != null) {
            if (!ancestor.alpha.isFinite() || ancestor.alpha <= 0f) return false
            ancestor = ancestor.parent as? View
        }
        val fitted = fittedHeight(value)
        var x = (width - value.aspectRatio * fitted) / 2 + panX
        val y = (height - fitted) / 2 + panY
        for (member in value.pages) {
            val pageWidth = member.bitmap.width.toFloat() / member.bitmap.height * fitted
            if (member.index == value.anchor) { imageBounds.set(x, y, x + pageWidth, y + fitted); break }
            x += pageWidth
        }
        if (!getLocalVisibleRect(visible) || !getGlobalVisibleRect(global) || global.width() <= 0 || global.height() <= 0) return false
        visibleBounds.set(visible)
        return RectF.intersects(imageBounds, visibleBounds)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(beforeDraw) }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) frameDrawn?.invoke(null) else invalidate()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) frameDrawn?.invoke(null) else invalidate()
    }
    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(beforeDraw)
        frameDrawn?.invoke(null); super.onDetachedFromWindow()
    }
    // Confirmed single taps call performClick through GestureDetector; ACTION_UP is too early
    // because a double tap zooms and must never open tools or invoke a navigation zone.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active() || frame == null) return false
        scaleDetector.onTouchEvent(event); detector.onTouchEvent(event); return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        if (active() && page != null) {
            val tap = pendingTap
            if (tap == null || !customTap(tap)) onTap()
        }
        return true
    }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.ImageView"
}

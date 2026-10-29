package com.yaprak.reader.feature.reader

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Optional inner-left-margin vertical finger gesture. Native interception cancels the child. */
internal class ReaderBrightnessGestureLayout(
    context: Context,
    private val allowed: () -> Boolean,
    private val readerIdentity: () -> Any?,
    private val basePercent: () -> Int?,
    private val onPreview: (Int) -> Unit,
    private val onCommit: (Int) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val density = resources.displayMetrics.density
    private val accessibility get() = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    private var candidate: Candidate? = null
    private var drain = false
    private var dispatchId = 0L
    private var handledDispatch = -1L

    private data class Candidate(val pointer: Int, val device: Int, val downTime: Long,
        val width: Int, val height: Int, val identity: Any, val base: Int, val x: Float, val y: Float,
        var claimed: Boolean = false, var preview: Int = base)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val id = ++dispatchId
        val before = candidate
        val delivered = super.dispatchTouchEvent(event)
        // ViewGroup's first intercepting MOVE cancels the child but does not send that
        // same MOVE to the parent. Handle it only after native cancellation succeeds.
        // A child callback can revoke readiness or reenter dispatch; never retarget it.
        if (event.actionMasked == MotionEvent.ACTION_MOVE && before != null && candidate === before &&
            before.claimed && id == dispatchId && handledDispatch != id)
            return onTouchEvent(event) || delivered
        return delivered
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelGesture(); drain = false
            val base = basePercent()
            val identity = readerIdentity()
            val bars = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemGestures())
            val left = max(bars?.left ?: 0, gestures?.left ?: 0) + 16f * density
            val right = minOf(width / 4f, left + 32f * density)
            val top = (bars?.top ?: 0) + 24f * density
            val bottom = height - (bars?.bottom ?: 0) - 24f * density
            if (allowed() && accessibility?.isTouchExplorationEnabled != true && identity != null &&
                base != null && base in 10..100 && width > 0 && height > 0 && event.pointerCount == 1 &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && event.x.isFinite() && event.y.isFinite() &&
                event.x in left..right && event.y in top..bottom)
                candidate = Candidate(event.getPointerId(0), event.deviceId, event.downTime,
                    width, height, identity, base, event.x, event.y)
            return false
        }
        if (drain) return true
        val current = candidate ?: return false
        if (!valid(event, current)) { cancelGesture(); return drain }
        if (current.claimed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (event.eventTime - current.downTime > ViewConfiguration.getLongPressTimeout()) {
                    candidate = null; return false
                }
                val vertical = abs(event.y - current.y)
                val horizontal = abs(event.x - current.x)
                if (horizontal > slop && horizontal >= vertical) { candidate = null; return false }
                if (vertical > slop && vertical > horizontal * 1.5f) {
                    current.claimed = true
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> candidate = null
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drain) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) drain = false
            return true
        }
        val current = candidate ?: return false
        if (!current.claimed) return false
        if (!valid(event, current)) {
            cancelGesture()
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) drain = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                handledDispatch = dispatchId
                val percent = percentage(event, current)
                current.preview = percent
                onPreview(percent)
            }
            MotionEvent.ACTION_UP -> {
                val percent = percentage(event, current)
                onPreview(percent)
                candidate = null
                onCommit(percent)
            }
            MotionEvent.ACTION_CANCEL -> { candidate = null; onCancel() }
        }
        return true
    }

    private fun percentage(event: MotionEvent, current: Candidate) =
        (current.base + (current.y - event.y) / current.height * 90f).roundToInt().coerceIn(10, 100)

    private fun valid(event: MotionEvent, current: Candidate): Boolean = allowed() &&
        accessibility?.isTouchExplorationEnabled != true && readerIdentity() === current.identity &&
        basePercent() == current.base &&
        width == current.width && height == current.height && event.pointerCount == 1 &&
        event.deviceId == current.device && event.downTime == current.downTime && event.eventTime >= current.downTime &&
        event.getPointerId(0) == current.pointer && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
        event.x.isFinite() && event.y.isFinite() && event.actionMasked != MotionEvent.ACTION_POINTER_DOWN &&
        event.actionMasked != MotionEvent.ACTION_POINTER_UP

    fun cancelGesture() {
        val wasClaimed = candidate?.claimed == true
        candidate = null
        if (wasClaimed) { drain = true; onCancel() }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw || h != oldh) cancelGesture()
        super.onSizeChanged(w, h, oldw, oldh)
    }
    override fun onDetachedFromWindow() { cancelGesture(); super.onDetachedFromWindow() }
}

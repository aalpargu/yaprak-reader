package com.yaprak.reader.reader

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.State
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.formats.pdf.PdfReaderFragment
import com.yaprak.reader.formats.pdf.PdfRenderStatus

/** Real screen input goes through the Activity, parent interceptors and both Compose roots. */
internal class ReaderWindowInput(
    private val compose: ComposeTestRule,
    private val scenario: ActivityScenario<ReaderActivity>,
    tag: String,
) {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val reader: ReaderActivity
    private val decor: View
    private val area: Rect

    init {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        var owner: View? = null
        var activityOwner: ReaderActivity? = null
        var screenArea: Rect? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            check(root.isAttachedToWindow && root.hasWindowFocus()) { "Reader window is not focused" }
            val screen = IntArray(2); val window = IntArray(2)
            root.getLocationOnScreen(screen); root.getLocationInWindow(window)
            owner = root
            activityOwner = activity
            screenArea = bounds.translate(Offset((screen[0] - window[0]).toFloat(),
                (screen[1] - window[1]).toFloat()))
        }
        decor = checkNotNull(owner)
        reader = checkNotNull(activityOwner)
        area = checkNotNull(screenArea)
        check(area.width > 0f && area.height > 0f)
    }

    val center: Offset get() = area.center

    fun tap(point: Offset = center) = path(listOf(0L to point, 40L to point))

    fun doubleTap() {
        tap()
        advance(80)
        tap()
    }

    fun swipeLeft() = path(listOf(0L to Offset(area.left + area.width * .9f, center.y),
        200L to Offset(area.left + area.width * .1f, center.y)))

    // Keep document input inside the viewport, clear of Android's system edge gestures.
    fun swipeUp() = path(listOf(0L to Offset(center.x, area.top + area.height * .7f),
        200L to Offset(center.x, area.top + area.height * .3f)))

    fun pan() = path(listOf(0L to center, 100L to center + Offset(20f, -30f),
        200L to center + Offset(60f, -100f)))

    private fun path(points: List<Pair<Long, Offset>>) {
        check(points.first().first == 0L && points.size >= 2)
        check(points.zipWithNext().all { it.first.first < it.second.first })
        check(points.all { area.contains(it.second) })
        val down = SystemClock.uptimeMillis()
        var pressed = false
        var last = points.first().second
        try {
            send(MotionEvent.ACTION_DOWN, down, last); pressed = true
            var elapsed = 0L
            for ((start, end) in points.zipWithNext()) {
                while (elapsed < end.first) {
                    val step = minOf(16L, end.first - elapsed)
                    advance(step); elapsed += step
                    val fraction = (elapsed - start.first).toFloat() / (end.first - start.first)
                    last = start.second + (end.second - start.second) * fraction
                    send(MotionEvent.ACTION_MOVE, down, last)
                }
            }
            send(MotionEvent.ACTION_UP, down, last); pressed = false
        } finally {
            if (pressed && currentWindow()) send(MotionEvent.ACTION_CANCEL, down, last)
        }
    }

    /** Elapsed time defines this finger path; it never substitutes for a readiness assertion. */
    private fun advance(duration: Long) {
        SystemClock.sleep(duration)
        compose.mainClock.advanceTimeBy(duration, ignoreFrameDuration = true)
    }

    private fun currentWindow(): Boolean {
        var current = false
        // ActivityScenario.onActivity waits for global idle before posting its callback.
        // A held pointer/edge effect need not become idle until the next event arrives.
        // Post directly to the main thread, retaining actual owner/attachment/focus checks.
        runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync {
            current = !reader.isDestroyed && !reader.isFinishing && reader.window.decorView === decor &&
                decor.isAttachedToWindow && decor.hasWindowFocus()
        } }
        return current
    }

    private fun send(action: Int, down: Long, point: Offset) {
        check(currentWindow()) { "Reader window changed during input" }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
            arrayOf(MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = 1f; size = 1f }),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { check(automation.injectInputEvent(event, true)) { "Android rejected reader input" } }
        finally { event.recycle() }
    }
}

/** Failure-only geometry/state, without source text, book identifiers or synthetic paint. */
internal fun readerWindowDiagnostic(scenario: ActivityScenario<ReaderActivity>): String = runCatching {
    var result = "Reader fragment unavailable"
    scenario.onActivity { activity ->
        val fragment = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>()
            .singleOrNull() ?: return@onActivity
        val status = PdfReaderFragment::class.java.getDeclaredField("status").apply { isAccessible = true }
            .get(fragment) as PdfRenderStatus
        val viewport = PdfRenderStatus::class.java.getDeclaredField("scrollViewport").apply { isAccessible = true }
            .get(status)
        val chrome = ReaderActivity::class.java.getDeclaredField("chrome\$delegate").apply { isAccessible = true }
            .get(activity) as State<*>
        val root = activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(root)
        result = "focus=${activity.window.decorView.hasWindowFocus()}, resumed=${fragment.isResumed}, " +
            "requested=${status.requestedPage.value}, rendered=${status.renderedPage.value}, " +
            "drawn=${status.drawnPosition.value}, navigation=${status.scrollNavigation.value}, " +
            "settled=${status.scrollNavigationSettled}, visible=${status.visibleScrollPages.value}, " +
            "selection=${fragment.textSelection?.active}, failed=${status.renderFailed.value}, " +
            "viewport=$viewport, chrome=${chrome.value}, root=${root.width}x${root.height}, " +
            "statusBar=${insets?.isVisible(WindowInsetsCompat.Type.statusBars())}, " +
            "navigationBar=${insets?.isVisible(WindowInsetsCompat.Type.navigationBars())}"
    }
    result
}.getOrElse { "Reader diagnostic unavailable: ${it.javaClass.simpleName}" }

/** Read-only admission/offset diagnosis; never invokes a semantic scroll action. */
internal fun ComposeTestRule.readerScrollDiagnostic(): String = onAllNodes(SemanticsMatcher("scroll range") {
    it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) ||
        it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
}, useUnmergedTree = true).fetchSemanticsNodes().take(12).joinToString(";") { node ->
    fun axis(key: androidx.compose.ui.semantics.SemanticsPropertyKey<androidx.compose.ui.semantics.ScrollAxisRange>): String =
        if (node.config.contains(key)) node.config[key].let { "${it.value()}/${it.maxValue()}" } else "none"
    "node=${node.id},x=${axis(SemanticsProperties.HorizontalScrollAxisRange)}," +
        "y=${axis(SemanticsProperties.VerticalScrollAxisRange)},action=${node.config.contains(SemanticsActions.ScrollBy)}"
}

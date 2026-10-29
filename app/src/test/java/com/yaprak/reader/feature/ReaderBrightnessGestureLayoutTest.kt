package com.yaprak.reader.feature

import android.app.Application
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.yaprak.reader.feature.reader.ReaderBrightnessGestureLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual MotionEvent/ViewGroup dispatch and child CANCEL, without physical display claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
class ReaderBrightnessGestureLayoutTest {
    private class Fixture(val activity: ComponentActivity) {
        var allowed = true; var identity: Any = Any(); var base: Int? = 50
        val preview = mutableListOf<Int>(); val commits = mutableListOf<Int>(); var cancellations = 0
        val childActions = mutableListOf<Int>(); var disallow = false; var revokeOnCancel = false
        val root = ReaderBrightnessGestureLayout(activity, { allowed }, { identity }, { base },
            preview::add, commits::add, { cancellations++ })
        init {
            root.addView(View(activity).apply {
                setOnTouchListener { view, event ->
                    childActions += event.actionMasked
                    if (disallow && event.actionMasked == MotionEvent.ACTION_DOWN) view.parent.requestDisallowInterceptTouchEvent(true)
                    if (revokeOnCancel && event.actionMasked == MotionEvent.ACTION_CANCEL) allowed = false
                    true
                }
            }, FrameLayout.LayoutParams(-1, -1))
            activity.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 320, 640)
        }
        fun event(action: Int, x: Float = 32f, y: Float = 300f, elapsed: Long = 0, pointers: Int = 1,
            tool: Int = MotionEvent.TOOL_TYPE_FINGER, down: Long = 1000) {
            val properties = Array(pointers) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = tool } }
            val coordinates = Array(pointers) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f } }
            val event = MotionEvent.obtain(down, down + elapsed, action, pointers, properties, coordinates,
                0, 0, 1f, 1f, 7, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { root.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
    private fun withLayout(block: (Fixture) -> Unit) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try { block(Fixture(host.get())) } finally { host.pause().stop().destroy() }
    }
    @Test fun `tap outside margin horizontal swipe and long press stay with child`() {
        withLayout { f -> f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_UP, elapsed = 20)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), f.childActions); assertTrue(f.preview.isEmpty()) }
        withLayout { f -> f.event(MotionEvent.ACTION_DOWN, x = 160f); f.event(MotionEvent.ACTION_MOVE, x = 160f, y = 150f, elapsed = 30)
            f.event(MotionEvent.ACTION_UP, x = 160f, y = 150f, elapsed = 40); assertFalse(f.childActions.contains(MotionEvent.ACTION_CANCEL)); assertTrue(f.commits.isEmpty()) }
        withLayout { f -> f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_MOVE, x = 100f, elapsed = 30)
            f.event(MotionEvent.ACTION_MOVE, x = 100f, y = 150f, elapsed = 40); f.event(MotionEvent.ACTION_UP, elapsed = 50)
            assertFalse(f.childActions.contains(MotionEvent.ACTION_CANCEL)); assertTrue(f.preview.isEmpty()) }
        withLayout { f -> f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_MOVE, y = 100f,
            elapsed = ViewConfiguration.getLongPressTimeout().toLong() + 1); f.event(MotionEvent.ACTION_UP, elapsed = 1000)
            assertFalse(f.childActions.contains(MotionEvent.ACTION_CANCEL)); assertTrue(f.preview.isEmpty()) }
    }
    @Test fun `vertical claim uses native child cancel and UP commits only bounded preview`() = withLayout { f ->
        f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_MOVE, y = 140f, elapsed = 30)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), f.childActions)
        assertEquals(listOf(73), f.preview); assertTrue(f.commits.isEmpty())
        f.event(MotionEvent.ACTION_UP, y = 140f, elapsed = 40)
        assertEquals(listOf(73), f.commits); assertEquals(0, f.cancellations)
        f.event(MotionEvent.ACTION_DOWN, down = 2000); f.event(MotionEvent.ACTION_MOVE, y = -1000f, elapsed = 30, down = 2000)
        f.event(MotionEvent.ACTION_UP, y = -1000f, elapsed = 40, down = 2000); assertEquals(100, f.commits.last())
        f.event(MotionEvent.ACTION_DOWN, down = 3000); f.event(MotionEvent.ACTION_MOVE, y = 2000f, elapsed = 30, down = 3000)
        f.event(MotionEvent.ACTION_UP, y = 2000f, elapsed = 40, down = 3000); assertEquals(10, f.commits.last())
        f.event(MotionEvent.ACTION_DOWN, down = 4000); f.event(MotionEvent.ACTION_MOVE, y = 260f, elapsed = 30, down = 4000)
        f.event(MotionEvent.ACTION_UP, y = 172f, elapsed = 40, down = 4000); assertEquals(68, f.commits.last())
    }
    @Test fun `CANCEL rolls back and no obsolete UP commits into next stream`() = withLayout { f ->
        f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_MOVE, y = 140f, elapsed = 30)
        f.event(MotionEvent.ACTION_CANCEL, y = 140f, elapsed = 40); f.event(MotionEvent.ACTION_UP, elapsed = 50)
        assertEquals(1, f.cancellations); assertTrue(f.commits.isEmpty())
        f.event(MotionEvent.ACTION_DOWN, down = 2000); f.event(MotionEvent.ACTION_UP, elapsed = 20, down = 2000)
        assertTrue(f.commits.isEmpty()); assertEquals(MotionEvent.ACTION_UP, f.childActions.last())
    }
    @Test fun `readiness source base and size changes revoke owned gesture and drain old stream`() {
        val changes: List<(Fixture) -> Unit> = listOf({ it.allowed = false }, { it.identity = Any() },
            { it.base = 60 }, { it.root.layout(0, 0, 300, 600) })
        changes.forEach { change -> withLayout { f ->
            f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_MOVE, y = 140f, elapsed = 30); change(f)
            f.event(MotionEvent.ACTION_MOVE, y = 100f, elapsed = 40); f.event(MotionEvent.ACTION_UP, y = 100f, elapsed = 50)
            assertEquals(1, f.cancellations); assertEquals(listOf(73), f.preview); assertTrue(f.commits.isEmpty())
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), f.childActions)
        } }
    }
    @Test fun `pinch before claim passes through and second pointer after claim cancels without commit`() {
        withLayout { f ->
            f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), elapsed = 20, pointers = 2)
            f.event(MotionEvent.ACTION_MOVE, y = 140f, elapsed = 30, pointers = 2); f.event(MotionEvent.ACTION_UP, elapsed = 50)
            assertTrue(f.preview.isEmpty()); assertFalse(f.childActions.contains(MotionEvent.ACTION_CANCEL))
        }
        withLayout { f ->
            f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_MOVE, y = 140f, elapsed = 30)
            f.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), elapsed = 40, pointers = 2)
            f.event(MotionEvent.ACTION_UP, elapsed = 50); assertEquals(1, f.cancellations); assertTrue(f.commits.isEmpty())
        }
    }
    @Test fun `disabled system choice stylus mouse and nonfinite input never start a brightness gesture`() {
        listOf<(Fixture) -> Unit>({ it.allowed = false }, { it.base = null }, { it.base = 9 }).forEach { change -> withLayout { f ->
            change(f); f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_MOVE, y = 140f, elapsed = 30); f.event(MotionEvent.ACTION_UP, elapsed = 40)
            assertTrue(f.preview.isEmpty()); assertTrue(f.commits.isEmpty())
        } }
        listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_MOUSE).forEach { tool -> withLayout { f ->
            f.event(MotionEvent.ACTION_DOWN, tool = tool); f.event(MotionEvent.ACTION_MOVE, y = 140f, elapsed = 30, tool = tool)
            f.event(MotionEvent.ACTION_UP, elapsed = 40, tool = tool); assertTrue(f.preview.isEmpty())
        } }
        withLayout { f -> f.event(MotionEvent.ACTION_DOWN, x = Float.NaN); f.event(MotionEvent.ACTION_MOVE, y = 140f, elapsed = 30)
            assertTrue(f.preview.isEmpty()) }
    }
    @Test fun `touch exploration prevents claiming even when reader brightness is enabled`() = withLayout { f ->
        val manager = f.activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setTouchExplorationEnabled(true)
        try {
            f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_MOVE, y = 140f, elapsed = 30); f.event(MotionEvent.ACTION_UP, elapsed = 40)
            assertTrue(f.preview.isEmpty()); assertFalse(f.childActions.contains(MotionEvent.ACTION_CANCEL))
        } finally { shadow.setTouchExplorationEnabled(false) }
    }

    @Test fun `child disallow interception is respected and gate revoked during native cancel cannot preview`() {
        withLayout { f -> f.disallow = true; f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_MOVE, y = 140f, elapsed = 30)
            f.event(MotionEvent.ACTION_UP, elapsed = 40); assertTrue(f.preview.isEmpty()); assertFalse(f.childActions.contains(MotionEvent.ACTION_CANCEL)) }
        withLayout { f -> f.revokeOnCancel = true; f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_MOVE, y = 140f, elapsed = 30)
            f.event(MotionEvent.ACTION_UP, elapsed = 40); assertTrue(f.preview.isEmpty()); assertTrue(f.commits.isEmpty()); assertEquals(1, f.cancellations) }
    }
}

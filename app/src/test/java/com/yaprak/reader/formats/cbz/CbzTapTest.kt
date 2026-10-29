package com.yaprak.reader.formats.cbz

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.fragment.app.FragmentActivity
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CbzTapTest {
    private fun click(view: View) {
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 100f, 100f, 0).let { event ->
            try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 100f, 100f, 0).let { event ->
            try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        }
    }
    @Test fun confirmedTapAlwaysUsesNativePerformClickButCustomZoneDoesNotAlsoOpenTools() {
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
        var active = true; var consumes = true; var zones = 0; var tools = 0; var nativeClicks = 0
        val view = CbzPageView(host.get(), { active }, { tools++ }, { zones++; consumes })
        try {
            host.get().setContentView(view); view.layout(0, 0, 200, 300)
            view.install(0, Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888), "Sayfa 1")
            view.setOnClickListener { nativeClicks++ }
            click(view); assertEquals(0, zones); assertEquals(0, tools)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(1, nativeClicks); assertEquals(1, zones); assertEquals(0, tools)
            consumes = false; click(view); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(2, nativeClicks); assertEquals(2, zones); assertEquals(1, tools)
            view.performClick() // Accessibility click has no fabricated screen position or zone.
            assertEquals(3, nativeClicks); assertEquals(2, zones); assertEquals(2, tools)
            active = false; view.performClick(); assertEquals(2, zones); assertEquals(2, tools)
        } finally { view.clear(); host.pause().stop().destroy() }
    }
    @Test fun actualDoubleTapZoomProducesNoSingleTapNavigationOrToolbarCallback() {
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
        var zones = 0; var tools = 0; var nativeClicks = 0
        val view = CbzPageView(host.get(), { true }, { tools++ }, { zones++; true })
        try {
            host.get().setContentView(view); view.layout(0, 0, 200, 300)
            view.install(0, Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888), "Sayfa 1")
            view.setOnClickListener { nativeClicks++ }
            click(view); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60)); click(view)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(0, nativeClicks); assertEquals(0, zones); assertEquals(0, tools)
            val zoom = view.javaClass.getDeclaredField("zoom").apply { isAccessible = true }.getFloat(view)
            assertEquals(3f, zoom, 0f)
        } finally { view.clear(); host.pause().stop().destroy() }
    }
}

package com.yaprak.reader.feature

import android.app.Application
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.yaprak.reader.feature.reader.ReaderBrightnessController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Checks actual window attributes; physical display brightness still requires a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReaderBrightnessControllerTest {
    private fun withReader(
        original: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
        action: (ActivityController<ComponentActivity>, ReaderBrightnessController) -> Unit,
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val reader = activity.get()
        reader.window.attributes = reader.window.attributes.apply { screenBrightness = original }
        try {
            action(activity, ReaderBrightnessController(reader.window, reader.lifecycle))
        } finally {
            if (!reader.isDestroyed) activity.pause().stop().destroy()
        }
    }

    private fun assertBrightness(activity: ComponentActivity, expected: Float) {
        assertEquals(expected, activity.window.attributes.screenBrightness, 0f)
    }

    @Test fun `manual choice requires ready reader and explicit window focus`() = withReader { activity, controller ->
        val reader = activity.get()
        assertBrightness(reader, -1f)
        controller.update(percent = 35, readerReady = true)
        assertBrightness(reader, -1f)
        controller.updateWindowFocus(true)
        assertBrightness(reader, 0.35f)
        controller.update(percent = 65, readerReady = false)
        assertBrightness(reader, -1f)
        controller.update(percent = 65, readerReady = true)
        assertBrightness(reader, 0.65f)
    }

    @Test fun `focus loss restores original value and delayed choice applies on focus return`() = withReader(original = 0.72f) { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(percent = 40, readerReady = true)
        assertBrightness(reader, 0.4f)
        controller.updateWindowFocus(false)
        assertBrightness(reader, 0.72f)
        controller.update(percent = 60, readerReady = true)
        assertBrightness(reader, 0.72f)
        controller.updateWindowFocus(true)
        assertBrightness(reader, 0.6f)
        // Loading, load/save/render failure and exit revoke readiness through this input.
        controller.update(percent = 60, readerReady = false)
        assertBrightness(reader, 0.72f)
    }

    @Test fun `system choice uses system policy while inactive reader restores captured value`() = withReader(original = 0.81f) { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(percent = null, readerReady = true)
        assertBrightness(reader, -1f)
        controller.update(percent = 100, readerReady = true)
        assertBrightness(reader, 1f)
        controller.update(percent = null, readerReady = true)
        assertBrightness(reader, -1f)
        controller.updateWindowFocus(false)
        assertBrightness(reader, 0.81f)
    }

    @Test fun `manual boundaries are accepted and invalid values cannot replace selection or block restoration`() = withReader { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(percent = 10, readerReady = true)
        assertBrightness(reader, 0.1f)
        controller.update(percent = 100, readerReady = true)
        assertBrightness(reader, 1f)
        for (invalid in listOf(9, 101, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            controller.update(percent = invalid, readerReady = true)
            assertBrightness(reader, 1f)
        }
        controller.update(percent = 9, readerReady = false)
        assertBrightness(reader, -1f)
        controller.update(percent = 9, readerReady = true)
        assertBrightness(reader, 1f)
        controller.update(percent = null, readerReady = true)
        assertBrightness(reader, -1f)
    }

    @Test fun `background restores original and resume waits for renewed focus without losing selection`() = withReader(original = 0.63f) { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(percent = 25, readerReady = true)
        assertBrightness(reader, 0.25f)
        activity.pause()
        assertBrightness(reader, 0.63f)
        activity.stop()
        controller.update(percent = 45, readerReady = true)
        assertBrightness(reader, 0.63f)
        activity.start().resume()
        assertBrightness(reader, 0.63f)
        controller.updateWindowFocus(true)
        assertBrightness(reader, 0.45f)
    }

    @Test fun `destroy restores original and late update focus and lifecycle callbacks are inert`() = withReader(original = 0.57f) { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(percent = 20, readerReady = true)
        assertBrightness(reader, 0.2f)
        activity.pause().stop().destroy()
        assertBrightness(reader, 0.57f)
        controller.update(percent = 90, readerReady = true)
        controller.updateWindowFocus(true)
        controller.onResume(reader)
        controller.onDestroy(reader)
        assertBrightness(reader, 0.57f)
    }

    @Test fun `brightness changes preserve current unrelated attributes rather than an old snapshot`() = withReader { activity, controller ->
        val reader = activity.get()
        val window = reader.window
        val retained = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE
        window.attributes = window.attributes.apply {
            flags = flags or retained
            buttonBrightness = 0.33f
            dimAmount = 0.42f
        }
        controller.updateWindowFocus(true)
        controller.update(percent = 30, readerReady = true)
        assertBrightness(reader, 0.3f)
        assertEquals(retained, window.attributes.flags and retained)
        assertEquals(0.33f, window.attributes.buttonBrightness, 0f)
        assertEquals(0.42f, window.attributes.dimAmount, 0f)
        // Independent changes made after applying brightness must also survive restoration.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { buttonBrightness = 0.66f; dimAmount = 0.24f }
        val latestFlags = window.attributes.flags
        controller.updateWindowFocus(false)
        assertBrightness(reader, -1f)
        assertEquals(latestFlags, window.attributes.flags)
        assertEquals(0.66f, window.attributes.buttonBrightness, 0f)
        assertEquals(0.24f, window.attributes.dimAmount, 0f)
    }

    @Test fun `unchanged brightness does not dispatch redundant window attribute writes`() = withReader { activity, controller ->
        val reader = activity.get()
        val window = reader.window
        val callback = requireNotNull(window.callback)
        var writes = 0
        window.callback = object : Window.Callback by callback {
            override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) {
                writes++
                callback.onWindowAttributesChanged(attrs)
            }
        }
        controller.updateWindowFocus(true)
        assertEquals(0, writes)
        controller.update(percent = 70, readerReady = true)
        assertEquals(1, writes)
        controller.update(percent = 70, readerReady = true)
        controller.updateWindowFocus(true)
        assertEquals(1, writes)
        controller.update(percent = 70, readerReady = false)
        assertEquals(2, writes)
        controller.update(percent = 50, readerReady = false)
        assertEquals(2, writes)
        controller.update(percent = 50, readerReady = true)
        assertEquals(3, writes)
        controller.update(percent = null, readerReady = true)
        assertEquals(4, writes)
        controller.update(percent = null, readerReady = true)
        assertEquals(4, writes)
    }
}

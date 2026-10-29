@file:Suppress("DEPRECATION")

package com.yaprak.reader.feature

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.yaprak.reader.feature.reader.ReaderImmersiveController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Tests the compat implementation's real window flags; modern system gestures need a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReaderImmersiveControllerTest {
    private fun withReader(action: (ActivityController<ComponentActivity>, ReaderImmersiveController) -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            action(activity, ReaderImmersiveController(activity.get().window, activity.get().lifecycle))
        } finally {
            if (!activity.get().isDestroyed) activity.pause().stop().destroy()
        }
    }

    private fun assertBars(activity: ComponentActivity, hidden: Boolean) {
        val flags = activity.window.decorView.systemUiVisibility
        assertEquals(hidden, flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        assertEquals(hidden, flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
    }

    @Test fun `reader starts visible and hides only when ready focused and controls are hidden`() = withReader { activity, controller ->
        val reader = activity.get()
        assertBars(reader, hidden = false)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = false) // No assumed window focus on construction.
        controller.updateWindowFocus(true)
        assertBars(reader, hidden = true)
        assertTrue(reader.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
        controller.update(readerReady = true, chromeVisible = true, blocked = false)
        assertBars(reader, hidden = false)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = true)
        // Loading, reader failure and exit all revoke readiness through this same input.
        controller.update(readerReady = false, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = false)
    }

    @Test fun `modal and keyboard restore bars and prevent premature hiding`() = withReader { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = true)
        controller.update(readerReady = true, chromeVisible = false, blocked = true)
        assertBars(reader, hidden = false)
        controller.updateImeVisible(true)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = false) // Closing a modal may leave its keyboard visible.
        controller.updateImeVisible(false)
        assertBars(reader, hidden = true)
        controller.update(readerReady = true, chromeVisible = false, blocked = true)
        controller.updateImeVisible(true)
        controller.updateImeVisible(false)
        assertBars(reader, hidden = false) // Keyboard dismissal cannot bypass a remaining modal/error.
    }

    @Test fun `background and destruction restore bars while resumed foreground can hide again`() = withReader { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = true)
        activity.pause()
        assertBars(reader, hidden = false)
        activity.stop()
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = false)
        activity.start().resume()
        assertBars(reader, hidden = true)
        activity.pause().stop().destroy()
        assertBars(reader, hidden = false)
        controller.updateWindowFocus(true)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = false)
    }

    @Test fun `temporary focus loss retains immersive mode and an active keyboard still blocks focus return`() = withReader { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = true)
        controller.updateWindowFocus(false)
        assertBars(reader, hidden = true)
        controller.updateImeVisible(true)
        assertBars(reader, hidden = false)
        controller.updateWindowFocus(true)
        assertBars(reader, hidden = false)
        controller.updateImeVisible(false)
        assertBars(reader, hidden = true)
    }

    @Test fun `repeated confirmation focus handoffs do not cycle the actual window flags`() = withReader { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        val flags = reader.window.decorView.systemUiVisibility
        val behavior = WindowCompat.getInsetsController(reader.window, reader.window.decorView).systemBarsBehavior
        repeat(4) {
            controller.updateWindowFocus(false)
            controller.update(readerReady = true, chromeVisible = false, blocked = false)
            assertBars(reader, hidden = true)
            assertEquals(flags, reader.window.decorView.systemUiVisibility)
            controller.updateWindowFocus(true)
            assertEquals(flags, reader.window.decorView.systemUiVisibility)
            assertEquals(behavior, WindowCompat.getInsetsController(reader.window, reader.window.decorView).systemBarsBehavior)
        }
    }

    @Test fun `unfocused reader cannot start a new hide after readiness or modal revocation`() = withReader { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        controller.updateWindowFocus(false)
        controller.update(readerReady = false, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = false)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = false)
        controller.updateWindowFocus(true)
        assertBars(reader, hidden = true)
        controller.updateWindowFocus(false)
        controller.update(readerReady = true, chromeVisible = false, blocked = true)
        assertBars(reader, hidden = false)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = false)
        controller.updateWindowFocus(true)
        assertBars(reader, hidden = true)
    }

    @Test fun `pausing during temporary focus loss restores bars until a focused resume`() = withReader { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        controller.updateWindowFocus(false)
        assertBars(reader, hidden = true)
        activity.pause().stop()
        assertBars(reader, hidden = false)
        activity.start().resume()
        assertBars(reader, hidden = false)
        controller.updateWindowFocus(true)
        assertBars(reader, hidden = true)
    }

    @Test fun `temporary focus does not counter a users transient system bar reveal`() = withReader { activity, controller ->
        val reader = activity.get()
        controller.updateWindowFocus(true)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        reader.window.decorView.systemUiVisibility = reader.window.decorView.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION).inv()
        controller.updateWindowFocus(false)
        controller.updateWindowFocus(true)
        assertBars(reader, hidden = false)
    }

    @Test fun `unchanged desired state does not reapply a temporarily cleared visibility request`() = withReader { activity, controller ->
        val reader = activity.get()
        val decor = reader.window.decorView
        controller.updateWindowFocus(true)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = true)
        // A controlled visibility change tests deduplication without claiming a real edge gesture.
        decor.systemUiVisibility = decor.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION).inv()
        assertBars(reader, hidden = false)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        controller.updateImeVisible(false)
        controller.updateWindowFocus(true)
        assertBars(reader, hidden = false)
        controller.update(readerReady = true, chromeVisible = true, blocked = false)
        controller.update(readerReady = true, chromeVisible = false, blocked = false)
        assertBars(reader, hidden = true)
    }

    @Test fun `restoring bars also restores prior behavior and preserves edge to edge and icon style flags`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val reader = activity.get()
        val decor = reader.window.decorView
        val retained = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        decor.systemUiVisibility = retained
        val originalBehavior = WindowCompat.getInsetsController(reader.window, decor).systemBarsBehavior
        try {
            val controller = ReaderImmersiveController(reader.window, reader.lifecycle)
            controller.updateWindowFocus(true)
            controller.update(readerReady = true, chromeVisible = false, blocked = false)
            assertBars(reader, hidden = true)
            assertEquals(retained, decor.systemUiVisibility and retained)
            controller.update(readerReady = true, chromeVisible = true, blocked = false)
            assertBars(reader, hidden = false)
            assertEquals(originalBehavior, WindowCompat.getInsetsController(reader.window, decor).systemBarsBehavior)
            assertFalse(decor.systemUiVisibility and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
            assertEquals(retained, decor.systemUiVisibility and retained)
        } finally { activity.pause().stop().destroy() }
    }
}

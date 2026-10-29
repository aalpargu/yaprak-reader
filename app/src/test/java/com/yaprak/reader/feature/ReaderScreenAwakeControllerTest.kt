package com.yaprak.reader.feature

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.yaprak.reader.feature.reader.ReaderScreenAwakeController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReaderScreenAwakeControllerTest {
    @Test fun `window stays awake only while enabled ready and resumed`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val window = activity.get().window
        val controller = ReaderScreenAwakeController(window, activity.get().lifecycle)
        fun awake() = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        try {
            assertFalse(awake())
            controller.update(enabled = true, readerReady = false)
            assertFalse(awake())
            controller.update(enabled = true, readerReady = true)
            assertTrue(awake())
            activity.pause()
            assertFalse(awake())
            activity.stop()
            assertFalse(awake())
            activity.start().resume()
            assertTrue(awake())
            controller.update(enabled = false, readerReady = true)
            assertFalse(awake())
            controller.update(enabled = true, readerReady = true)
            assertTrue(awake())
            // Failed/loading/exiting reader states revoke readiness through the same path.
            controller.update(enabled = true, readerReady = false)
            assertFalse(awake())
        } finally { activity.pause().stop().destroy() }
    }
}

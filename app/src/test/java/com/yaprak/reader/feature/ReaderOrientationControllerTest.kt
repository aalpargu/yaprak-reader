package com.yaprak.reader.feature

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import com.yaprak.reader.domain.settings.ReaderOrientation
import com.yaprak.reader.feature.reader.ReaderOrientationController
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReaderOrientationControllerTest {
    @Test fun `known saved choices map to actual Activity requests once per successful target`() {
        Robolectric.buildActivity(ComponentActivity::class.java).use { host ->
            host.setup(); val activity = host.get(); val requests = mutableListOf<Int>()
            val controller = ReaderOrientationController(activity, activity.lifecycle) { target ->
                requests += target; activity.requestedOrientation = target
            }
            controller.update(ReaderOrientation.PORTRAIT, preferenceReady = false, readerReady = true)
            controller.update(ReaderOrientation.PORTRAIT, preferenceReady = true, readerReady = false)
            assertTrue(requests.isEmpty())
            controller.update(ReaderOrientation.PORTRAIT, true, true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
            repeat(5) { controller.update(ReaderOrientation.PORTRAIT, true, true) }
            controller.update(ReaderOrientation.LANDSCAPE, true, true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
            controller.update(ReaderOrientation.AUTO, true, true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
            assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED), requests)
        }
    }

    @Test fun `loading reader error pause and destroy preserve the applied target without AUTO reset`() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = host.get(); val requests = mutableListOf<Int>()
        val controller = ReaderOrientationController(activity, activity.lifecycle) { requests += it; activity.requestedOrientation = it }
        controller.update(ReaderOrientation.LANDSCAPE, true, true)
        controller.update(ReaderOrientation.AUTO, false, false)
        host.pause().stop()
        controller.update(ReaderOrientation.PORTRAIT, true, true)
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE), requests)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        host.start().resume()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
        controller.update(ReaderOrientation.AUTO, false, false)
        host.pause().stop().destroy()
        controller.update(ReaderOrientation.AUTO, true, true); controller.retry()
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT), requests)
    }

    @Test fun `recreated Activity applies restored preference without intermediate AUTO request`() {
        val requests = mutableListOf<Int>()
        repeat(2) {
            Robolectric.buildActivity(ComponentActivity::class.java).use { host ->
                host.setup(); val activity = host.get()
                val controller = ReaderOrientationController(activity, activity.lifecycle) { requests += it }
                controller.update(ReaderOrientation.AUTO, false, false)
                controller.update(ReaderOrientation.LANDSCAPE, true, true)
            }
        }
        assertEquals(List(2) { ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }, requests)
    }

    @Test fun `successful request is deduplicated even when platform getter ignores it`() {
        Robolectric.buildActivity(ComponentActivity::class.java).use { host ->
            host.setup(); val activity = host.get(); var requests = 0
            val unchanged = activity.requestedOrientation
            val controller = ReaderOrientationController(activity, activity.lifecycle) { requests++ }
            repeat(10) { controller.update(ReaderOrientation.PORTRAIT, true, true) }
            host.pause().resume()
            assertEquals(1, requests)
            assertEquals(unchanged, activity.requestedOrientation)
        }
    }

    @Test fun `apply exception is visible retryable and not an automatic retry loop`() {
        Robolectric.buildActivity(ComponentActivity::class.java).use { host ->
            host.setup(); val activity = host.get(); var requests = 0; var fail = true
            val controller = ReaderOrientationController(activity, activity.lifecycle) {
                requests++; if (fail) throw IllegalStateException("test request rejected")
                activity.requestedOrientation = it
            }
            controller.update(ReaderOrientation.LANDSCAPE, true, true)
            repeat(10) { controller.update(ReaderOrientation.LANDSCAPE, true, true) }
            assertTrue(controller.applyFailed.value); assertEquals(1, requests)
            host.pause(); controller.retry(); assertEquals(1, requests)
            host.resume(); assertEquals(1, requests)
            fail = false; controller.retry()
            assertFalse(controller.applyFailed.value); assertEquals(2, requests)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        }
    }

    @Test fun `render recovery applies waiting saved choice and cancellation is propagated`() {
        Robolectric.buildActivity(ComponentActivity::class.java).use { host ->
            host.setup(); val activity = host.get(); val requests = mutableListOf<Int>()
            val controller = ReaderOrientationController(activity, activity.lifecycle) { requests += it }
            controller.update(ReaderOrientation.PORTRAIT, true, false)
            controller.update(ReaderOrientation.LANDSCAPE, true, false)
            assertTrue(requests.isEmpty())
            controller.update(ReaderOrientation.LANDSCAPE, true, true)
            assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE), requests)
            val cancellation = ReaderOrientationController(activity, activity.lifecycle) { throw CancellationException() }
            try { cancellation.update(ReaderOrientation.AUTO, true, true); fail("Cancellation expected") }
            catch (_: CancellationException) { assertFalse(cancellation.applyFailed.value) }
        }
    }
}

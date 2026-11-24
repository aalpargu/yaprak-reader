package com.yaprak.reader.reader

import com.yaprak.reader.readerMethod
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.settings.TapZoneViewModel

/** Adapt the navigation tests to the real compact controls without bypassing native input. */
internal fun showReaderControls(compose: ComposeTestRule, scenario: ActivityScenario<ReaderActivity>) {
    if (compose.onAllNodesWithTag("reader-bottom-bar").fetchSemanticsNodes().isNotEmpty()) return
    val confirmation = ReaderImmersiveConfirmation(scenario)
    val admission = ReaderActivity::class.java.readerMethod("canNavigateWithReadingInput",
        Boolean::class.javaPrimitiveType).apply { isAccessible = true }
    val tapZones = ReaderActivity::class.java.readerMethod("getTapZones").apply { isAccessible = true }
    val visible = ReaderActivity::class.java.getDeclaredField("durationVisible").apply { isAccessible = true }
    var diagnostic = "immersiveConfirmed=false"
    fun ready(activity: ReaderActivity): Boolean {
        val root = activity.window.decorView
        val preferences = (tapZones.invoke(activity) as TapZoneViewModel).state.value
        val admitted = admission.invoke(activity, false) as Boolean
        diagnostic = "admitted=$admitted, attached=${root.isAttachedToWindow}, focused=${root.hasWindowFocus()}, " +
            "visible=${visible.getBoolean(activity)}, tapLoading=${preferences.loading}, " +
            "tapSaving=${preferences.saving}, tapLoadFailed=${preferences.loadFailed}"
        return !activity.isDestroyed && !activity.isFinishing && root.isAttachedToWindow &&
            root.hasWindowFocus() && admitted && !preferences.loading && !preferences.saving && !preferences.loadFailed
    }
    // Fragment resume precedes Readium's visual/document probe. An earlier tap is intentionally
    // consumed by the production admission gate and cannot open controls once loading finishes.
    try {
        compose.waitUntil(15_000) {
            if (!confirmation.ready()) return@waitUntil false
            var admitted = false
            scenario.onActivity { admitted = ready(it) }
            admitted
        }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError("Reader controls input was not ready: $diagnostic", timeout)
    }
    var x = 0f; var y = 0f
    lateinit var reader: ReaderActivity
    lateinit var window: android.view.View
    scenario.onActivity {
        reader = it; window = it.window.decorView
        x = window.width / 2f; y = window.height / 2f
    }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val down = SystemClock.uptimeMillis()
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        instrumentation.runOnMainSync {
            check(!reader.isDestroyed && !reader.isFinishing && reader.window.decorView === window &&
                window.isAttachedToWindow && window.hasWindowFocus()) { "Reader input window changed" }
            if (action == MotionEvent.ACTION_DOWN) check(ready(reader)) { "Reader input admission changed: $diagnostic" }
        }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
        if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
    }
    compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-bottom-bar").fetchSemanticsNodes().isNotEmpty() }
}

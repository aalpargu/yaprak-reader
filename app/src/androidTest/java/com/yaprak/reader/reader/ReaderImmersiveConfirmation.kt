package com.yaprak.reader.reader

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.ReaderImmersiveController
import java.io.File

/** Owned Android35 harness only: acknowledge the real first-use tutorial, never set its setting. */
internal class ReaderImmersiveConfirmation(private val scenario: ActivityScenario<ReaderActivity>) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private var confirmed = readConfirmed()
    private var clicked = false
    private var captured = false

    @Suppress("DEPRECATION") // AccessibilityNodeInfo pooling exists on the harness' supported Android APIs.
    fun ready(): Boolean {
        if (confirmed) return true
        if (clicked) { confirmed = readConfirmed(); return confirmed }
        val root = automation.rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (root.packageName?.toString() !in setOf("android", "com.android.systemui")) return false
            for (prefix in listOf("android:id/", "com.android.systemui:id/")) {
                val titles = root.findAccessibilityNodeInfosByViewId(prefix + "immersive_cling_title")
                val descriptions = root.findAccessibilityNodeInfosByViewId(prefix + "immersive_cling_description")
                val buttons = root.findAccessibilityNodeInfosByViewId(prefix + "ok")
                nodes.addAll(titles); nodes.addAll(descriptions); nodes.addAll(buttons)
                if (titles.size != 1 || descriptions.size != 1 || buttons.size != 1) continue
                val button = buttons.single()
                if (!button.isEnabled || !button.isVisibleToUser) continue
                var lostFocus = false
                scenario.onActivity { activity ->
                    check(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                    lostFocus = !activity.window.decorView.hasWindowFocus()
                    if (lostFocus) {
                        val controller = ReaderActivity::class.java.getDeclaredField("immersiveController")
                            .apply { isAccessible = true }.get(activity) as ReaderImmersiveController
                        val hidden = ReaderImmersiveController::class.java.getDeclaredField("requestedHidden")
                            .apply { isAccessible = true }.get(controller)
                        check(hidden == true) { "First-use tutorial focus must not revoke the reader's hidden request" }
                    }
                }
                if (!lostFocus) return false // Wait until the real focus callback has reached the reader.
                if (!captured) {
                    val directory = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
                        "yaprak-reader-0.178").apply { check(isDirectory || mkdirs()) }
                    val bitmap = checkNotNull(automation.takeScreenshot())
                    try {
                        File(directory, "immersive-first-use.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                    captured = true
                }
                clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return false // Require the real acknowledgement and focused window on a later pass.
            }
            return false
        } finally {
            nodes.forEach { it.recycle() }
            root.recycle()
        }
    }

    private fun readConfirmed(): Boolean {
        // Shell capability is supplied by instrumentation. This command only reads one known
        // platform setting; the actual system button is the only acknowledgement action.
        val descriptor = automation.executeShellCommand("settings get secure immersive_mode_confirmations")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
            it.readText().trim() == "confirmed"
        }
    }
}

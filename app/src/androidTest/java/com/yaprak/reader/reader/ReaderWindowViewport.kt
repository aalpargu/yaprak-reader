package com.yaprak.reader.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import com.yaprak.reader.feature.reader.ReaderActivity
import kotlin.math.abs

/** An old painted point alone does not prove that the system-bar resize has finished. */
internal fun ComposeTestRule.awaitReaderWindowViewport(
    scenario: ActivityScenario<ReaderActivity>, tag: String, toolsHidden: Boolean,
) {
    var last: ReaderWindowViewport? = null
    val confirmation = if (toolsHidden) ReaderImmersiveConfirmation(scenario) else null
    try {
        waitUntil(20_000) {
            if (confirmation?.ready() == false) return@waitUntil false
            var window: ReaderWindowViewport? = null
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(root)
                if (insets != null && root.width > 0 && root.height > 0) {
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    window = ReaderWindowViewport(root.width, root.height, root.hasWindowFocus(),
                        insets.isVisible(WindowInsetsCompat.Type.statusBars()),
                        insets.isVisible(WindowInsetsCompat.Type.navigationBars()),
                        root.isLayoutRequested,
                        Rect(bars.left.toFloat(), bars.top.toFloat(),
                            (root.width - bars.right).toFloat(), (root.height - bars.bottom).toFloat()))
                }
            }
            val bounds = onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.boundsInWindow
            last = window?.copy(actual = bounds)
            last?.matches(toolsHidden) == true
        }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError("Actual reader window did not settle (toolsHidden=$toolsHidden): $last; " +
            readerWindowDiagnostic(scenario), timeout)
    }
}

private data class ReaderWindowViewport(
    val width: Int, val height: Int, val focused: Boolean, val statusVisible: Boolean, val navigationVisible: Boolean,
    val layoutRequested: Boolean, val expected: Rect, val actual: Rect? = null,
) {
    fun matches(toolsHidden: Boolean): Boolean {
        if (!focused || layoutRequested || statusVisible == toolsHidden || navigationVisible == toolsHidden) return false
        val bounds = actual ?: return false
        val target = if (toolsHidden) Rect(0f, 0f, width.toFloat(), height.toFloat()) else expected
        return abs(bounds.left - target.left) <= 1f && abs(bounds.top - target.top) <= 1f &&
            abs(bounds.right - target.right) <= 1f && abs(bounds.bottom - target.bottom) <= 1f
    }
}

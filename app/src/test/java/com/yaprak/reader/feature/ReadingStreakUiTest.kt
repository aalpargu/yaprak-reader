package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.R
import com.yaprak.reader.domain.statistics.*
import com.yaprak.reader.feature.statistics.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.time.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingStreakUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun resetFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }
    private val today = LocalDate.parse("2026-10-09")
    private val days = listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-08", "2026-10-09", "2026-10-10")
        .map { ReadingDurationDay(it, 1) }
    private fun ready() = ReadingStatisticsState(today, "Europe/Istanbul", snapshot =
        ReadingDurationSnapshot(days, listOf(BookReadingDuration(F.book, 6)), 6), loading = false, active = true)
    private fun scroll(tag: String) = compose.onNodeWithTag("statistics-body").performScrollToNode(hasTestTag(tag))
        .let { compose.onNodeWithTag(tag).assertIsDisplayed() }
    private fun readable(tag: String, text: String) {
        val node = scroll(tag).assertTextEquals(text); val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale); assertFalse(layout.hasVisualOverflow)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        val bounds = node.fetchSemanticsNode().boundsInRoot; val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left && bounds.right <= root.right && bounds.top >= root.top && bounds.bottom <= root.bottom)
    }
    private fun native(name: String) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(ready(), {}, {}) } }
        readable("statistics-streak-current", compose.activity.getString(R.string.reading_streak_current, 2))
        readable("statistics-streak-longest", compose.activity.getString(R.string.reading_streak_longest, 3))
        readable("statistics-streak-last", compose.activity.getString(R.string.reading_streak_last, today.toString()))
        compose.runOnIdle {
            val view = compose.activity.window.decorView; val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap)); val file = File(System.getProperty("yaprak.screenshotDir"), "reading-streak-0.131.0/$name.png")
                file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
    @Test fun narrowDoubleTypeKeepsCurrentLongestAndActualLastDateWhole() { native("narrow") }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun shortLandscapeDoubleTypeKeepsAllSeriesRowsReachable() { native("landscape") }
    @Test fun unavailableSnapshotCannotRetainAFormerSeriesAndReadyEmptyIsAnExplicitZero() {
        val original = ready(); var state by mutableStateOf(original)
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(state, {}, {}) } }
        scroll("statistics-streak-current").assertTextEquals(compose.activity.getString(R.string.reading_streak_current, 2))
        for (gated in listOf(original.copy(active = false), original.copy(loading = true),
            original.copy(failure = ReadingDurationFailure.RECOVERY), original.copy(snapshot = null))) {
            compose.runOnIdle { state = gated }
            compose.onNodeWithTag("statistics-streak-current").assertDoesNotExist()
            compose.onNodeWithTag("statistics-streak-longest").assertDoesNotExist()
            compose.onNodeWithTag("statistics-streak-last").assertDoesNotExist()
        }
        compose.runOnIdle { state = original.copy(snapshot = ReadingDurationSnapshot(emptyList(), emptyList(), 0)) }
        scroll("statistics-streak-current").assertTextEquals(compose.activity.getString(R.string.reading_streak_current, 0))
        scroll("statistics-streak-longest").assertTextEquals(compose.activity.getString(R.string.reading_streak_longest, 0))
        compose.onNodeWithTag("statistics-streak-last").assertDoesNotExist()
    }
}

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
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DailyReadingGoalUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val date = LocalDate.parse("2026-10-05")
    private fun statistics(millis: Long) = ReadingStatisticsState(date, "Europe/Istanbul",
        ReadingDurationSnapshot(listOf(ReadingDurationDay(date.toString(), millis)), emptyList(), millis),
        loading = false, active = true)
    private val goal = DailyReadingGoalState(savedMinutes = 1, loading = false, active = true, revision = 7)
    private val initialDraft = DailyReadingGoalDraft("fixture-request", enabled = true, minutesText = "15")

    @Test fun `panel uses exact saved duration and goal changes never invent prior progress`() {
        var stats by mutableStateOf(statistics(59_999))
        var currentGoal by mutableStateOf(goal)
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(stats, {}, {}, currentGoal, {}, {}) } }
        scrollText(text(R.string.daily_goal_progress, 99)).assertIsDisplayed()
        scrollText(text(R.string.daily_goal_remaining, text(R.string.reading_statistics_under_second))).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.daily_goal_achieved)).assertDoesNotExist()
        compose.runOnIdle { stats = statistics(60_000) }
        scrollText(text(R.string.daily_goal_progress, 100)).assertIsDisplayed()
        scrollText(text(R.string.daily_goal_achieved)).assertIsDisplayed()
        compose.runOnIdle { currentGoal = goal.copy(savedMinutes = 1000, revision = 8) }
        scrollText(text(R.string.daily_goal_progress, 0)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.daily_goal_achieved)).assertDoesNotExist()
        compose.runOnIdle { stats = statistics(59_999_999) }
        scrollText(text(R.string.daily_goal_progress, 99)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.daily_goal_progress, 100)).assertDoesNotExist()
    }

    @Test fun `goal and duration source failures are unknown rather than off or fabricated zero progress`() {
        var stats by mutableStateOf(statistics(0).copy(snapshot = null, failure = ReadingDurationFailure.SOURCE))
        var currentGoal by mutableStateOf(goal)
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(stats, {}, {}, currentGoal, {}, {}) } }
        scrollText(text(R.string.daily_goal_duration_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.daily_goal_progress, 0)).assertDoesNotExist()
        compose.runOnIdle { stats = statistics(0).copy(snapshot = null, loading = true) }
        scrollText(text(R.string.daily_goal_duration_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.daily_goal_progress, 0)).assertDoesNotExist()
        compose.runOnIdle { currentGoal = goal.copy(savedMinutes = null, readFailure = DailyReadingGoalReadFailure.SOURCE) }
        scrollText(text(R.string.daily_goal_load_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.daily_goal_off)).assertDoesNotExist()
        compose.runOnIdle { currentGoal = goal.copy(savedMinutes = null, loading = true) }
        scrollText(text(R.string.daily_goal_loading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.daily_goal_off)).assertDoesNotExist()
    }

    @Test fun `old open and retry actions cannot survive ABA pause or disposal`() {
        var shown by mutableStateOf(true)
        var currentGoal by mutableStateOf(goal)
        val effects = mutableListOf<Long>()
        val open: (Long) -> Unit = effects::add
        val retry: (Long) -> Unit = effects::add
        compose.setContent { YaprakTheme { if (shown) ReadingStatisticsScreen(statistics(0), {}, {}, currentGoal, open, retry) } }
        val oldOpen = action("statistics-goal-open")
        compose.runOnIdle { currentGoal = goal.copy(savedMinutes = 15, revision = 8) }
        compose.runOnIdle { currentGoal = goal.copy(revision = 9) }
        compose.runOnIdle { oldOpen() }; assertTrue(effects.isEmpty())
        scrollTag("statistics-goal-open").performClick(); assertEquals(listOf(9L), effects)
        compose.runOnIdle { currentGoal = goal.copy(readFailure = DailyReadingGoalReadFailure.SOURCE, revision = 10) }
        val oldRetry = action("statistics-goal-retry")
        compose.runOnIdle { currentGoal = currentGoal.copy(active = false) }
        compose.runOnIdle { oldRetry() }; assertEquals(listOf(9L), effects)
        compose.runOnIdle { shown = false }
        compose.runOnIdle { oldOpen(); oldRetry() }; assertEquals(listOf(9L), effects)
    }

    @Test fun `dialog shows invalid input and failed exact draft until explicit retry`() {
        var draft by mutableStateOf(initialDraft)
        var failed by mutableStateOf(false)
        var saving by mutableStateOf(false)
        val saved = mutableListOf<String>()
        compose.setContent { YaprakTheme { DailyReadingGoalDialog(draft, saving, failed, { true },
            { _, _, value -> draft = draft.copy(enabled = value, revision = draft.revision + 1) },
            { _, _, value -> draft = draft.copy(minutesText = value, revision = draft.revision + 1) },
            { _, _ -> saved += draft.minutesText; failed = true }, { _, _ -> }) } }
        compose.onNodeWithTag("daily-goal-minutes").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("daily-goal-save").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.daily_goal_invalid)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("daily-goal-minutes").performScrollTo().performTextReplacement("1000")
        compose.onNodeWithTag("daily-goal-save").performClick()
        compose.onNodeWithText(text(R.string.daily_goal_save_failed)).performScrollTo().assertIsDisplayed()
        assertEquals("1000", draft.minutesText); assertEquals(listOf("1000"), saved)
        compose.onNodeWithTag("daily-goal-save").performClick(); assertEquals(listOf("1000", "1000"), saved)
        compose.runOnIdle { saving = true }
        compose.onNodeWithTag("daily-goal-save").assertIsNotEnabled()
        compose.onNodeWithTag("daily-goal-cancel").assertIsNotEnabled()
    }

    @Test fun `dialog stale revision UUID and disposed callbacks cannot affect a replacement draft`() {
        var shown by mutableStateOf(true)
        var draft by mutableStateOf(initialDraft)
        var effects = 0
        compose.setContent { YaprakTheme { if (shown) DailyReadingGoalDialog(draft, false, false, { true },
            { _, _, _ -> effects++ }, { _, _, _ -> effects++ }, { _, _ -> effects++ }, { _, _ -> effects++ }) } }
        val save = compose.onNodeWithTag("daily-goal-save").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val close = compose.onNodeWithTag("daily-goal-cancel").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { draft = draft.copy(revision = 1, minutesText = "30") }
        compose.runOnIdle { save(); close() }; assertEquals(0, effects)
        compose.runOnIdle { draft = initialDraft.copy(requestId = "replacement") }
        compose.runOnIdle { save(); close() }; assertEquals(0, effects)
        compose.runOnIdle { shown = false }
        compose.runOnIdle { save(); close() }; assertEquals(0, effects)
    }

    @Test fun `native narrow double type editor scrolls original labels while footer stays accessible`() {
        largeFont()
        compose.setContent { YaprakTheme { DailyReadingGoalDialog(initialDraft.copy(minutesText = "0"),
            false, true, { true }, { _, _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _ -> }) } }
        for (id in listOf(R.string.daily_goal_title, R.string.daily_goal_save_failed, R.string.daily_goal_help,
                R.string.daily_goal_enabled, R.string.daily_goal_minutes_label, R.string.daily_goal_range, R.string.daily_goal_invalid)) {
            compose.onNodeWithText(text(id), useUnmergedTree = true).performScrollTo().assertIsDisplayed(); readable(text(id))
        }
        assertDialogFooter(false)
        capture("daily-goal-editor-double-type", true)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-mdpi")
    fun `native short landscape double type editor retains fixed save and cancel during storage failure`() {
        largeFont()
        compose.setContent { YaprakTheme { DailyReadingGoalDialog(initialDraft, false, true, { true },
            { _, _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _ -> }) } }
        compose.onNodeWithText(text(R.string.daily_goal_save_failed)).performScrollTo().assertIsDisplayed()
        readable(text(R.string.daily_goal_save_failed)); assertDialogFooter(true)
        capture("daily-goal-landscape-double-type", true)
    }

    @Test fun `native narrow double type panel keeps exact remaining fraction and back accessible`() {
        largeFont()
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(statistics(59_999), {}, {}, goal, {}, {}) } }
        for (id in listOf(R.string.daily_goal_title, R.string.daily_goal_device)) {
            scrollText(text(id)).assertIsDisplayed(); readable(text(id))
        }
        val remaining = text(R.string.daily_goal_remaining, text(R.string.reading_statistics_under_second))
        scrollText(remaining).assertIsDisplayed(); readable(remaining)
        scrollTag("statistics-goal-open").assertIsDisplayed()
        readable(text(R.string.daily_goal_edit), "statistics-goal-open")
        compose.onNodeWithTag("statistics-back").assertIsDisplayed().assertIsEnabled()
        readable(text(R.string.reading_statistics_back), "statistics-back")
        capture("daily-goal-panel-double-type", false)
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun scrollTag(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("statistics-body").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun scrollText(value: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("statistics-body").performScrollToNode(hasText(value))
        return compose.onNodeWithText(value)
    }
    private fun action(tag: String) = scrollTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
    }
    private fun readable(value: String, ancestor: String? = null) {
        val layouts = mutableListOf<TextLayoutResult>()
        val matcher = if (ancestor == null) hasText(value) else hasText(value) and hasAnyAncestor(hasTestTag(ancestor))
        compose.onNode(matcher, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse("Text overflow: $value", layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun assertDialogFooter(saveEnabled: Boolean) {
        compose.onNodeWithTag("daily-goal-save").assertIsDisplayed()
        if (saveEnabled) compose.onNodeWithTag("daily-goal-save").assertIsEnabled()
        else compose.onNodeWithTag("daily-goal-save").assertIsNotEnabled()
        compose.onNodeWithTag("daily-goal-cancel").assertIsDisplayed().assertIsEnabled()
        readable(text(R.string.daily_goal_save), "daily-goal-save")
        readable(text(R.string.daily_goal_cancel), "daily-goal-cancel")
        val body = compose.onNodeWithTag("daily-goal-body").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("daily-goal-actions").fetchSemanticsNode().boundsInRoot
        assertTrue("Actions must remain outside scroll body", footer.top >= body.bottom)
    }
    private fun capture(name: String, dialog: Boolean) = compose.runOnIdle {
        val view = if (dialog) requireNotNull(ShadowDialog.getLatestDialog().window).decorView else compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

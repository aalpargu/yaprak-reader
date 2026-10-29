package com.yaprak.reader.feature

import android.app.Application
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
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeeklyReadingGoalUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val today = LocalDate.parse("2026-10-08")
    private fun stats(millis: Long) = ReadingStatisticsState(today, "Europe/Istanbul",
        ReadingDurationSnapshot(listOf(ReadingDurationDay("2026-10-05", millis),
            ReadingDurationDay("2026-10-09", 99_999)), emptyList(), millis + 99_999), loading = false, active = true)
    private val goal = WeeklyReadingGoalState(1, loading = false, active = true, revision = 7)
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun scroll(value: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("statistics-body").performScrollToNode(hasText(value)); return compose.onNodeWithText(value)
    }
    @Test fun progressUsesCalendarWeekSavedTimeAndFutureDaysCannotSatisfyGoal() {
        var current by mutableStateOf(stats(59_999))
        var currentGoal by mutableStateOf(goal)
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(current, {}, {}, weeklyState = currentGoal, onWeeklyOpen = {}) } }
        scroll("Hedef: 1 dakika").assertIsDisplayed()
        scroll(text(R.string.weekly_goal_period, "2026-10-05", "2026-10-11")).assertIsDisplayed()
        scroll(text(R.string.weekly_goal_progress, 99)).assertIsDisplayed()
        scroll(text(R.string.weekly_goal_remaining, text(R.string.reading_statistics_under_second))).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.weekly_goal_achieved)).assertDoesNotExist()
        compose.runOnIdle { current = stats(60_000) }
        scroll(text(R.string.weekly_goal_achieved)).assertIsDisplayed()
        compose.runOnIdle { current = stats(60_000).copy(today = today.plusDays(4)) }
        scroll(text(R.string.weekly_goal_progress, 0)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.weekly_goal_achieved)).assertDoesNotExist()
        compose.runOnIdle { currentGoal = goal.copy(savedMinutes = 7000, revision = 8) }
        scroll("Hedef: 7000 dakika").assertIsDisplayed()
    }
    @Test fun unavailableDurationAndGoalSourceAreUnknownAndNeverRenderedAsZeroOrOff() {
        var current by mutableStateOf(goal)
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(stats(0).copy(snapshot = null, failure = ReadingDurationFailure.SOURCE),
            {}, {}, weeklyState = current, onWeeklyRetry = {}) } }
        scroll(text(R.string.weekly_goal_duration_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.weekly_goal_progress, 0)).assertDoesNotExist()
        compose.runOnIdle { current = goal.copy(savedMinutes = null, readFailure = WeeklyReadingGoalReadFailure.SOURCE) }
        scroll(text(R.string.weekly_goal_load_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.weekly_goal_off)).assertDoesNotExist()
    }
    @Test fun staleWeeklyOpenCannotSurviveRevisionOtherGoalEditorPauseOrDisposal() {
        var current by mutableStateOf(goal)
        var daily by mutableStateOf<DailyReadingGoalState?>(null)
        var shown by mutableStateOf(true)
        var active by mutableStateOf(true)
        val effects = mutableListOf<Long>(); val callback: (Long) -> Unit = effects::add
        compose.setContent { YaprakTheme { if (shown) ReadingStatisticsScreen(stats(0).copy(active = active), {}, {},
            goalState = daily, weeklyState = current, onWeeklyOpen = callback) } }
        compose.onNodeWithTag("statistics-body").performScrollToNode(hasTestTag("statistics-weekly-goal-open"))
        val stale = compose.onNodeWithTag("statistics-weekly-goal-open").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { current = goal.copy(revision = 8) }; compose.runOnIdle { stale() }; assertTrue(effects.isEmpty())
        val valid = compose.onNodeWithTag("statistics-weekly-goal-open").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { daily = DailyReadingGoalState(loading = false, active = true,
            editor = DailyReadingGoalDraft("daily-editor", enabled = true, minutesText = "15")) }
        compose.runOnIdle { valid() }; assertTrue(effects.isEmpty())
        compose.runOnIdle { daily = null; active = false }; compose.runOnIdle { valid() }; assertTrue(effects.isEmpty())
        compose.runOnIdle { shown = false }; compose.runOnIdle { valid() }; assertTrue(effects.isEmpty())
    }
    @Test fun doubleTypeWeeklyEditorShowsExactFailedDraftAndReadablePersistentFooter() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        var draft by mutableStateOf(WeeklyReadingGoalDraft("fixture", enabled = true, minutesText = "0"))
        var writes = 0
        compose.setContent { YaprakTheme {
            WeeklyReadingGoalDialog(draft, false, true, { true }, { _, _, _ -> },
                { _, _, value -> draft = draft.copy(minutesText = value, revision = draft.revision + 1) }, { _, _ -> writes++ }, { _, _ -> })
        } }
        compose.onNodeWithTag("weekly-goal-save").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("weekly-goal-cancel").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(text(R.string.weekly_goal_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.weekly_goal_save_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("weekly-goal-minutes").performScrollTo().performTextReplacement("7000")
        compose.onNodeWithTag("weekly-goal-save").assertIsEnabled()
        assertEquals("7000", draft.minutesText); assertEquals(0, writes)
        for ((value, tag) in listOf(text(R.string.weekly_goal_save) to "weekly-goal-save", text(R.string.weekly_goal_cancel) to "weekly-goal-cancel")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
            assertFalse(layout.hasVisualOverflow); assertEquals(value.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        }
        val body = compose.onNodeWithTag("weekly-goal-body").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("weekly-goal-actions").fetchSemanticsNode().boundsInRoot
        assertTrue(footer.top >= body.bottom)
        compose.onNodeWithTag("weekly-goal-save").performClick(); assertEquals(1, writes)
    }
}

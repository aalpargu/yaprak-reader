package com.yaprak.reader.feature

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.yaprak.reader.R
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.statistics.ReadingDurationReceipt
import com.yaprak.reader.feature.statistics.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeeklyReadingGoalActivityTest {
    @get:Rule val compose = createAndroidComposeRule<ReadingStatisticsActivity>()
    private fun container() = (compose.activity.application as YaprakApplication).container
    private fun weekly() = ViewModelProvider(compose.activity)[WeeklyReadingGoalViewModel::class.java]
    private fun daily() = ViewModelProvider(compose.activity)[DailyReadingGoalViewModel::class.java]
    private fun statistics() = ViewModelProvider(compose.activity)[ReadingStatisticsViewModel::class.java]
    private fun ready() { compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("statistics-body").fetchSemanticsNodes().isNotEmpty() &&
            weekly().state.value.known && weekly().state.value.active && daily().state.value.known &&
            statistics().state.value.active && !statistics().state.value.loading
    } }
    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("statistics-body").performScrollToNode(hasTestTag(tag)); return compose.onNodeWithTag(tag)
    }
    @Test fun actualWeeklySaveReopenDisablePreservesDailyChoiceAndRealReceipts() {
        ready()
        val book = Book("weekly-book", "Kitap", "Deniz", BookFormat.EPUB, "book.epub", "book.epub", 1, "a".repeat(64), 1)
        val today = LocalDate.now(); val zone = ZoneId.systemDefault()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        fun receipt(date: LocalDate, millis: Long): ReadingDurationReceipt {
            val start = date.atStartOfDay(zone).plusSeconds(10).toInstant().toEpochMilli()
            return ReadingDurationReceipt(UUID.randomUUID().toString(), book.id, book.sha256, date.toString(), zone.id, start, start + millis, millis)
        }
        runBlocking {
            container().library.add(book); container().readingGoal.saveDailyMinutes(25)
            container().readingDuration.record(receipt(monday, 61_000)); container().readingDuration.record(receipt(today, 999))
            container().readingDuration.record(receipt(today.plusDays(1), 3_000))
        }
        compose.waitUntil(15_000) { statistics().state.value.snapshot?.totalMillis == 64_999L && daily().state.value.savedMinutes == 25 }
        assertEquals(61_999L, statistics().state.value.currentWeekMillis)
        assertNull(runBlocking { container().weeklyReadingGoal.weeklyMinutes.first() })
        node("statistics-weekly-goal-open").performClick()
        compose.onNodeWithTag("weekly-goal-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("weekly-goal-minutes").performScrollTo().performTextReplacement("1")
        compose.onNodeWithTag("weekly-goal-save").performClick()
        compose.waitUntil(15_000) { weekly().state.value.known && !weekly().state.value.saving && weekly().state.value.savedMinutes == 1 }
        assertEquals(1, runBlocking { container().weeklyReadingGoal.weeklyMinutes.first() })
        compose.onNodeWithTag("statistics-body").performScrollToNode(hasText(compose.activity.getString(R.string.weekly_goal_achieved)))
        compose.onNodeWithText(compose.activity.getString(R.string.weekly_goal_achieved)).assertIsDisplayed()
        compose.activityRule.scenario.recreate(); ready()
        assertEquals(1, weekly().state.value.savedMinutes); assertEquals(25, daily().state.value.savedMinutes)
        node("statistics-weekly-goal-open").performClick()
        compose.onNodeWithTag("weekly-goal-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("weekly-goal-save").performClick()
        compose.waitUntil(15_000) { weekly().state.value.known && !weekly().state.value.saving && weekly().state.value.savedMinutes == null }
        assertNull(runBlocking { container().weeklyReadingGoal.weeklyMinutes.first() })
        assertEquals(25, runBlocking { container().readingGoal.dailyMinutes.first() })
        assertEquals(64_999L, runBlocking { container().readingDuration.observe().first().totalMillis })
        assertNull(runBlocking { container().reading.resume(book.id, book.sha256) })
    }
    @Test fun oldWeeklyAndHistoryCallbacksCannotOpenOtherDialogAndRecoveryDisconnectsWeeklySource() {
        ready()
        val weeklyOpen = node("statistics-weekly-goal-open").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val historyOpen = node("statistics-completion-history").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        node("statistics-goal-open").performClick()
        compose.runOnIdle { weeklyOpen(); historyOpen() }
        assertNotNull(daily().state.value.editor); assertNull(weekly().state.value.editor)
        compose.onNodeWithTag("completion-history").assertDoesNotExist()
        compose.onNodeWithTag("daily-goal-cancel").performClick()
        node("statistics-weekly-goal-open").performClick()
        compose.runOnIdle { historyOpen() }
        compose.onNodeWithTag("completion-history").assertDoesNotExist(); assertNotNull(weekly().state.value.editor)
        compose.onNodeWithTag("weekly-goal-cancel").performClick()
        val retained = weekly()
        compose.runOnIdle { container().startup.requireRecovery() }
        compose.onNodeWithTag("statistics-body").assertDoesNotExist(); assertFalse(retained.state.value.active)
        compose.onNodeWithText(compose.activity.getString(R.string.reading_statistics_retry)).performClick(); ready()
        assertTrue(weekly().state.value.active)
        assertEquals(0L, runBlocking { container().readingDuration.observe().first().totalMillis })
    }
}

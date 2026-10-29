package com.yaprak.reader.feature

import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.yaprak.reader.R
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.statistics.ReadingDurationReceipt
import com.yaprak.reader.feature.statistics.ReadingStatisticsActivity
import com.yaprak.reader.feature.statistics.ReadingStatisticsViewModel
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

/** Actual main Room receipts and saved UI recreation. Test receipts are explicit fixture data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingStatisticsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<ReadingStatisticsActivity>()

    @Test fun `activity reads persisted idempotent receipts current metadata and zero books through recreation without writing duration`() {
        waitForReady()
        val container = (compose.activity.application as YaprakApplication).container
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val book = Book("stats-book", "Özgün başlık", "Deniz", BookFormat.PDF,
            "fixture.pdf", "fixture.pdf", 1, "a".repeat(64), 1, pageCount = 10)
        val zero = book.copy(id = "stats-zero", title = "Sıfır süreli kitap", sha256 = "b".repeat(64), storageName = "zero.pdf")
        fun receipt(date: LocalDate, millis: Long): ReadingDurationReceipt {
            val started = date.atStartOfDay(zone).plusSeconds(10).toInstant().toEpochMilli()
            return ReadingDurationReceipt(UUID.randomUUID().toString(), book.id, book.sha256,
                date.toString(), zone.id, started, started + millis, millis)
        }
        val current = receipt(today, 61_500)
        val previous = receipt(today.minusDays(1), 3_600_123)
        runBlocking {
            container.library.add(book); container.library.add(zero)
            container.readingDuration.record(current)
            container.readingDuration.record(current)
            container.readingDuration.record(previous)
        }
        waitForState { it.snapshot?.totalMillis == 3_661_623L }
        assertEquals(61_500L, model().state.value.todayMillis)
        assertEquals(3_661_623L, model().state.value.lastSevenMillis)
        assertEquals(2, model().state.value.snapshot!!.books.size)
        assertEquals(2, model().state.value.readingStreak!!.currentDays)
        assertEquals(2, model().state.value.readingStreak!!.longestDays)
        scrollTag("statistics-streak-current").assertTextEquals(compose.activity.getString(R.string.reading_streak_current, 2))
        assertEquals(0L, model().state.value.snapshot!!.books.single { it.book.id == zero.id }.totalMillis)
        scrollTag("statistics-calendar-toggle").performClick()
        scrollTag("statistics-day-$today").assertExists()
        val before = compose.activity
        compose.activityRule.scenario.recreate()
        assertNotSame(before, compose.activity)
        waitForReady()
        waitForState { it.snapshot?.totalMillis == 3_661_623L }
        scrollTag("statistics-streak-longest").assertTextEquals(compose.activity.getString(R.string.reading_streak_longest, 2))
        scrollTag("statistics-day-$today").assertExists()
        runBlocking { assertTrue(container.library.updateMetadata(book.id, book.sha256, "Güncel İ/ı 😀", "Yeni yazar")) }
        waitForState { it.snapshot?.books?.singleOrNull { entry -> entry.book.id == book.id }?.book?.title == "Güncel İ/ı 😀" }
        scrollTag("statistics-book-${book.id}").assertExists()
        compose.onNodeWithText("Güncel İ/ı 😀").assertExists()
        compose.onNodeWithTag("statistics-book-${zero.id}").assertDoesNotExist()
        assertEquals(3_661_623L, model().state.value.snapshot!!.totalMillis)
        assertNull(runBlocking { container.reading.resume(book.id, book.sha256) })
    }

    @Test fun `startup recovery removes persisted statistics source and retry reattaches current Activity owner`() {
        waitForReady()
        val container = (compose.activity.application as YaprakApplication).container
        val retained = model()
        compose.runOnIdle { container.startup.requireRecovery() }
        compose.onNodeWithText(compose.activity.getString(R.string.reading_statistics_startup_failed)).assertIsDisplayed()
        compose.onNodeWithTag("statistics-body").assertDoesNotExist()
        assertFalse(retained.state.value.active)
        compose.onNodeWithText(compose.activity.getString(R.string.reading_statistics_retry)).performClick()
        waitForReady()
        assertTrue(model().state.value.active)
    }

    @Test fun `statistics intent is internal without externally supplied date duration or book`() {
        val intent = ReadingStatisticsActivity.intent(compose.activity)
        assertEquals(ReadingStatisticsActivity::class.java.name, intent.component!!.className)
        assertNull(intent.extras)
        val info = compose.activity.packageManager.getActivityInfo(ComponentName(compose.activity, ReadingStatisticsActivity::class.java), 0)
        assertFalse(info.exported)
    }

    @Test fun `actual Activity edits saves recreates and disables device goal without creating duration`() {
        waitForReady()
        val container = (compose.activity.application as YaprakApplication).container
        compose.waitUntil(10_000) { !goalModel().state.value.loading }
        assertNull(runBlocking { container.readingGoal.dailyMinutes.first() })
        scrollTag("statistics-goal-open").performClick()
        compose.onNodeWithTag("daily-goal-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("daily-goal-minutes").performScrollTo().performTextReplacement("1")
        compose.onNodeWithTag("daily-goal-save").performClick()
        compose.waitUntil(10_000) { !goalModel().state.value.saving && goalModel().state.value.savedMinutes == 1 }
        assertEquals(1, runBlocking { container.readingGoal.dailyMinutes.first() })
        assertEquals(0L, model().state.value.snapshot!!.totalMillis)
        compose.activityRule.scenario.recreate()
        waitForReady()
        compose.waitUntil(10_000) { goalModel().state.value.known && goalModel().state.value.savedMinutes == 1 }
        scrollTag("statistics-goal-open").performClick()
        compose.onNodeWithTag("daily-goal-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("daily-goal-save").performClick()
        compose.waitUntil(10_000) { goalModel().state.value.known && !goalModel().state.value.saving &&
            goalModel().state.value.editor == null && goalModel().state.value.savedMinutes == null }
        assertNull(runBlocking { container.readingGoal.dailyMinutes.first() })
        assertEquals(0L, model().state.value.snapshot!!.totalMillis)
    }

    @Test fun historyUsesRealStatusTransitionsAndSurvivesRecreationWithoutWritingReadingTime() {
        waitForReady()
        val container = (compose.activity.application as YaprakApplication).container
        val book = Book("history-book", "Bitirdiğim kitap", "Deniz", BookFormat.PDF,
            "history.pdf", "history.pdf", 1, "a".repeat(64), 1, pageCount = 10)
        val completed = com.yaprak.reader.domain.library.BookOrganization(
            readingStatus = com.yaprak.reader.domain.library.BookReadingStatus.COMPLETED)
        runBlocking {
            container.library.add(book)
            assertTrue(container.library.updateOrganization(book.id, book.sha256, completed))
            assertTrue(container.library.updateOrganization(book.id, book.sha256, completed))
        }
        scrollTag("statistics-completion-history").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Kayıtlı bitirme: 1").fetchSemanticsNodes().isNotEmpty()
        }
        val event = runBlocking { container.bookCompletions.observe().first().entries.single().event }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("completion-history").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Kayıtlı bitirme: 1").fetchSemanticsNodes().isNotEmpty()
        }
        val current = (compose.activity.application as YaprakApplication).container
        assertEquals(event, runBlocking { current.bookCompletions.observe().first().entries.single().event })
        runBlocking { assertTrue(current.library.updateMetadata(book.id, book.sha256, "Güncel bitirme başlığı", "Yeni yazar")) }
        compose.waitUntil(15_000) {
            ViewModelProvider(compose.activity)[com.yaprak.reader.feature.statistics.BookCompletionViewModel::class.java]
                .state.value.snapshot?.entries?.singleOrNull()?.title == "Güncel bitirme başlığı"
        }
        compose.onNodeWithTag("completion-history").performScrollToNode(hasText("Güncel bitirme başlığı"))
        compose.onNodeWithText("Güncel bitirme başlığı").assertIsDisplayed()
        compose.onNodeWithText("Kapat").performClick()
        compose.onNodeWithTag("completion-history").assertDoesNotExist()
        assertEquals(0L, runBlocking { current.readingDuration.observe().first().totalMillis })
        assertNull(runBlocking { current.reading.resume(book.id, book.sha256) })
    }

    private fun goalModel() = ViewModelProvider(compose.activity)[com.yaprak.reader.feature.statistics.DailyReadingGoalViewModel::class.java]

    private fun model() = ViewModelProvider(compose.activity)[ReadingStatisticsViewModel::class.java]
    private fun waitForReady() = waitForState { it.active && !it.loading && it.failure == null }
    private fun waitForState(predicate: (com.yaprak.reader.feature.statistics.ReadingStatisticsState) -> Boolean) = compose.waitUntil(15_000) {
        if (compose.onAllNodesWithTag("statistics-body").fetchSemanticsNodes().isEmpty()) false else predicate(model().state.value)
    }
    private fun scrollTag(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("statistics-body").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
}

package com.yaprak.reader.feature

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.statistics.*
import com.yaprak.reader.feature.statistics.*
import com.yaprak.reader.feature.library.DiscoveryState
import com.yaprak.reader.feature.library.LibraryScreen
import com.yaprak.reader.feature.library.LibraryState
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingStatisticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val today = LocalDate.parse("2026-10-05")
    private val book = Book("book", "Özgün kitap İ/ı 😀\nİkinci satır e\u0301", "Deniz 🌿", BookFormat.EPUB,
        "book.epub", "book.epub", 1, "a".repeat(64), 1)
    private val snapshot = ReadingDurationSnapshot(listOf(ReadingDurationDay("2026-10-05", 61_999),
        ReadingDurationDay("2026-10-04", 499), ReadingDurationDay("2026-09-01", 3_600_000)),
        listOf(BookReadingDuration(book, 3_662_498), BookReadingDuration(book.copy(id = "zero", title = "Zero book"), 0)), 3_662_498)
    private val ready = ReadingStatisticsState(today, "Europe/Istanbul", snapshot, loading = false, active = true, revision = 7)

    @Test fun `weekly average and recorded day count are explicit while completed books use marked current metadata`() {
        val completed = book.copy(id = "completed", organization = com.yaprak.reader.domain.library.BookOrganization(
            readingStatus = com.yaprak.reader.domain.library.BookReadingStatus.COMPLETED))
        val values = ReadingDurationSnapshot(snapshot.days, snapshot.books + BookReadingDuration(completed, 0), snapshot.totalMillis)
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(ready.copy(snapshot = values), {}, {}) } }
        scrollTag("statistics-average"); inRow("statistics-average", text(R.string.reading_statistics_seconds, 8)).assertExists()
        scrollTag("statistics-reading-days").assertTextEquals(text(R.string.reading_statistics_days, 2))
        scrollTag("statistics-completed").assertTextEquals(text(R.string.reading_statistics_completed, 1))
        scrollText(text(R.string.reading_statistics_completed_help)).assertExists()
    }

    @Test fun `unavailable loading and failed duration snapshot do not fabricate new metric values`() {
        var state by mutableStateOf(ready.copy(snapshot = null, loading = true))
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(state, {}, {}) } }
        compose.onNodeWithTag("statistics-average").assertDoesNotExist(); compose.onNodeWithTag("statistics-completed").assertDoesNotExist()
        compose.runOnIdle { state = ready.copy(failure = ReadingDurationFailure.SOURCE) }
        compose.onNodeWithTag("statistics-average").assertDoesNotExist(); compose.onNodeWithTag("statistics-completed").assertDoesNotExist()
        compose.runOnIdle { state = ready.copy(snapshot = ReadingDurationSnapshot(emptyList(), emptyList(), 0)) }
        scrollTag("statistics-completed").assertTextEquals(text(R.string.reading_statistics_completed, 0))
    }

    @Test fun `summary only shows persisted exact period and current library totals with original metadata`() {
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(ready, {}, {}) } }
        scrollTag("statistics-today").assertExists()
        inRow("statistics-today", text(R.string.reading_statistics_minutes, 1, 1)).assertExists()
        scrollTag("statistics-week").assertExists()
        inRow("statistics-week", text(R.string.reading_statistics_minutes, 1, 2)).assertExists()
        scrollTag("statistics-total").assertExists()
        inRow("statistics-total", text(R.string.reading_statistics_hours, 1, 1, 2)).assertExists()
        scrollText(text(R.string.reading_statistics_dates_help)).assertIsDisplayed()
        scrollTag("statistics-book-book").assertExists()
        inRow("statistics-book-book", book.title).assertExists()
        inRow("statistics-book-book", book.authors!!).assertExists()
        compose.onNodeWithTag("statistics-book-zero").assertDoesNotExist()
        compose.onNodeWithTag("statistics-back").assertIsDisplayed()
    }

    @Test fun `28 day list has exact recorded labels and missing dates are no record rather than fabricated duration`() {
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(ready, {}, {}) } }
        scrollTag("statistics-calendar-toggle").performClick()
        scrollTag("statistics-day-2026-10-05").assertExists()
        inRow("statistics-day-2026-10-05", text(R.string.reading_statistics_minutes, 1, 1)).assertExists()
        scrollTag("statistics-day-2026-10-04").assertExists()
        inRow("statistics-day-2026-10-04", text(R.string.reading_statistics_under_second)).assertExists()
        scrollTag("statistics-day-2026-10-03").assertExists()
        inRow("statistics-day-2026-10-03", text(R.string.reading_statistics_day_empty)).assertExists()
        scrollTag("statistics-day-2026-09-08").assertExists()
        compose.onNodeWithTag("statistics-day-2026-09-07").assertDoesNotExist()
        compose.onNodeWithTag("statistics-day-2026-10-06").assertDoesNotExist()
        scrollTag("statistics-calendar-toggle").performClick()
        compose.onNodeWithTag("statistics-day-2026-10-05").assertDoesNotExist()
    }

    @Test fun `typed failure empty and loading stay distinct and retry retains exact revision`() {
        var state by mutableStateOf(ready.copy(snapshot = null, failure = ReadingDurationFailure.SOURCE))
        val retries = mutableListOf<Long>()
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(state, retries::add, {}) } }
        val old = action("statistics-retry")
        compose.runOnIdle { state = state.copy(revision = 8, failure = ReadingDurationFailure.RECOVERY) }
        compose.runOnIdle { old() }
        assertTrue(retries.isEmpty())
        scrollText(text(R.string.reading_statistics_recovery)).assertIsDisplayed()
        scrollTag("statistics-retry").performClick()
        assertEquals(listOf(8L), retries)
        compose.runOnIdle { state = ready.copy(snapshot = ReadingDurationSnapshot(emptyList(), emptyList(), 0)) }
        scrollText(text(R.string.reading_statistics_empty)).assertIsDisplayed()
        compose.onNodeWithTag("statistics-retry").assertDoesNotExist()
        compose.runOnIdle { state = ready.copy(snapshot = null, loading = true) }
        scrollText(text(R.string.reading_statistics_loading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reading_statistics_empty)).assertDoesNotExist()
        compose.onNodeWithTag("statistics-total").assertDoesNotExist()
    }

    @Test fun `old calendar retry and back events are rejected after pause state replacement and disposal`() {
        var shown by mutableStateOf(true)
        var state by mutableStateOf(ready)
        var backs = 0
        var retries = 0
        compose.setContent { YaprakTheme { if (shown) ReadingStatisticsScreen(state, { retries++ }, { backs++ }) } }
        val oldCalendar = action("statistics-calendar-toggle")
        val oldBack = compose.onNodeWithTag("statistics-back").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        scrollTag("statistics-calendar-toggle").performClick()
        compose.runOnIdle { oldCalendar() }
        scrollTag("statistics-day-2026-10-05").assertExists()
        compose.runOnIdle { state = ready.copy(active = false) }
        compose.runOnIdle { oldCalendar(); oldBack() }
        assertEquals(0, backs)
        compose.runOnIdle { state = ready.copy(snapshot = null, failure = ReadingDurationFailure.SOURCE) }
        val oldRetry = action("statistics-retry")
        compose.runOnIdle { state = state.copy(active = false) }
        compose.runOnIdle { oldRetry() }
        assertEquals(0, retries)
        compose.runOnIdle { state = ready; shown = false }
        compose.runOnIdle { oldCalendar(); oldRetry(); oldBack() }
        assertEquals(0, retries); assertEquals(0, backs)
    }

    @Test fun `native narrow double type summary book Unicode and fixed back have no visual text overflow`() {
        largeFont()
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(ready, {}, {}) } }
        listOf(R.string.reading_statistics_title, R.string.reading_statistics_help, R.string.reading_statistics_scope,
            R.string.reading_statistics_week, R.string.reading_statistics_total,
            R.string.reading_statistics_dates_help).forEach { readableScroll(text(it)) }
        readableScroll(text(R.string.reading_statistics_today, today.toString()))
        readableScroll(text(R.string.reading_statistics_zone, "Europe/Istanbul"))
        scrollTag("statistics-calendar-toggle").assertIsDisplayed()
        readable(text(R.string.reading_statistics_calendar_show), "statistics-calendar-toggle")
        scrollTag("statistics-book-book").assertExists()
        inRow("statistics-book-book", book.title).performScrollTo().assertIsDisplayed()
        readable(book.title, "statistics-book-book")
        inRow("statistics-book-book", book.authors!!).performScrollTo().assertIsDisplayed()
        readable(book.authors!!, "statistics-book-book")
        readable(text(R.string.reading_statistics_hours, 1, 1, 2), "statistics-book-book")
        assertFooter()
        capture("reading-statistics-summary-double-type")
    }

    @Test fun `native narrow double type calendar blank labels and maximum Long duration remain readable`() {
        largeFont()
        val maximum = ReadingDurationSnapshot(listOf(ReadingDurationDay(today.toString(), MAX_READING_DURATION_TOTAL_MILLIS)),
            listOf(BookReadingDuration(book, MAX_READING_DURATION_TOTAL_MILLIS)), MAX_READING_DURATION_TOTAL_MILLIS)
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(ready.copy(snapshot = maximum), {}, {}) } }
        scrollTag("statistics-today").assertExists()
        readable(text(R.string.reading_statistics_hours, 4_800_000L, 0L, 0L), "statistics-today")
        scrollTag("statistics-calendar-toggle").performClick()
        readable(text(R.string.reading_statistics_calendar_hide), "statistics-calendar-toggle")
        scrollTag("statistics-day-2026-10-05").assertExists()
        readable(today.toString(), "statistics-day-2026-10-05")
        readable(text(R.string.reading_statistics_hours, 4_800_000L, 0L, 0L), "statistics-day-2026-10-05")
        scrollTag("statistics-day-2026-09-08").assertExists()
        readable("2026-09-08", "statistics-day-2026-09-08")
        readable(text(R.string.reading_statistics_day_empty), "statistics-day-2026-09-08")
        assertFooter()
        capture("reading-statistics-calendar-double-type")
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `native short landscape double type retry and calendar actions scroll while fixed back stays accessible`() {
        largeFont()
        var state by mutableStateOf(ready.copy(snapshot = null, failure = ReadingDurationFailure.SOURCE))
        var retries = 0
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(state, { retries++ }, {}) } }
        readableScroll(text(R.string.reading_statistics_failed))
        scrollTag("statistics-retry").assertIsDisplayed().performClick()
        readable(text(R.string.reading_statistics_retry), "statistics-retry")
        assertFooter()
        capture("reading-statistics-landscape-double-type")
        assertEquals(1, retries)
        compose.runOnIdle { state = ready }
        scrollTag("statistics-calendar-toggle").assertIsDisplayed().performClick()
        readable(text(R.string.reading_statistics_calendar_hide), "statistics-calendar-toggle")
        scrollTag("statistics-day-2026-09-08").assertExists()
        readable("2026-09-08", "statistics-day-2026-09-08")
        assertFooter()
    }

    @Test fun `route clock broadcast reperiods actual saved snapshot without Room source resubscription and pause blocks old callbacks`() {
        var now = ZonedDateTime.parse("2026-10-05T12:00:00+03:00[Europe/Istanbul]")
        val repository = Repository()
        val model = ReadingStatisticsViewModel(repository) { now }
        compose.activity.viewModelStore.put("statistics", model)
        var backs = 0
        compose.setContent { YaprakTheme { ReadingStatisticsRoute(model) { backs++ } } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("statistics-body").fetchSemanticsNodes()
            model.state.value.active && !model.state.value.loading
        }
        val saved = model.state.value.snapshot
        val clockReceiver = shadowOf(compose.activity.application).registeredReceivers.single {
            it.intentFilter.hasAction(Intent.ACTION_DATE_CHANGED) &&
                it.intentFilter.hasAction(Intent.ACTION_TIME_CHANGED) &&
                it.intentFilter.hasAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        assertTrue((clockReceiver.flags and Context.RECEIVER_NOT_EXPORTED) != 0)
        compose.runOnIdle {
            now = ZonedDateTime.parse("2026-10-06T00:01:00+09:00[Asia/Tokyo]")
            compose.activity.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED).setPackage(compose.activity.packageName))
        }
        // Robolectric dispatches broadcasts through the paused Android main looper,
        // separately from Compose's coroutine/frame scheduler. Deliver the real queued
        // broadcast instead of calling the receiver or ViewModel directly.
        ShadowLooper.idleMainLooper()
        compose.waitUntil(5_000) { model.state.value.today == LocalDate.parse("2026-10-06") }
        assertEquals(1, repository.subscriptions)
        assertSame(saved, model.state.value.snapshot)
        val oldBack = compose.onNodeWithTag("statistics-back").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnIdle { oldBack() }
        assertEquals(0, backs)
        assertFalse(model.state.value.active)
        compose.runOnIdle {
            now = ZonedDateTime.parse("2026-10-07T00:01:00+09:00[Asia/Tokyo]")
            compose.activity.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED).setPackage(compose.activity.packageName))
        }
        ShadowLooper.idleMainLooper()
        assertEquals(LocalDate.parse("2026-10-06"), model.state.value.today)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil { model.state.value.active && !model.state.value.loading }
        assertEquals(LocalDate.parse("2026-10-07"), model.state.value.today)
        assertEquals(2, repository.subscriptions)
        assertEquals(0, repository.records)
    }

    @Test fun `optional statistics library entry cannot retarget replacement callback or bypass current library mutation`() {
        var discovery by mutableStateOf<DiscoveryState>(DiscoveryState.Idle)
        var available by mutableStateOf(true)
        var version by mutableStateOf(1)
        val effects = mutableListOf<Int>()
        val metadataChanges = mutableListOf<String>()
        val first: () -> Unit = { effects += 1 }; val second: () -> Unit = { effects += 2 }
        compose.setContent { YaprakTheme { LibraryScreen(LibraryState.Ready(emptyList()), false,
            SnackbarHostState(), {}, {}, {}, "metadata query", metadataChanges::add,
            discoveryState = discovery, onStatistics = if (!available) null else if (version == 1) first else second) } }
        val old = compose.onNodeWithText(text(R.string.reading_statistics_title)).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { discovery = DiscoveryState.Choosing("picker") }
        compose.onNodeWithText(text(R.string.reading_statistics_title)).assertIsNotEnabled()
        compose.runOnIdle { old() }
        assertTrue(effects.isEmpty())
        compose.runOnIdle { discovery = DiscoveryState.Idle; version = 2 }
        compose.runOnIdle { old() }
        assertTrue(effects.isEmpty())
        compose.onNodeWithText(text(R.string.reading_statistics_title)).performClick()
        assertEquals(listOf(2), effects)
        assertTrue(metadataChanges.isEmpty())
        compose.runOnIdle { available = false }
        compose.onNodeWithText(text(R.string.reading_statistics_title)).assertDoesNotExist()
        compose.runOnIdle { old() }
        assertEquals(listOf(2), effects)
    }

    private inner class Repository : ReadingDurationRepository {
        val source = MutableStateFlow(snapshot)
        var subscriptions = 0
        var records = 0
        override fun observe() = kotlinx.coroutines.flow.flow {
            subscriptions++
            source.collect { emit(it) }
        }
        override suspend fun record(receipt: ReadingDurationReceipt) { records++ }
    }
    private fun scrollTag(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("statistics-body").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun scrollText(value: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("statistics-body").performScrollToNode(hasText(value))
        return compose.onNodeWithText(value)
    }
    private fun inRow(tag: String, value: String) = compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(tag)))
    private fun action(tag: String): () -> Boolean = scrollTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
    }
    private fun readableScroll(value: String) { scrollText(value).assertIsDisplayed(); readable(value) }
    private fun readable(value: String, ancestorTag: String? = null) {
        val layouts = mutableListOf<TextLayoutResult>()
        val matcher = if (ancestorTag == null) hasText(value) else hasText(value) and hasAnyAncestor(hasTestTag(ancestorTag))
        compose.onNode(matcher, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse("Text overflow: $value size=${layout.size} width=${layout.didOverflowWidth} height=${layout.didOverflowHeight}", layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun assertFooter() {
        compose.onNodeWithTag("statistics-back").assertIsDisplayed().assertIsEnabled()
        readable(text(R.string.reading_statistics_back), "statistics-back")
        val body = compose.onNodeWithTag("statistics-body").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("statistics-back").fetchSemanticsNode().boundsInRoot
        assertTrue("Footer must stay outside scrollable body", footer.top >= body.bottom)
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingMonthUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("calendar-book", "Calendar", null, BookFormat.EPUB, "b.epub", "b.epub", 1, "a".repeat(64), 1)
    private val ready = ReadingStatisticsState(LocalDate.of(2026, 10, 5), "Europe/Istanbul",
        ReadingDurationSnapshot(listOf(ReadingDurationDay("2026-09-30", 10000), ReadingDurationDay("2026-10-05", 61000)),
            listOf(BookReadingDuration(book, 71000)), 71000), loading = false, active = true)

    @Test fun `large text month cells expose exact saved and missing receipts and navigation rejects old ABA actions`() {
        largeFont()
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(ready, {}, {}) } }
        scroll("statistics-calendar-toggle").performClick()
        scroll("calendar-cell-2026-10-05").assertContentDescriptionContains("Bugün · 2026-10-05 · 1 dk 1 sn")
        scroll("calendar-cell-2026-10-06").assertContentDescriptionContains("2026-10-06 · Kayıtlı süre yok")
        capture("calendar-narrow-double-type")
        scroll("statistics-month-previous").assertIsDisplayed()
        readable("Önceki ay")
        val old = compose.onNodeWithTag("statistics-month-previous").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("statistics-month-previous").performClick()
        scroll("statistics-month-label").assertTextEquals("Eylül 2026")
        compose.runOnIdle { old() }
        scroll("statistics-month-label").assertTextEquals("Eylül 2026")
        scroll("statistics-month-next").performClick()
        compose.runOnIdle { old() }
        scroll("statistics-month-label").assertTextEquals("Ekim 2026")
        scroll("statistics-month-next").assertIsNotEnabled()
        compose.onNodeWithTag("statistics-back").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short landscape large type reaches calendar week and independent navigation while loading or errors hide calendar`() {
        largeFont()
        var state by mutableStateOf(ready)
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(state, {}, {}) } }
        scroll("statistics-calendar-toggle").performClick()
        scroll("calendar-cell-2026-10-05").assertIsDisplayed()
        capture("calendar-landscape-double-type")
        scroll("statistics-month-previous").assertIsDisplayed(); readable("Önceki ay")
        scroll("statistics-month-next").assertIsDisplayed(); readable("Sonraki ay")
        compose.onNodeWithTag("statistics-back").assertIsDisplayed()
        compose.runOnIdle { state = ready.copy(loading = true, snapshot = null) }
        compose.onNodeWithTag("statistics-month-label").assertDoesNotExist()
        compose.runOnIdle { state = ready.copy(snapshot = null, failure = ReadingDurationFailure.SOURCE) }
        compose.onNodeWithTag("statistics-month-previous").assertDoesNotExist()
    }

    @Test fun `default current month follows civil month rollover without relabelling saved days`() {
        var state by mutableStateOf(ready)
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(state, {}, {}) } }
        scroll("statistics-calendar-toggle").performClick()
        scroll("statistics-month-label").assertTextEquals("Ekim 2026")
        compose.runOnIdle { state = ready.copy(today = LocalDate.of(2026, 11, 1)) }
        scroll("statistics-month-label").assertTextEquals("Kasım 2026")
        scroll("calendar-cell-2026-11-01").assertContentDescriptionContains("Bugün · 2026-11-01 · Kayıtlı süre yok")
        scroll("statistics-month-previous").performClick()
        scroll("calendar-cell-2026-10-05").assertContentDescriptionContains("2026-10-05 · 1 dk 1 sn")
    }

    @Test fun `explicit September survives civil month rollover and saved instance state restore`() {
        var state by mutableStateOf(ready)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { YaprakTheme { ReadingStatisticsScreen(state, {}, {}) } }
        scroll("statistics-calendar-toggle").performClick()
        scroll("statistics-month-previous").performClick()
        scroll("statistics-month-label").assertTextEquals("Eylül 2026")
        compose.runOnIdle { state = ready.copy(today = LocalDate.of(2026, 11, 1)) }
        scroll("statistics-month-label").assertTextEquals("Eylül 2026")
        restoration.emulateSavedInstanceStateRestore()
        scroll("statistics-month-label").assertTextEquals("Eylül 2026")
        scroll("statistics-month-next").performClick()
        scroll("statistics-month-label").assertTextEquals("Ekim 2026")
    }

    @Test fun `retained opposite month callbacks cannot replace a selection before recomposition`() {
        val bounded = withDays("2026-08-31" to 1, "2026-10-05" to 61000, "2026-11-01" to 2)
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(bounded, {}, {}) } }
        scroll("statistics-calendar-toggle").performClick()
        scroll("statistics-month-previous").assertIsEnabled()
        val octoberPrevious = action("statistics-month-previous")
        scroll("statistics-month-next").assertIsEnabled()
        val octoberNext = action("statistics-month-next")
        compose.runOnIdle { octoberPrevious(); octoberNext() }
        scroll("statistics-month-label").assertTextEquals("Eylül 2026")
        scroll("statistics-month-previous").assertIsEnabled()
        val septemberPrevious = action("statistics-month-previous")
        scroll("statistics-month-next").assertIsEnabled()
        val septemberNext = action("statistics-month-next")
        compose.runOnIdle { septemberNext(); septemberPrevious() }
        scroll("statistics-month-label").assertTextEquals("Ekim 2026")
    }

    @Test fun `same month source replacement rejects retained navigation but permits fresh navigation`() {
        var state by mutableStateOf(withDays("2026-08-31" to 1, "2026-10-05" to 61000, "2026-11-01" to 2))
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(state, {}, {}) } }
        scroll("statistics-calendar-toggle").performClick()
        scroll("statistics-month-previous").assertIsEnabled()
        val previous = action("statistics-month-previous")
        scroll("statistics-month-next").assertIsEnabled()
        val next = action("statistics-month-next")
        compose.runOnIdle { state = withDays("2026-08-31" to 1, "2026-10-05" to 62000, "2026-11-01" to 2) }
        compose.runOnIdle { previous(); next() }
        scroll("statistics-month-label").assertTextEquals("Ekim 2026")
        scroll("calendar-cell-2026-10-05").assertContentDescriptionContains("Bugün · 2026-10-05 · 1 dk 2 sn")
        scroll("statistics-month-previous").performClick()
        scroll("statistics-month-label").assertTextEquals("Eylül 2026")
    }

    @Test fun `retained show action cannot reopen calendar after show hide ABA`() {
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(ready, {}, {}) } }
        scroll("statistics-calendar-toggle")
        val oldShow = action("statistics-calendar-toggle")
        compose.onNodeWithTag("statistics-calendar-toggle").performClick()
        scroll("statistics-month-label").assertTextEquals("Ekim 2026")
        scroll("statistics-calendar-toggle").performClick()
        compose.onNodeWithTag("statistics-month-label").assertDoesNotExist()
        compose.runOnIdle { oldShow() }
        compose.onNodeWithTag("statistics-month-label").assertDoesNotExist()
        scroll("statistics-calendar-toggle").performClick()
        scroll("statistics-month-label").assertTextEquals("Ekim 2026")
    }

    @Test fun `recorded zero duration and missing day keep distinct accessible descriptions`() {
        val state = withDays("2026-10-05" to 0)
        compose.setContent { YaprakTheme { ReadingStatisticsScreen(state, {}, {}) } }
        scroll("statistics-calendar-toggle").performClick()
        scroll("calendar-cell-2026-10-05").assertContentDescriptionContains("Bugün · 2026-10-05 · 0 sn")
        scroll("calendar-cell-2026-10-06").assertContentDescriptionContains("2026-10-06 · Kayıtlı süre yok")
    }

    private fun scroll(tag: String) = compose.onNodeWithTag("statistics-body").performScrollToNode(hasTestTag(tag))
        .let { compose.onNodeWithTag(tag) }
    private fun action(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun withDays(vararg days: Pair<String, Long>): ReadingStatisticsState {
        val total = days.sumOf { it.second }
        return ready.copy(snapshot = ReadingDurationSnapshot(days.map { ReadingDurationDay(it.first, it.second) },
            listOf(BookReadingDuration(book, total)), total))
    }
    private fun largeFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate() }
    private fun readable(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, true))
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val file = File(System.getProperty("yaprak.screenshotDir"), "$name.png")
            file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

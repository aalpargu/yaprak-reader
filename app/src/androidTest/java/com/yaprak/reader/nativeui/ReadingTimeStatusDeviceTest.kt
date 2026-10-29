package com.yaprak.reader.nativeui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled state/Compose checks, separate from the real timed EPUB ReaderActivity case. */
@RunWith(AndroidJUnit4::class)
class ReadingTimeStatusDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun minuteHourAndEndpointLabelsDoNotImplyCompletion() {
        var estimate by mutableStateOf<ReadingTimeEstimate>(ReadingTimeEstimate.Measuring)
        compose.setContent { YaprakTheme { ReadingTimeStatus(estimate) } }
        compose.onNodeWithText(text(R.string.reading_time_measuring)).assertIsDisplayed()
        for ((minutes, value) in listOf(1 to "1 dk", 59 to "59 dk", 60 to "1 sa", 61 to "1 sa 1 dk", 403 to "6 sa 43 dk")) {
            compose.runOnIdle { estimate = ReadingTimeEstimate.Remaining(minutes) }
            compose.onNodeWithText(text(R.string.reading_time_remaining, value)).assertIsDisplayed()
        }
        compose.runOnIdle { estimate = ReadingTimeEstimate.Remaining(403, 63) }
        compose.onNodeWithText(text(R.string.reading_time_chapter_remaining, "1 sa 3 dk")).assertIsDisplayed()
        compose.runOnIdle { estimate = ReadingTimeEstimate.Remaining(403, 0) }
        compose.onNodeWithText(text(R.string.reading_time_chapter_end_position)).assertIsDisplayed()
        compose.onNodeWithText("0 dk", substring = true).assertDoesNotExist()
        compose.runOnIdle { estimate = ReadingTimeEstimate.EndPosition }
        compose.onNodeWithText(text(R.string.reading_time_end_position)).assertIsDisplayed()
        compose.onNodeWithText("0 dk", substring = true).assertDoesNotExist()
    }

    @Test fun readerShowsOnlyAnAvailableReflowableEstimateAndHidesItOnLoadOrSaveError() {
        var reader by mutableStateOf(ready(BookFormat.EPUB))
        var failed by mutableStateOf(false)
        var saveFailed by mutableStateOf(false)
        var estimate by mutableStateOf<ReadingTimeEstimate?>(ReadingTimeEstimate.Remaining(25))
        compose.setContent { YaprakTheme {
            ReaderChrome(reader, true, saveFailed, failed, {}, {}, {}, {}, {}, {}, {}, readingTime = estimate)
        } }
        compose.onNodeWithText(text(R.string.reading_time_remaining, "25 dk")).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { reader = ready(BookFormat.PDF) }
        compose.onNodeWithTag("reading-time-status").assertDoesNotExist()
        compose.runOnIdle { reader = ready(BookFormat.CBZ) }
        compose.onNodeWithTag("reading-time-status").assertDoesNotExist()
        compose.runOnIdle { reader = ready(BookFormat.EPUB); failed = true }
        compose.onNodeWithTag("reading-time-status").assertDoesNotExist()
        compose.runOnIdle { failed = false; saveFailed = true }
        compose.onNodeWithTag("reading-time-status").assertDoesNotExist()
        compose.runOnIdle { saveFailed = false; estimate = null }
        compose.onNodeWithTag("reading-time-status").assertDoesNotExist()
    }

    @Test fun bookChapterAndEndpointStatesWrapAt320dpAndFontScaleTwoWithoutClippingCharacters() {
        var estimate by mutableStateOf<ReadingTimeEstimate>(ReadingTimeEstimate.Measuring)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                YaprakTheme { Box(Modifier.width(320.dp).fillMaxHeight()) { ReadingTimeStatus(estimate) } }
            }
        }
        val metrics = JSONArray()
        val cases = listOf(
            Triple("measuring", ReadingTimeEstimate.Measuring, text(R.string.reading_time_measuring)),
            Triple("remaining", ReadingTimeEstimate.Remaining(403, 63), text(R.string.reading_time_remaining, "6 sa 43 dk")),
            Triple("end-position", ReadingTimeEstimate.EndPosition, text(R.string.reading_time_end_position)),
            Triple("chapter-end", ReadingTimeEstimate.Remaining(403, 0), text(R.string.reading_time_remaining, "6 sa 43 dk")),
        )
        val directory = File(checkNotNull(compose.activity.getExternalFilesDir(null)), "yaprak-reading-time-0.182")
        assertTrue(directory.isDirectory || directory.mkdirs())
        for ((name, value, label) in cases) {
            compose.runOnIdle { estimate = value }
            val captions = mutableListOf(label)
            (value as? ReadingTimeEstimate.Remaining)?.chapterMinutes?.let { chapter ->
                captions += if (chapter == 0) text(R.string.reading_time_chapter_end_position)
                    else { assertEquals(63, chapter); text(R.string.reading_time_chapter_remaining, "1 sa 3 dk") }
            }
            captions += text(R.string.reading_time_session_note)
            for (caption in captions) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(caption, useUnmergedTree = true).assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val layout = layouts.single()
                assertEquals(2f, layout.layoutInput.density.fontScale)
                assertEquals(caption, layout.layoutInput.text.text)
                val overflow = caption.indices.filterNot { caption[it].isWhitespace() }.maxOf { index ->
                    val cell = layout.getBoundingBox(index)
                    maxOf(-cell.left, cell.right - layout.size.width, -cell.top, cell.bottom - layout.size.height, 0f)
                }
                assertTrue("$caption: glyph overflow=$overflow", overflow.isFinite() && overflow <= 1f)
                for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
                assertEquals(caption.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                metrics.put(JSONObject().put("state", name).put("text", caption).put("fontScale", 2)
                    .put("width", layout.size.width).put("height", layout.size.height).put("maxGlyphOutsidePixels", overflow))
            }
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            try { File(directory, "$name-font2-320dp.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        File(directory, "text-layout-metrics.json").writeText(metrics.toString(2))
    }

    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private fun ready(format: BookFormat) = ReaderState.Ready(object : ReaderSession {
        override val book = Book(UUID.randomUUID().toString(), "Süre görünümü", null, format,
            "controlled.book", "controlled.book", 1, "a".repeat(64), 0)
        override val resume: ReadingLocation? = null
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface =
            error("Controlled UI case never creates a reader engine")
        override fun close() = Unit
    })
}

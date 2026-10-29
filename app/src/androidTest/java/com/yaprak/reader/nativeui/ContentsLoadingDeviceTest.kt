package com.yaprak.reader.nativeui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
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
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled contents widgets; the separate PDF ReaderActivity case uses real document bytes. */
@RunWith(AndroidJUnit4::class)
class ContentsLoadingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val contents = BookContents(listOf(ContentsEntry("one", "Birinci bölüm", 0, true),
        ContentsEntry("external", "Dış bağlantı", 0, false)))
    private val source = object : ContentsNavigator {
        override val contents get() = this@ContentsLoadingDeviceTest.contents
        override fun goTo(entryId: String) = error("Controlled widget does not create a reader")
    }

    @Test fun loadingAndFailureNeverPretendThatTheBookHasNoContents() {
        val request = ContentsRequest(source)
        var state by mutableStateOf(ContentsState(request, loading = true))
        compose.setContent { YaprakTheme { ContentsLoadBody(state, { _, _ -> false }, {}, {}) } }
        compose.onNodeWithText(text(R.string.contents_loading)).assertIsDisplayed()
        compose.onNodeWithText("Birinci bölüm").assertDoesNotExist()
        compose.runOnIdle { state = ContentsState(request, failure = ContentsLoadFailure.SOURCE) }
        compose.onNodeWithText(text(R.string.contents_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.contents_retry)).assertIsDisplayed()
        compose.onNodeWithText("Birinci bölüm").assertDoesNotExist()
        compose.runOnIdle { state = ContentsState(request, contents = contents) }
        compose.onNodeWithText("Birinci bölüm").assertIsDisplayed()
        compose.onNodeWithText("Dış bağlantı").assertIsNotEnabled()
    }

    @Test fun narrowDoubleTypeShowsLoadingAndKeepsLimitRetryAndRowsReachable() {
        val request = ContentsRequest(source)
        var state by mutableStateOf(ContentsState(request, loading = true))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                YaprakTheme { Box(Modifier.width(320.dp).fillMaxHeight()) { ContentsLoadBody(state, { _, _ -> false }, {}, {}) } }
            }
        }
        val metrics = JSONArray()
        val cases = listOf(
            Triple("loading", ContentsState(request, loading = true), text(R.string.contents_loading)),
            Triple("limit", ContentsState(request, failure = ContentsLoadFailure.LIMIT), text(R.string.contents_limit)),
            Triple("ready", ContentsState(request, contents = contents), "Birinci bölüm"),
        )
        val directory = File(checkNotNull(compose.activity.getExternalFilesDir(null)), "yaprak-contents-ui-0.182")
        assertTrue(directory.isDirectory || directory.mkdirs())
        for ((name, value, caption) in cases) {
            compose.runOnIdle { state = value }
            val node = compose.onNodeWithText(caption, useUnmergedTree = true)
            if (name != "loading") node.performScrollTo()
            node.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
            assertEquals(caption, layout.layoutInput.text.text)
            val overflow = caption.indices.filterNot { caption[it].isWhitespace() }.maxOf { index ->
                val cell = layout.getBoundingBox(index)
                maxOf(-cell.left, cell.right - layout.size.width, -cell.top, cell.bottom - layout.size.height, 0f)
            }
            assertTrue(overflow.isFinite() && overflow <= 1f)
            for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
            assertEquals(caption.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            metrics.put(JSONObject().put("state", name).put("text", caption).put("fontScale", 2)
                .put("width", layout.size.width).put("height", layout.size.height).put("maxGlyphOutsidePixels", overflow))
            if (name == "limit") compose.onNodeWithText(text(R.string.contents_retry)).performScrollTo().assertIsDisplayed()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            try { File(directory, "$name-font2-320dp.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        File(directory, "text-layout-metrics.json").writeText(metrics.toString(2))
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}

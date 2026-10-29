package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.R
import com.yaprak.reader.feature.reader.PdfThumbnailsDialog
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
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
class PdfThumbnailsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val green = Color.rgb(23, 198, 61)
    private val picture by lazy { Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(green) } }
    private var activePageCount = 1

    @Test fun `ten thousand pages render lazily near the actual current page without navigation`() {
        val requested = mutableListOf<Int>()
        val selected = mutableListOf<Int>()
        var dismissed = 0
        show(10_000, 8_888, render = { requested += it; picture }, onGo = { selected += it; true },
            onDismiss = { dismissed++ })
        image(8_888)
        compose.onNodeWithTag("pdf-thumbnail-8888").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, text(R.string.pdf_thumbnail_current)))
        assertTrue(requested.contains(8_888))
        assertTrue("Whole-book rendering must stay lazy", requested.size in 1..30)
        assertFalse(requested.contains(0))
        assertTrue(selected.isEmpty()); assertEquals(0, dismissed)
        scrollToPage(9_999); image(9_999)
        assertTrue(requested.contains(9_999))
        assertTrue("A direct jump must not draw intervening pages: ${requested.size} requests", requested.size < 60)
        assertTrue(selected.isEmpty()); assertEquals(0, dismissed)
        compose.onNodeWithTag("pdf-thumbnail-9999").performClick()
        assertEquals(listOf(9_999), selected); assertEquals(1, dismissed)
    }

    @Test fun `real native bitmap colors are painted and shared bitmap survives dismissal`() {
        val open = mutableStateOf(true)
        var selected = 0
        compose.setContent { YaprakTheme {
            if (open.value) PdfThumbnailsDialog("picture", 1, 0, { picture }, { open.value },
                { selected++; true }, { open.value = false })
        } }
        scrollToPage(0); image(0)
        compose.runOnIdle {
            val capture = capture("pdf-thumbnails-original-colors")
            val pixels = IntArray(capture.width * capture.height)
            capture.getPixels(pixels, 0, capture.width, 0, 0, capture.width, capture.height)
            assertTrue("Native dialog must draw the actual supplied bitmap", pixels.count { it == green } > 500)
            capture.recycle()
            assertEquals(green, picture.getPixel(24, 32))
        }
        compose.onNodeWithTag("pdf-thumbnails-close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("pdf-thumbnails-grid").assertDoesNotExist()
        assertEquals(0, selected)
        assertFalse("UI never recycles source-owned images", picture.isRecycled)
        assertEquals(green, picture.getPixel(24, 32))
    }

    @Test fun `maximum integer page count preserves the last real index without header overflow`() {
        val requested = mutableListOf<Int>()
        var selected: Int? = null
        show(Int.MAX_VALUE, Int.MAX_VALUE - 1, render = { requested += it; picture },
            onGo = { selected = it; true })
        image(Int.MAX_VALUE - 1)
        compose.onNodeWithText(text(R.string.pdf_thumbnails_summary, Int.MAX_VALUE)).assertExists()
        compose.onNodeWithTag("pdf-thumbnail-${Int.MAX_VALUE - 1}").performClick()
        assertEquals(Int.MAX_VALUE - 1, selected)
        assertTrue(requested.contains(Int.MAX_VALUE - 1))
        assertTrue(requested.size < 30)
        assertTrue(requested.all { it in 0 until Int.MAX_VALUE })
    }

    @Test fun `live current page marker changes without resetting the same UUID scroll anchor`() {
        val currentPage = mutableStateOf<Int?>(2)
        val requested = mutableListOf<Int>()
        var selected = 0
        var dismissed = 0
        compose.setContent { YaprakTheme {
            PdfThumbnailsDialog("same-request", 1_000, currentPage.value, { requested += it; picture },
                { true }, { selected++; true }, { dismissed++ }, initialPage = 2)
        } }
        image(2)
        val marker = text(R.string.pdf_thumbnail_current)
        val noMarker = SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)
        val firstCard = compose.onNodeWithTag("pdf-thumbnail-2")
        val anchorTop = firstCard.fetchSemanticsNode().boundsInRoot.top
        firstCard.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, marker))
        assertFalse(requested.contains(0))

        compose.runOnIdle { currentPage.value = 3 }
        firstCard.assert(noMarker).assertIsDisplayed()
        compose.onNodeWithTag("pdf-thumbnail-3").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, marker))
        assertEquals("Updating the marker must retain the existing scroll anchor", anchorTop,
            firstCard.fetchSemanticsNode().boundsInRoot.top, 0.5f)

        compose.runOnIdle { currentPage.value = null }
        firstCard.assert(noMarker).assertIsDisplayed()
        compose.onNodeWithTag("pdf-thumbnail-3").assert(noMarker)
        compose.onNodeWithText(marker).assertDoesNotExist()
        assertEquals("A pending main page must not reset the preview scroll anchor", anchorTop,
            firstCard.fetchSemanticsNode().boundsInRoot.top, 0.5f)
        assertFalse("Marker changes must not jump to or render page zero", requested.contains(0))
        assertEquals(0, selected); assertEquals(0, dismissed)
    }

    @Test fun `opening page seeds scroll independently from the latest current page marker`() {
        val requested = mutableListOf<Int>()
        var selected = 0
        compose.setContent { YaprakTheme {
            PdfThumbnailsDialog("opening-page", 1_000, 3, { requested += it; picture }, { true },
                { selected++; true }, {}, initialPage = 80)
        } }
        image(80)
        compose.onNodeWithTag("pdf-thumbnail-80").assertIsDisplayed().assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithText(text(R.string.pdf_thumbnail_current)).assertDoesNotExist()
        assertTrue(requested.contains(80))
        assertFalse("Latest marker must not replace the immutable opening page", requested.contains(3))
        assertTrue(requested.size < 30)
        assertEquals(0, selected)
    }

    @Test fun `failed thumbnail can retry and rejected page navigation keeps the browser open`() {
        var reads = 0
        val selected = mutableListOf<Int>()
        var accepted = false
        var dismissed = 0
        show(1, 0, render = { reads++; if (reads == 1) throw IOException("decode") else picture },
            onGo = { selected += it; accepted }, onDismiss = { dismissed++ })
        scrollToPage(0)
        retry(0).performClick()
        image(0)
        assertEquals(2, reads)
        compose.onNodeWithTag("pdf-thumbnail-0").performClick()
        compose.onNodeWithText(text(R.string.pdf_thumbnails_navigation_error)).assertIsDisplayed()
        compose.onNodeWithTag("pdf-thumbnails-grid").assertExists()
        compose.onNodeWithTag("pdf-thumbnails-close").assertIsDisplayed()
        assertEquals(0, dismissed); assertEquals(listOf(0), selected)
        scrollToPage(0)
        compose.runOnIdle { accepted = true }
        compose.onNodeWithTag("pdf-thumbnail-0").performClick()
        assertEquals(listOf(0, 0), selected); assertEquals(1, dismissed)
    }

    @Test fun `old retry selection and close actions cannot retarget a replacement UUID`() {
        val request = mutableStateOf("old")
        var liveRequest = "old"
        val reads = mutableListOf<String>()
        val selected = mutableListOf<String>()
        val closed = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            val id = request.value
            PdfThumbnailsDialog(id, 1, 0, { reads += id; if (id == "old") throw IOException("old") else picture },
                { liveRequest == id }, { selected += id; true }, { closed += id })
        } }
        scrollToPage(0)
        val oldRetry = retry(0).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val oldGo = compose.onNodeWithTag("pdf-thumbnail-0").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val oldClose = compose.onNodeWithTag("pdf-thumbnails-close").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            liveRequest = "new"
            // Admission must use the fresh root gate even before another composition.
            oldRetry(); oldGo(); oldClose()
            request.value = "new"
        }
        scrollToPage(0); image(0)
        compose.runOnIdle { oldRetry(); oldGo(); oldClose() }
        assertEquals(listOf("old", "new"), reads)
        assertTrue(selected.isEmpty()); assertTrue(closed.isEmpty())
        compose.onNodeWithTag("pdf-thumbnail-0").performClick()
        assertEquals(listOf("new"), selected); assertEquals(listOf("new"), closed)
    }

    @Test fun `late image from a cancelled old request is not published over the new request`() {
        val request = mutableStateOf("old")
        var liveRequest = "old"
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldPicture = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val reads = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            val id = request.value
            PdfThumbnailsDialog(id, 1, 0, {
                reads += id
                if (id == "old") withContext(NonCancellable) {
                    started.complete(Unit); release.await(); oldPicture
                } else picture
            }, { liveRequest == id }, { true }, {})
        } }
        scrollToPage(0)
        compose.waitUntil(5_000) { started.isCompleted }
        compose.onNodeWithText(text(R.string.pdf_thumbnail_loading)).assertExists()
        compose.runOnIdle { liveRequest = "new"; request.value = "new" }
        scrollToPage(0); image(0)
        compose.runOnIdle { release.complete(Unit) }
        compose.waitForIdle()
        assertEquals(listOf("old", "new"), reads)
        compose.onNodeWithText(text(R.string.pdf_thumbnail_failed)).assertDoesNotExist()
        compose.runOnIdle {
            val capture = capture("pdf-thumbnails-late-request")
            val pixels = IntArray(capture.width * capture.height)
            capture.getPixels(pixels, 0, capture.width, 0, 0, capture.width, capture.height)
            assertEquals(0, pixels.count { it == Color.MAGENTA })
            assertTrue(pixels.count { it == green } > 500)
            capture.recycle()
        }
        assertFalse(oldPicture.isRecycled)
    }

    @Test fun `a page leaving the viewport cancels its pending thumbnail request`() {
        var started = false
        var cancelled = false
        show(10_000, 0, render = { index ->
            if (index == 0) {
                started = true
                try { awaitCancellation() } finally { cancelled = true }
            } else picture
        })
        scrollToPage(0)
        compose.waitUntil(5_000) { started }
        scrollToPage(9_999); image(9_999)
        compose.waitUntil(5_000) { cancelled }
        compose.onNodeWithTag("pdf-thumbnail-image-0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `expired request does not render select retry or dismiss`() {
        var reads = 0; var selected = 0; var dismissed = 0
        show(1, 0, render = { reads++; picture }, isCurrent = { false },
            onGo = { selected++; true }, onDismiss = { dismissed++ })
        scrollToPage(0)
        compose.onNodeWithTag("pdf-thumbnail-0").performClick()
        compose.onNodeWithTag("pdf-thumbnails-close").performClick()
        assertEquals(0, reads); assertEquals(0, selected); assertEquals(0, dismissed)
        compose.onNodeWithTag("pdf-thumbnail-image-0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `narrow double type preserves page labels actual preview and fixed close`() {
        largeFont()
        var closed = 0
        show(10_000, 9_999, onDismiss = { closed++ })
        image(9_999)
        readable(text(R.string.pdf_thumbnails_title))
        readable(text(R.string.pdf_thumbnails_close))
        readable(text(R.string.pdf_thumbnail_page, 10_000))
        readable(text(R.string.pdf_thumbnail_current))
        compose.onNodeWithTag("pdf-thumbnails-close").assertIsDisplayed()
        assertFooterOutsideGrid()
        compose.runOnIdle { capture("pdf-thumbnails-narrow-double-type").recycle() }
        compose.onNodeWithTag("pdf-thumbnails-close").performClick()
        assertEquals(1, closed)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short landscape double type keeps retry page selection and fixed close reachable`() {
        largeFont()
        var reads = 0
        var selected: Int? = null
        var closed = 0
        show(1, 0, render = { reads++; if (reads == 1) throw IOException("retry") else picture },
            onGo = { selected = it; true }, onDismiss = { closed++ })
        compose.onNodeWithTag("pdf-thumbnails-close").assertIsDisplayed()
        scrollToPage(0)
        val retry = retry(0)
        readable(text(R.string.pdf_thumbnail_retry, 1))
        retry.assertIsDisplayed()
        compose.runOnIdle { capture("pdf-thumbnails-landscape-retry-double-type").recycle() }
        retry.assertIsDisplayed().performClick()
        image(0)
        readable(text(R.string.pdf_thumbnails_title))
        readable(text(R.string.pdf_thumbnails_close))
        readable(text(R.string.pdf_thumbnail_page, 1))
        compose.onNodeWithTag("pdf-thumbnails-close").assertIsDisplayed()
        assertFooterOutsideGrid()
        compose.runOnIdle { capture("pdf-thumbnails-landscape-double-type").recycle() }
        compose.onNodeWithTag("pdf-thumbnail-0").performClick()
        assertEquals(0, selected); assertEquals(1, closed)
    }

    private fun show(pageCount: Int, currentPage: Int?, render: suspend (Int) -> Bitmap = { picture },
        isCurrent: () -> Boolean = { true }, onGo: (Int) -> Boolean = { true }, onDismiss: () -> Unit = {}) {
        activePageCount = pageCount
        compose.setContent { YaprakTheme {
            PdfThumbnailsDialog("test", pageCount, currentPage, render, isCurrent, onGo, onDismiss)
        } }
    }

    private fun scrollToPage(page: Int) {
        // ScrollToNode searches intermediate viewports. A direct page jump should
        // exercise lazy demand at the destination without browsing those pages.
        val offset = if (activePageCount < Int.MAX_VALUE) 1 else 0
        compose.onNodeWithTag("pdf-thumbnails-grid").performScrollToIndex(page + offset)
    }
    private fun image(page: Int) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("pdf-thumbnail-image-$page", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun retry(page: Int): SemanticsNodeInteraction {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("pdf-thumbnail-retry-$page").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pdf-thumbnails-grid").performScrollToNode(hasTestTag("pdf-thumbnail-retry-$page"))
        // A tile can be taller than the viewport at large font scales. Locating
        // the item is insufficient: scroll the actual child action into view.
        return compose.onNodeWithTag("pdf-thumbnail-retry-$page").performScrollTo()
    }
    private fun text(id: Int, vararg args: Any) = RuntimeEnvironment.getApplication().getString(id, *args)
    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
    }
    private fun readable(label: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertFalse("Text overflow: $label (${layout.size}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight})",
            layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun assertFooterOutsideGrid() {
        val grid = compose.onNodeWithTag("pdf-thumbnails-grid").fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithTag("pdf-thumbnails-close").fetchSemanticsNode().boundsInRoot
        assertTrue("Close must stay outside the grid scroll area", grid.bottom <= close.top)
    }
    private fun capture(name: String): Bitmap {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
}

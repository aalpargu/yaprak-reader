package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.R
import com.yaprak.reader.feature.reader.PdfCropDialog
import com.yaprak.reader.feature.reader.PdfBookViewPreferencesState
import com.yaprak.reader.feature.reader.PdfBookViewStatus
import com.yaprak.reader.domain.settings.BookPdfViewPreferences
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
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
class PdfCropDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val centerColor = Color.rgb(23, 198, 61)
    private val edgeColors = listOf(Color.rgb(213, 17, 43), Color.rgb(11, 52, 197),
        Color.rgb(242, 204, 13), Color.rgb(88, 15, 119))
    private val picture by lazy { stripedPage() }

    @Test fun `integer sliders preserve the other axis and apply only after an explicit action`() {
        val applied = mutableListOf<PdfPageCrop>()
        var closed = 0
        show(PdfPageCrop(4, 7), onApply = { applied += it; true }, onDismiss = { closed++ })
        val horizontal = compose.onNodeWithTag("pdf-crop-horizontal")
        assertEquals(19, horizontal.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
        change("pdf-crop-horizontal", 10.4f)
        percent("pdf-crop-horizontal", 10)
        percent("pdf-crop-vertical", 7)
        change("pdf-crop-vertical", 20f)
        percent("pdf-crop-horizontal", 10)
        percent("pdf-crop-vertical", 20)
        assertTrue(applied.isEmpty()); assertEquals(0, closed)
        compose.onNodeWithText(text(R.string.pdf_crop_preview_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        assertEquals(listOf(PdfPageCrop(10, 20)), applied); assertEquals(1, closed)
    }

    @Test fun `reset remains a draft and cancel reopening restores the saved crop`() {
        val open = mutableStateOf(true)
        val request = mutableStateOf("first")
        val saved = PdfPageCrop(12, 5)
        val applied = mutableListOf<PdfPageCrop>()
        var closed = 0
        compose.setContent { YaprakTheme {
            if (open.value) PdfCropDialog(request.value, saved, null, null, { open.value },
                { applied += it; true }, { closed++; open.value = false })
        } }
        change("pdf-crop-horizontal", 20f)
        compose.onNodeWithTag("pdf-crop-reset").performScrollTo().performClick()
        percent("pdf-crop-horizontal", 0); percent("pdf-crop-vertical", 0)
        assertTrue(applied.isEmpty()); assertEquals(PdfPageCrop(12, 5), saved)
        compose.onNodeWithTag("pdf-crop-cancel").performClick()
        compose.onNodeWithTag("pdf-crop-body").assertDoesNotExist()
        assertEquals(1, closed); assertTrue(applied.isEmpty())
        compose.runOnIdle { request.value = "second"; open.value = true }
        percent("pdf-crop-horizontal", 12); percent("pdf-crop-vertical", 5)
        assertTrue(applied.isEmpty())
    }

    @Test fun `failed apply retains both axes and can retry without losing the draft`() {
        val applied = mutableListOf<PdfPageCrop>()
        var accepted = false
        var closed = 0
        show(PdfPageCrop(2, 3), onApply = { applied += it; if (!accepted) throw IOException("setter") else true },
            onDismiss = { closed++ })
        change("pdf-crop-horizontal", 15f); change("pdf-crop-vertical", 8f)
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        compose.onNodeWithText(text(R.string.pdf_crop_apply_error)).performScrollTo().assertIsDisplayed()
        percent("pdf-crop-horizontal", 15); percent("pdf-crop-vertical", 8)
        assertEquals(0, closed); assertEquals(listOf(PdfPageCrop(15, 8)), applied)
        compose.runOnIdle { accepted = true }
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        assertEquals(listOf(PdfPageCrop(15, 8), PdfPageCrop(15, 8)), applied)
        assertEquals(1, closed)
    }

    @Test fun `draft crop removes real preview edge pixels without rerendering or modifying its source`() {
        var reads = 0
        val applied = mutableListOf<PdfPageCrop>()
        show(PdfPageCrop(), 0, { reads++; picture }, onApply = { applied += it; true })
        preview()
        compose.onNodeWithTag("pdf-crop-preview").performScrollTo().assertIsDisplayed()
        val original = captureCounts("pdf-crop-original-preview")
        edgeColors.forEach { assertTrue("Original preview must draw edge $it", (original[it] ?: 0) > 100) }
        change("pdf-crop-horizontal", 20f); change("pdf-crop-vertical", 10f)
        compose.onNodeWithTag("pdf-crop-preview").performScrollTo().assertIsDisplayed()
        val cropped = captureCounts("pdf-crop-trimmed-preview")
        edgeColors.forEach { assertEquals("Cropped edge $it is no longer visible", 0, cropped[it] ?: 0) }
        assertTrue((cropped[centerColor] ?: 0) > 500)
        assertEquals(1, reads); assertTrue(applied.isEmpty())
        assertFalse(picture.isRecycled)
        assertEquals(edgeColors[0], picture.getPixel(5, 50))
        assertEquals(edgeColors[1], picture.getPixel(95, 50))
        compose.onNodeWithTag("pdf-crop-reset").performScrollTo().performClick()
        compose.onNodeWithTag("pdf-crop-preview").performScrollTo()
        val reset = captureCounts("pdf-crop-reset-preview")
        edgeColors.forEach { assertTrue((reset[it] ?: 0) > 100) }
        assertEquals(1, reads); assertTrue(applied.isEmpty())
    }

    @Test fun `preview error retries independently and changing draft does not repeat native rendering`() {
        var reads = 0
        val applied = mutableListOf<PdfPageCrop>()
        show(PdfPageCrop(3, 4), 6, { reads++; if (reads == 1) throw IOException("preview") else picture },
            onApply = { applied += it; true })
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("pdf-crop-retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.pdf_crop_failed)).performScrollTo().assertIsDisplayed()
        change("pdf-crop-horizontal", 9f)
        assertEquals(1, reads); assertTrue(applied.isEmpty())
        compose.onNodeWithTag("pdf-crop-retry").performScrollTo().performClick()
        preview()
        compose.onNodeWithText(text(R.string.pdf_crop_preview_page, 7)).performScrollTo().assertIsDisplayed()
        assertEquals(2, reads)
        percent("pdf-crop-horizontal", 9); percent("pdf-crop-vertical", 4)
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        assertEquals(listOf(PdfPageCrop(9, 4)), applied)
    }

    @Test fun `missing stable page or renderer honestly omits the preview and still permits draft application`() {
        val request = mutableStateOf("missing-page")
        val page = mutableStateOf<Int?>(null)
        val renderer = mutableStateOf<(suspend () -> Bitmap)?>({ error("Missing capability must not draw") })
        val applied = mutableListOf<PdfPageCrop>()
        compose.setContent { YaprakTheme {
            PdfCropDialog(request.value, PdfPageCrop(5, 6), page.value, renderer.value, { true },
                { applied += it; true }, {})
        } }
        compose.onNodeWithText(text(R.string.pdf_crop_preview_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pdf-crop-preview", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { request.value = "invalid-page"; page.value = -1 }
        compose.onNodeWithText(text(R.string.pdf_crop_preview_unavailable)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { request.value = "missing-renderer"; page.value = 0; renderer.value = null }
        compose.onNodeWithText(text(R.string.pdf_crop_preview_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        assertEquals(listOf(PdfPageCrop(5, 6)), applied)
    }

    @Test fun `old apply cannot submit a newer draft even after returning to the same percentages`() {
        val applied = mutableListOf<PdfPageCrop>()
        show(PdfPageCrop(1, 2), onApply = { applied += it; true })
        val oldApply = action("pdf-crop-apply")
        change("pdf-crop-horizontal", 20f)
        compose.runOnIdle { oldApply() }
        assertTrue(applied.isEmpty())
        change("pdf-crop-horizontal", 1f)
        compose.runOnIdle { oldApply() }
        assertTrue(applied.isEmpty())
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        assertEquals(listOf(PdfPageCrop(1, 2)), applied)
    }

    @Test fun `old reset apply cancel retry and slider callbacks cannot retarget a replacement UUID`() {
        val request = mutableStateOf("old")
        var live = "old"
        val applied = mutableListOf<Pair<String, PdfPageCrop>>()
        val closed = mutableListOf<String>()
        val reads = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            val id = request.value
            val saved = if (id == "old") PdfPageCrop(1, 2) else PdfPageCrop(3, 8)
            PdfCropDialog(id, saved, 0, { reads += id; if (id == "old") throw IOException("old") else picture },
                { live == id }, { applied += id to it; true }, { closed += id })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("pdf-crop-retry").fetchSemanticsNodes().isNotEmpty() }
        val oldRetry = scrollAction("pdf-crop-retry")
        val oldReset = scrollAction("pdf-crop-reset")
        val oldApply = action("pdf-crop-apply")
        val oldCancel = action("pdf-crop-cancel")
        val oldSlider = compose.onNodeWithTag("pdf-crop-horizontal").performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        compose.runOnIdle {
            live = "new"
            oldRetry(); oldReset(); oldApply(); oldCancel(); oldSlider(20f)
            request.value = "new"
        }
        preview()
        compose.runOnIdle { oldRetry(); oldReset(); oldApply(); oldCancel(); oldSlider(20f) }
        percent("pdf-crop-horizontal", 3); percent("pdf-crop-vertical", 8)
        assertEquals(listOf("old", "new"), reads)
        assertTrue(applied.isEmpty()); assertTrue(closed.isEmpty())
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        assertEquals(listOf("new" to PdfPageCrop(3, 8)), applied); assertEquals(listOf("new"), closed)
    }

    @Test fun `late cancelled preview is ignored and neither old nor delivered image is recycled`() {
        val request = mutableStateOf("old")
        var live = "old"
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldPicture = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        compose.setContent { YaprakTheme {
            val id = request.value
            PdfCropDialog(id, PdfPageCrop(), 0, {
                if (id == "old") withContext(NonCancellable) {
                    started.complete(Unit); release.await(); oldPicture
                } else picture
            }, { live == id }, { true }, {})
        } }
        compose.waitUntil(5_000) { started.isCompleted }
        compose.runOnIdle { live = "new"; request.value = "new" }
        preview()
        compose.runOnIdle { release.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag("pdf-crop-preview").performScrollTo()
        val pixels = captureCounts("pdf-crop-late-preview")
        assertEquals(0, pixels[Color.MAGENTA] ?: 0)
        assertTrue((pixels[centerColor] ?: 0) > 500)
        compose.onNodeWithText(text(R.string.pdf_crop_failed)).assertDoesNotExist()
        assertFalse(oldPicture.isRecycled); assertFalse(picture.isRecycled)
    }

    @Test fun `cancelled apply coroutine leaves dialog open without claiming a failed apply`() {
        var closed = 0
        show(PdfPageCrop(4, 5), onApply = { throw CancellationException("owner cancelled") }, onDismiss = { closed++ })
        val apply = action("pdf-crop-apply")
        compose.runOnIdle { apply() }
        compose.waitForIdle()
        assertEquals(0, closed)
        compose.onNodeWithText(text(R.string.pdf_crop_apply_error)).assertDoesNotExist()
        percent("pdf-crop-horizontal", 4); percent("pdf-crop-vertical", 5)
    }

    @Test fun `suspended durable apply keeps dialog open disables draft edits and closes only after success`() {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val applied = mutableListOf<PdfPageCrop>(); var closed = 0
        show(PdfPageCrop(4, 5), onApply = {
            started.complete(Unit); release.await(); applied += it; true
        }, onDismiss = { closed++ })
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        compose.waitUntil(5_000) { started.isCompleted }
        compose.onNodeWithTag("pdf-crop-apply").assertIsNotEnabled()
        compose.onNodeWithTag("pdf-crop-reset").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("pdf-crop-horizontal").performScrollTo().assertIsNotEnabled()
        assertEquals(0, closed); assertTrue(applied.isEmpty())
        compose.runOnIdle { release.complete(Unit) }; compose.waitForIdle()
        assertEquals(listOf(PdfPageCrop(4, 5)), applied); assertEquals(1, closed)
    }

    @Test fun `suspended failed apply retains draft for explicit retry instead of closing optimistically`() {
        val release = CompletableDeferred<Unit>(); var accepted = false; var closed = 0
        show(PdfPageCrop(2, 3), onApply = { release.await(); accepted }, onDismiss = { closed++ })
        change("pdf-crop-horizontal", 12f); change("pdf-crop-vertical", 7f)
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        compose.runOnIdle { release.complete(Unit) }; compose.waitForIdle()
        assertEquals(0, closed)
        percent("pdf-crop-horizontal", 12); percent("pdf-crop-vertical", 7)
        compose.onNodeWithText(text(R.string.pdf_crop_apply_error)).performScrollTo().assertIsDisplayed()
        accepted = true
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        assertEquals(1, closed)
    }

    @Test fun `late noncancellable apply cannot close a replacement crop dialog`() {
        val request = mutableStateOf("old"); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val closed = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            val id = request.value
            PdfCropDialog(id, PdfPageCrop(4, 5), null, null, { request.value == id }, {
                if (id == "old") withContext(NonCancellable) { started.complete(Unit); release.await() }
                true
            }, { closed += id })
        } }
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        compose.waitUntil(5_000) { started.isCompleted }
        compose.runOnIdle { request.value = "new" }; compose.waitForIdle()
        compose.runOnIdle { release.complete(Unit) }; compose.waitForIdle()
        assertTrue(closed.isEmpty()); compose.onNodeWithTag("pdf-crop-apply").assertIsEnabled()
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        assertEquals(listOf("new"), closed)
    }

    @Test fun `old slider and reset cannot change draft after native application failed and reapply is required`() {
        val preferences = mutableStateOf(PdfBookViewPreferencesState(preferences = BookPdfViewPreferences(4, 5),
            status = PdfBookViewStatus.READY))
        compose.setContent { YaprakTheme {
            PdfCropDialog("same", PdfPageCrop(4, 5), null, null, { true }, { false }, {}, preferences.value)
        } }
        val slider = compose.onNodeWithTag("pdf-crop-horizontal").fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        val reset = scrollAction("pdf-crop-reset")
        compose.runOnIdle { preferences.value = preferences.value.copy(status = PdfBookViewStatus.APPLY_FAILED) }
        compose.waitForIdle()
        compose.runOnIdle { slider(20f); reset() }
        percent("pdf-crop-horizontal", 4); percent("pdf-crop-vertical", 5)
        compose.onNodeWithTag("pdf-crop-reset").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("pdf-crop-horizontal").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("pdf-crop-apply").assertIsEnabled()
    }

    @Test fun `expired gate admits no preview draft edit apply or cancel`() {
        var reads = 0; var applications = 0; var closed = 0
        show(PdfPageCrop(4, 5), 0, { reads++; picture }, { false },
            { applications++; true }, { closed++ })
        compose.onNodeWithTag("pdf-crop-apply").assertIsNotEnabled()
        compose.onNodeWithTag("pdf-crop-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("pdf-crop-reset").performScrollTo().assertIsNotEnabled()
        percent("pdf-crop-horizontal", 4); percent("pdf-crop-vertical", 5)
        assertEquals(0, reads); assertEquals(0, applications); assertEquals(0, closed)
        compose.onNodeWithTag("pdf-crop-preview", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `narrow double type keeps all controls readable and actions outside the scrolling body`() {
        largeFont()
        show(PdfPageCrop(12, 6), 2, { picture })
        preview()
        assertReadableBody(listOf(R.string.pdf_crop_title, R.string.pdf_crop_help, R.string.pdf_crop_horizontal,
            R.string.pdf_crop_vertical, R.string.pdf_crop_reset))
        readable(text(R.string.pdf_crop_preview_page, 3), scroll = true)
        compose.onNodeWithTag("pdf-crop-preview").performScrollTo().assertIsDisplayed()
        assertFixedActions()
        compose.runOnIdle { capture("pdf-crop-narrow-double-type").recycle() }
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short landscape double type keeps both sliders reset error and fixed actions reachable`() {
        largeFont()
        val applied = mutableListOf<PdfPageCrop>()
        show(PdfPageCrop(2, 3), onApply = { applied += it; false })
        assertReadableBody(listOf(R.string.pdf_crop_title, R.string.pdf_crop_help, R.string.pdf_crop_horizontal,
            R.string.pdf_crop_vertical, R.string.pdf_crop_preview_unavailable, R.string.pdf_crop_reset))
        change("pdf-crop-horizontal", 20f); change("pdf-crop-vertical", 15f)
        assertFixedActions()
        compose.onNodeWithTag("pdf-crop-apply").performClick()
        readable(text(R.string.pdf_crop_apply_error), scroll = true)
        assertFixedActions()
        compose.runOnIdle { capture("pdf-crop-landscape-double-type").recycle() }
        assertEquals(listOf(PdfPageCrop(20, 15)), applied)
    }

    private fun show(saved: PdfPageCrop, previewPage: Int? = null, renderer: (suspend () -> Bitmap)? = null,
        isCurrent: () -> Boolean = { true }, onApply: suspend (PdfPageCrop) -> Boolean = { true }, onDismiss: () -> Unit = {}) {
        compose.setContent { YaprakTheme { PdfCropDialog("test", saved, previewPage, renderer, isCurrent, onApply, onDismiss) } }
    }
    private fun change(tag: String, amount: Float) {
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(amount) }
    }
    private fun percent(tag: String, value: Int) {
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            text(R.string.pdf_crop_percent, value)))
    }
    private fun action(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun scrollAction(tag: String): () -> Boolean { compose.onNodeWithTag(tag).performScrollTo(); return action(tag) }
    private fun preview() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("pdf-crop-preview", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun text(id: Int, vararg args: Any) = RuntimeEnvironment.getApplication().getString(id, *args)
    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
    }
    private fun assertReadableBody(ids: List<Int>) {
        ids.forEach { readable(text(it), scroll = true); assertFixedActions() }
    }
    private fun readable(label: String, scroll: Boolean = false) {
        if (scroll) compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("Text overflow: $label (${layout.size})", layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun assertFixedActions() {
        listOf("pdf-crop-apply", "pdf-crop-cancel").forEach { compose.onNodeWithTag(it).assertIsDisplayed().assertIsEnabled() }
        readable(text(R.string.pdf_crop_apply)); readable(text(R.string.pdf_crop_cancel))
        val body = compose.onNodeWithTag("pdf-crop-body").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("pdf-crop-actions").fetchSemanticsNode().boundsInRoot
        assertTrue("Actions must stay outside the scrolling body", body.bottom <= actions.top)
    }
    private fun stripedPage() = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
        eraseColor(centerColor)
        val canvas = Canvas(this)
        val paint = Paint()
        paint.color = edgeColors[0]; canvas.drawRect(0f, 0f, 20f, 100f, paint)
        paint.color = edgeColors[1]; canvas.drawRect(80f, 0f, 100f, 100f, paint)
        paint.color = edgeColors[2]; canvas.drawRect(0f, 0f, 100f, 10f, paint)
        paint.color = edgeColors[3]; canvas.drawRect(0f, 90f, 100f, 100f, paint)
    }
    private fun captureCounts(name: String): Map<Int, Int> = compose.runOnIdle {
        val capture = capture(name)
        try {
            val pixels = IntArray(capture.width * capture.height)
            capture.getPixels(pixels, 0, capture.width, 0, 0, capture.width, capture.height)
            pixels.asSequence().groupingBy { it }.eachCount()
        } finally { capture.recycle() }
    }
    private fun capture(name: String): Bitmap {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

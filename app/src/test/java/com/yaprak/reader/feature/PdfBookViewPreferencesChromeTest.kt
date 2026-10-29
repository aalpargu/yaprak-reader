package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.settings.BookPdfViewPreferences
import com.yaprak.reader.domain.settings.BookPdfViewPreferencesFailure
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.feature.reader.PdfBookViewOwner
import com.yaprak.reader.feature.reader.PdfBookViewPreferencesState
import com.yaprak.reader.feature.reader.PdfBookViewStatus
import com.yaprak.reader.feature.reader.ReaderChrome
import com.yaprak.reader.feature.reader.ReaderState
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Compose text/layout and retained Material callbacks; no PDF renderer or Room substitute. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfBookViewPreferencesChromeTest {
    @After fun resetFontScale() { RuntimeEnvironment.setFontScale(1f) }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val events = mutableListOf<String>()
    private val value = BookPdfViewPreferences(7, 14, PdfFitPreference.WIDTH, true)
    private val hash = "a".repeat(64)
    private fun accepted(id: String = "pdf-book", generation: Long = 1) = PdfBookViewPreferencesState(
        PdfBookViewOwner(id, hash, generation), value, value, PdfBookViewStatus.READY, revision = 4)
    private val preferenceActions get() = listOf(text(R.string.pdf_crop_title), "Sayfaya sığdır",
        "Genişliğe sığdır", text(R.string.pdf_spread_single))
    private val footer = "Araçlar gizliyken tam ekran okuma · Sistem çubukları için ekran kenarından kaydır"

    @Test fun scrollChoiceNarrowLargeTypeKeepsTheWholeActionAndInvalidatesOldModeAndSpreadCallbacks() {
        verifyScrolling("pdf-scroll-narrow-double-type")
    }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun scrollChoiceShortLandscapeLargeTypeKeepsToolsAndSessionScopeReadable() {
        verifyScrolling("pdf-scroll-landscape-double-type")
    }
    private fun verifyScrolling(screenshot: String) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        val reader = ready(); val preferences = mutableStateOf(accepted()); val scrolling = mutableStateOf(false)
        compose.setContent { YaprakTheme {
            PreferenceChrome(reader, preferences.value, scrolling.value) {
                events += "scroll"; scrolling.value = !scrolling.value
            }
        } }
        val oldMode = materialAction(text(R.string.pdf_scroll_enable))
        val oldSpread = materialAction(text(R.string.pdf_spread_single))
        readable(text(R.string.pdf_scroll_enable))
        compose.readerToolNodeWithText(text(R.string.pdf_scroll_enable)).performClick()
        readable(text(R.string.pdf_scroll_disable)); capture(screenshot)
        compose.readerToolNodeWithText(text(R.string.pdf_spread_single)).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { oldMode(); oldSpread() }; assertEquals(listOf("scroll"), events)
        readable(text(R.string.pdf_scroll_help)); readable(footer)
        compose.readerToolNodeWithText("Kitaplık").assertIsDisplayed().assertIsEnabled()
        val oldDisable = materialAction(text(R.string.pdf_scroll_disable))
        compose.runOnIdle { preferences.value = accepted().copy(status = PdfBookViewStatus.SAVING) }
        compose.readerToolNodeWithText(text(R.string.pdf_scroll_disable)).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { oldDisable() }; assertEquals(listOf("scroll"), events)
        compose.runOnIdle { preferences.value = accepted() }
        compose.runOnIdle { oldDisable() }; assertEquals(listOf("scroll"), events)
        compose.readerToolNodeWithText(text(R.string.pdf_scroll_disable)).performScrollTo().performClick()
        assertEquals(listOf("scroll", "scroll"), events)
        compose.readerToolNodeWithText(text(R.string.pdf_spread_single)).performScrollTo().assertIsEnabled()
        compose.runOnIdle { oldMode(); oldSpread() }; assertEquals(listOf("scroll", "scroll"), events)
    }

    @Test fun `narrow double type preserves durable summary saving gates errors retries and independent PDF tools`() {
        verifyLayoutAndStatuses("pdf-prefs-narrow-double-type")
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short landscape double type preserves durable summary saving gates errors retries and independent PDF tools`() {
        verifyLayoutAndStatuses("pdf-prefs-landscape-double-type")
    }

    @Test fun `retained Material commands stay invalid after exact READY SAVING READY ABA and retry owner replacement`() {
        val reader = mutableStateOf<ReaderState>(ready())
        val preferences = mutableStateOf(accepted())
        compose.setContent { YaprakTheme { PreferenceChrome(reader.value, preferences.value) } }
        val retainedCommands = preferenceActions.map(::materialAction)
        val sameReady = preferences.value
        compose.runOnIdle { preferences.value = sameReady.copy(status = PdfBookViewStatus.SAVING) }
        preferenceActions.forEach { compose.readerToolNodeWithText(it).performScrollTo().assertIsNotEnabled() }
        compose.runOnIdle { retainedCommands.forEach { it() } }
        assertTrue(events.isEmpty())
        // Keep the exact value, revision, owner and session: disposal must still invalidate the old lease.
        compose.runOnIdle { preferences.value = sameReady }
        compose.runOnIdle { retainedCommands.forEach { it() } }
        assertTrue(events.isEmpty())
        compose.readerToolNodeWithText("Sayfaya sığdır").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("fitPage:pdf-book"), events)
        events.clear()

        compose.runOnIdle {
            preferences.value = sameReady.copy(status = PdfBookViewStatus.LOAD_FAILED,
                failure = BookPdfViewPreferencesFailure.SOURCE, preferences = null)
        }
        val oldRetry = materialAction(text(R.string.pdf_book_view_retry))
        compose.runOnIdle {
            reader.value = ready("other-book")
            preferences.value = accepted("other-book", 8).copy(status = PdfBookViewStatus.LOAD_FAILED,
                preferences = null, applied = null, failure = BookPdfViewPreferencesFailure.SOURCE)
        }
        compose.readerToolNodeWithText(text(R.string.pdf_book_view_retry)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { oldRetry() }
        assertTrue(events.isEmpty())
        compose.readerToolNodeWithText(text(R.string.pdf_book_view_retry)).performClick()
        assertEquals(listOf("retry:other-book"), events)
    }

    private fun verifyLayoutAndStatuses(screenshot: String) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        val reader = ready()
        val preferences = mutableStateOf(accepted())
        compose.setContent { YaprakTheme { PreferenceChrome(reader, preferences.value) } }
        preferenceActions.forEach { label ->
            readable(label)
            compose.readerToolNodeWithText(label).assertIsEnabled().performClick()
        }
        assertEquals(listOf("crop:pdf-book", "fitPage:pdf-book", "fitWidth:pdf-book", "spread:pdf-book"), events)
        val summary = text(R.string.pdf_book_view_saved, 7, 14, text(R.string.pdf_book_view_fit_width))
        readable(summary)
        compose.readerToolNodeWithText("Kitaplık").assertIsDisplayed()
        capture(screenshot)
        readable(text(R.string.pdf_spread_help))
        listOf("Sayfaya git", text(R.string.pdf_thumbnails_title), "Önceki", "Sonraki", footer).forEach(::readable)
        // Scrolling the lower Surface must not move the upper library/back control out of reach.
        compose.readerToolNodeWithText("Kitaplık").assertIsDisplayed().assertIsEnabled()

        compose.runOnIdle { preferences.value = accepted().copy(status = PdfBookViewStatus.SAVING) }
        readable(text(R.string.pdf_book_view_saving))
        preferenceActions.forEach { compose.readerToolNodeWithText(it).performScrollTo().assertIsNotEnabled() }
        compose.readerToolNodeWithText(summary).assertDoesNotExist()
        compose.readerToolNodeWithText(text(R.string.pdf_book_view_retry)).assertDoesNotExist()
        compose.readerToolNodeWithText("Sonraki").performScrollTo().assertIsEnabled()

        for ((status, message) in listOf(PdfBookViewStatus.SAVE_FAILED to R.string.pdf_book_view_save_error,
            PdfBookViewStatus.LOAD_FAILED to R.string.pdf_book_view_load_error,
            PdfBookViewStatus.APPLY_FAILED to R.string.pdf_book_view_apply_error)) {
            compose.runOnIdle { preferences.value = accepted().copy(status = status,
                preferences = if (status == PdfBookViewStatus.LOAD_FAILED) null else value,
                failure = if (status == PdfBookViewStatus.APPLY_FAILED) null else BookPdfViewPreferencesFailure.SOURCE) }
            readable(text(message)); readable(text(R.string.pdf_book_view_retry))
            compose.readerToolNodeWithText(text(R.string.pdf_book_view_retry)).assertIsEnabled().performClick()
            compose.readerToolNodeWithText(summary).assertDoesNotExist()
            preferenceActions.forEach { label ->
                val action = compose.readerToolNodeWithText(label).performScrollTo()
                if (status == PdfBookViewStatus.SAVE_FAILED) action.assertIsEnabled() else action.assertIsNotEnabled()
            }
            readable(footer)
            compose.readerToolNodeWithText("Kitaplık").assertIsDisplayed()
        }
        assertEquals(3, events.count { it == "retry:pdf-book" })
    }

    @Composable private fun PreferenceChrome(reader: ReaderState, preferences: PdfBookViewPreferencesState,
        scrolling: Boolean = false, onScroll: (() -> Unit)? = null) {
        val owner = preferences.owner?.bookId.orEmpty()
        ReaderChrome(reader, true, false, false,
            onBack = { events += "back" }, onRetry = { events += "openRetry" },
            onRetrySave = { events += "positionRetry" }, onLeaveUnsaved = { events += "leave" },
            onHide = { events += "hide" }, onPrevious = { events += "previous" }, onNext = { events += "next" },
            pageLabel = "9999 / 10000", onBookmarks = {}, onPageJump = {}, onSavedWords = {}, onBookNotes = {},
            onScreenAwake = {}, onPdfNightMode = {}, onBrightness = {}, onOrientation = {}, onPdfThumbnails = {},
            onPdfFitPage = { events += "fitPage:$owner" }, onPdfFitWidth = { events += "fitWidth:$owner" },
            onPdfCrop = { events += "crop:$owner" }, pdfDoublePagePreferred = preferences.applied?.doublePagePreferred == true,
            onPdfSpread = { events += "spread:$owner" }, pdfBookViewState = preferences,
            onRetryPdfBookView = { events += "retry:$owner" }, pdfScrolling = scrolling, onPdfScroll = onScroll)
    }

    private fun ready(id: String = "pdf-book") = ReaderState.Ready(object : ReaderSession {
        override val book = Book(id, "Kalıcı PDF görünümü denemesi", "Yazar", BookFormat.PDF,
            "book.pdf", "book.pdf", 100, hash, 1, pageCount = 10000)
        override val resume: ReadingLocation? = null
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit) =
            error("Chrome must not create a PDF renderer")
        override fun close() = Unit
    })

    private fun materialAction(label: String): () -> Boolean = compose.readerToolNodeWithText(label).performScrollTo()
        .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!

    private fun readable(label: String) {
        compose.readerToolNodeWithText(label).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.readerToolNodeWithText(label, useUnmergedTree = true).assertFontScale(2f)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("Missing text layout: $label", layout.lineCount > 0 && layout.size.width > 0)
        assertFalse("Overflowing text: $label", layout.hasVisualOverflow)
        assertFalse("Ellipsized text: $label", (0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals("Incomplete text: $label", layout.layoutInput.text.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    private fun text(id: Int, vararg args: Any) = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.settings.BookComicViewPreferences
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicBookViewPreferencesChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
    private fun preferences(status: ComicBookViewStatus = ComicBookViewStatus.READY) = ComicBookViewPreferencesState(
        ComicBookViewOwner("comic", "a".repeat(64), 1), BookComicViewPreferences(true), BookComicViewPreferences(true), status, revision = 4)
    private fun ready() = ReaderState.Ready(object : ReaderSession {
        override val book = Book("comic", "Çizgi", null, BookFormat.CBZ, "comic.cbz", "comic.cbz", 1, "a".repeat(64), 1, 5)
        override val resume: ReadingLocation? = null
        override fun close() = Unit
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface = error("UI only")
    })
    @Composable private fun Chrome(reader: ReaderState, state: ComicBookViewPreferencesState, toggle: () -> Unit, retry: () -> Unit) {
        ReaderChrome(reader, true, false, false, {}, {}, {}, {}, {}, {}, {}, pageLabel = "1–2 / 5",
            comicDoublePagePreferred = true, onComicSpread = toggle, comicBookViewState = state, onRetryComicBookView = retry)
    }
    private fun readable(value: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.readerToolNodeWithText(value, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse(layout.didOverflowWidth); assertFalse(layout.didOverflowHeight)
        assertEquals(value.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun statusLayout(name: String) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var state by mutableStateOf(preferences()); val reader = ready(); var writes = 0; var retries = 0
        compose.setContent { YaprakTheme { Chrome(reader, state, { writes++ }, { retries++ }) } }
        readable(text(R.string.cbz_spread_single)); readable(text(R.string.cbz_spread_help))
        for ((status, message) in listOf(ComicBookViewStatus.LOADING to R.string.cbz_spread_loading,
            ComicBookViewStatus.SAVING to R.string.cbz_spread_saving, ComicBookViewStatus.APPLYING to R.string.cbz_spread_applying,
            ComicBookViewStatus.LOAD_FAILED to R.string.cbz_spread_load_failed, ComicBookViewStatus.SAVE_FAILED to R.string.cbz_spread_save_failed,
            ComicBookViewStatus.APPLY_FAILED to R.string.cbz_spread_apply_failed)) {
            compose.runOnIdle { state = preferences(status) }; readable(text(message))
            compose.readerToolNodeWithText(text(R.string.cbz_spread_single)).performScrollTo().apply {
                if (status == ComicBookViewStatus.SAVE_FAILED) assertIsEnabled() else assertIsNotEnabled()
            }
            if (state.failed) {
                readable(text(R.string.cbz_spread_retry)); compose.readerToolNodeWithText(text(R.string.cbz_spread_retry)).performScrollTo().assertIsDisplayed()
            }
        }
        compose.runOnIdle {
            val view = compose.activity.window.decorView; val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try { view.draw(Canvas(bitmap)); val file = File(System.getProperty("yaprak.screenshotDir"), "$name.png")
                file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        compose.readerToolNodeWithText(text(R.string.cbz_spread_retry)).performClick(); assertEquals(1, retries); assertEquals(0, writes)
    }
    @Test fun narrowDoubleTypeMakesBusyErrorsAndSavedBookScopeReadable() { statusLayout("comic-book-pref-narrow") }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun shortLandscapeDoubleTypeKeepsFailureRetryReachable() { statusLayout("comic-book-pref-landscape") }
    @Test fun retainedToggleAndRetryCallbacksAreRevokedAcrossBusyReadyAndOwnerReplacement() {
        var state by mutableStateOf(preferences()); val reader = ready(); var writes = 0; var retries = 0
        compose.setContent { YaprakTheme { Chrome(reader, state, { writes++ }, { retries++ }) } }
        val old = compose.readerToolNodeWithText(text(R.string.cbz_spread_single)).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val same = state; compose.runOnIdle { state = same.copy(status = ComicBookViewStatus.SAVING) }
        compose.runOnIdle { state = same }; compose.runOnIdle { old() }; assertEquals(0, writes)
        compose.readerToolNodeWithText(text(R.string.cbz_spread_single)).performScrollTo().performClick(); assertEquals(1, writes)
        compose.runOnIdle { state = state.copy(status = ComicBookViewStatus.APPLY_FAILED) }
        val retry = compose.readerToolNodeWithText(text(R.string.cbz_spread_retry)).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(owner = state.owner!!.copy(generation = 2), revision = 5) }
        compose.runOnIdle { retry() }; assertEquals(0, retries)
        compose.readerToolNodeWithText(text(R.string.cbz_spread_retry)).performScrollTo().performClick(); assertEquals(1, retries)
    }
}

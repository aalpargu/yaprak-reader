package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.ui.theme.YaprakTheme
import com.yaprak.reader.R
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
class IndexedChapterChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val session = object : ReaderSession {
        override val book = Book("book", "Indexed chapter", null, BookFormat.EPUB,
            "book.epub", "book.epub", 1, "a".repeat(64), 1)
        override val resume: ReadingLocation? = null
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit) =
            error("Chrome cannot create a rendering engine")
        override fun close() = Unit
    }

    @Test fun `all chapter failures remain visible with hidden chrome and retry preserves ordinary reading actions`() {
        val failure = mutableStateOf<IndexedChapterFailure?>(IndexedChapterFailure.SOURCE)
        var retry = 0; var reopen = 0; var next = 0
        compose.setContent { YaprakTheme {
            ReaderChrome(ReaderState.Ready(session), false, false, false, {}, { reopen++ }, {}, {}, {}, {}, { next++ },
                indexedChapterFailure = failure.value, onRetryIndexedChapter = { retry++ })
        } }
        for ((reason, resource) in listOf(IndexedChapterFailure.SOURCE to R.string.indexed_chapter_source_error,
            IndexedChapterFailure.UNSUPPORTED to R.string.indexed_chapter_unsupported,
            IndexedChapterFailure.NAVIGATION to R.string.indexed_chapter_navigation_error,
            IndexedChapterFailure.INTERRUPTED to R.string.indexed_chapter_interrupted)) {
            compose.runOnIdle { failure.value = reason }
            compose.onNodeWithText(compose.activity.getString(resource)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.indexed_chapter_retry)).performScrollTo().assertIsDisplayed().performClick()
        }
        compose.onNodeWithText("Bu bölüm yüklenemedi. Kitaplığa dönüp yeniden açmayı dene.").assertDoesNotExist()
        compose.onNodeWithText("Sonraki").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(4, retry); assertEquals(0, reopen); assertEquals(1, next)
    }

    @Test @Config(qualifiers = "w640dp-h240dp-land-mdpi")
    fun `short landscape source failure and retry remain reachable at double font scale`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        var retry = 0
        compose.setContent { YaprakTheme {
            ReaderChrome(ReaderState.Ready(session), false, false, false, {}, {}, {}, {}, {}, {}, {},
                indexedChapterFailure = IndexedChapterFailure.SOURCE, onRetryIndexedChapter = { retry++ })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.indexed_chapter_source_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.indexed_chapter_retry)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, retry)
        compose.onNodeWithText("Kitaplık").performScrollTo().assertIsDisplayed()
    }

    @Test fun `verification shows status without retry and settled hidden reader removes transient banner`() {
        val verifying = mutableStateOf(true)
        compose.setContent { YaprakTheme {
            ReaderChrome(ReaderState.Ready(session), false, false, false, {}, {}, {}, {}, {}, {}, {},
                indexedChapterVerifying = verifying.value)
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.indexed_chapter_verifying)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.indexed_chapter_retry)).assertDoesNotExist()
        compose.runOnIdle { verifying.value = false }
        compose.onNodeWithText(compose.activity.getString(R.string.indexed_chapter_verifying)).assertDoesNotExist()
        compose.onNodeWithText("Sonraki").assertDoesNotExist()
    }
}

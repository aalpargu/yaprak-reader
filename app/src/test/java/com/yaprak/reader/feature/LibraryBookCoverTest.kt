package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookCover
import com.yaprak.reader.feature.library.LibraryBookCover
import com.yaprak.reader.feature.library.LibraryLayout
import com.yaprak.reader.feature.library.LibraryScreen
import com.yaprak.reader.feature.library.LibraryState
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryBookCoverTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val initial = Book("cover", "Kapak Denemesi", "Deneme Yazarı", BookFormat.EPUB,
        "cover.epub", "cover.epub", 100, "abcd", 1)

    @Test fun `successful decorative image replaces jacket and fits without cropping`() {
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val loaded = AtomicBoolean()
        val loader: suspend (Book) -> Bitmap? = { loaded.set(true); bitmap }
        var layout by mutableStateOf<LibraryLayout?>(null)
        var coverBounds = Rect.Zero
        compose.setContent {
            YaprakTheme {
                val libraryLayout = layout
                if (libraryLayout == null) {
                    Box(Modifier.size(120.dp, 180.dp).onGloballyPositioned {
                        coverBounds = it.boundsInWindow()
                    }) {
                        LibraryBookCover(initial, Modifier.fillMaxSize(), coverLoader = loader)
                    }
                } else {
                    LibraryScreen(LibraryState.Ready(listOf(initial)), false,
                        SnackbarHostState(), {}, {}, {}, "", {}, layout = libraryLayout,
                        onLayout = { layout = it }, coverLoader = loader)
                }
            }
        }
        waitForCover { loaded.get() }
        compose.onNodeWithText(initial.title).assertDoesNotExist()
        compose.onNodeWithText("EPUB").assertDoesNotExist()
        val rendered = captureWindow()
        try {
            assertEquals(Color.MAGENTA,
                rendered.getPixel(coverBounds.center.x.toInt(), coverBounds.center.y.toInt()))
            assertNotEquals("A wide cover must keep its aspect ratio rather than fill the tall box",
                Color.MAGENTA, rendered.getPixel(coverBounds.center.x.toInt(),
                    (coverBounds.top + coverBounds.height / 10).toInt()))
        } finally {
            rendered.recycle()
        }

        compose.runOnIdle { layout = LibraryLayout.GRID }
        compose.onNodeWithText(requireNotNull(initial.authors)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("${initial.title}, ${initial.authors}, EPUB, Belirlenmedi").assertIsDisplayed()
        saveScreenshot("library-real-cover-grid")
        compose.runOnIdle { layout = LibraryLayout.LIST }
        compose.onNodeWithText(requireNotNull(initial.authors)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("${initial.title}, ${initial.authors}, EPUB, Belirlenmedi").assertIsDisplayed()
        saveScreenshot("library-real-cover-list")
    }

    @Test fun `missing or failed extraction leaves the typographic title and format`() {
        var book by mutableStateOf(initial)
        val calls = CopyOnWriteArrayList<Book>()
        val loader: suspend (Book) -> Bitmap? = {
            calls += it
            if (it.id == "failed") throw IllegalStateException("Expected extraction failure")
            null
        }
        compose.setContent {
            YaprakTheme {
                Box(Modifier.size(180.dp, 240.dp)) {
                    LibraryBookCover(book, Modifier.fillMaxSize(), coverLoader = loader)
                }
            }
        }
        waitForCover { calls.size == 1 }
        compose.onNodeWithText(initial.title).assertIsDisplayed()
        compose.onNodeWithText("EPUB").assertIsDisplayed()
        compose.runOnIdle { book = book.copy(id = "failed") }
        waitForCover { calls.size == 2 }
        compose.onNodeWithText(initial.title).assertIsDisplayed()
        compose.onNodeWithText("EPUB").assertIsDisplayed()
    }

    @Test fun `metadata edits keep existing request while content identity changes reload`() {
        var book by mutableStateOf(initial)
        val calls = CopyOnWriteArrayList<Book>()
        val loader: suspend (Book) -> Bitmap? = { calls += it; null }
        compose.setContent {
            YaprakTheme {
                Box(Modifier.size(180.dp, 240.dp)) {
                    LibraryBookCover(book, Modifier.fillMaxSize(), coverLoader = loader)
                }
            }
        }
        waitForCover { calls.size == 1 }
        compose.runOnIdle { book = book.copy(title = "Düzeltilmiş Başlık", authors = "Yeni Yazar") }
        compose.onNodeWithText("Düzeltilmiş Başlık").assertIsDisplayed()
        assertEquals(1, calls.size)

        compose.runOnIdle { book = book.copy(sha256 = "new-content") }
        waitForCover { calls.size == 2 }
        assertEquals("new-content", calls.last().sha256)
        compose.runOnIdle { book = book.copy(format = BookFormat.PDF) }
        waitForCover { calls.size == 3 }
        compose.onNodeWithText("PDF").assertIsDisplayed()
        compose.runOnIdle { book = book.copy(storageName = "replacement.pdf") }
        waitForCover { calls.size == 4 }
        assertEquals("replacement.pdf", calls.last().storageName)
        compose.runOnIdle { book = book.copy(id = "replacement") }
        waitForCover { calls.size == 5 }
        assertEquals("replacement", calls.last().id)
    }

    @Test fun `loading displays fallback and removing the cover cancels extraction`() {
        var shown by mutableStateOf(true)
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val loader: suspend (Book) -> Bitmap? = {
            started.set(true)
            try { awaitCancellation() }
            finally { cancelled.set(true) }
        }
        compose.setContent {
            YaprakTheme {
                Box(Modifier.size(180.dp, 240.dp)) {
                    if (shown) LibraryBookCover(initial, Modifier.fillMaxSize(), coverLoader = loader)
                }
            }
        }
        waitForCover { started.get() }
        compose.onNodeWithText(initial.title).assertIsDisplayed()
        compose.onNodeWithText("EPUB").assertIsDisplayed()
        compose.runOnIdle { shown = false }
        waitForCover { cancelled.get() }
        compose.onNodeWithText(initial.title).assertDoesNotExist()
    }

    @Test fun `custom cover revision reloads while metadata preserves the request and reset reloads original`() {
        var book by mutableStateOf(initial)
        var compact by mutableStateOf(false)
        val calls = CopyOnWriteArrayList<Book>()
        val loader: suspend (Book) -> Bitmap? = { calls += it; null }
        compose.setContent { YaprakTheme {
            Box(Modifier.size(180.dp, 240.dp)) {
                LibraryBookCover(book, Modifier.fillMaxSize(), compact = compact, coverLoader = loader)
            }
        } }
        waitForCover { calls.size == 1 }
        val first = BookCover("first", "first.png", "image/png", 10, "same-image-hash", 10, 20)
        compose.runOnIdle { book = book.copy(customCover = first) }
        waitForCover { calls.size == 2 }
        compose.runOnIdle { book = book.copy(title = "New metadata"); compact = true }
        compose.waitForIdle()
        assertEquals(2, calls.size)
        // Equal image bytes still belong to a distinct immutable revision.
        compose.runOnIdle { book = book.copy(customCover = first.copy(id = "second", storageName = "second.png")) }
        waitForCover { calls.size == 3 }
        assertEquals("second", calls.last().customCover?.id)
        compose.runOnIdle { book = book.copy(customCover = null) }
        waitForCover { calls.size == 4 }
        assertEquals(null, calls.last().customCover)
    }

    private fun waitForCover(condition: () -> Boolean) {
        // Pump Robolectric's initial view/composition work before polling effects.
        // These fakes need no background IO; waitUntil alone does not idle that work.
        compose.waitForIdle()
        compose.waitUntil(5_000, condition)
    }

    private fun captureWindow(): Bitmap = compose.runOnIdle {
        val view = compose.activity.window.decorView
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
    }

    private fun saveScreenshot(name: String) {
        val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
        val screenshot = captureWindow()
        try {
            File(directory, "$name.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            screenshot.recycle()
        }
    }
}

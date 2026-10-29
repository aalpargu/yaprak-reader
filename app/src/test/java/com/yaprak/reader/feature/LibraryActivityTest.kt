package com.yaprak.reader.feature

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.MainActivity
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Uses the actual Activity factory, SavedStateHandle, Room and lifecycle collectors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun `search survives activity recreation and continues observing imported metadata`() {
        val repository = (compose.activity.application as YaprakApplication).container.library
        val book = Book("first", "İstanbul", "Deniz", BookFormat.EPUB, "first.epub", "first.epub", 1, "first-hash", 1)
        runBlocking { repository.add(book); repository.add(book.copy(id = "second", title = "Quiet Morning", sha256 = "second-hash")) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Kitaplıkta ara").fetchSemanticsNodes().isNotEmpty() }
        // Bring the real interactive section above the floating add action before native taps.
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("librarySearchControls")
        compose.onNodeWithText("Kitaplıkta ara").performTextReplacement("istanbul")
        compose.onNodeWithText("Kitaplıkta ara").performImeAction()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 / 2 KİTAP").fetchSemanticsNodes().isNotEmpty() }
        val before = compose.activity
        compose.activityRule.scenario.recreate()
        assertNotSame(before, compose.activity)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("1 / 2 KİTAP").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("librarySearchControls")
        compose.onNode(hasSetTextAction()).assertTextContains("istanbul")
        runBlocking { repository.add(book.copy(id = "third", sha256 = "third-hash")) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2 / 3 KİTAP").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Temizle").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("3 KİTAP").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).assertTextContains("")
    }
}

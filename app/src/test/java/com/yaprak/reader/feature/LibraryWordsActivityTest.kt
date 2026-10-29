package com.yaprak.reader.feature

import android.content.ComponentName
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.librarywords.LibraryWordsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual AppContainer/Room/lifecycle/Activity composition; user content fixtures stay on this host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryWordsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<LibraryWordsActivity>()
    @Test fun `internal global vocabulary shows saved Room entries and recreation restores query with no practice answer or writes`() {
        val container = (compose.activity.application as YaprakApplication).container
        val book = Book("actual-word-book", "İstanbul", "Deniz", BookFormat.PDF, "book.pdf", "book.pdf", 1, "a".repeat(64), 1, 2)
        val id = runBlocking {
            container.library.add(book)
            container.savedWords.add(book.id, book.sha256, "tree", "ağaç")
            container.savedWords.observe(book.id, book.sha256).first().single().id
        }
        waitTag("library-words-search")
        compose.onNodeWithTag("library-words-search").performTextReplacement("istanbul tree")
        waitTag("word-start"); scroll("word-start").performClick()
        compose.onNodeWithTag("library-word-answer").assertDoesNotExist()
        scroll("word-reveal").performClick()
        scroll("library-word-answer").assertTextEquals("ağaç")
        val oldActivity = compose.activity
        compose.activityRule.scenario.recreate()
        assertNotSame(oldActivity, compose.activity)
        waitTag("word-start")
        compose.onNodeWithTag("library-words-search").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("istanbul tree")))
        compose.onNodeWithTag("library-word-answer").assertDoesNotExist()
        waitTag("word-open-$id")
        assertEquals(id, runBlocking { container.savedWords.observe(book.id, book.sha256).first().single().id })
        assertEquals("ağaç", runBlocking { container.savedWords.observe(book.id, book.sha256).first().single().turkish })
        val component = ComponentName(compose.activity, LibraryWordsActivity::class.java)
        assertFalse(compose.activity.packageManager.getActivityInfo(component, 0).exported)
        assertNull(LibraryWordsActivity.intent(compose.activity).extras)
    }
    private fun waitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun scroll(tag: String) = compose.onNodeWithTag("library-words-list").performScrollToNode(hasTestTag(tag))
        .let { compose.onNodeWithTag(tag) }
}

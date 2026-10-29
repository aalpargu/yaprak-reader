package com.yaprak.reader.feature

import android.content.ClipboardManager
import android.content.ComponentName
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.yaprak.reader.R
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.librarynotes.LibraryNotesActivity
import com.yaprak.reader.feature.librarynotes.LibraryNotesViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual composition, local database, saved query and Android clipboard; no simulated reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryNotesActivityTest {
    @get:Rule val compose = createAndroidComposeRule<LibraryNotesActivity>()

    @Test fun `internal activity observes saved Room note survives recreation and copies exact current text`() {
        val container = (compose.activity.application as YaprakApplication).container
        val book = Book("actual-notes-book", "İstanbul", "Deniz", BookFormat.PDF,
            "book.pdf", "book.pdf", 1, "a".repeat(64), 1, pageCount = 20)
        val payload = "Notun ilk satırı 😀\nİkinci satır ı e\u0301"
        val id = runBlocking {
            container.library.add(book)
            container.bookNotes.addAtPdfPage(book.id, book.sha256, payload, 7)
            container.bookNotes.observe(book.id, book.sha256).first().single().id
        }
        waitForSearch()
        compose.onNodeWithTag("library-notes-search").performTextReplacement("istanbul Deniz")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library-note-copy-$id").fetchSemanticsNodes().isNotEmpty() }
        val before = compose.activity
        compose.activityRule.scenario.recreate()
        assertNotSame(before, compose.activity)
        waitForSearch()
        compose.onNodeWithTag("library-notes-search").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("istanbul Deniz")))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library-note-copy-$id").fetchSemanticsNodes().isNotEmpty() }
        val copyTag = "library-note-copy-$id"
        compose.onNodeWithTag("library-notes-list").performScrollToNode(hasTestTag(copyTag))
        compose.onNodeWithTag(copyTag).performClick()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        try {
            // Semantics access pumps Robolectric's main looper after the actual Room IO read.
            // Polling only ClipboardManager can leave its Main-dispatch callback queued.
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(compose.activity.getString(R.string.library_notes_copied))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            val model = ViewModelProvider(compose.activity)[LibraryNotesViewModel::class.java]
            throw AssertionError("Copy state=${model.state.value}; lifecycle=${compose.activity.lifecycle.currentState}; " +
                "clipboard=${clipboard.primaryClip?.getItemAt(0)?.text}", failure)
        }
        val copied = compose.runOnIdle { clipboard.primaryClip!!.getItemAt(0).text.toString() }
        assertEquals(payload, copied)
        assertEquals(payload, runBlocking { container.bookNotes.observe(book.id, book.sha256).first().single().text })
    }

    @Test fun `recovery gate removes notes UI and retry reopens safe local screen`() {
        val container = (compose.activity.application as YaprakApplication).container
        waitForSearch()
        compose.runOnIdle { container.startup.requireRecovery() }
        compose.onNodeWithText(compose.activity.getString(R.string.library_notes_startup_failed)).assertIsDisplayed()
        compose.onNodeWithTag("library-notes-list").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.retry)).performClick()
        waitForSearch()
        compose.onNodeWithTag("library-notes-list").assertExists()
    }

    @Test fun `all notes intent is internal without externally supplied book or navigation scope`() {
        val intent = LibraryNotesActivity.intent(compose.activity)
        assertEquals(LibraryNotesActivity::class.java.name, intent.component!!.className)
        assertNull(intent.extras)
        val info = compose.activity.packageManager.getActivityInfo(
            ComponentName(compose.activity, LibraryNotesActivity::class.java), 0)
        assertFalse(info.exported)
    }

    private fun waitForSearch() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("library-notes-search").fetchSemanticsNodes().isNotEmpty()
    }
}

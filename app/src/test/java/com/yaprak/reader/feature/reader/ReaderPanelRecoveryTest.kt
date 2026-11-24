package com.yaprak.reader.feature.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.notes.BookNote
import com.yaprak.reader.domain.notes.BookNoteRepository
import com.yaprak.reader.feature.notes.BookNotesRoute
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPanelRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedReaderDisablesWritesButUserCanKeepTypingCancelAndClose() {
        val stored = BookNote("one", "Stored note", 1)
        val records = MutableStateFlow(listOf(stored))
        var writes = 0
        val repository = object : BookNoteRepository {
            override fun observe(bookId: String, contentSha256: String) = records
            override suspend fun add(bookId: String, contentSha256: String, text: String) { writes++ }
            override suspend fun update(bookId: String, contentSha256: String, id: String, text: String) { writes++ }
            override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
        }
        val sourceReady = mutableStateOf(true)
        val visible = mutableStateOf(true)
        var dismissed = false
        var snapshot: android.os.Bundle? = null
        val book = Book("book", "Reader recovery", null, BookFormat.PDF, "book.pdf", "book.pdf", 1,
            "a".repeat(64), 1, pageCount = 1)
        compose.setContent {
            YaprakTheme {
                if (visible.value) BookNotesRoute(book, repository, onDismiss = {
                    dismissed = true; visible.value = false
                }, canMutate = { sourceReady.value }, canInteract = { true },
                    onRestorationChange = { snapshot = it })
            }
        }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasText("Düzenle"))
        compose.onNodeWithText("Düzenle").performClick()
        compose.onNodeWithTag("book-note-edit-draft").performTextReplacement("Dirty draft")
        compose.runOnIdle { sourceReady.value = false }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsEnabled()
        val input = checkNotNull(compose.onNodeWithTag("book-note-edit-draft").fetchSemanticsNode()
            .config[SemanticsActions.SetText].action)
        compose.runOnIdle {
            assertTrue(input(AnnotatedString("Still typing while recovery is available")))
            // Android can save immediately, before Compose draws another frame.
            assertEquals("Still typing while recovery is available", ReaderDraftSnapshot.fromBundle(snapshot)?.editDraft)
        }
        compose.onNodeWithTag("book-note-edit-draft").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Still typing while recovery is available")))
        compose.onNodeWithText("Vazgeç").performClick()
        compose.onNodeWithText("Kitap notları").assertIsDisplayed()
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasText("Düzenle"))
        compose.onNodeWithText("Düzenle").assertIsNotEnabled()
        compose.onNodeWithText("Kapat").assertIsEnabled().performClick()
        assertTrue(dismissed)
        assertEquals(listOf(stored), records.value)
        assertEquals(0, writes)
    }
}

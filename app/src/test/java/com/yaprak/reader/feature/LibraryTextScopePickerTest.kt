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
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.search.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
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
class LibraryTextScopePickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("book", "İstanbul ve okuma kaynakları 🌿", "Deniz Yazar", BookFormat.EPUB,
        "book.epub", "book.epub", 12, "a".repeat(64), 1)
    private val pdf = book.copy(id = "pdf", format = BookFormat.PDF)
    private val scope = BookTextScope(book.id, book.sha256)
    private val result = LibraryTextSearchResult("", emptyList(), 0,
        listOf(LibraryTextBookStatus(book, LibraryTextBookState.NOT_INDEXED), LibraryTextBookStatus(pdf, LibraryTextBookState.UNSUPPORTED)))
    private val ready = LibraryTextSearchState(result = result, active = true, loading = false, revision = 7)

    @Test fun `PDF with supported native index joins exact book chooser and old API PDF stays excluded`() {
        val supported = pdf.copy(id = "native", title = "Metin PDF")
        val state = ready.copy(result = result.copy(books = result.books + LibraryTextBookStatus(supported, LibraryTextBookState.NOT_INDEXED)))
        val changes = mutableListOf<Pair<BookTextScope?, Long>>()
        compose.setContent { YaprakTheme { LibraryTextScopePicker(state) { value, revision -> changes += value to revision } } }
        compose.onNodeWithTag("library-text-scope-pick").performClick()
        compose.onNodeWithTag("library-text-scope-option-pdf").assertDoesNotExist()
        compose.onNodeWithTag("library-text-scope-options").performScrollToNode(hasTestTag("library-text-scope-option-native"))
        compose.onNodeWithTag("library-text-scope-option-native").performClick()
        assertEquals(listOf(BookTextScope(supported.id, supported.sha256) to 7L), changes)
    }

    @Test fun `picker selects exact EPUB identity and dismissed or revised scope actions cannot act after ABA`() {
        var state by mutableStateOf(ready)
        val changes = mutableListOf<Pair<BookTextScope?, Long>>()
        compose.setContent { YaprakTheme { LibraryTextScopePicker(state) { value, revision -> changes += value to revision } } }
        compose.onNodeWithTag("library-text-scope-pick").performClick()
        compose.onNodeWithTag("library-text-scope-option-pdf").assertDoesNotExist()
        val old = action("library-text-scope-option-book")
        compose.onNodeWithTag("library-text-scope-close").performClick()
        compose.onNodeWithTag("library-text-scope-pick").performClick()
        compose.runOnIdle { old() }; assertTrue(changes.isEmpty())
        compose.onNodeWithTag("library-text-scope-option-book").performClick()
        assertEquals(listOf(scope to 7L), changes)
        compose.runOnIdle { state = ready.copy(scope = scope, revision = 8, result = result.copy(scope = scope)) }
        val clear = action("library-text-scope-clear")
        compose.runOnIdle { state = ready.copy(revision = 9) }
        compose.runOnIdle { state = ready.copy(scope = scope, revision = 10, result = result.copy(scope = scope)) }
        compose.runOnIdle { clear(); old() }; assertEquals(1, changes.size)
        compose.onNodeWithTag("library-text-scope-clear").performClick()
        assertEquals(null to 10L, changes.last())
    }

    @Test fun `changed metadata and inactive loading failure or build gate obsolete choices while missing scope can clear`() {
        var state by mutableStateOf(ready)
        val changes = mutableListOf<BookTextScope?>()
        compose.setContent { YaprakTheme { LibraryTextScopePicker(state) { value, _ -> changes += value } } }
        compose.onNodeWithTag("library-text-scope-pick").performClick()
        val old = action("library-text-scope-option-book")
        compose.runOnIdle { state = ready.copy(result = result.copy(books = listOf(LibraryTextBookStatus(
            book.copy(title = "Yeni başlık"), LibraryTextBookState.READY, 1)))) }
        compose.runOnIdle { old() }; assertTrue(changes.isEmpty())
        compose.onNodeWithTag("library-text-scope-close").performClick()
        val pick = action("library-text-scope-pick")
        for (gated in listOf(ready.copy(active = false), ready.copy(loading = true),
            ready.copy(failure = LibraryTextIndexFailure.SOURCE), ready.copy(build = LibraryTextBuildPhase.Running(1)))) {
            compose.runOnIdle { state = gated }
            compose.runOnIdle { pick(); old() }; assertTrue(changes.isEmpty())
        }
        compose.runOnIdle { state = ready.copy(scope = scope, failure = LibraryTextIndexFailure.SOURCE, result = null) }
        compose.onNodeWithTag("library-text-scope-clear").assertIsEnabled().performClick()
        assertEquals(listOf<BookTextScope?>(null), changes)
    }

    @Test fun `narrow double type book chooser keeps full title and fixed close readable`() {
        largeFont()
        compose.setContent { YaprakTheme { LibraryTextScopePicker(ready) { _, _ -> } } }
        compose.onNodeWithTag("library-text-scope-pick").performClick()
        compose.onNodeWithTag("library-text-scope-options").performScrollToNode(hasTestTag("library-text-scope-option-book"))
        compose.onNodeWithTag("library-text-scope-option-book").assertIsDisplayed()
        capture("book-scope-narrow-double-type")
        readable(book.title); readable(book.authors!!); readable("Kapat")
        compose.onNodeWithTag("library-text-scope-close").assertIsDisplayed()
        capture("book-scope-narrow-double-type")
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `short landscape double type chooser scrolls options with reachable fixed close`() {
        largeFont()
        compose.setContent { YaprakTheme { LibraryTextScopePicker(ready) { _, _ -> } } }
        compose.onNodeWithTag("library-text-scope-pick").performClick()
        compose.onNodeWithTag("library-text-scope-options").performScrollToNode(hasTestTag("library-text-scope-option-book"))
        compose.onNodeWithTag("library-text-scope-option-book").assertIsDisplayed()
        capture("book-scope-landscape-double-type")
        readable(book.title); readable("Kapat")
        compose.onNodeWithTag("library-text-scope-close").assertIsDisplayed()
        capture("book-scope-landscape-double-type")
    }

    private fun action(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
    }
    private fun readable(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse("Text overflow: $label; size=${layout.size}; constraints=${layout.layoutInput.constraints}; " +
            "width=${layout.didOverflowWidth}; height=${layout.didOverflowHeight}; bottom=${layout.getLineBottom(layout.lineCount - 1)}", layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

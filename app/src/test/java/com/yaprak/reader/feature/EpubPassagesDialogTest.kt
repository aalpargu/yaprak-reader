package com.yaprak.reader.feature

import android.app.Application
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.R
import com.yaprak.reader.domain.passages.EpubPassageRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.passages.EpubPassagesDialog
import com.yaprak.reader.feature.passages.EpubPassagesRoute
import com.yaprak.reader.feature.passages.EpubPassagesState
import com.yaprak.reader.feature.passages.PassageDraft
import com.yaprak.reader.feature.reader.copyPlainSelectedText
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubPassagesDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val anchor = SourceTextAnchor("chapter.xhtml", 0, 4, "a".repeat(64))

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large text keeps explicit save and close reachable while duplicate is blocked`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        var state by mutableStateOf(EpubPassagesState(
            draft = PassageDraft("Leaf", anchor), loading = false))
        var saves = 0
        var closes = 0
        compose.setContent { YaprakTheme { EpubPassagesDialog(state, { saves++ }, {}, {}, {}, {}, { closes++ }) } }
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-passage-save"))
        compose.onNodeWithTag("epub-passage-save").assertIsDisplayed().performClick()
        assertEquals(1, saves)
        compose.runOnIdle { state = state.copy(entries = listOf(EpubPassage("one", "Leaf", anchor, 1))) }
        compose.onNodeWithText("Bu pasaj zaten kayıtlı.").assertExists()
        compose.onNodeWithTag("epub-passage-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertEquals(1, closes)
    }

    @Test fun `delete requires confirmation and keeps entry visible until callback succeeds`() {
        val saved = EpubPassage("one", "Leaf", anchor, 1)
        var state by mutableStateOf(EpubPassagesState(entries = listOf(saved), loading = false))
        var deletes = 0
        compose.setContent { YaprakTheme { EpubPassagesDialog(state, {}, {},
            { id -> state = state.copy(pendingDelete = state.entries.firstOrNull { it.id == id }) },
            { deletes++; state = state.copy(entries = emptyList(), pendingDelete = null) },
            { state = state.copy(pendingDelete = null) }, {}) } }
        compose.onNodeWithTag("epub-passages-list")
            .performScrollToNode(hasContentDescription("1. pasaj: Leaf, sil"))
        compose.onNodeWithContentDescription("1. pasaj: Leaf, sil").performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("Pasaj silinsin mi?").assertIsDisplayed()
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("Pasaj silinsin mi?").assertDoesNotExist()
        compose.onNodeWithContentDescription("1. pasaj: Leaf, sil").performClick()
        compose.onNodeWithText("Pasajı sil").performClick()
        assertEquals(1, deletes)
        compose.onNodeWithText("Bu kitap için henüz pasaj kaydedilmedi.").assertExists()
    }

    @Test fun `closing route discards transferred selection before reopening`() {
        val book = Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100,
            "hash", 1)
        val entries = MutableStateFlow<List<EpubPassage>>(emptyList())
        var adds = 0
        val repository = object : EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = entries
            override suspend fun add(bookId: String, contentSha256: String, text: String,
                anchor: com.yaprak.reader.reader.EpubSourceAnchor) { adds++ }
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
        }
        var open by mutableStateOf(true)
        var prefillText by mutableStateOf<String?>("Leaf")
        var prefillAnchor by mutableStateOf<SourceTextAnchor?>(anchor)
        compose.setContent { YaprakTheme {
            if (open) EpubPassagesRoute(book, repository,
                onDismiss = { open = false; prefillText = null; prefillAnchor = null },
                prefillText = prefillText, prefillAnchor = prefillAnchor)
        } }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("epub-passage-draft").assertExists() }.isSuccess
        }
        compose.onNodeWithTag("epub-passage-save").assertIsDisplayed()
        assertEquals(0, adds)
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { open = true }
        compose.onNodeWithTag("epub-passage-draft").assertDoesNotExist()
        compose.onNodeWithTag("epub-passage-save").assertDoesNotExist()
        assertEquals(0, adds)
    }

    @Test fun `chapter action keeps dialog on failure and opens only selected entry on success`() {
        val book = Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100,
            "hash", 1)
        val first = EpubPassage("first", "Leaf", anchor, 1)
        val second = EpubPassage("second", "Leaf", anchor.copy(start = 4, end = 8), 2)
        val entries = MutableStateFlow(listOf(first, second))
        val repository = object : EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = entries
            override suspend fun add(bookId: String, contentSha256: String, text: String,
                anchor: com.yaprak.reader.reader.EpubSourceAnchor) = Unit
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
        }
        var open by mutableStateOf(true)
        var succeed = false
        var selected: String? = null
        var closes = 0
        compose.setContent { YaprakTheme {
            if (open) EpubPassagesRoute(book, repository, onDismiss = { closes++; open = false },
                onOpenChapter = { passage -> selected = passage.id; succeed })
        } }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithContentDescription("1. pasaj: Leaf, bölümünü aç")
                .assertExists() }.isSuccess
        }
        val help = compose.activity.getString(R.string.annotations_list_help)
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasText(help))
        compose.onNodeWithText(help).assertIsDisplayed()
        compose.onNodeWithTag("epub-passages-list")
            .performScrollToNode(hasContentDescription("2. pasaj: Leaf, bölümünü aç"))
        compose.onNodeWithContentDescription("2. pasaj: Leaf, bölümünü aç").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("epub-passages-list")
                .performScrollToNode(hasText(
                    "Bölüm doğrulanamadı veya açılamadı. Pasajın tam yeri gösterilemez.")) }.isSuccess
        }
        assertEquals("second", selected)
        assertEquals(0, closes)
        compose.onNodeWithTag("epub-passages-list")
            .performScrollToNode(hasContentDescription("2. pasaj: Leaf, bölümünü aç"))
        compose.onNodeWithContentDescription("2. pasaj: Leaf, bölümünü aç").assertIsDisplayed()
        succeed = true
        compose.onNodeWithContentDescription("2. pasaj: Leaf, bölümünü aç").performClick()
        compose.waitUntil(5_000) { closes == 1 }
        assertEquals("second", selected)
        compose.onNodeWithText("Vurgular ve notlar").assertDoesNotExist()
    }

    @Test fun `copy sends only the selected saved text on tap and retries a failed clipboard write`() {
        val book = Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100, "hash", 1)
        val first = EpubPassage("first", "Leaf", anchor, 1)
        val text = "  Second\npassage.  "
        val second = EpubPassage("second", text, anchor.copy(end = text.length), 2)
        val entries = MutableStateFlow(listOf(first, second))
        var writes = 0
        val repository = object : EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = entries
            override suspend fun add(bookId: String, contentSha256: String, text: String,
                anchor: com.yaprak.reader.reader.EpubSourceAnchor) { writes++ }
            override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
        }
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)!!
        clipboard.clearPrimaryClip()
        var failCopy = true
        var copies = 0
        compose.setContent { YaprakTheme {
            EpubPassagesRoute(book, repository, {}, prefillText = "Draft",
                prefillAnchor = anchor.copy(end = 5), onCopy = { copiedText ->
                    copies++
                    check(!failCopy) { "Expected clipboard failure" }
                    copyPlainSelectedText(compose.activity, copiedText)
                })
        } }
        compose.waitForIdle()
        val description = "2. pasaj: ${text.replace('\n', ' ').take(40)}, kopyala"
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasContentDescription(description))
        assertFalse(clipboard.hasPrimaryClip())
        assertEquals(0, copies)
        compose.onNodeWithContentDescription(description).performClick()
        compose.onNodeWithText("Pasaj kopyalanamadı. Yeniden deneyebilirsin.").assertExists()
        assertFalse(clipboard.hasPrimaryClip())
        failCopy = false
        compose.onNodeWithContentDescription(description).performClick()
        compose.onNodeWithText("Pasaj panoya kopyalandı.").assertExists()
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(2, copies)
        assertEquals(0, writes)
        compose.onNodeWithTag("epub-passages-list")
            .performScrollToNode(hasTestTag("epub-passage-draft"))
        compose.onNodeWithTag("epub-passage-draft").assertExists()
        compose.runOnIdle { entries.value = listOf(first) }
        compose.onNodeWithText("Pasaj panoya kopyalandı.").assertDoesNotExist()
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(0, writes)
    }

    @Test fun `copy is blocked while writing or navigating and unavailable before a valid load`() {
        val saved = EpubPassage("one", "Leaf", anchor, 1)
        var state by mutableStateOf(EpubPassagesState(entries = listOf(saved), loading = false, busy = true))
        var openingId by mutableStateOf<String?>(null)
        val copied = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            EpubPassagesDialog(state, {}, {}, {}, {}, {}, {}, openingChapterId = openingId,
                onCopy = { copied += it })
        } }
        val description = "1. pasaj: Leaf, kopyala"
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), copied)
        compose.runOnIdle { state = state.copy(busy = false); openingId = saved.id }
        compose.onNodeWithContentDescription(description).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), copied)
        compose.runOnIdle { openingId = null }
        compose.onNodeWithContentDescription(description).performClick()
        assertEquals(listOf(saved.id), copied)
        compose.runOnIdle { state = state.copy(loadFailed = true) }
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = true, loadFailed = false) }
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
        assertEquals(listOf(saved.id), copied)
    }
}

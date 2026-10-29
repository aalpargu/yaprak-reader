package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.domain.passages.EpubPassageRepository
import com.yaprak.reader.feature.passages.EpubPassagesDialog
import com.yaprak.reader.feature.passages.EpubPassagesRoute
import com.yaprak.reader.feature.passages.EpubPassagesState
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class EpubPassageFilterUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100, "hash", 1)

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `large font route searches sorts and clears while actions retain saved identity`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        val zulu = entry("zulu", "Zulu leaf", 3)
        val apple = entry("apple", "Apple leaf", 2)
        val tree = entry("tree", "Tree", 1)
        val repository = Repository(listOf(zulu, apple, tree))
        val copied = mutableListOf<String>()
        val opened = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            EpubPassagesRoute(book, repository, {}, onCopy = { copied += it },
                onOpenChapter = { opened += it.id; false })
        } }
        search("leaf")
        revealTag("epub-passage-sort").assertIsDisplayed().performClick()
        compose.onNodeWithText("Pasaj metni A–Z").assertIsDisplayed().performClick()
        revealDescription("1. pasaj: Apple leaf, kopyala").assertIsDisplayed().performClick()
        assertEquals(listOf("Apple leaf"), copied)

        revealTag("epub-passage-search-clear").assertIsDisplayed().performClick()
        revealTag("epub-passage-sort").assertTextEquals("Sıralama: Pasaj metni A–Z")
        // Filtering alone preserves feedback for the same saved entry.
        revealText("Pasaj panoya kopyalandı.").assertIsDisplayed()
        revealDescription("2. pasaj: Tree, sil").performClick()
        compose.onNodeWithText("Pasaj silinsin mi?").assertIsDisplayed()
        compose.onNodeWithText("Pasajı sil").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { repository.removed.isNotEmpty() }
        assertEquals(listOf("tree"), repository.removed)
        revealDescription("2. pasaj: Zulu leaf, bölümünü aç").performClick()
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(listOf("zulu"), opened)
        revealText("Bölüm doğrulanamadı veya açılamadı. Pasajın tam yeri gösterilemez.").assertIsDisplayed()
    }

    @Test fun `draft is outside saved text search and can be saved while no saved passage matches`() {
        val repository = Repository(listOf(entry("old", "Existing", 1)))
        val draftText = "Needle"
        val draftAnchor = SourceTextAnchor("draft.xhtml", 0, draftText.length, "a".repeat(64))
        compose.setContent { YaprakTheme {
            EpubPassagesRoute(book, repository, {}, prefillText = draftText, prefillAnchor = draftAnchor)
        } }
        search("needle")
        revealText("Aramanla eşleşen pasaj bulunamadı.").assertIsDisplayed()
        revealTag("epub-passage-draft").assertTextEquals(draftText)
        revealTag("epub-passage-save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { repository.added.isNotEmpty() }
        assertEquals(listOf(draftText), repository.added)
        compose.onNodeWithTag("epub-passage-draft").assertDoesNotExist()
        revealDescription("1. pasaj: Needle, sil").assertExists()
        compose.onNodeWithText("Aramanla eşleşen pasaj bulunamadı.").assertDoesNotExist()
    }

    @Test fun `writing and chapter navigation disable search clear and sort without dispatching changes`() {
        var state by mutableStateOf(EpubPassagesState(entries = listOf(entry("one", "Leaf", 1)),
            loading = false, query = "leaf", busy = true))
        var opening by mutableStateOf<String?>(null)
        val changes = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            EpubPassagesDialog(state, {}, {}, {}, {}, {}, {}, openingChapterId = opening,
                onSearch = { changes += "query" }, onClearSearch = { changes += "clear" },
                onSort = { changes += "sort" })
        } }
        revealTag("epub-passage-search").assertIsNotEnabled()
        revealTag("epub-passage-search-clear").assertIsNotEnabled().performClick()
        revealTag("epub-passage-sort").assertIsNotEnabled().performClick()
        compose.runOnIdle { state = state.copy(busy = false); opening = "one" }
        revealTag("epub-passage-search").assertIsNotEnabled()
        revealTag("epub-passage-search-clear").assertIsNotEnabled().performClick()
        revealTag("epub-passage-sort").assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), changes)
        compose.runOnIdle { opening = null }
        revealTag("epub-passage-search").assertIsEnabled()
        revealTag("epub-passage-search-clear").assertIsEnabled().performClick()
        assertEquals(listOf("clear"), changes)
    }

    @Test fun `loading and failed loads take precedence over old unmatched entries`() {
        var state by mutableStateOf(EpubPassagesState(entries = listOf(entry("one", "Leaf", 1)),
            query = "missing", loading = true))
        var reloads = 0
        compose.setContent { YaprakTheme { EpubPassagesDialog(state, {}, { reloads++ }, {}, {}, {}, {}) } }
        compose.onNodeWithTag("epub-passage-search").assertDoesNotExist()
        compose.onNodeWithText("Aramanla eşleşen pasaj bulunamadı.").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, loadFailed = true) }
        revealText("Pasajlar yüklenemedi.").assertIsDisplayed()
        compose.onNodeWithTag("epub-passage-search").assertDoesNotExist()
        compose.onNodeWithText("Aramanla eşleşen pasaj bulunamadı.").assertDoesNotExist()
        revealText("Yeniden yükle").assertIsEnabled().performClick()
        assertEquals(1, reloads)
    }

    @Test fun `empty source and unmatched saved results are distinct and clear restores saved rows`() {
        var state by mutableStateOf(EpubPassagesState(loading = false, query = "missing"))
        compose.setContent { YaprakTheme {
            EpubPassagesDialog(state, {}, {}, {}, {}, {}, {},
                onSearch = { state = state.copy(query = it) },
                onClearSearch = { state = state.copy(query = "") })
        } }
        revealText("Bu kitap için henüz pasaj kaydedilmedi.").assertIsDisplayed()
        compose.onNodeWithText("Aramanla eşleşen pasaj bulunamadı.").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(entries = listOf(entry("one", "Leaf", 1))) }
        revealText("Aramanla eşleşen pasaj bulunamadı.").assertIsDisplayed()
        compose.onNodeWithText("Bu kitap için henüz pasaj kaydedilmedi.").assertDoesNotExist()
        revealTag("epub-passage-search-clear").performClick()
        revealDescription("1. pasaj: Leaf, sil").assertIsDisplayed()
        assertEquals("", state.query)
    }

    private fun search(query: String) {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("epub-passages-list")
                .performScrollToNode(hasTestTag("epub-passage-search")) }.isSuccess
        }
        compose.onNodeWithTag("epub-passage-search").performTextReplacement(query)
    }

    private fun revealTag(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun revealText(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    private fun revealDescription(description: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasContentDescription(description))
        return compose.onNodeWithContentDescription(description)
    }

    private fun entry(id: String, text: String, createdAt: Long) = EpubPassage(id, text,
        SourceTextAnchor("$id.xhtml", 0, text.length, "a".repeat(64)), createdAt)

    private inner class Repository(initial: List<EpubPassage>) : EpubPassageRepository {
        val entries = MutableStateFlow(initial)
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        override fun observe(bookId: String, contentSha256: String) = entries
        override suspend fun add(bookId: String, contentSha256: String, text: String, anchor: com.yaprak.reader.reader.EpubSourceAnchor) {
            added += text
            entries.value = listOf(EpubPassage("saved", text, anchor, 10)) + entries.value
        }
        override suspend fun remove(bookId: String, contentSha256: String, id: String) {
            removed += id
            entries.value = entries.value.filterNot { it.id == id }
        }
        override suspend fun removeExpected(bookId: String, contentSha256: String, expected: EpubPassage) {
            check(entries.value.singleOrNull { it.id == expected.id } == expected)
            removed += expected.id
            entries.value = entries.value.filterNot { it.id == expected.id }
        }
    }
}

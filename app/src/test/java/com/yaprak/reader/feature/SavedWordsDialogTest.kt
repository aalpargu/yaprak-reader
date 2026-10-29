package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.vocabulary.SavedWord
import com.yaprak.reader.domain.vocabulary.SavedWordRepository
import com.yaprak.reader.feature.vocabulary.SavedWordsDialog
import com.yaprak.reader.feature.vocabulary.SavedWordsRoute
import com.yaprak.reader.feature.vocabulary.SavedWordsState
import com.yaprak.reader.feature.vocabulary.SavedWordSort
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class SavedWordsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val entry = SavedWord("one", "leaf", "yaprak", 1)

    @Test fun `manual English and Turkish drafts are required before save`() {
        var state by mutableStateOf(SavedWordsState(loading = false))
        var saved = 0
        compose.setContent { YaprakTheme { SavedWordsDialog(state,
            { state = state.copy(englishDraft = it) }, { state = state.copy(turkishDraft = it) },
            { saved++ }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Otomatik çeviri yapılmaz.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Kelimeyi kaydet").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("saved-word-english").performTextReplacement("leaf")
        compose.onNodeWithTag("saved-word-turkish").performTextReplacement("yaprak")
        compose.onNodeWithText("Kelimeyi kaydet").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, saved)
    }

    @Test fun `delete targets a saved row and requires confirmation`() {
        val second = entry.copy(id = "two", turkish = "sayfa")
        var state by mutableStateOf(SavedWordsState(entries = listOf(entry, second), loading = false))
        var deleted = 0
        compose.setContent { YaprakTheme { SavedWordsDialog(state,
            { state = state.copy(englishDraft = it) }, { state = state.copy(turkishDraft = it) }, {}, {},
            { id -> state = state.copy(pendingDelete = state.entries.first { it.id == id }) },
            { deleted++ }, { state = state.copy(pendingDelete = null) }, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasContentDescription("2. kelime: leaf, sil"))
        compose.onNodeWithContentDescription("2. kelime: leaf, sil").performClick()
        assertEquals("two", state.pendingDelete?.id)
        assertEquals(0, deleted)
        compose.onNodeWithText("Vazgeç").performClick()
        assertNull(state.pendingDelete)
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasContentDescription("2. kelime: leaf, sil"))
        compose.onNodeWithContentDescription("2. kelime: leaf, sil").performClick()
        compose.onNodeWithText("Kelimeyi sil").performClick()
        assertEquals(1, deleted)
    }

    @Test fun `load error offers retry and prevents writes`() {
        var state by mutableStateOf(SavedWordsState(loading = false, loadFailed = true,
            englishDraft = "leaf", turkishDraft = "yaprak"))
        var reloaded = 0
        compose.setContent { YaprakTheme { SavedWordsDialog(state, {}, {}, {}, { reloaded++ }, {}, {}, {}, {},
            {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Kelimeyi kaydet").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasText("Yeniden yükle"))
        compose.onNodeWithText("Yeniden yükle").performClick()
        assertEquals(1, reloaded)
        compose.runOnIdle { state = state.copy(loadFailed = false, busy = true, entries = listOf(entry)) }
        compose.onNodeWithText("Kelimeyi kaydet").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Kapat").assertIsNotEnabled()
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasContentDescription("1. kelime: leaf, sil"))
        compose.onNodeWithContentDescription("1. kelime: leaf, sil").assertIsNotEnabled()
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large type search filters and clear restores saved words`() {
        val city = SavedWord("two", "city", "İstanbul", 2)
        var state by mutableStateOf(SavedWordsState(entries = listOf(entry, city), loading = false))
        var selectedId: String? = null
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { SavedWordsDialog(state, {}, {}, {}, {}, {}, {}, {}, {},
            { selectedId = it }, {}, {}, {}, {}, onSearch = { state = state.copy(query = it) }) } }
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag("saved-word-search"))
        compose.onNodeWithTag("saved-word-search").assertIsDisplayed().performTextReplacement("CITY istanbul")
        assertEquals(listOf(city), state.visibleEntries)
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasText("city"))
        compose.onNodeWithText("city").assertIsDisplayed()
        compose.onNodeWithTag("saved-words-list")
            .performScrollToNode(hasContentDescription("1. kelime: city, düzenle"))
        compose.onNodeWithContentDescription("1. kelime: city, düzenle").performClick()
        assertEquals("two", selectedId)
        compose.onNodeWithTag("saved-word-search").performTextReplacement("olmayan")
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasText("Aramanla eşleşen kelime yok."))
        compose.onNodeWithText("Aramanla eşleşen kelime yok.").assertIsDisplayed()
        compose.onNodeWithText("Temizle").performClick()
        assertEquals("", state.query)
        assertEquals(listOf(entry, city), state.visibleEntries)
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large type sort menu combines with search and keeps row identity`() {
        val zebra = SavedWord("zebra", "zebra", "elma", 3)
        val apple = SavedWord("apple", "apple", "zeytin", 2)
        val cherry = SavedWord("cherry", "cherry", "armut", 1)
        var state by mutableStateOf(SavedWordsState(entries = listOf(zebra, apple, cherry), loading = false))
        var selectedId: String? = null
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { SavedWordsDialog(state, {}, {}, {}, {}, {}, {}, {}, {},
            { selectedId = it }, {}, {}, {}, {},
            onSearch = { state = state.copy(query = it) },
            onSort = { state = state.copy(sort = it) }) } }

        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag("saved-word-sort"))
        compose.onNodeWithTag("saved-word-sort").assertIsDisplayed().performClick()
        compose.onNodeWithText("İngilizce A–Z").performClick()
        assertEquals(SavedWordSort.ENGLISH, state.sort)
        assertEquals(listOf(apple, cherry, zebra), state.visibleEntries)

        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag("saved-word-search"))
        compose.onNodeWithTag("saved-word-search").performTextReplacement("z")
        assertEquals(listOf(apple, zebra), state.visibleEntries)
        compose.onNodeWithTag("saved-words-list")
            .performScrollToNode(hasContentDescription("1. kelime: apple, düzenle"))
        compose.onNodeWithContentDescription("1. kelime: apple, düzenle").performClick()
        assertEquals("apple", selectedId)

        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag("saved-word-sort"))
        compose.onNodeWithTag("saved-word-sort").assertIsDisplayed().performClick()
        compose.onNodeWithText("Türkçe A–Z").performClick()
        assertEquals(SavedWordSort.TURKISH, state.sort)
        assertEquals(listOf(zebra, apple), state.visibleEntries)
        compose.onNodeWithTag("saved-words-list")
            .performScrollToNode(hasContentDescription("1. kelime: zebra, düzenle"))
        compose.onNodeWithContentDescription("1. kelime: zebra, düzenle").performClick()
        assertEquals("zebra", selectedId)
        assertEquals(listOf(zebra, apple, cherry), state.entries)
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large type landscape keeps fields save long list and close reachable`() {
        val words = (1..30).map { SavedWord("$it", "English $it", "Türkçe $it", it.toLong()) }
        var state by mutableStateOf(SavedWordsState(entries = words, loading = false))
        var saved = false
        var closed = false
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { SavedWordsDialog(state,
            { state = state.copy(englishDraft = it) }, { state = state.copy(turkishDraft = it) },
            { saved = true }, {}, {}, {}, {}, { closed = true }, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag("saved-word-english"))
        compose.onNodeWithTag("saved-word-english").performTextReplacement("leaf")
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag("saved-word-turkish"))
        compose.onNodeWithTag("saved-word-turkish").performTextReplacement("yaprak")
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasText("Kelimeyi kaydet"))
        compose.onNodeWithText("Kelimeyi kaydet").assertIsDisplayed().performClick()
        assertTrue(saved)
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag("saved-word-search"))
        compose.onNodeWithTag("saved-word-search").assertIsDisplayed()
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertTrue(closed)
    }

    @Test fun `same English text edits selected record and failed save keeps draft`() {
        val second = entry.copy(id = "two", turkish = "sayfa")
        var state by mutableStateOf(SavedWordsState(entries = listOf(entry, second), loading = false))
        var saves = 0
        compose.setContent { YaprakTheme { SavedWordsDialog(state, {}, {}, {}, {}, {}, {}, {}, {},
            { id ->
                val selected = state.entries.first { it.id == id }
                state = state.copy(pendingEdit = selected, editEnglishDraft = selected.english,
                    editTurkishDraft = selected.turkish)
            },
            { state = state.copy(editEnglishDraft = it, message = null) },
            { state = state.copy(editTurkishDraft = it, message = null) },
            { saves++; state = state.copy(busy = true) },
            { state = state.copy(pendingEdit = null, editEnglishDraft = "", editTurkishDraft = "", message = null) }) } }
        compose.onNodeWithTag("saved-words-list")
            .performScrollToNode(hasContentDescription("2. kelime: leaf, düzenle"))
        compose.onNodeWithContentDescription("2. kelime: leaf, düzenle").performClick()
        assertEquals("two", state.pendingEdit?.id)
        compose.onNodeWithTag("saved-word-edit-turkish")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("sayfa")))
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithTag("saved-word-edit-turkish").performTextReplacement(" ")
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithTag("saved-word-edit-turkish").performTextReplacement("yeni karşılık")
        compose.onNodeWithText("Kaydet").assertIsEnabled().performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
        compose.onNodeWithTag("saved-word-edit-turkish").assertIsNotEnabled()
        compose.runOnIdle {
            state = state.copy(busy = false, message = "Kelime düzenlenemedi. Yeniden deneyebilirsin.")
        }
        compose.onNodeWithTag("saved-word-edit-content")
            .performScrollToNode(hasText("Kelime düzenlenemedi. Yeniden deneyebilirsin."))
        compose.onNodeWithText("Kelime düzenlenemedi. Yeniden deneyebilirsin.").assertIsDisplayed()
        compose.onNodeWithTag("saved-word-edit-content").performScrollToNode(hasTestTag("saved-word-edit-turkish"))
        compose.onNodeWithTag("saved-word-edit-turkish")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("yeni karşılık")))
        assertEquals(listOf(entry, second), state.entries)
        compose.onNodeWithText("Vazgeç").performClick()
        assertNull(state.pendingEdit)
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large type landscape edit fields save and cancel remain reachable`() {
        var state by mutableStateOf(SavedWordsState(entries = listOf(entry), loading = false,
            pendingEdit = entry, editEnglishDraft = "leaf", editTurkishDraft = "yeni karşılık"))
        var saved = false
        var cancelled = false
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { SavedWordsDialog(state, {}, {}, {}, {}, {}, {}, {}, {},
            {}, { state = state.copy(editEnglishDraft = it) }, { state = state.copy(editTurkishDraft = it) },
            { saved = true }, { cancelled = true; state = state.copy(pendingEdit = null) }) } }
        compose.onNodeWithTag("saved-word-edit-content").performScrollToNode(hasTestTag("saved-word-edit-english"))
        compose.onNodeWithTag("saved-word-edit-english").assertIsDisplayed()
        compose.onNodeWithTag("saved-word-edit-content").performScrollToNode(hasTestTag("saved-word-edit-turkish"))
        compose.onNodeWithTag("saved-word-edit-turkish").assertIsDisplayed()
        compose.onNodeWithText("Kaydet").assertIsDisplayed().performClick()
        assertTrue(saved)
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        assertTrue(cancelled)
    }

    @Test fun `selection opened route discards unsaved prefill before later manual opening`() {
        val book = Book("book-id", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100,
            "hash-a", 1)
        var addCalls = 0
        val words = MutableStateFlow<List<SavedWord>>(emptyList())
        val repository = object : SavedWordRepository {
            override fun observe(bookId: String, contentSha256: String) = words
            override suspend fun add(bookId: String, contentSha256: String, english: String, turkish: String) {
                addCalls++
                words.value = words.value + SavedWord("new", english, turkish, 2)
            }
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
            override suspend fun update(bookId: String, contentSha256: String, id: String,
                english: String, turkish: String) = Unit
        }
        var open by mutableStateOf(true)
        var fromSelection by mutableStateOf(true)
        compose.setContent { YaprakTheme {
            if (open) SavedWordsRoute(book, repository, { open = false }, if (fromSelection) "leaf" else null)
        } }
        compose.onNodeWithTag("saved-word-english")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("leaf")))
        compose.onNodeWithTag("saved-word-turkish")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals(0, addCalls)
        compose.onNodeWithTag("saved-word-turkish").performTextReplacement("yaprak")
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag("saved-word-search"))
        compose.onNodeWithTag("saved-word-search").performTextReplacement("leaf")
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag("saved-word-sort"))
        compose.onNodeWithTag("saved-word-sort").performClick()
        compose.onNodeWithText("İngilizce A–Z").performClick()
        compose.onNodeWithText("Sıralama: İngilizce A–Z").assertIsDisplayed()
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { fromSelection = false; open = true }
        compose.onNodeWithTag("saved-word-english")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("saved-word-turkish")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("saved-word-search")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("saved-words-list").performScrollToNode(hasTestTag("saved-word-sort"))
        compose.onNodeWithText("Sıralama: Kayıt sırası").assertIsDisplayed()
        assertEquals(0, addCalls)
    }
}

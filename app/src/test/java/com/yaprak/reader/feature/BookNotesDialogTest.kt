package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.notes.BookNote
import com.yaprak.reader.domain.notes.BookNoteRepository
import com.yaprak.reader.feature.notes.BookNotesDialog
import com.yaprak.reader.feature.notes.BookNotesRoute
import com.yaprak.reader.feature.notes.BookNotesState
import com.yaprak.reader.feature.notes.BookNoteSort
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
class BookNotesDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val entry = BookNote("one", "İlk not", 1)

    @Test fun `dialog explains whole book scope and needs a valid manual draft`() {
        var state by mutableStateOf(BookNotesState(loading = false))
        var saves = 0
        compose.setContent { YaprakTheme { BookNotesDialog(state,
            { state = state.copy(draft = it) }, { saves++ }, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Bu not kitabın geneline aittir; okuduğun konuma bağlanmaz.")
            .assertIsDisplayed()
        compose.onNodeWithText("Notu kaydet").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("book-note-draft").performTextReplacement("Birinci satır\nİkinci satır")
        compose.onNodeWithText("Notu kaydet").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, saves)
        compose.onNodeWithTag("book-note-draft").performTextReplacement(" ")
        compose.onNodeWithText("Notu kaydet").assertIsNotEnabled()
    }

    @Test fun `delete targets one saved note and requires separate confirmation`() {
        val second = entry.copy(id = "two", text = "İkinci not")
        var state by mutableStateOf(BookNotesState(entries = listOf(entry, second), loading = false))
        var deleted = 0
        compose.setContent { YaprakTheme { BookNotesDialog(state, {}, {}, {},
            { id -> state = state.copy(pendingDelete = state.entries.first { it.id == id }) },
            { deleted++ }, { state = state.copy(pendingDelete = null) }, {}) } }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("2. not: İkinci not, sil"))
        compose.onNodeWithContentDescription("2. not: İkinci not, sil").performClick()
        assertEquals("two", state.pendingDelete?.id)
        assertEquals(0, deleted)
        compose.onNodeWithText("Vazgeç").performClick()
        assertNull(state.pendingDelete)
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("2. not: İkinci not, sil"))
        compose.onNodeWithContentDescription("2. not: İkinci not, sil").performClick()
        compose.onNodeWithText("Notu sil").performClick()
        assertEquals(1, deleted)
    }

    @Test fun `load error offers retry and busy state blocks writes and closing`() {
        var state by mutableStateOf(BookNotesState(loading = false, loadFailed = true, draft = "Taslak"))
        var retries = 0
        compose.setContent { YaprakTheme { BookNotesDialog(state, {}, {}, { retries++ }, {}, {}, {}, {}) } }
        compose.onNodeWithText("Notu kaydet").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-draft"))
        compose.onNodeWithTag("book-note-draft")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Taslak")))
        compose.onNodeWithTag("book-notes-list").performScrollToNode(androidx.compose.ui.test.hasText("Yeniden yükle"))
        compose.onNodeWithText("Yeniden yükle").performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state = state.copy(loadFailed = false, busy = true, entries = listOf(entry)) }
        compose.onNodeWithText("Notu kaydet").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Kapat").assertIsNotEnabled()
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("1. not: İlk not, sil"))
        compose.onNodeWithContentDescription("1. not: İlk not, sil").assertIsNotEnabled()
    }

    @Test fun `note search filters only saved text and clear restores original entries`() {
        val second = entry.copy(id = "two", text = "İkinci not")
        var state by mutableStateOf(BookNotesState(entries = listOf(entry, second), loading = false))
        var selectedId: String? = null
        compose.setContent { YaprakTheme { BookNotesDialog(state, {}, {}, {},
            onRequestDelete = { selectedId = it }, onDelete = {}, onCancelDelete = {}, onDismiss = {},
            onSearch = { state = state.copy(query = it) },
            onClearSearch = { state = state.copy(query = "") },
        ) } }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-search"))
        compose.onNodeWithTag("book-note-search").assertIsDisplayed().performTextReplacement("  İKİNCİ  ")
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("1. not: İkinci not, sil"))
        compose.onNodeWithContentDescription("1. not: İkinci not, sil").performClick()
        assertEquals("two", selectedId)
        compose.onNodeWithText("İlk not").assertDoesNotExist()
        compose.onNodeWithTag("book-notes-list").performScrollToNode(androidx.compose.ui.test.hasText("Temizle"))
        compose.onNodeWithText("Temizle").performClick()
        assertEquals("", state.query)
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("2. not: İkinci not, sil"))
        compose.onNodeWithContentDescription("2. not: İkinci not, sil").assertIsDisplayed()
        compose.onNodeWithTag("book-notes-list").performScrollToNode(androidx.compose.ui.test.hasText("İlk not"))
        compose.onNodeWithText("İlk not").assertIsDisplayed()
    }

    @Test fun `sort options combine with search while edit and delete retain saved row ids`() {
        val zebra = BookNote("z", "Zeytin not", 3)
        val apple = BookNote("a", "Armut not", 2)
        val cherry = BookNote("e", "Elma başka", 1)
        var state by mutableStateOf(BookNotesState(entries = listOf(zebra, apple, cherry), loading = false))
        var editedId: String? = null
        var deletedId: String? = null
        compose.setContent { YaprakTheme { BookNotesDialog(state, {}, {}, {},
            onRequestDelete = { deletedId = it }, onDelete = {}, onCancelDelete = {}, onDismiss = {},
            onRequestEdit = { editedId = it },
            onSearch = { state = state.copy(query = it) },
            onSort = { state = state.copy(sort = it) },
        ) } }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-sort"))
        compose.onNodeWithText("Sıralama: Kayıt sırası").assertIsDisplayed().performClick()
        compose.onNodeWithText("Kayıt sırası").assertIsDisplayed()
        compose.onNodeWithText("Not metni A–Z").assertIsDisplayed().performClick()
        assertEquals(BookNoteSort.TEXT_ASC, state.sort)
        assertEquals(listOf(apple, cherry, zebra), state.visibleEntries)

        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-search"))
        compose.onNodeWithTag("book-note-search").performTextReplacement("NOT")
        assertEquals(listOf(apple, zebra), state.visibleEntries)
        compose.onNodeWithTag("book-notes-list")
            .performScrollToNode(hasContentDescription("1. not: Armut not, düzenle"))
        compose.onNodeWithContentDescription("1. not: Armut not, düzenle").performClick()
        assertEquals("a", editedId)
        compose.onNodeWithTag("book-notes-list")
            .performScrollToNode(hasContentDescription("2. not: Zeytin not, sil"))
        compose.onNodeWithContentDescription("2. not: Zeytin not, sil").performClick()
        assertEquals("z", deletedId)

        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-sort"))
        compose.onNodeWithTag("book-note-sort").performClick()
        compose.onNodeWithText("Kayıt sırası").performClick()
        assertEquals(BookNoteSort.RECORD_ORDER, state.sort)
        assertEquals(listOf(zebra, apple), state.visibleEntries)
        assertEquals(listOf(zebra, apple, cherry), state.entries)
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large type landscape keeps sorting search result action and close reachable`() {
        val notes = (30 downTo 1).map { BookNote("$it", "Not ${it.toString().padStart(2, '0')}", it.toLong()) }
        var state by mutableStateOf(BookNotesState(entries = notes, loading = false))
        var selectedId: String? = null
        var closed = false
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { BookNotesDialog(state, {}, {}, {},
            onRequestDelete = { selectedId = it }, onDelete = {}, onCancelDelete = {},
            onDismiss = { closed = true },
            onSearch = { state = state.copy(query = it) },
            onSort = { state = state.copy(sort = it) },
        ) } }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-sort"))
        compose.onNodeWithTag("book-note-sort").assertIsDisplayed().performClick()
        compose.onNodeWithText("Not metni A–Z").performClick()
        assertEquals("1", state.visibleEntries.first().id)
        assertEquals("30", state.visibleEntries.last().id)
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-search"))
        compose.onNodeWithTag("book-note-search").assertIsDisplayed().performTextReplacement("Not 2")
        compose.waitForIdle()
        assertEquals("Not 2", state.query)
        assertEquals(listOf("2", "12", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29"),
            state.visibleEntries.map { it.id })
        compose.onNodeWithTag("book-notes-list")
            .performScrollToNode(hasContentDescription("3. not: Not 20, sil"))
        compose.onNodeWithContentDescription("3. not: Not 20, sil").assertIsDisplayed().performClick()
        assertEquals("20", selectedId)
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertTrue(closed)
    }

    @Test fun `empty saved notes and unmatched search have different messages`() {
        var state by mutableStateOf(BookNotesState(loading = false, query = "olmayan"))
        compose.setContent { YaprakTheme { BookNotesDialog(state, {}, {}, {}, {}, {}, {}, {},
            onSearch = { state = state.copy(query = it) },
        ) } }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(
            androidx.compose.ui.test.hasText("Bu kitap için henüz not kaydedilmedi."))
        compose.onNodeWithText("Bu kitap için henüz not kaydedilmedi.").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(entries = listOf(entry)) }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(
            androidx.compose.ui.test.hasText("Aramanla eşleşen not bulunamadı."))
        compose.onNodeWithText("Aramanla eşleşen not bulunamadı.").assertIsDisplayed()
    }

    @Test fun `duplicate note text has distinct edit actions and unchanged draft cannot save`() {
        val second = entry.copy(id = "two", createdAt = 2)
        var state by mutableStateOf(BookNotesState(entries = listOf(entry, second), loading = false))
        var updated = 0
        compose.setContent { YaprakTheme { BookNotesDialog(state, {}, {}, {}, {}, {}, {}, {},
            onRequestEdit = { id ->
                val target = state.entries.first { it.id == id }
                state = state.copy(pendingEdit = target, editDraft = target.text)
            },
            onEditSelectedDraft = { state = state.copy(editDraft = it) },
            onSaveEdit = { updated++ },
            onCancelEdit = { state = state.copy(pendingEdit = null, editDraft = "") },
        ) } }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("2. not: İlk not, düzenle"))
        compose.onNodeWithContentDescription("2. not: İlk not, düzenle").performClick()
        assertEquals(second.id, state.pendingEdit?.id)
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithTag("book-note-edit-content").performScrollToNode(hasTestTag("book-note-edit-draft"))
        compose.onNodeWithTag("book-note-edit-draft").performTextReplacement("  Düzenlenmiş not  ")
        compose.onNodeWithText("Kaydet").assertIsEnabled().performClick()
        assertEquals(1, updated)
        compose.onNodeWithText("Vazgeç").performClick()
        assertNull(state.pendingEdit)
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("1. not: İlk not, düzenle"))
        compose.onNodeWithContentDescription("1. not: İlk not, düzenle").performClick()
        assertEquals(entry.id, state.pendingEdit?.id)
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large type short landscape keeps draft save long list deletion and close reachable`() {
        val notes = (1..30).map { BookNote("$it", "Not $it", it.toLong()) }
        var state by mutableStateOf(BookNotesState(entries = notes, loading = false))
        var saved = false
        var deletedId: String? = null
        var closed = false
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { BookNotesDialog(state,
            { state = state.copy(draft = it) }, { saved = true }, {},
            { id -> deletedId = id; state = state.copy(pendingDelete = state.entries.first { it.id == id }) },
            {}, { state = state.copy(pendingDelete = null) }, { closed = true }) } }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-draft"))
        compose.onNodeWithTag("book-note-draft").assertIsDisplayed().performTextReplacement("Yeni not")
        compose.onNodeWithTag("book-notes-list").performScrollToNode(androidx.compose.ui.test.hasText("Notu kaydet"))
        compose.onNodeWithText("Notu kaydet").assertIsDisplayed().performClick()
        assertTrue(saved)
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("30. not: Not 30, sil"))
        compose.onNodeWithContentDescription("30. not: Not 30, sil").assertIsDisplayed().performClick()
        assertEquals("30", deletedId)
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertTrue(closed)
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large type short landscape keeps last note edit field save and cancel reachable`() {
        val notes = (1..30).map { BookNote("$it", "Not $it", it.toLong()) }
        var state by mutableStateOf(BookNotesState(entries = notes, loading = false))
        var saved = false
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { BookNotesDialog(state, {}, {}, {}, {}, {}, {}, {},
            onRequestEdit = { id ->
                val target = state.entries.first { it.id == id }
                state = state.copy(pendingEdit = target, editDraft = target.text)
            },
            onEditSelectedDraft = { state = state.copy(editDraft = it) },
            onSaveEdit = { saved = true },
            onCancelEdit = { state = state.copy(pendingEdit = null, editDraft = "") },
        ) } }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("30. not: Not 30, düzenle"))
        compose.onNodeWithContentDescription("30. not: Not 30, düzenle").assertIsDisplayed().performClick()
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithTag("book-note-edit-content").performScrollToNode(hasTestTag("book-note-edit-draft"))
        compose.onNodeWithTag("book-note-edit-draft").assertIsDisplayed().performTextReplacement("Yeni metin")
        compose.onNodeWithText("Kaydet").assertIsDisplayed().performClick()
        assertTrue(saved)
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        assertNull(state.pendingEdit)
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large type short landscape keeps search field clear and filtered note reachable`() {
        val notes = (1..30).map { BookNote("$it", "Not $it", it.toLong()) }
        var state by mutableStateOf(BookNotesState(entries = notes, loading = false))
        var selectedId: String? = null
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent { YaprakTheme { BookNotesDialog(state, {}, {}, {},
            onRequestDelete = { selectedId = it }, onDelete = {}, onCancelDelete = {}, onDismiss = {},
            onSearch = { state = state.copy(query = it) },
            onClearSearch = { state = state.copy(query = "") },
        ) } }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasTestTag("book-note-search"))
        compose.onNodeWithTag("book-note-search").assertIsDisplayed().performTextReplacement("Not 30")
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("1. not: Not 30, sil"))
        compose.onNodeWithContentDescription("1. not: Not 30, sil").assertIsDisplayed().performClick()
        assertEquals("30", selectedId)
        compose.onNodeWithTag("book-notes-list").performScrollToNode(androidx.compose.ui.test.hasText("Temizle"))
        compose.onNodeWithText("Temizle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("30. not: Not 30, sil"))
        compose.onNodeWithContentDescription("30. not: Not 30, sil").assertIsDisplayed()
    }

    @Test fun `route prefills selected text without saving then clears it before manual reopen`() {
        val book = Book("book-id", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100,
            "hash-a", 1)
        val notes = MutableStateFlow<List<BookNote>>(emptyList())
        var addCalls = 0
        val repository = object : BookNoteRepository {
            override fun observe(bookId: String, contentSha256: String) = notes
            override suspend fun add(bookId: String, contentSha256: String, text: String) {
                addCalls++
                notes.value = notes.value + BookNote("new", text, 2)
            }
            override suspend fun addAtPdfPage(bookId: String, contentSha256: String, text: String, pageIndex: Int) = Unit
            override suspend fun update(bookId: String, contentSha256: String, id: String, text: String) = Unit
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
        }
        var open by mutableStateOf(true)
        var fromSelection by mutableStateOf(true)
        compose.setContent { YaprakTheme { if (open) BookNotesRoute(
            book, repository, onDismiss = { open = false },
            prefillText = if (fromSelection) "  Seçili Türkçe\n😀  " else null,
        ) } }
        compose.onNodeWithTag("book-note-draft")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,
                AnnotatedString("Seçili Türkçe\n😀")))
        assertEquals(0, addCalls)
        compose.onNodeWithTag("book-note-draft").performTextReplacement("Kaydedilmemiş düzenleme")
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { fromSelection = false; open = true }
        compose.onNodeWithTag("book-note-draft")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals(0, addCalls)
    }

    @Test fun `selected note draft needs explicit save and can be edited first`() {
        val book = Book("book-selection", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100,
            "hash-a", 1)
        val notes = MutableStateFlow<List<BookNote>>(emptyList())
        var addCalls = 0
        var savedText: String? = null
        val repository = object : BookNoteRepository {
            override fun observe(bookId: String, contentSha256: String) = notes
            override suspend fun add(bookId: String, contentSha256: String, text: String) {
                addCalls++
                savedText = text
                notes.value = notes.value + BookNote("new", text, 2)
            }
            override suspend fun addAtPdfPage(bookId: String, contentSha256: String, text: String, pageIndex: Int) = Unit
            override suspend fun update(bookId: String, contentSha256: String, id: String, text: String) = Unit
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
        }
        compose.setContent { YaprakTheme { BookNotesRoute(
            book, repository, onDismiss = {}, prefillText = "  Seçili metin  ",
        ) } }
        compose.onNodeWithTag("book-note-draft")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Seçili metin")))
        assertEquals(0, addCalls)
        compose.onNodeWithTag("book-note-draft").performTextReplacement("  Düzeltilmiş not  ")
        compose.onNodeWithText("Notu kaydet").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, addCalls)
        assertEquals("Düzeltilmiş not", savedText)
    }

    @Test fun `changing book uses separate note draft`() {
        val first = Book("book-one", "Bir", null, BookFormat.EPUB, "one.epub", "one", 100, "hash-one", 1)
        val second = Book("book-two", "İki", null, BookFormat.PDF, "two.pdf", "two", 100, "hash-two", 1)
        val notes = MutableStateFlow<List<BookNote>>(emptyList())
        val repository = object : BookNoteRepository {
            override fun observe(bookId: String, contentSha256: String) = notes
            override suspend fun add(bookId: String, contentSha256: String, text: String) = Unit
            override suspend fun addAtPdfPage(bookId: String, contentSha256: String, text: String, pageIndex: Int) = Unit
            override suspend fun update(bookId: String, contentSha256: String, id: String, text: String) = Unit
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
        }
        var book by mutableStateOf(first)
        compose.setContent { YaprakTheme { BookNotesRoute(book, repository, onDismiss = {}) } }
        compose.onNodeWithTag("book-note-draft").performTextReplacement("Birinci kitabın taslağı")
        compose.runOnIdle { book = second }
        compose.onNodeWithTag("book-note-draft")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun `route closing after edit cancellation leaves no pending state or edit draft`() {
        val book = Book("book-id", "Kitap", null, BookFormat.PDF, "book.pdf", "stored.pdf", 100,
            "hash-a", 1)
        val notes = MutableStateFlow<List<BookNote>>(listOf(entry))
        val repository = object : BookNoteRepository {
            override fun observe(bookId: String, contentSha256: String) = notes
            override suspend fun add(bookId: String, contentSha256: String, text: String) = Unit
            override suspend fun addAtPdfPage(bookId: String, contentSha256: String, text: String, pageIndex: Int) = Unit
            override suspend fun update(bookId: String, contentSha256: String, id: String, text: String) = Unit
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
        }
        var open by mutableStateOf(true)
        compose.setContent { YaprakTheme { if (open) BookNotesRoute(book, repository, onDismiss = { open = false }) } }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("1. not: İlk not, düzenle"))
        compose.onNodeWithContentDescription("1. not: İlk not, düzenle").performClick()
        compose.onNodeWithTag("book-note-edit-draft").performTextReplacement("Kaydedilmemiş değişiklik")
        compose.onNodeWithText("Vazgeç").performClick()
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { open = true }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(hasContentDescription("1. not: İlk not, düzenle"))
        compose.onNodeWithContentDescription("1. not: İlk not, düzenle").performClick()
        compose.onNodeWithTag("book-note-edit-draft")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("İlk not")))
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
    }

    @Test fun `PDF route captures page at save and navigates only when accepted`() {
        val book = Book("pdf-note-book", "PDF", null, BookFormat.PDF, "book.pdf", "stored.pdf", 100,
            "pdf-hash", 1)
        val notes = MutableStateFlow<List<BookNote>>(emptyList())
        var page: Int? = null
        var captureCalls = 0
        var pageAdds = 0
        var wholeBookAdds = 0
        var navigationAccepted = false
        var requestedPage: Int? = null
        var open by mutableStateOf(true)
        val repository = object : BookNoteRepository {
            override fun observe(bookId: String, contentSha256: String) = notes
            override suspend fun add(bookId: String, contentSha256: String, text: String) {
                wholeBookAdds++
                notes.value = notes.value + BookNote("whole", text, 1)
            }
            override suspend fun addAtPdfPage(bookId: String, contentSha256: String, text: String, pageIndex: Int) {
                pageAdds++
                notes.value = notes.value + BookNote("page", text, 2, pageIndex)
            }
            override suspend fun update(bookId: String, contentSha256: String, id: String, text: String) = Unit
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
        }
        compose.setContent { YaprakTheme { if (open) BookNotesRoute(book, repository,
            onDismiss = { open = false },
            capturePdfPage = { captureCalls++; page },
            onGoToPdfPage = { index -> requestedPage = index; navigationAccepted },
        ) } }
        compose.onNodeWithTag("book-note-draft").performTextReplacement("Bu sayfanın notu")
        compose.onNodeWithTag("book-note-save-at-page").performScrollTo().performClick()
        compose.onNodeWithTag("book-notes-list").performScrollToNode(
            androidx.compose.ui.test.hasText("Görüntülenen PDF sayfası doğrulanamadı. Not kaydedilmedi."))
        compose.onNodeWithText("Görüntülenen PDF sayfası doğrulanamadı. Not kaydedilmedi.").assertIsDisplayed()
        assertEquals(1, captureCalls)
        assertEquals(0, pageAdds)
        assertEquals(0, wholeBookAdds)
        compose.runOnIdle { page = 4 }
        compose.onNodeWithTag("book-note-save-at-page").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(2, captureCalls)
        assertEquals(1, pageAdds)
        assertEquals(0, wholeBookAdds)
        assertEquals(4, notes.value.single().pdfPageIndex)
        compose.onNodeWithTag("book-notes-list").performScrollToNode(
            hasContentDescription("1. not: PDF sayfası 5, sayfaya git"))
        compose.onNodeWithContentDescription("1. not: PDF sayfası 5, sayfaya git").performClick()
        assertEquals(4, requestedPage)
        assertTrue(open)
        compose.onNodeWithTag("book-notes-list").performScrollToNode(
            androidx.compose.ui.test.hasText("PDF sayfasına gidilemedi. Yeniden deneyebilirsin."))
        compose.onNodeWithText("PDF sayfasına gidilemedi. Yeniden deneyebilirsin.").assertIsDisplayed()
        compose.runOnIdle { navigationAccepted = true }
        compose.onNodeWithTag("book-notes-list").performScrollToNode(
            hasContentDescription("1. not: PDF sayfası 5, sayfaya git"))
        compose.onNodeWithContentDescription("1. not: PDF sayfası 5, sayfaya git").performClick()
        assertEquals(4, requestedPage)
        assertTrue(!open)
    }

    @Test fun `PDF route retains explicit whole book save and EPUB hides page action`() {
        val pdf = Book("book-note-pdf", "PDF", null, BookFormat.PDF, "book.pdf", "stored.pdf", 100,
            "pdf-hash", 1)
        val epub = pdf.copy(id = "book-note-epub", format = BookFormat.EPUB)
        val notes = MutableStateFlow<List<BookNote>>(emptyList())
        var wholeBookAdds = 0
        var pageAdds = 0
        val repository = object : BookNoteRepository {
            override fun observe(bookId: String, contentSha256: String) = notes
            override suspend fun add(bookId: String, contentSha256: String, text: String) {
                wholeBookAdds++
                notes.value = notes.value + BookNote("whole", text, 1)
            }
            override suspend fun addAtPdfPage(bookId: String, contentSha256: String, text: String, pageIndex: Int) {
                pageAdds++
            }
            override suspend fun update(bookId: String, contentSha256: String, id: String, text: String) = Unit
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
        }
        var book by mutableStateOf(pdf)
        compose.setContent { YaprakTheme { BookNotesRoute(book, repository, onDismiss = {},
            capturePdfPage = { 2 }, onGoToPdfPage = { true }) } }
        compose.onNodeWithTag("book-note-draft").performTextReplacement("Genel not")
        compose.onNodeWithText("Notu kaydet").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, wholeBookAdds)
        assertEquals(0, pageAdds)
        assertNull(notes.value.single().pdfPageIndex)
        compose.runOnIdle { book = epub }
        compose.onNodeWithTag("book-note-save-at-page").assertDoesNotExist()
    }
}

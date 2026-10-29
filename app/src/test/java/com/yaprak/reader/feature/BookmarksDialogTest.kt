package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.yaprak.reader.domain.bookmarks.*
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.bookmarks.*
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
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookmarksDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val target = BookmarkTarget("Bahçede bir sabah", ReadingLocation("locator", "one.xhtml"))
    private val entry = Bookmark("id", target, 1_700_000_000_000)

    @Test fun `same-title bookmarks expose distinct navigation and delete actions`() {
        val second = entry.copy(id = "id-2", target = target.copy(location = ReadingLocation("locator-2", "two.xhtml")))
        var deleteId: String? = null
        var destination: ReadingLocation? = null
        var dismissed = false
        compose.setContent {
            YaprakTheme { BookmarksDialog(BookmarksState(entries = listOf(entry, second), loading = false),
                {}, {}, { deleteId = it }, {}, {}, { destination = it; true }, { dismissed = true },
                {}, {}, {}, {}) }
        }
        compose.onNodeWithContentDescription("1. yer imi: Bahçede bir sabah, sil")
            .assertExists()
        compose.onNodeWithTag("bookmarks-list")
            .performScrollToNode(hasContentDescription("2. yer imi: Bahçede bir sabah, sil"))
        compose.onNodeWithContentDescription("2. yer imi: Bahçede bir sabah, sil")
            .performClick()
        assertEquals(second.id, deleteId)
        compose.onNodeWithContentDescription("2. yer imi: Bahçede bir sabah, konuma git")
            .performScrollTo().performClick()
        assertEquals(second.target.location, destination)
        assertTrue(dismissed)
    }

    @Test fun `large type list shows navigation failure and requires deletion confirmation`() {
        var state by mutableStateOf(BookmarksState(entries = listOf(entry), target = target, loading = false))
        var deleted = 0
        var dismissed = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme { BookmarksDialog(state, {}, {}, { state = state.copy(pendingDelete = entry) },
                { deleted++ }, { state = state.copy(pendingDelete = null) }, { false }, { dismissed++ },
                {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Yer imleri").assertFontScale(1.5f)
        compose.onNodeWithText("Bu konumu kaydet").assertIsNotEnabled()
        compose.onNodeWithText(target.label).performScrollTo().performClick()
        compose.onNodeWithText("Kayıtlı konuma gidilemedi.").assertIsDisplayed()
        assertEquals(0, dismissed)
        compose.runOnIdle {
            val active = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(active.width, active.height, Bitmap.Config.ARGB_8888)
            active.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "bookmarks-large-type.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("Sil").performScrollTo().performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Sil").performScrollTo().performClick()
        compose.onNodeWithText("Yer imini sil").performClick()
        assertEquals(1, deleted)
    }

    @Test fun `load failure offers retry and busy state disables mutations`() {
        var state by mutableStateOf(BookmarksState(target = target, loading = false, loadFailed = true))
        var reloads = 0
        compose.setContent { YaprakTheme { BookmarksDialog(state, { error("Cannot add") }, { reloads++ },
            {}, {}, {}, { error("Cannot navigate") }, { error("Cannot close while saving") },
            {}, {}, {}, {}) } }
        compose.onNodeWithText("Bu konumu kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Yeniden yükle").performClick()
        assertEquals(1, reloads)
        compose.runOnIdle { state = state.copy(entries = listOf(entry), loadFailed = false, busy = true) }
        compose.onNodeWithText("Bu konumu kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Kapat").assertIsNotEnabled()
        compose.onNodeWithText(target.label).assertIsNotEnabled()
        compose.onNodeWithText("Sil").assertIsNotEnabled()
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-mdpi")
    fun `landscape large type keeps long list and close reachable after navigation error`() {
        val entries = (1..20).map { entry.copy(id = "$it", target = target.copy(label = "Yer imi $it")) }
        var closed = false
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme { BookmarksDialog(BookmarksState(entries = entries, loading = false,
                message = "İşlem tamamlanamadı. Yeniden deneyebilirsin."), {}, {}, {}, {}, {}, { false }, { closed = true },
                {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Yer imleri").assertFontScale(1.5f)
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasText("Yer imi 20"))
        compose.onNodeWithText("Yer imi 20").performClick()
        compose.onNodeWithTag("bookmarks-list")
            .performScrollToNode(hasText("Kayıtlı konuma gidilemedi."))
        compose.onNodeWithText("Kayıtlı konuma gidilemedi.").assertIsDisplayed()
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertTrue(closed)
    }

    @Test fun `rename targets second same-title bookmark and keeps failed draft until cancel`() {
        val second = entry.copy(id = "id-2", target = target.copy(location = ReadingLocation("locator-2", "two.xhtml")))
        var state by mutableStateOf(BookmarksState(entries = listOf(entry, second), loading = false))
        var requestedId: String? = null
        var savedDraft: String? = null
        var cancellations = 0
        compose.setContent {
            YaprakTheme { BookmarksDialog(state, {}, {}, {}, {}, {}, { false }, {},
                { id ->
                    requestedId = id
                    val selected = state.entries.first { it.id == id }
                    state = state.copy(pendingRename = selected, renameDraft = selected.target.label, message = null)
                },
                { state = state.copy(renameDraft = it, message = null) },
                { savedDraft = state.renameDraft; state = state.copy(message = "İşlem tamamlanamadı. Yeniden deneyebilirsin.") },
                { cancellations++; state = state.copy(pendingRename = null, renameDraft = "", message = null) }) }
        }
        compose.onNodeWithContentDescription("1. yer imi: Bahçede bir sabah, düzenle").assertExists()
        compose.onNodeWithTag("bookmarks-list")
            .performScrollToNode(hasContentDescription("2. yer imi: Bahçede bir sabah, düzenle"))
        compose.onNodeWithContentDescription("2. yer imi: Bahçede bir sabah, düzenle")
            .performClick()
        assertEquals(second.id, requestedId)
        compose.onNodeWithTag("bookmark-rename-field").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(target.label)))

        compose.onNodeWithTag("bookmark-rename-field").performTextReplacement("   ")
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithTag("bookmark-rename-field").performTextReplacement("x".repeat(201))
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithTag("bookmark-rename-field").performTextReplacement("Yeni başlık")
        compose.onNodeWithText("Kaydet").assertIsEnabled().performClick()
        assertEquals("Yeni başlık", savedDraft)
        compose.onNodeWithText("İşlem tamamlanamadı. Yeniden deneyebilirsin.").assertIsDisplayed()
        compose.onNodeWithTag("bookmark-rename-field").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Yeni başlık")))

        compose.runOnIdle { state = state.copy(busy = true) }
        compose.onNodeWithTag("bookmark-rename-field").assertIsNotEnabled()
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(busy = false) }
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(1, cancellations)
        assertNull(state.pendingRename)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `rename dialog keeps save and cancel reachable at large font in landscape`() {
        var state by mutableStateOf(BookmarksState(entries = listOf(entry), loading = false))
        var saved = false
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme { BookmarksDialog(state, {}, {}, {}, {}, {}, { false }, {},
                { state = state.copy(pendingRename = entry, renameDraft = entry.target.label) },
                { state = state.copy(renameDraft = it) },
                { saved = true; state = state.copy(pendingRename = null) },
                { state = state.copy(pendingRename = null) }) }
        }
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasText("Düzenle"))
        compose.onNodeWithText("Düzenle").performClick()
        compose.onNodeWithText("Yer imi başlığını düzenle").assertFontScale(1.5f)
        compose.onNodeWithTag("bookmark-rename-field").performTextReplacement("Yeni başlık")
        compose.onNodeWithText("Kaydet").assertIsDisplayed().performClick()
        assertTrue(saved)
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasText("Düzenle"))
        compose.onNodeWithText("Düzenle").performClick()
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        assertNull(state.pendingRename)
    }

    @Test fun `empty bookmarks and search miss have distinct messages and clear restores entries`() {
        var state by mutableStateOf(BookmarksState(loading = false))
        compose.setContent {
            YaprakTheme { BookmarksDialog(state, {}, {}, {}, {}, {}, { false }, {},
                {}, {}, {}, {}, { state = state.copy(query = it) }, { state = state.copy(query = "") }) }
        }
        compose.onNodeWithText("Bu kitapta henüz yer imi yok.").assertIsDisplayed()
        compose.onNodeWithText("Aramanla eşleşen yer imi bulunamadı.").assertDoesNotExist()
        compose.onNodeWithTag("bookmark-search").assertExists()

        compose.runOnIdle { state = state.copy(entries = listOf(entry)) }
        compose.onNodeWithTag("bookmark-search").performTextReplacement("olmayan")
        compose.onNodeWithText("Aramanla eşleşen yer imi bulunamadı.").assertIsDisplayed()
        compose.onNodeWithText("Bu kitapta henüz yer imi yok.").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasText("Temizle"))
        compose.onNodeWithText("Temizle").performClick()
        compose.onNodeWithTag("bookmark-search")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(
            hasContentDescription("1. yer imi: Bahçede bir sabah, konuma git"))
        compose.onNodeWithContentDescription("1. yer imi: Bahçede bir sabah, konuma git")
            .assertExists()
    }

    @Test fun `filtered bookmark actions retain record identity`() {
        val first = entry.copy(id = "first", target = target.copy(label = "Orman"))
        val second = entry.copy(id = "second", target = target.copy(label = "Sahil",
            location = ReadingLocation("locator-2", "two.xhtml")))
        var state by mutableStateOf(BookmarksState(entries = listOf(first, second), loading = false))
        var editId: String? = null
        var deleteId: String? = null
        var destination: ReadingLocation? = null
        var dismissed = false
        compose.setContent {
            YaprakTheme { BookmarksDialog(state, {}, {}, { deleteId = it }, {}, {},
                { destination = it; true }, { dismissed = true }, { editId = it }, {}, {}, {},
                { state = state.copy(query = it) }, { state = state.copy(query = "") }) }
        }
        compose.onNodeWithTag("bookmark-search").performTextReplacement("sahil")
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(
            hasContentDescription("1. yer imi: Sahil, düzenle"))
        compose.onNodeWithContentDescription("1. yer imi: Sahil, düzenle").assertExists().performClick()
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(
            hasContentDescription("1. yer imi: Sahil, sil"))
        compose.onNodeWithContentDescription("1. yer imi: Sahil, sil").performClick()
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(
            hasContentDescription("1. yer imi: Sahil, konuma git"))
        compose.onNodeWithContentDescription("1. yer imi: Sahil, konuma git").performClick()
        assertEquals(second.id, editId)
        assertEquals(second.id, deleteId)
        assertEquals(second.target.location, destination)
        assertTrue(dismissed)
        compose.onNodeWithText("Orman").assertDoesNotExist()
    }

    @Test fun `title sort combines with search and actions retain bookmark identity`() {
        val deniz = entry.copy(id = "deniz", target = target.copy(label = "Deniz",
            location = ReadingLocation("deniz-locator", "deniz.xhtml")))
        val badem = entry.copy(id = "badem", target = target.copy(label = "Badem",
            location = ReadingLocation("badem-locator", "badem.xhtml")))
        val armut = entry.copy(id = "armut", target = target.copy(label = "Armut",
            location = ReadingLocation("armut-locator", "armut.xhtml")))
        var state by mutableStateOf(BookmarksState(entries = listOf(deniz, badem, armut), loading = false))
        var renameId: String? = null
        var deleteId: String? = null
        var destination: ReadingLocation? = null
        compose.setContent {
            YaprakTheme { BookmarksDialog(state, {}, {}, { deleteId = it }, {}, {},
                { destination = it; true }, {}, { renameId = it }, {}, {}, {},
                onSearch = { state = state.copy(query = it) },
                onSort = { state = state.copy(sort = it) }) }
        }

        compose.onNodeWithText("Sıralama: Kayıt sırası").assertExists()
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-sort"))
        compose.onNodeWithTag("bookmark-sort").performClick()
        compose.onNodeWithText("Başlık A–Z").performClick()
        assertEquals(BookmarkSort.TITLE_ASC, state.sort)
        assertEquals(listOf(armut, badem, deniz), state.visibleEntries)
        compose.onNodeWithTag("bookmark-search").performTextReplacement("de")
        assertEquals(listOf(badem, deniz), state.visibleEntries)

        compose.onNodeWithTag("bookmarks-list")
            .performScrollToNode(hasContentDescription("1. yer imi: Badem, düzenle"))
        compose.onNodeWithContentDescription("1. yer imi: Badem, düzenle").performClick()
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(
            hasContentDescription("1. yer imi: Badem, sil"))
        compose.onNodeWithContentDescription("1. yer imi: Badem, sil").performClick()
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(
            hasContentDescription("1. yer imi: Badem, konuma git"))
        compose.onNodeWithContentDescription("1. yer imi: Badem, konuma git").performClick()
        assertEquals(badem.id, renameId)
        assertEquals(badem.id, deleteId)
        assertEquals(badem.target.location, destination)
        assertEquals(listOf(deniz, badem, armut), state.entries)

        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-sort"))
        compose.onNodeWithTag("bookmark-sort").performClick()
        compose.onNodeWithText("Kayıt sırası").performClick()
        assertEquals(BookmarkSort.RECORD_ORDER, state.sort)
        assertEquals(listOf(deniz, badem), state.visibleEntries)
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `search and clear stay reachable with large font in short landscape`() {
        val entries = (1..12).map { entry.copy(id = "$it", target = target.copy(label = "Yer imi $it")) }
        var state by mutableStateOf(BookmarksState(entries = entries, loading = false))
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme { BookmarksDialog(state, {}, {}, {}, {}, {}, { false }, {},
                {}, {}, {}, {}, { state = state.copy(query = it) }, { state = state.copy(query = "") },
                { state = state.copy(sort = it) }) }
        }
        compose.onNodeWithText("Yer imleri").assertFontScale(1.5f)
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-sort"))
        compose.onNodeWithTag("bookmark-sort").assertIsDisplayed().performClick()
        compose.onNodeWithText("Başlık A–Z").performClick()
        assertEquals(BookmarkSort.TITLE_ASC, state.sort)
        compose.onNodeWithText("Sıralama: Başlık A–Z").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-search"))
        compose.onNodeWithTag("bookmark-search").performTextReplacement("Yer imi 12")
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(
            hasContentDescription("1. yer imi: Yer imi 12, konuma git"))
        compose.onNodeWithContentDescription("1. yer imi: Yer imi 12, konuma git").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasText("Temizle"))
        compose.onNodeWithText("Temizle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("bookmark-search")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Kapat").assertIsDisplayed()
    }
}

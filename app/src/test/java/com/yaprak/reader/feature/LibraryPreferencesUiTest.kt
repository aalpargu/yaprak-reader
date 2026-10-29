package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.library.LibraryFilter
import com.yaprak.reader.feature.library.LibraryLayout
import com.yaprak.reader.feature.library.LibraryPreferencesStatus
import com.yaprak.reader.feature.library.LibraryScreen
import com.yaprak.reader.feature.library.LibrarySort
import com.yaprak.reader.feature.library.LibraryState
import com.yaprak.reader.feature.library.sortBooks
import com.yaprak.reader.ui.theme.YaprakTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LibraryPreferencesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("saved-id", "Ardıç Günlüğü", "Deniz Yazar", BookFormat.EPUB,
        "ardic.epub", "saved-id.epub", 100, "abcd", 1)

    @Test fun `preference loading disables choices while search filter import and reading work`() {
        var query by mutableStateOf("")
        var filter by mutableStateOf(LibraryFilter.ALL)
        var imports = 0
        var opened: String? = null
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(),
                { imports++ }, {}, { opened = it }, query, { query = it },
                filter = filter, onFilter = { filter = it }, layout = LibraryLayout.LIST,
                preferenceStatus = LibraryPreferencesStatus.Loading)
        } }

        compose.onNodeWithText("Kitaplık tercihleri yükleniyor…").assertIsDisplayed()
        assertChoicesDisabled()
        compose.onNodeWithText("Kitaplıkta ara").performScrollTo()
            .assertIsEnabled().performTextReplacement("Ardıç")
        assertEquals("Ardıç", query)
        compose.onNodeWithContentDescription("Filtre: EPUB").performScrollTo()
            .assertIsEnabled().performClick()
        assertEquals(LibraryFilter.EPUB, filter)
        compose.onNodeWithText("Kitap ekle", useUnmergedTree = true).assertIsEnabled().performClick()
        assertEquals(1, imports)
        compose.onNodeWithContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi")
            .performScrollTo().performClick()
        compose.onNodeWithText("Oku").assertIsEnabled().performClick()
        assertEquals(book.id, opened)
    }

    @Test fun `preference load failure keeps book actions available and retries only preferences`() {
        var preferenceRetries = 0
        var bookRetries = 0
        var imports = 0
        var opened: String? = null
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(),
                { imports++ }, { bookRetries++ }, { opened = it }, "", {},
                layout = LibraryLayout.LIST,
                preferenceStatus = LibraryPreferencesStatus.LoadFailed,
                onRetryPreferences = { preferenceRetries++ })
        } }

        compose.onNodeWithText("Kitaplık tercihleri okunamadı.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Kitaplık tercihlerini yeniden dene").performClick()
        assertEquals(1, preferenceRetries)
        assertEquals(0, bookRetries)
        assertChoicesDisabled()
        compose.onNodeWithText("Kitap ekle", useUnmergedTree = true).assertIsEnabled().performClick()
        assertEquals(1, imports)
        compose.onNodeWithContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi")
            .performScrollTo().performClick()
        compose.onNodeWithText("Oku").assertIsEnabled().performClick()
        assertEquals(book.id, opened)
    }

    @Test fun `save failure explains session selection and offers retry without locking choices`() {
        var retries = 0
        var layout by mutableStateOf(LibraryLayout.LIST)
        var sort by mutableStateOf(LibrarySort.TITLE)
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(),
                {}, {}, {}, "", {}, layout = layout, onLayout = { layout = it },
                sort = sort, onSort = { sort = it },
                preferenceStatus = LibraryPreferencesStatus.SaveFailed,
                onRetryPreferences = { retries++ })
        } }

        compose.onNodeWithText("Görünüm ve sıralama kaydedilemedi. Bu seçim yalnız bu oturumda geçerli.")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Kitaplık tercihlerini yeniden dene").performClick()
        assertEquals(1, retries)
        compose.onNodeWithContentDescription("Görünüm: Liste").performScrollTo()
            .assertIsSelected().assertIsEnabled()
        compose.onNodeWithContentDescription("Görünüm: Kapaklar").performClick()
        assertEquals(LibraryLayout.GRID, layout)
        compose.onNodeWithText("Sırala: Başlık A–Z").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Son okunanlar").performClick()
        assertEquals(LibrarySort.LAST_READ, sort)
    }

    @Test fun `saving stays editable and sorting preserves keyed book cover requests and read identity`() {
        val other = book.copy(id = "second-id", title = "Çınar Defteri", authors = "Ada Yazar",
            storageName = "second-id.epub", sha256 = "second", importedAt = 2)
        val books = listOf(other, book)
        val covers = CopyOnWriteArrayList<String>()
        val loader: suspend (Book) -> Bitmap? = { covers += it.id; null }
        var status by mutableStateOf<LibraryPreferencesStatus>(LibraryPreferencesStatus.Ready)
        var sort by mutableStateOf(LibrarySort.RECENT)
        var layout by mutableStateOf(LibraryLayout.LIST)
        var opened: String? = null
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(sortBooks(books, sort)), false, SnackbarHostState(),
                {}, {}, { opened = it }, "", {}, sort = sort, onSort = { sort = it },
                layout = layout, onLayout = { layout = it }, coverLoader = loader,
                preferenceStatus = status)
        } }
        compose.onNodeWithContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi")
            .performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        compose.waitUntil(5_000) { covers.size == 2 }
        val requested = covers.toList()

        compose.runOnIdle { status = LibraryPreferencesStatus.Saving }
        compose.onNodeWithContentDescription("Görünüm: Liste").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("Görünüm: Kapaklar").assertIsEnabled()
        compose.onNodeWithText("Sırala: Son eklenenler").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Başlık A–Z").performClick()
        assertEquals(LibrarySort.TITLE, sort)
        compose.onNodeWithContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi")
            .performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(requested.sorted(), covers.toList().sorted())
        compose.onNodeWithContentDescription("Ardıç Günlüğü, Deniz Yazar, EPUB, Belirlenmedi").performClick()
        compose.onNodeWithText("Oku").performClick()
        assertEquals(book.id, opened)
        compose.onNodeWithContentDescription("Görünüm: Kapaklar").performScrollTo().performClick()
        assertEquals(LibraryLayout.GRID, layout)
    }

    private fun assertChoicesDisabled() {
        compose.onNodeWithContentDescription("Görünüm: Kapaklar").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Görünüm: Liste").assertIsNotEnabled()
        compose.onNodeWithText("Sırala: Son eklenenler").performScrollTo().assertIsNotEnabled()
    }
}

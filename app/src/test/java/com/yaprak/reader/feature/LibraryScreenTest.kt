package com.yaprak.reader.feature

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookRemovalResult
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.domain.library.ImportResult
import com.yaprak.reader.feature.library.LibraryScreen
import com.yaprak.reader.feature.library.LibraryState
import com.yaprak.reader.feature.library.LibrarySort
import com.yaprak.reader.feature.library.LibraryFilter
import com.yaprak.reader.feature.library.LibraryLayout
import com.yaprak.reader.feature.library.BookRemovalState
import com.yaprak.reader.feature.library.BookMetadataEditState
import com.yaprak.reader.feature.library.OpenLocalBooks
import com.yaprak.reader.feature.library.ImportNotice
import com.yaprak.reader.feature.library.ImportProgress
import com.yaprak.reader.feature.library.CreateLocalBook
import com.yaprak.reader.feature.library.CreateBackupDocument
import com.yaprak.reader.feature.library.OpenBackupDocument
import com.yaprak.reader.feature.library.searchBooks
import com.yaprak.reader.feature.library.filterBooks
import com.yaprak.reader.feature.library.suggestedExportFileName
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
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
class LibraryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `empty state offers real import action`() {
        var imports = 0
        compose.setContent { YaprakTheme { LibraryScreen(LibraryState.Ready(emptyList()), false, SnackbarHostState(), { imports++ }, {}, {}, "", {}) } }
        compose.onNodeWithText("Bir kitapla başlar.").assertIsDisplayed()
        compose.onNodeWithText("Kitap ekle").performClick()
        assertEquals(1, imports)
        capture("library-empty")
    }

    @Test fun `saved PDF opens metadata and read action sends persisted identity`() {
        var opened: String? = null
        val books = listOf(
            Book("1", "Bir Yaz Sabahı", "Deniz Yazar", BookFormat.EPUB, "sabah.epub", "1.epub", 250000, "1abc", 1),
            Book("2", "Yol Notları", null, BookFormat.PDF, "notlar.pdf", "2.pdf", 1250000, "2abc", 2, 12),
            Book("3", "Sessiz Bahçe", "Ada Deniz", BookFormat.EPUB, "bahce.epub", "3.epub", 100000, "3abc", 3),
        )
        compose.setContent { YaprakTheme { LibraryScreen(LibraryState.Ready(books), false, SnackbarHostState(), {}, {}, { opened = it }, "", {}) } }
        compose.onNodeWithText("3 KİTAP").assertIsDisplayed()
        capture("library-populated")
        compose.onNodeWithText("Yazar bilgisi yok").performClick()
        compose.onNodeWithText("Kitap bilgileri").assertIsDisplayed()
        compose.onNodeWithText("notlar.pdf").assertIsDisplayed()
        compose.onNodeWithText("Oku").performClick()
        assertEquals("2", opened)
    }

    @Test fun `continue reading opens the last read book and hides during search`() {
        var opened: String? = null
        var query by mutableStateOf("")
        val books = listOf(
            Book("unread", "New import", null, BookFormat.EPUB, "new.epub", "new.epub", 1, "u", 30),
            Book("read", "Last read", null, BookFormat.PDF, "read.pdf", "read.pdf", 1, "r", 10,
                pageCount = 2, lastReadAt = 200),
        )
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(books), false, SnackbarHostState(), {}, {},
                { opened = it }, query, { query = it })
        } }
        compose.onNodeWithText("Kaldığın yerden devam et").assertIsDisplayed()
        compose.onNodeWithText("Okumaya devam et").performClick()
        assertEquals("read", opened)
        compose.runOnIdle { query = "New" }
        compose.onNodeWithText("Kaldığın yerden devam et").assertDoesNotExist()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `library filters combine with search and empty state resets both`() {
        val books = listOf(
            Book("new", "Ardıç Yolculuğu", "Yazar EPUB Yeni", BookFormat.EPUB,
                "new.epub", "new.epub", 1, "new", 3),
            Book("pdf", "Kıyı Atlası", "Yazar PDF", BookFormat.PDF,
                "atlas.pdf", "atlas.pdf", 1, "pdf", 2, pageCount = 10, lastReadAt = 50),
            Book("read", "Gecenin Defteri", "Yazar EPUB Eski", BookFormat.EPUB,
                "read.epub", "read.epub", 1, "read", 1, lastReadAt = 20),
        )
        var query by mutableStateOf("")
        var filter by mutableStateOf(LibraryFilter.ALL)
        compose.setContent { YaprakTheme {
            LibraryScreen(
                state = LibraryState.Ready(filterBooks(searchBooks(books, query), filter), books.size, query),
                importing = false, snackbarHostState = SnackbarHostState(),
                onImport = {}, onRetry = {}, onRead = {}, query = query, onSearch = { query = it },
                filter = filter, onFilter = { filter = it },
            )
        } }

        compose.onNodeWithContentDescription("Filtre: PDF").performScrollTo().performClick()
        compose.onNodeWithText("1 / 3 KİTAP").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Yazar PDF").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Kaldığın yerden devam et").assertDoesNotExist()

        compose.onNodeWithText("Kitaplıkta ara").performScrollTo().performTextReplacement("Ardıç")
        compose.onNodeWithText("Bu filtrede kitap yok.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0 / 3 KİTAP").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription("Filtre: Konum kaydı yok").performScrollTo().performClick()
        compose.onNodeWithText("1 / 3 KİTAP").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Yazar EPUB Yeni").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Kitaplıkta ara").performScrollTo().performTextReplacement("Kıyı")
        compose.onNodeWithText("Bu filtrede kitap yok.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tüm kitapları göster").performScrollTo().performClick()
        compose.onNodeWithText("3 KİTAP").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Yazar PDF").performScrollTo().assertIsDisplayed()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `compact library layout keeps the same filtered books`() {
        val books = listOf(
            Book("epub", "Ardıç Günlüğü", "EPUB Yazarı", BookFormat.EPUB,
                "ardic.epub", "ardic.epub", 1, "epub", 2),
            Book("pdf", "Kıyı Atlası", "PDF Yazarı", BookFormat.PDF,
                "kiyi.pdf", "kiyi.pdf", 1, "pdf", 1, pageCount = 10),
        )
        var query by mutableStateOf("")
        var filter by mutableStateOf(LibraryFilter.ALL)
        var layout by mutableStateOf(LibraryLayout.GRID)
        compose.setContent { YaprakTheme {
            LibraryScreen(
                state = LibraryState.Ready(filterBooks(searchBooks(books, query), filter), books.size, query),
                importing = false, snackbarHostState = SnackbarHostState(),
                onImport = {}, onRetry = {}, onRead = {}, query = query, onSearch = { query = it },
                filter = filter, onFilter = { filter = it },
                layout = layout, onLayout = { layout = it },
            )
        } }

        compose.onNodeWithContentDescription("Görünüm: Kapaklar").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Görünüm: Liste").performScrollTo().performClick()
        assertEquals(LibraryLayout.LIST, layout)
        compose.onNodeWithContentDescription("Görünüm: Liste").assertIsSelected()
        compose.onNodeWithContentDescription("Ardıç Günlüğü, EPUB Yazarı, EPUB, Belirlenmedi").assertIsDisplayed()
        compose.onNodeWithContentDescription("Kıyı Atlası, PDF Yazarı, PDF, Belirlenmedi").assertIsDisplayed()

        compose.onNodeWithText("Kitaplıkta ara").performScrollTo().performTextReplacement("Kıyı")
        compose.onNodeWithContentDescription("Filtre: PDF").performScrollTo().performClick()
        compose.onNodeWithText("1 / 2 KİTAP").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Kıyı Atlası, PDF Yazarı, PDF, Belirlenmedi").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ardıç Günlüğü, EPUB Yazarı, EPUB, Belirlenmedi").assertDoesNotExist()

        compose.onNodeWithContentDescription("Görünüm: Kapaklar").performScrollTo().performClick()
        assertEquals(LibraryLayout.GRID, layout)
        compose.onNodeWithContentDescription("Görünüm: Kapaklar").assertIsSelected()
        compose.onNodeWithText("1 / 2 KİTAP").assertIsDisplayed()
        compose.onNodeWithText("PDF Yazarı").assertIsDisplayed()
        compose.onNodeWithText("EPUB Yazarı").assertDoesNotExist()
    }

    @Test fun `import cannot be started twice and remains reachable with large type`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                YaprakTheme { LibraryScreen(LibraryState.Ready(emptyList()), true, SnackbarHostState(), {}, {}, {}, "", {}) }
            }
        }
        compose.onNodeWithText("Kitap içe aktarılıyor…").assertIsDisplayed()
        compose.onNodeWithText("Kitap ekle").performScrollTo().assertIsNotEnabled()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-night-mdpi")
    fun `dark library renders and load error can be retried`() {
        var retries = 0
        compose.setContent { YaprakTheme { LibraryScreen(LibraryState.Error, false, SnackbarHostState(), {}, { retries++ }, {}, "", {}) } }
        compose.onNodeWithText("Yeniden dene").performClick()
        assertEquals(1, retries)
        capture("library-dark-error")
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `picker uses SAF with local documents and cancellation is harmless`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val contract = OpenLocalBooks()
        val intent = contract.createIntent(context, arrayOf("application/epub+zip", "application/pdf"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
        assertTrue(contract.parseResult(android.app.Activity.RESULT_CANCELED, null).isEmpty())
        val first = Uri.parse("content://books/first")
        val second = Uri.parse("content://books/second")
        val result = Intent().apply {
            clipData = ClipData.newUri(context.contentResolver, "books", first).apply {
                addItem(ClipData.Item(second))
            }
        }
        assertEquals(listOf(first, second), contract.parseResult(android.app.Activity.RESULT_OK, result))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `batch import progress and mixed result remain readable`() {
        var dismissed = false
        var importing by mutableStateOf(true)
        var notice by mutableStateOf<ImportNotice?>(null)
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(emptyList()), importing, SnackbarHostState(), {}, {}, {}, "", {},
                importProgress = if (importing) ImportProgress(2, 3) else null,
                importNotice = notice,
                onDismissImportNotice = { dismissed = true; notice = null })
        } }
        compose.onNodeWithText("2 / 3 dosya işlendi.").assertIsDisplayed()
        compose.runOnIdle {
            importing = false
            notice = ImportNotice(1, listOf(ImportResult.Added, ImportResult.Duplicate,
                ImportResult.Failed(ImportFailure.INVALID)), skipped = 1)
        }
        compose.onNodeWithText("Kitap ekleme sonucu").assertIsDisplayed()
        compose.onNodeWithText("Eklenen: 1").assertIsDisplayed()
        compose.onNodeWithText("Zaten bulunan: 1").assertIsDisplayed()
        compose.onNodeWithText("Eklenemeyen: 1").assertIsDisplayed()
        compose.onNodeWithText("1 dosya, tek seferde en fazla 100 dosya sınırı nedeniyle işlenmedi.").assertIsDisplayed()
        compose.onNodeWithText("Kapat").performClick()
        assertTrue(dismissed)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `pending import picker disables another selection`() {
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(emptyList()), false, SnackbarHostState(), {}, {}, {}, "", {},
                importPickerPending = true)
        } }
        compose.onNodeWithText("Kitap ekle").performScrollTo().assertIsNotEnabled()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `sorting menu is reachable on narrow library and reports choice`() {
        val book = Book("1", "Çınar", "Deniz", BookFormat.EPUB, "1.epub", "1.epub", 1, "1", 1)
        var sort by mutableStateOf(LibrarySort.RECENT)
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(), {}, {}, {}, "", {},
                sort = sort, onSort = { sort = it })
        } }
        compose.onNodeWithText("Sırala: Son eklenenler").performScrollTo()
        val firstBounds = compose.onNodeWithText("Sırala: Son eklenenler").fetchSemanticsNode().boundsInRoot
        val addBounds = compose.onNodeWithText("Kitap ekle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // Minimal ScrollTo can leave the sort action under the real floating Add button.
        // Bring its stable section to the top before injecting a native touch.
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("librarySearchControls")
        val reachable = compose.onNodeWithText("Sırala: Son eklenenler").fetchSemanticsNode().boundsInRoot
        assertTrue("Sort action must be above Add: before=$firstBounds add=$addBounds after=$reachable",
            reachable.bottom <= addBounds.top)
        capture("library-sort-narrow-cbz")
        compose.onNodeWithText("Sırala: Son eklenenler").assertIsDisplayed().performClick()
        compose.onNodeWithText("Başlık A–Z").performClick()
        assertEquals(LibrarySort.TITLE, sort)
        compose.onNodeWithText("Sırala: Başlık A–Z").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("librarySearchControls")
        compose.onNodeWithText("Sırala: Başlık A–Z").performClick()
        compose.onNodeWithText("Son okunanlar").performClick()
        assertEquals(LibrarySort.LAST_READ, sort)
        compose.onNodeWithText("Sırala: Son okunanlar").assertIsDisplayed()
    }

    @Test fun `export picker creates a local document with the selected book MIME`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((mime, name) in listOf("application/epub+zip" to "kitap.epub", "application/pdf" to "kitap.pdf")) {
            val contract = CreateLocalBook(mime)
            val intent = contract.createIntent(context, name)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals(mime, intent.type)
            assertEquals(name, intent.getStringExtra(Intent.EXTRA_TITLE))
            assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
            assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
            assertNull(contract.parseResult(android.app.Activity.RESULT_CANCELED, null))
        }
    }

    @Test fun `backup pickers use ZIP creation and unrestricted document opening`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val create = CreateBackupDocument().createIntent(context, "Yaprak-yedek.yaprak")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.action)
        assertEquals("application/zip", create.type)
        assertEquals("Yaprak-yedek.yaprak", create.getStringExtra(Intent.EXTRA_TITLE))
        val open = OpenBackupDocument().createIntent(context, arrayOf("*/*"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.action)
        assertEquals("*/*", open.type)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `narrow empty library confirms restore and nonempty library disables it`() {
        var restored = 0
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(emptyList()), false, SnackbarHostState(), {}, {}, {}, "", {},
                backupAvailable = true, onBackupRestore = { restored++ })
        } }
        compose.onNodeWithText("Yedekleme").performScrollTo().performClick()
        compose.onNodeWithText("Yedeği geri yükle").performScrollTo().performClick()
        assertEquals(0, restored)
        compose.onNodeWithText("Yedek geri yüklensin mi?").assertIsDisplayed()
        compose.onNodeWithText("Dosya seç").performClick()
        assertEquals(1, restored)
    }

    @Test fun `populated library cannot start restore`() {
        val book = Book("saved", "Başlık", "Deniz", BookFormat.EPUB, "1.epub", "1.epub", 1, "hash", 1)
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(), {}, {}, {}, "", {},
                backupAvailable = true, onBackupRestore = { fail("Restore must be disabled") })
        } }
        compose.onNodeWithText("Yedekleme").performScrollTo().performClick()
        compose.onNodeWithText("Yedeği geri yükle").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `suggested export name strips unsafe characters and follows stored format`() {
        val base = Book("id", "Başlık", null, BookFormat.EPUB, " ../Kötü\\ad:ı.epub ",
            "id.epub", 1, "hash", 1)
        assertEquals("Kötü_ad_ı.epub", suggestedExportFileName(base))
        assertEquals("Kötü_ad_ı.pdf", suggestedExportFileName(base.copy(format = BookFormat.PDF)))
        assertEquals("Yaprak-kitap.pdf", suggestedExportFileName(base.copy(format = BookFormat.PDF, originalName = "../")))
        assertEquals("kitap.pdf", suggestedExportFileName(base.copy(format = BookFormat.PDF, originalName = "kitap.EPUB")))
    }

    @Test fun `book details explain file-only export and pass selected book`() {
        val book = Book("saved-id", "Başlık", "Deniz", BookFormat.PDF, "kitap.pdf", "id.pdf", 42, "hash", 1, 3)
        var selected: Book? = null
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(), {}, {}, {}, "", {},
                onExport = { selected = it })
        } }
        compose.onNodeWithText("Deniz").performClick()
        compose.onNodeWithText("Yalnız kitap dosyası dışa aktarılır. Notlar ve okuma konumu bu kopyada yoktur.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dosyayı dışa aktar").performScrollTo().performClick()
        assertEquals(book, selected)
    }

    @Test
    fun `book metadata draft validates trims retries and closes only after save`() {
        val book = Book("saved-id", "Başlık", "Deniz", BookFormat.PDF, "kitap.pdf", "id.pdf", 42, "hash", 1, 3)
        var displayedBook by mutableStateOf(book)
        var editState by mutableStateOf<BookMetadataEditState>(BookMetadataEditState.Idle)
        val saves = mutableListOf<Triple<Book, String, String?>>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(displayedBook)), false, SnackbarHostState(), {}, {}, {}, "", {},
                metadataEdit = editState,
                onSaveMetadata = { selected, title, authors ->
                    saves += Triple(selected, title, authors)
                    editState = BookMetadataEditState.Saving(selected.id)
                },
                onClearMetadataState = { id ->
                    if (editState is BookMetadataEditState.Failed || editState is BookMetadataEditState.Saved) {
                        if (id == book.id) editState = BookMetadataEditState.Idle
                    }
                })
        } }
        compose.onNodeWithText("Deniz").performClick()
        compose.onNodeWithText("Başlığı ve yazarı düzenle").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Kitap başlığı alanı").performTextReplacement("   ")
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Kitap başlığı alanı").performTextReplacement("  Yeni Başlık  ")
        compose.onNodeWithContentDescription("Kitap yazarı alanı").performTextReplacement("   ")
        compose.onNodeWithText("Kaydet").performClick()
        assertEquals(listOf(Triple(book, "Yeni Başlık", null)), saves)
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()

        compose.runOnIdle { editState = BookMetadataEditState.Failed(book.id) }
        compose.onNodeWithText("Kitap bilgileri kaydedilemedi. Taslağı düzelterek yeniden dene.")
            .assertIsDisplayed()
        compose.onNodeWithText("Yeni Başlık", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Kaydet").performClick()
        assertEquals(2, saves.size)
        // A new process restores the draft, while its new ViewModel starts idle.
        compose.runOnIdle { editState = BookMetadataEditState.Idle }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Yeni Başlık", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Kaydet").performClick()
        assertEquals(3, saves.size)
        // Activity recreation keeps an in-flight ViewModel request locked.
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.runOnIdle {
            displayedBook = book.copy(title = "Yeni Başlık", authors = null)
            editState = BookMetadataEditState.Saved(book.id, 1)
        }
        compose.onNodeWithText("Kitap bilgilerini düzenle").assertDoesNotExist()
        compose.onNodeWithText("Kitap bilgileri").assertIsDisplayed()
    }

    @Test fun `another export cannot start while destination selection is pending`() {
        val book = Book("saved-id", "Başlık", "Deniz", BookFormat.PDF, "kitap.pdf", "id.pdf", 42, "hash", 1, 3)
        var exporting by mutableStateOf(false)
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(), {}, {}, {}, "", {},
                exporting = exporting, onExport = { fail("Disabled export started") })
        } }
        compose.onNodeWithText("Deniz").performClick()
        compose.runOnIdle { exporting = true }
        compose.onNodeWithText("Dosyayı dışa aktar").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Kapat").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Deniz"))
        compose.onNodeWithText("Deniz").performClick()
        compose.onNodeWithText("Kitap bilgileri").assertDoesNotExist()
    }

    @Test fun `book removal needs confirmation and keeps retry visible after a failure`() {
        val book = Book("saved-id", "Başlık", "Deniz", BookFormat.EPUB, "kitap.epub", "id.epub", 42, "hash", 1)
        var removals = 0
        var removal by mutableStateOf<BookRemovalState>(BookRemovalState.Idle)
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(), {}, {}, {}, "", {},
                removal = removal,
                onRemove = {
                    removals++
                    removal = BookRemovalState.Error(it.id, BookRemovalResult.Failed)
                },
                onClearRemovalError = { removal = BookRemovalState.Idle })
        } }
        compose.onNodeWithText("Deniz").performClick()
        compose.onNodeWithText("Kitabı kaldır").performScrollTo().performClick()
        assertEquals(0, removals)
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(0, removals)
        compose.onNodeWithText("Kitabı kaldır").performScrollTo().performClick()
        compose.onNodeWithText("Evet, kaldır").performClick()
        assertEquals(1, removals)
        compose.onNodeWithText("Kitap kaldırılamadı. Kayıtları korunuyor; yeniden dene.").assertIsDisplayed()
        compose.onNodeWithText("Evet, kaldır").performClick()
        assertEquals(2, removals)
    }

    @Test fun `EPUB read action sends the persisted book identity`() {
        val book = Book("saved-id", "Bir Yaz Sabahı", "Deniz", BookFormat.EPUB, "book.epub", "saved-id.epub", 100, "1abc", 1)
        var opened: String? = null
        compose.setContent { YaprakTheme { LibraryScreen(LibraryState.Ready(listOf(book)), false, SnackbarHostState(), {}, {}, { opened = it }, "", {}) } }
        compose.onNodeWithText("Deniz").performClick()
        compose.onNodeWithText("Oku").performClick()
        assertEquals(book.id, opened)
    }

    @Test fun `search empty result keeps import available and clearing restores readable books`() {
        val book = Book("saved", "İstanbul", "Deniz", BookFormat.EPUB, "1.epub", "1.epub", 1, "1", 1)
        var query by mutableStateOf("")
        var imports = 0
        var opened: String? = null
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(searchBooks(listOf(book), query), 1, query), false,
                SnackbarHostState(), { imports++ }, {}, { opened = it }, query, { query = it })
        } }
        compose.onNodeWithText("Kitaplıkta ara").performTextReplacement("bulunmayan")
        compose.onNodeWithText("Kitaplıkta ara").performImeAction()
        compose.onNodeWithText("Eşleşen kitap yok.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0 / 1 KİTAP").assertIsDisplayed()
        capture("library-search-empty")
        compose.onNodeWithText("Kitap ekle", useUnmergedTree = true).assertIsDisplayed().performClick()
        assertEquals(1, imports)
        compose.onNodeWithText("Temizle").performScrollTo().performClick()
        compose.onNodeWithText("1 KİTAP").assertIsDisplayed()
        compose.onNodeWithText("Deniz").performScrollTo().performClick()
        compose.onNodeWithText("Oku").performClick()
        assertEquals("saved", opened)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-mdpi")
    fun `landscape large type can clear search and reach book details`() {
        val book = Book("saved", "İstanbul", "Deniz", BookFormat.EPUB, "1.epub", "1.epub", 1, "1", 1)
        var query by mutableStateOf("bulunmayan")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                YaprakTheme { LibraryScreen(LibraryState.Ready(searchBooks(listOf(book), query), 1, query),
                    false, SnackbarHostState(), {}, {}, {}, query, { query = it }) }
            }
        }
        compose.onNodeWithText("Temizle").performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Deniz"))
        compose.onNodeWithText("Deniz").performClick()
        compose.onNodeWithText("Kitap bilgileri").assertIsDisplayed()
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
    }

    private fun capture(name: String) {
        val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
        // Draw the real Compose view tree using Robolectric's native Canvas; PixelCopy's
        // Android display callback is not reliable in this host environment.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}


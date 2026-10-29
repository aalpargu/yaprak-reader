package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookImporter
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.domain.library.ImportResult
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.feature.library.ImportNotice
import com.yaprak.reader.feature.library.ImportProgress
import com.yaprak.reader.feature.library.LibraryLayout
import com.yaprak.reader.feature.library.LibraryRoute
import com.yaprak.reader.feature.library.LibraryScreen
import com.yaprak.reader.feature.library.LibraryState
import com.yaprak.reader.feature.library.LibraryViewModel
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportStopUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val owner = ViewModelStore()

    @After fun clearModels() {
        compose.runOnIdle { owner.clear() }
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun `batch stop calls its action once and waits with disabled controls`() {
        var requests = 0
        var progress by mutableStateOf(ImportProgress(1, 3))
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(emptyList()), true, SnackbarHostState(),
                { error("Import must stay locked") }, {}, {}, "", {},
                importProgress = progress,
                onStopRemainingImports = {
                    requests++
                    progress = progress.copy(stopRequested = true)
                })
        } }
        compose.onNodeWithText("1 / 3 dosya işlendi.").assertIsDisplayed()
        compose.onNodeWithText(stopAction).assertIsEnabled().performClick()
        compose.onNodeWithText(stopAction).assertIsNotEnabled().performClick()
        compose.onNodeWithText(waiting).assertIsDisplayed()
        compose.onNodeWithText("Kitap ekle").performScrollTo().assertIsNotEnabled()
        assertEquals(1, requests)
    }

    @Test fun `single complete and unavailable stop actions stay hidden`() {
        var progress by mutableStateOf<ImportProgress?>(ImportProgress(0, 1))
        var callbackAvailable by mutableStateOf(true)
        var importing by mutableStateOf(true)
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(emptyList()), importing, SnackbarHostState(),
                {}, {}, {}, "", {}, importProgress = progress,
                onStopRemainingImports = if (callbackAvailable) ({ error("Hidden action invoked") }) else null)
        } }
        compose.onNodeWithText(stopAction).assertDoesNotExist()
        compose.runOnIdle { progress = ImportProgress(0, 2); callbackAvailable = false }
        compose.onNodeWithText(stopAction).assertDoesNotExist()
        compose.runOnIdle { progress = ImportProgress(2, 2); callbackAvailable = true }
        compose.onNodeWithText(stopAction).assertDoesNotExist()
        compose.runOnIdle { progress = null }
        compose.onNodeWithText(stopAction).assertDoesNotExist()
        compose.runOnIdle { importing = false; progress = ImportProgress(0, 2) }
        compose.onNodeWithText(stopAction).assertDoesNotExist()
    }

    @Test fun `waiting keeps reading backups and new imports locked`() {
        val book = Book("saved", "Ardıç Günlüğü", "Deniz", BookFormat.EPUB,
            "ardic.epub", "saved.epub", 100, "hash", 1, lastReadAt = 200)
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(listOf(book)), true, SnackbarHostState(),
                { error("New import must stay locked") }, {}, { error("Reading must stay locked") }, "", {},
                layout = LibraryLayout.LIST, backupAvailable = true,
                onBackupCreate = { error("Backup must stay locked") },
                onBackupRestore = { error("Restore must stay locked") },
                importProgress = ImportProgress(0, 2, stopRequested = true),
                onStopRemainingImports = { error("Stop already requested") })
        } }
        compose.onNodeWithText("Yedekleme").performClick()
        compose.onNodeWithText("Tam yedek oluştur").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Yedeği geri yükle").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Okumaya devam et").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(stopAction).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Kitap ekle").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasContentDescription("Ardıç Günlüğü, Deniz, EPUB, Belirlenmedi"))
        compose.onNodeWithContentDescription("Ardıç Günlüğü, Deniz, EPUB, Belirlenmedi").performClick()
        compose.onNodeWithText("Kitap bilgileri").assertDoesNotExist()
    }

    @Test fun `summary separates stopped files selection cap and real result counts`() {
        var notice by mutableStateOf<ImportNotice?>(ImportNotice(1,
            listOf(ImportResult.Added, ImportResult.Duplicate, ImportResult.Failed(ImportFailure.IO)),
            skipped = 5, stopped = 97))
        var dismissals = 0
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(emptyList()), false, SnackbarHostState(),
                {}, {}, {}, "", {}, importNotice = notice,
                onDismissImportNotice = { dismissals++; notice = null })
        } }
        listOf("Eklenen: 1", "Zaten bulunan: 1", "Eklenemeyen: 1",
            "Durdurulduğu için işlenmeyen: 97", capFive,
            "1 dosya: Dosya kaydedilemedi. Boş alanı ve dosyanın erişilebilirliğini kontrol et.")
            .forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("Kapat").performScrollTo().performClick()
        compose.onNodeWithText(summaryTitle).assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    @Test fun `screen does not treat a single result as a batch summary`() {
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(emptyList()), false, SnackbarHostState(),
                {}, {}, {}, "", {}, importNotice = ImportNotice(1, listOf(ImportResult.Added), 0))
        } }
        compose.onNodeWithText(summaryTitle).assertDoesNotExist()
    }

    @Test fun `real route renders a stop before the first document without a success snackbar`() {
        var imports = 0
        val model = queuedModel(object : BookImporter {
            override suspend fun import(documentUri: String): ImportResult { imports++; return ImportResult.Added }
        }) { model, _ ->
            assertTrue(model.prepareImport())
            model.onImportDocumentResult(listOf("first", "second"))
            model.requestStopRemainingImports()
        }
        assertEquals(0, imports)
        assertEquals(ImportNotice(1, emptyList(), 0, stopped = 2), model.notice.value)
        compose.setContent { YaprakTheme { LibraryRoute(model) } }
        compose.onNodeWithText(summaryTitle).assertIsDisplayed()
        compose.onNodeWithText("Eklenen: 0").assertIsDisplayed()
        compose.onNodeWithText("Eklenemeyen: 0").assertIsDisplayed()
        compose.onNodeWithText("Durdurulduğu için işlenmeyen: 2").assertIsDisplayed()
        compose.onNodeWithText(singleSuccess).assertDoesNotExist()
        compose.onNodeWithText("Kapat").performScrollTo().performClick()
        compose.onNodeWithText(summaryTitle).assertDoesNotExist()
        assertNull(model.notice.value)
    }

    @Test fun `real route stops via the native action and summarizes one completed document`() {
        val finishCurrent = CompletableDeferred<ImportResult>()
        val imports = mutableListOf<String>()
        val model = queuedModel(object : BookImporter {
            override suspend fun import(documentUri: String): ImportResult {
                imports += documentUri
                return finishCurrent.await()
            }
        }) { model, scheduler ->
            assertTrue(model.prepareImport())
            model.onImportDocumentResult(listOf("first", "second"))
            scheduler.runCurrent()
        }
        compose.setContent { YaprakTheme { LibraryRoute(model) } }
        compose.onNodeWithText(stopAction).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(stopAction).assertIsNotEnabled()
        compose.onNodeWithText(waiting).assertIsDisplayed()
        assertTrue(model.importing.value)
        assertEquals(listOf("first"), imports)
        compose.runOnIdle { finishCurrent.complete(ImportResult.Added) }
        compose.onNodeWithText(summaryTitle).assertIsDisplayed()
        compose.onNodeWithText("Eklenen: 1").assertIsDisplayed()
        compose.onNodeWithText("Durdurulduğu için işlenmeyen: 1").assertIsDisplayed()
        compose.onNodeWithText(singleSuccess).assertDoesNotExist()
        assertEquals(listOf("first"), imports)
        assertFalse(model.importing.value)
        compose.onNodeWithText("Kapat").performScrollTo().performClick()
        assertNull(model.notice.value)
    }

    @Test fun `real route preserves the existing single document snackbar`() {
        val model = queuedModel(object : BookImporter {
            override suspend fun import(documentUri: String) = ImportResult.Added
        }) { model, _ -> model.importBook("single") }
        compose.setContent { YaprakTheme { LibraryRoute(model) } }
        compose.onNodeWithText(singleSuccess).assertIsDisplayed()
        compose.onNodeWithText(summaryTitle).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w320dp-h480dp-mdpi")
    fun `double font narrow short screen reads stop waiting counts and close`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        var importing by mutableStateOf(true)
        var progress by mutableStateOf(ImportProgress(1, 100))
        var notice by mutableStateOf<ImportNotice?>(null)
        var requests = 0
        compose.setContent { YaprakTheme {
            LibraryScreen(LibraryState.Ready(emptyList()), importing, SnackbarHostState(),
                {}, {}, {}, "", {}, importProgress = if (importing) progress else null,
                onStopRemainingImports = { requests++; progress = progress.copy(stopRequested = true) },
                importNotice = notice, onDismissImportNotice = { notice = null })
        } }
        val stop = compose.onNodeWithText(stopAction)
        stop.performScrollTo().assertIsDisplayed().assertIsEnabled()
        readable(compose.onNodeWithText(stopAction, useUnmergedTree = true).assertFontScale(2f))
        stop.performClick()
        stop.assertIsNotEnabled()
        compose.onNodeWithText(waiting).performScrollTo().assertIsDisplayed().assertFontScale(2f)
        readable(compose.onNodeWithText(waiting))
        capture("import-stop-waiting-large-font")
        assertEquals(1, requests)
        compose.runOnIdle {
            importing = false
            notice = ImportNotice(1, listOf(ImportResult.Added), skipped = 5, stopped = 99)
        }
        listOf(summaryTitle, "Eklenen: 1", "Zaten bulunan: 0", "Eklenemeyen: 0",
            "Durdurulduğu için işlenmeyen: 99", capFive).forEach { label ->
            val node = compose.onNodeWithText(label)
            node.performScrollTo().assertIsDisplayed().assertFontScale(2f)
            readable(node)
        }
        capture("import-stop-summary-large-font", dialog = true)
        compose.onNodeWithText("Kapat").performScrollTo().assertIsDisplayed()
        readable(compose.onNodeWithText("Kapat", useUnmergedTree = true).assertFontScale(2f))
        compose.onNodeWithText("Kapat").performClick()
        compose.onNodeWithText(summaryTitle).assertDoesNotExist()
    }

    /** Queue the import so the zero-result edge is produced by the real ViewModel API. */
    private fun queuedModel(importer: BookImporter,
        prepare: (LibraryViewModel, TestCoroutineScheduler) -> Unit): LibraryViewModel {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        return try {
            LibraryViewModel(Repository(), importer, SavedStateHandle(), Dispatchers.Unconfined)
                .also { model ->
                    owner.put("library", model)
                    prepare(model, scheduler)
                    scheduler.runCurrent()
                }
        } finally { Dispatchers.resetMain() }
    }

    private fun readable(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.lineCount > 0 && layout.size.width > 0)
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
        compose.runOnIdle {
            val view = if (dialog) requireNotNull(ShadowDialog.getLatestDialog().window).decorView
                else compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private class Repository : LibraryRepository {
        private val books = MutableStateFlow(emptyList<Book>())
        override fun observeBooks() = books
        override suspend fun findById(id: String): Book? = null
        override suspend fun findByHash(sha256: String): Book? = null
        override suspend fun storedFileNames() = emptySet<String>()
        override suspend fun add(book: Book) { error("UI test must not write books") }
        override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?): Boolean =
            error("UI test must not write metadata")
    }

    private companion object {
        const val stopAction = "Kalan dosyaları durdur"
        const val waiting = "Geçerli dosya tamamlandıktan sonra duracak."
        const val summaryTitle = "Kitap ekleme sonucu"
        const val singleSuccess = "Kitap kitaplığına eklendi."
        const val capFive = "5 dosya, tek seferde en fazla 100 dosya sınırı nedeniyle işlenmedi."
    }
}

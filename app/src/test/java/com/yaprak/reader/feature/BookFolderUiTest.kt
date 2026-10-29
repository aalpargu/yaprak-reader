package com.yaprak.reader.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.library.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
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
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookFolderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val uri = "content://folder.provider/tree/primary%3ABooks"
    private val saved = BookFolderSnapshot(UUID.randomUUID().toString(), SavedBookFolder(uri, "Ders kitapları 🌿"))
    private val ready = BookFolderState(available = true, snapshot = saved)

    @Test fun `native double type panel preserves name complete actions help and disposed revision guards`() {
        largeFont()
        var state by mutableStateOf(ready)
        var scans = 0; var forgets = 0
        compose.setContent { YaprakTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BookFolderPanel(state, true, { assertEquals(saved, it); scans++ }, { forgets++ }, {}, {})
        } } }
        compose.onNodeWithText(text(R.string.book_folder_selected, "Ders kitapları 🌿")).performScrollTo().assertIsDisplayed()
        readable(text(R.string.book_folder_selected, "Ders kitapları 🌿"))
        val scan = action("book-folder-scan")
        readable(text(R.string.book_folder_scan))
        compose.onNodeWithTag("book-folder-scan").performClick()
        val forget = action("book-folder-forget")
        readable(text(R.string.book_folder_forget))
        compose.onNodeWithText(text(R.string.book_folder_help)).performScrollTo().assertIsDisplayed()
        readable(text(R.string.book_folder_help)); capture("book-folder-panel-double-type")
        compose.runOnIdle { state = ready.copy(snapshot = saved.copy(revision = UUID.randomUUID().toString())) }
        compose.runOnIdle { scan(); forget() }
        assertEquals(1, scans); assertEquals(0, forgets)
        compose.runOnIdle { state = BookFolderState() }
        compose.runOnIdle { scan(); forget() }
        assertEquals(1, scans); assertEquals(0, forgets)
    }

    @Test fun `unknown source shows explicit retry while busy read proof exposes cancellation only`() {
        var state by mutableStateOf(ready.copy(snapshot = null, failure = BookFolderFailure.RECOVERY))
        var retries = 0; var cancels = 0
        compose.setContent { YaprakTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BookFolderPanel(state, true, { fail("No scan") }, { fail("No forget") }, { retries++ }, { cancels++ })
        } } }
        compose.onNodeWithText(text(R.string.book_folder_load_failed)).assertIsDisplayed()
        compose.onNodeWithTag("book-folder-scan").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.book_folder_retry)).performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state = ready.copy(resolving = true) }
        compose.onNodeWithText(text(R.string.book_folder_checking)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.book_folder_cancel)).performClick()
        assertEquals(1, cancels)
        compose.runOnIdle { state = ready.copy(saving = true) }
        compose.onNodeWithText(text(R.string.book_folder_saving)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.book_folder_cancel)).assertDoesNotExist()
        compose.onNodeWithTag("book-folder-forget").assertDoesNotExist()
    }

    @Test fun `native double type preview remember does not import and freezes native actions during save`() {
        largeFont()
        val candidate = BookCandidate("one", "Ders.pdf", "content://folder.provider/document/book", 100, BookFormat.PDF)
        var discovery by mutableStateOf<DiscoveryState>(DiscoveryState.Preview("scan", listOf(candidate), 1,
            selectedIds = setOf("one"), treeUri = uri))
        var folder by mutableStateOf(ready)
        var remembers = 0; var imports = 0; var closes = 0
        compose.setContent { YaprakTheme { BookDiscoveryDialog(discovery, { _, _ -> }, { _, _, _ -> }, {}, {},
            { imports++ }, { closes++ }, {}, folder, { id, tree, expected ->
                assertEquals("scan", id); assertEquals(uri, tree); assertEquals(saved, expected)
                remembers++; folder = ready.copy(saving = true)
            }) } }
        compose.onNodeWithTag("book-folder-remember").performScrollTo().assertIsDisplayed()
        readable(text(R.string.book_folder_remember))
        val remember = compose.onNodeWithTag("book-folder-remember").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val import = compose.onNodeWithText(text(R.string.discovery_import_selected, 1)).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        readable(text(R.string.discovery_import_selected, 1))
        capture("book-folder-preview-double-type", dialog = true)
        compose.onNodeWithTag("book-folder-remember").performClick()
        compose.onNodeWithTag("book-folder-remember").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.discovery_import_selected, 1)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.cancel_remove)).assertIsNotEnabled()
        compose.runOnIdle { remember(); import() }
        assertEquals(1, remembers); assertEquals(0, imports); assertEquals(0, closes)
        compose.runOnIdle { folder = ready; discovery = DiscoveryState.Idle }
        compose.runOnIdle { remember(); import() }
        assertEquals(1, remembers); assertEquals(0, imports)
    }

    @Test fun `real library route pause invalidates late proof and resume accepts only fresh explicit scan`() {
        val owner = ViewModelStore()
        val scans = mutableListOf<String>()
        var imports = 0
        val release = CompletableDeferred<Unit>()
        val folders = object : BookFolderRepository {
            var late = true
            override fun observe(): Flow<BookFolderSnapshot> = MutableStateFlow(saved)
            override suspend fun save(expected: BookFolderSnapshot, selectedTreeUri: String): BookFolderWrite = error("No save")
            override suspend fun forget(expected: BookFolderSnapshot): BookFolderWrite = error("No forget")
            override suspend fun retryCleanup() = Unit
            override suspend fun resolveForScan(expected: BookFolderSnapshot): String {
                if (late) { late = false; withContext(NonCancellable) { release.await() } }
                return uri
            }
        }
        val library = object : LibraryRepository, BookImporter {
            override fun observeBooks(): Flow<List<Book>> = MutableStateFlow(emptyList())
            override suspend fun findById(id: String): Book? = null
            override suspend fun findByHash(sha256: String): Book? = null
            override suspend fun storedFileNames() = emptySet<String>()
            override suspend fun add(book: Book) = error("No writes")
            override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?): Boolean = error("No writes")
            override suspend fun import(documentUri: String): ImportResult { imports++; return ImportResult.Added }
        }
        lateinit var model: LibraryViewModel
        compose.runOnUiThread {
            model = LibraryViewModel(library, library, SavedStateHandle(), discovery = object : BookDiscovery {
                override suspend fun scan(treeUri: String, onProgress: (DiscoveryProgress) -> Unit): DiscoveryResult {
                    scans += treeUri; return DiscoveryResult.Complete(emptyList(), 0)
                }
            }, bookFolders = folders)
            owner.put("model", model)
        }
        try {
            compose.setContent { YaprakTheme { LibraryRoute(model) } }
            compose.waitUntil { model.bookFolderState.value.ready }
            compose.onNodeWithTag("book-folder-scan").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil { model.bookFolderState.value.resolving }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            release.complete(Unit); compose.waitForIdle()
            assertTrue(scans.isEmpty()); assertEquals(DiscoveryState.Idle, model.discoveryState.value)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertTrue(scans.isEmpty())
            compose.onNodeWithTag("book-folder-scan").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil { model.discoveryState.value is DiscoveryState.Preview }
            assertEquals(listOf(uri), scans)
            assertEquals(uri, (model.discoveryState.value as DiscoveryState.Preview).treeUri)
            assertEquals(0, imports)
        } finally { release.complete(Unit); compose.runOnUiThread { owner.clear() } }
    }

    @Test fun `native double type opening preference label status and old callback retain explicit next-opening intent`() {
        largeFont()
        var state by mutableStateOf(ready)
        var changes = 0
        compose.setContent { YaprakTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BookFolderPanel(state, true, {}, {}, {}, {}, { expected, enabled ->
                assertEquals(state.snapshot, expected)
                changes++; state = ready.copy(snapshot = saved.copy(revision = UUID.randomUUID().toString(), automaticScan = enabled))
            })
        } } }
        val old = action("book-folder-automatic")
        readable(text(R.string.book_folder_auto_turn_on))
        compose.onNodeWithTag("book-folder-automatic").performClick()
        compose.onNodeWithTag("book-folder-automatic").performScrollTo().assertIsDisplayed()
        readable(text(R.string.book_folder_auto_turn_off))
        compose.onNodeWithText(text(R.string.book_folder_auto_on)).performScrollTo().assertIsDisplayed()
        readable(text(R.string.book_folder_auto_on))
        compose.onNodeWithText(text(R.string.book_folder_auto_help)).performScrollTo().assertIsDisplayed()
        readable(text(R.string.book_folder_auto_help)); capture("book-folder-auto-double-type")
        compose.runOnIdle { old() }; assertEquals(1, changes)
        compose.onNodeWithTag("book-folder-automatic").performScrollTo().performClick()
        assertEquals(2, changes); assertFalse(state.snapshot!!.automaticScan)
    }

    @Test fun `actual library route opening opt-in reaches existing preview once and never imports on resume`() {
        val owner = ViewModelStore()
        val automatic = saved.copy(automaticScan = true)
        var resolves = 0; var scans = 0; var imports = 0
        val folders = object : BookFolderRepository {
            override fun observe(): Flow<BookFolderSnapshot> = MutableStateFlow(automatic)
            override suspend fun save(expected: BookFolderSnapshot, selectedTreeUri: String): BookFolderWrite = error("No save")
            override suspend fun forget(expected: BookFolderSnapshot): BookFolderWrite = error("No forget")
            override suspend fun retryCleanup() = Unit
            override suspend fun resolveForScan(expected: BookFolderSnapshot): String {
                assertEquals(automatic, expected); resolves++; return uri
            }
        }
        val library = object : LibraryRepository, BookImporter {
            override fun observeBooks(): Flow<List<Book>> = MutableStateFlow(emptyList())
            override suspend fun findById(id: String): Book? = null
            override suspend fun findByHash(sha256: String): Book? = null
            override suspend fun storedFileNames() = emptySet<String>()
            override suspend fun add(book: Book) = error("No writes")
            override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?): Boolean = error("No writes")
            override suspend fun import(documentUri: String): ImportResult { imports++; return ImportResult.Added }
        }
        lateinit var model: LibraryViewModel
        compose.runOnUiThread {
            model = LibraryViewModel(library, library, SavedStateHandle(), discovery = object : BookDiscovery {
                override suspend fun scan(treeUri: String, onProgress: (DiscoveryProgress) -> Unit): DiscoveryResult {
                    assertEquals(uri, treeUri); scans++; return DiscoveryResult.Complete(emptyList(), 0)
                }
            }, bookFolders = folders)
            owner.put("model", model)
        }
        try {
            compose.setContent { YaprakTheme { LibraryRoute(model) } }
            compose.waitUntil { model.discoveryState.value is DiscoveryState.Preview }
            assertEquals(1, resolves); assertEquals(1, scans); assertEquals(0, imports)
            compose.onNodeWithText(text(R.string.cancel_remove)).assertIsDisplayed().performClick()
            compose.waitUntil { model.discoveryState.value == DiscoveryState.Idle }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            compose.runOnUiThread { model.reloadBookFolder() }; compose.waitForIdle()
            assertEquals(1, resolves); assertEquals(1, scans); assertEquals(0, imports)
            assertEquals(DiscoveryState.Idle, model.discoveryState.value)
        } finally { compose.runOnUiThread { owner.clear() } }
    }

    private fun text(id: Int, vararg values: Any) = compose.activity.getString(id, *values)
    private fun action(tag: String): () -> Boolean = compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
    }
    private fun readable(value: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(value, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse("Overflow: $value", layout.hasVisualOverflow)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String, dialog: Boolean = false) = compose.runOnIdle {
        val view = if (dialog) requireNotNull(ShadowDialog.getLatestDialog().window).decorView else compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

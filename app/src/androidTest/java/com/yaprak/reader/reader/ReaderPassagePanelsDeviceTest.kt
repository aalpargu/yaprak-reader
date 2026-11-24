package com.yaprak.reader.reader

import android.os.Bundle
import android.app.Activity
import android.app.Application
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.domain.passages.EpubPassageRepository
import com.yaprak.reader.domain.settings.TapZoneSettings
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.ReaderDraftSnapshot
import com.yaprak.reader.feature.reader.ReaderPanelKind
import com.yaprak.reader.feature.reader.ReaderPresentationSavedState
import com.yaprak.reader.feature.passages.EpubPassagesViewModel
import com.yaprak.reader.formats.epub.SourceAnchorRequest
import com.yaprak.reader.formats.epub.SourceTextAnchorVerifier
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real ReaderActivity/Readium + Room rows. The pending-write recreation case controls write failures explicitly. */
@RunWith(AndroidJUnit4::class)
class ReaderPassagePanelsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as YaprakApplication).container

    @Test fun pendingExistingPassageSaveSurvivesActualActivityRecreationAndFailureWithExactInputRetryAndCancel() = runBlocking {
        withTimeout(30_000) { container.startup.awaitReady() }
        val oldTaps = container.tapZones.tapZones.first()
        val id = UUID.randomUUID().toString()
        val quote = "This is an original paragraph for an existing passage edit."
        val xhtml = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Existing edit</title></head><body><p>$quote</p></body></html>"""
        val file = File(context.filesDir, "books/$id.epub").apply { parentFile!!.mkdirs() }
        writeEpub(file, id, xhtml)
        val original = file.readBytes()
        val book = Book(id, "Pending passage fixture", null, BookFormat.EPUB, "pending.epub", file.name,
            original.size.toLong(), sha256(original), System.currentTimeMillis())
        container.library.add(book)
        val firstWrite = CompletableDeferred<Unit>()
        val retryWrite = CompletableDeferred<Unit>()
        val writes = AtomicInteger()
        val attemptedNotes = mutableListOf<String?>()
        // Read real Room records and open the real EPUB, but make the write outcome deterministic.
        // This is Activity/route/lifecycle acceptance, not a claim about a failing Room transaction.
        val repository = object : EpubPassageRepository by container.epubPassages {
            override suspend fun updateAnnotation(bookId: String, contentSha256: String, expected: EpubPassage,
                highlightColor: EpubHighlightColor?, attachedNote: String?): EpubPassage {
                attemptedNotes += attachedNote
                when (writes.incrementAndGet()) {
                    1 -> firstWrite.await()
                    2 -> retryWrite.await()
                    else -> error("An unauthorized duplicate write was attempted")
                }
                error("Controlled pending update failure")
            }
        }
        try {
            container.tapZones.saveTapZones(TapZoneSettings())
            val anchor = SourceTextAnchor("EPUB/text.xhtml", 0, quote.length, sha256(quote.toByteArray()))
            val proof = SourceTextAnchorVerifier.verifyXhtmlBatch(xhtml.toByteArray(), anchor.resourceHref,
                listOf(SourceAnchorRequest("fixture", anchor, quote)))
            check(proof.nodeTexts["fixture"] == quote)
            container.epubPassages.createAnnotation(book.id, book.sha256, quote, anchor, EpubHighlightColor.YELLOW, "Stored note")
            val stored = container.epubPassages.observe(book.id, book.sha256).first()
            val target = stored.single()
            val exactInput = "  Exact existing note\nkept across recreation  "
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                val confirmation = ReaderImmersiveConfirmation(scenario)
                compose.waitUntil(20_000) {
                    var resumed = false
                    scenario.onActivity { activity ->
                        resumed = activity.supportFragmentManager.fragments.any { it.isResumed && it.view?.isShown == true }
                    }
                    confirmation.ready() && resumed
                }
                lateinit var model: EpubPassagesViewModel
                val modelKey = "epub-passages-${book.id}-${book.sha256}"
                scenario.onActivity { activity ->
                    model = EpubPassagesViewModel(book.id, book.sha256, repository)
                    activity.viewModelStore.put(modelKey, model)
                }
                showReaderControls(compose, scenario)
                compose.onNodeWithText("İçerik").assertIsDisplayed().performClick()
                compose.onNodeWithText("Vurgular ve notlar").assertIsDisplayed().performClick()
                visibleTag("epub-passages-list")
                compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-annotation-edit-${target.id}"))
                compose.onNodeWithTag("epub-annotation-edit-${target.id}").performClick()
                visibleTag("epub-annotation-body")
                compose.onNodeWithTag("epub-annotation-note").performScrollTo().performTextReplacement(exactInput)
                compose.onNodeWithTag("epub-annotation-save").assertIsEnabled().performClick()
                compose.waitUntil(10_000) { writes.get() == 1 && model.state.value.busy }
                compose.onNodeWithText("Kaydediliyor…").assertIsDisplayed()
                compose.onNodeWithTag("epub-annotation-cancel").assertIsNotEnabled()
                // Read the framework's real old-Activity Bundle during recreate. Calling
                // onSaveInstanceState manually would perturb ComponentActivity's lifecycle.
                var oldActivity: ReaderActivity? = null
                var oldSession: String? = null
                scenario.onActivity { activity ->
                    assertSame(model, activity.viewModelStore.get(modelKey))
                    oldActivity = activity
                    oldSession = model.state.value.sessionId
                }
                val savedDraft = AtomicReference<ReaderDraftSnapshot?>()
                val callbacks = object : Application.ActivityLifecycleCallbacks {
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                        if (activity === oldActivity) savedDraft.set(ReaderDraftSnapshot.fromBundle(
                            ReaderPresentationSavedState.restore(outState, book.id)?.drafts?.get(ReaderPanelKind.PASSAGES)))
                    }
                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityResumed(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivityDestroyed(activity: Activity) = Unit
                }
                val application = context.applicationContext as Application
                instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
                try { scenario.recreate() }
                finally { instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) } }
                val actualSaved = savedDraft.get()
                assertNotNull("The framework must save the already-published busy editor input", actualSaved)
                assertEquals(target.id, actualSaved!!.editId)
                assertEquals(exactInput, actualSaved.editDraft)
                scenario.onActivity { activity ->
                    assertNotSame(oldActivity, activity)
                    assertSame("Compose must keep the same admitted in-flight model", model, activity.viewModelStore.get(modelKey))
                }
                visibleTag("epub-annotation-body")
                compose.waitUntil(10_000) { model.state.value.sessionId != oldSession }
                compose.onNodeWithText("Kaydediliyor…").assertIsDisplayed()
                compose.onNodeWithTag("epub-annotation-note").performScrollTo().assertEditableTextExactly(exactInput)
                assertEquals(1, writes.get())
                firstWrite.complete(Unit)
                compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.message != null }
                compose.onNodeWithTag("epub-annotation-note").performScrollTo().assertEditableTextExactly(exactInput)
                compose.onNodeWithText(context.getString(com.yaprak.reader.R.string.annotation_save_failed)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("epub-annotation-save").assertIsEnabled()
                compose.onNodeWithTag("epub-annotation-cancel").assertIsEnabled()
                captureReaderScreenshot(compose, "epub-passage-pending-save-failed-restored")
                assertEquals(1, writes.get())
                assertEquals(stored, container.epubPassages.observe(book.id, book.sha256).first())
                compose.onNodeWithTag("epub-annotation-save").performClick()
                compose.waitUntil(10_000) { writes.get() == 2 && model.state.value.busy }
                retryWrite.complete(Unit)
                compose.waitUntil(10_000) { !model.state.value.busy }
                compose.onNodeWithTag("epub-annotation-note").performScrollTo().assertEditableTextExactly(exactInput)
                compose.onNodeWithTag("epub-annotation-cancel").assertIsEnabled().performClick()
                visibleTag("epub-passages-list")
                assertEquals(2, writes.get())
                assertEquals(listOf(exactInput, exactInput), attemptedNotes)
                assertEquals(stored, container.epubPassages.observe(book.id, book.sha256).first())
            }
            assertArrayEquals(original, file.readBytes())
            assertEquals(book.sha256, container.library.findById(book.id)?.sha256)
        } finally {
            firstWrite.complete(Unit); retryWrite.complete(Unit)
            container.tapZones.saveTapZones(oldTaps)
            val removed = withTimeout(15_000) {
                var result = container.remover.remove(book.id, book.sha256)
                while (result == BookRemovalResult.InUse) { delay(50); result = container.remover.remove(book.id, book.sha256) }
                result
            }
            assertTrue(removed is BookRemovalResult.Removed)
        }
    }

    @Test fun editAndDeleteCancelKeepOneWindowTheScrolledCallerAndEveryStoredOriginalRecord() = runBlocking {
        withTimeout(30_000) { container.startup.awaitReady() }
        val oldTaps = container.tapZones.tapZones.first()
        val id = UUID.randomUUID().toString()
        val quotes = (1..24).map { "Passage $it is an original paragraph with its own unique source text." }
        val xhtml = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Original passages</title></head><body>${quotes.joinToString("") { "<p>$it</p>" }}</body></html>"""
        val file = File(context.filesDir, "books/$id.epub").apply { parentFile!!.mkdirs() }
        writeEpub(file, id, xhtml)
        val original = file.readBytes()
        val book = Book(id, "Passage panel fixture", null, BookFormat.EPUB, "panels.epub", file.name,
            original.size.toLong(), sha256(original), System.currentTimeMillis())
        container.library.add(book)
        try {
            container.tapZones.saveTapZones(TapZoneSettings())
            quotes.forEach { quote ->
                val anchor = SourceTextAnchor("EPUB/text.xhtml", 0, quote.length, sha256(quote.toByteArray()))
                val proof = SourceTextAnchorVerifier.verifyXhtmlBatch(xhtml.toByteArray(), anchor.resourceHref,
                    listOf(SourceAnchorRequest("fixture", anchor, quote)))
                check(proof.nodeTexts["fixture"] == quote)
                container.epubPassages.createAnnotation(book.id, book.sha256, quote, anchor,
                    EpubHighlightColor.YELLOW, "Saved note for $quote")
            }
            val stored = container.epubPassages.observe(book.id, book.sha256).first()
            assertEquals(24, stored.size)
            val target = stored[12]
            val themeBefore = container.bookThemes.observe(book.id, book.sha256).first()
            val editTag = "epub-annotation-edit-${target.id}"
            val deleteTag = "epub-annotation-delete-${target.id}"
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                val confirmation = ReaderImmersiveConfirmation(scenario)
                compose.waitUntil(20_000) {
                    var resumed = false
                    scenario.onActivity { activity ->
                        resumed = activity.supportFragmentManager.fragments.any { it.isResumed && it.view?.isShown == true }
                    }
                    confirmation.ready() && resumed
                }
                showReaderControls(compose, scenario)
                compose.onNodeWithText("İçerik").assertIsDisplayed().performClick()
                compose.onNodeWithText("Vurgular ve notlar").assertIsDisplayed().performClick()
                visibleTag("epub-passages-list")
                compose.onNodeWithText("Kitap içeriği").assertDoesNotExist()
                compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag(editTag))
                val before = compose.onNodeWithTag(editTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
                val scrollBefore = compose.onNodeWithTag("epub-passages-list").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("The test must actually leave the top of the saved list", scrollBefore > 0f)
                compose.onNodeWithTag(editTag).performClick()
                visibleTag("epub-annotation-body")
                compose.onNodeWithText("Vurguyu ve notu düzenle").assertIsDisplayed()
                compose.onNodeWithTag("epub-passages-list").assertDoesNotExist()
                compose.onNodeWithTag("epub-annotation-note").performScrollTo().performTextReplacement("Uncommitted replacement note")
                var oldActivity: ReaderActivity? = null
                scenario.onActivity { oldActivity = it }
                scenario.recreate()
                scenario.onActivity { assertNotSame("The real Activity must be recreated", oldActivity, it) }
                visibleTag("epub-annotation-body")
                compose.onNodeWithTag("epub-passages-list").assertDoesNotExist()
                compose.onNodeWithTag("epub-annotation-note").performScrollTo().assertEditableTextExactly("Uncommitted replacement note")
                captureReaderScreenshot(compose, "epub-passage-dirty-restored")
                assertEquals("Restoring a dirty form must not commit it", stored,
                    container.epubPassages.observe(book.id, book.sha256).first())
                assertArrayEquals(original, file.readBytes())
                assertEquals(book.sha256, container.library.findById(book.id)?.sha256)
                compose.onNodeWithTag("epub-annotation-cancel").assertIsDisplayed().performClick()
                visibleTag("epub-passages-list")
                focusedTag(editTag)
                compose.waitUntil(10_000) { runCatching {
                    val after = compose.onNodeWithTag(editTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
                    kotlin.math.abs(before - after) <= 1f
                }.getOrDefault(false) }
                captureReaderScreenshot(compose, "epub-passages-returned")
                assertEquals(stored, container.epubPassages.observe(book.id, book.sha256).first())
                // Reopening must use the saved note, never the cancelled text draft.
                compose.onNodeWithTag(editTag).performClick()
                visibleTag("epub-annotation-body")
                compose.onNodeWithTag("epub-annotation-note").performScrollTo().assertEditableTextExactly(target.attachedNote!!)
                compose.onNodeWithTag("epub-annotation-cancel").performClick()
                visibleTag("epub-passages-list")
                compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag(deleteTag))
                compose.onNodeWithTag(deleteTag).assertIsDisplayed().performClick()
                compose.onNodeWithText("Pasaj silinsin mi?").assertIsDisplayed()
                compose.onNodeWithTag("epub-passages-list").assertDoesNotExist()
                captureReaderScreenshot(compose, "epub-passage-delete")
                compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
                visibleTag("epub-passages-list")
                focusedTag(deleteTag)
                assertEquals(stored, container.epubPassages.observe(book.id, book.sha256).first())
                compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
                focusedTag("reader-focus-passages")
                compose.onNodeWithText("Kitap içeriği").assertIsDisplayed()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                focusedTag("reader-focus-content")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.onNodeWithTag("reader-bottom-bar").assertDoesNotExist()
                scenario.onActivity { assertFalse(it.isFinishing) }
                showReaderControls(compose, scenario)
                compose.onNodeWithText("Aa").assertIsDisplayed().performClick()
                compose.onNodeWithText("Bu kitabın görünümü").assertIsDisplayed().performClick()
                visibleTag("reader-focus-book_theme")
                val themeClick = compose.onNodeWithTag("reader-focus-book_theme").assertIsDisplayed()
                    .captureApplicationClick()
                val fontSizeClick = compose.onNodeWithTag("reader-focus-book_font_size").assertIsDisplayed()
                    .captureApplicationClick()
                // Invoke both real parent button callbacks in the same main-thread turn, before
                // the next Compose SideEffect; only the first can acquire the child presentation.
                compose.runOnIdle { themeClick(); fontSizeClick() }
                awaitThemeChoices()
                compose.onNodeWithText("Kitap görünümü").assertDoesNotExist()
                val retiredThemeEdit = compose.onNodeWithText("Koyu").performScrollTo().captureApplicationClick()
                val retiredThemeSave = compose.onNodeWithText("Kaydet").captureApplicationClick()
                val retiredThemeDismiss = compose.onNodeWithText("Vazgeç").captureApplicationClick()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                focusedTag("reader-focus-book_theme")
                compose.onNodeWithText("Kitap görünümü").assertIsDisplayed()
                // The logical parent/scroll key survives, but callbacks from its disposed
                // pre-child exposure must not open another child after the return.
                compose.runOnIdle { fontSizeClick(); themeClick() }
                compose.onNodeWithText("Kitap görünümü").assertIsDisplayed()
                compose.onNodeWithTag("reader-focus-book_theme").performClick()
                awaitThemeChoices()
                // A retired Window's callback cannot edit/save/close a later same-kind form.
                compose.runOnIdle { retiredThemeEdit(); retiredThemeSave(); retiredThemeDismiss() }
                compose.onNodeWithText("Kitap teması").assertIsDisplayed()
                compose.onNodeWithText("Koyu").performScrollTo().assertIsNotSelected()
                assertEquals(themeBefore, container.bookThemes.observe(book.id, book.sha256).first())
                val beforePauseEdit = compose.onNodeWithText("Koyu").captureApplicationClick()
                val beforePauseSave = compose.onNodeWithText("Kaydet").captureApplicationClick()
                val beforePauseDismiss = compose.onNodeWithText("Vazgeç").captureApplicationClick()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                compose.waitForIdle()
                compose.runOnIdle { beforePauseEdit(); beforePauseSave(); beforePauseDismiss() }
                compose.onNodeWithText("Kitap teması").assertIsDisplayed()
                compose.onNodeWithText("Koyu").performScrollTo().assertIsNotSelected()
                assertEquals(themeBefore, container.bookThemes.observe(book.id, book.sha256).first())
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                focusedTag("reader-focus-book_theme")
                compose.onNodeWithText("Kitap görünümü").assertIsDisplayed()
                assertEquals(themeBefore, container.bookThemes.observe(book.id, book.sha256).first())
                compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
                focusedTag("reader-focus-book_appearance")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                focusedTag("reader-focus-appearance")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.onNodeWithTag("reader-bottom-bar").assertDoesNotExist()
            }
            assertEquals(stored, container.epubPassages.observe(book.id, book.sha256).first())
            assertArrayEquals(original, file.readBytes())
        } finally {
            container.tapZones.saveTapZones(oldTaps)
            val removed = withTimeout(15_000) {
                var result = container.remover.remove(book.id, book.sha256)
                while (result == BookRemovalResult.InUse) { delay(50); result = container.remover.remove(book.id, book.sha256) }
                result
            }
            assertTrue(removed is BookRemovalResult.Removed)
        }
    }

    private fun visibleTag(tag: String) = compose.waitUntil(15_000) {
        runCatching { compose.onNodeWithTag(tag).assertIsDisplayed(); true }.getOrDefault(false)
    }
    private fun focusedTag(tag: String) = compose.waitUntil(15_000) {
        runCatching { compose.onNodeWithTag(tag).assertIsDisplayed().assertIsFocused(); true }.getOrDefault(false)
    }
    private fun awaitThemeChoices() = compose.waitUntil(10_000) {
        runCatching {
            // The title is composed while the actual per-book preferences are still loading.
            compose.onNodeWithText("Kitap teması").assertIsDisplayed()
            compose.onNodeWithText("Koyu").assertExists().assertIsEnabled()
            true
        }.getOrDefault(false)
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeEpub(file: File, id: String, xhtml: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                val bytes = text.toByteArray()
                zip.putNextEntry(ZipEntry(name).apply { if (name == "mimetype") {
                    method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                } })
                zip.write(bytes); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("EPUB/package.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">$id</dc:identifier><dc:title>Passage panel fixture</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="text" href="text.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="text"/></spine></package>""")
            entry("EPUB/text.xhtml", xhtml)
            entry("EPUB/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="text.xhtml">Original passages</a></li></ol></nav></body></html>""")
        }
    }
}

package com.yaprak.reader.nativeui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.feature.pdfannotations.PdfAnnotationsRoute
import com.yaprak.reader.feature.pdfannotations.PdfAnnotationsWindow
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import com.yaprak.reader.reader.PdfSourceAnchor
import com.yaprak.reader.reader.PdfPagePoint
import com.yaprak.reader.reader.PdfTextBounds
import com.yaprak.reader.reader.assertEditableTextExactly
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native component recovery contracts with controlled state; no persistence or renderer acceptance. */
@RunWith(AndroidJUnit4::class)
class ReaderPresentationChromeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun ready(format: BookFormat = BookFormat.EPUB) = ReaderState.Ready(object : ReaderSession {
        override val book = Book("notice", "Notice fixture", null, format,
            "notice.${format.name.lowercase()}", "notice.${format.name.lowercase()}", 1, "a".repeat(64), 1)
        override val resume: ReadingLocation? = null
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface =
            error("The component fixture cannot render a book")
        override fun close() = Unit
    })

    @Test fun saveFailureOffersDirectRetryWithoutOpeningToolsOrHidingTheFailureOnControlChanges() {
        val controller = ReaderPresentationController().apply {
            attach("native-notice")
            updateStatus(ReaderPhase.BlockingError(ReaderBlockingReason.SAVE), ReaderNoticeKind.SAVE)
        }
        var failed by mutableStateOf(true)
        var retries = 0
        val state = ready()
        compose.setContent { YaprakTheme {
            ReaderChrome(state, false, failed, false, {}, {}, {
                retries++; failed = false; controller.updateStatus(ReaderPhase.Ready, null)
            }, {}, {}, {}, {}, presentationController = controller)
        } }
        compose.onNodeWithTag("reader-notice").assertIsDisplayed()
        compose.onNodeWithText("Okuma araçları").assertDoesNotExist()
        compose.runOnIdle { controller.setControls(true); controller.setControls(false) }
        compose.onNodeWithTag("reader-notice").assertIsDisplayed()
        compose.onNodeWithText("Tekrar kaydet").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-notice").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, retries)
        compose.onNodeWithText("Okuma araçları").assertDoesNotExist()
        compose.onNodeWithTag("reader-bottom-bar").assertDoesNotExist()
    }

    @Test fun recoveryDetailsKeepRealRetryAndLeaveReachableWhileSuppressingSourceCommands() {
        val controller = ReaderPresentationController().apply {
            attach("native-recovery")
            updateStatus(ReaderPhase.BlockingError(ReaderBlockingReason.SAVE), ReaderNoticeKind.SAVE)
        }
        var failed by mutableStateOf(true)
        var retries = 0
        var sourceCommands = 0
        var leaves = 0
        val state = ready()
        compose.setContent { YaprakTheme {
            ReaderChrome(state, true, failed, false, { leaves++ }, {}, {
                retries++; failed = false; controller.updateStatus(ReaderPhase.Ready, null)
            }, {}, {}, { sourceCommands++ }, { sourceCommands++ },
                onContents = { sourceCommands++ }, onBookmarks = { sourceCommands++ },
                onBookSearch = { sourceCommands++ }, onSelectedText = { sourceCommands++ },
                presentationController = controller)
        } }
        compose.onNodeWithText("Ayrıntılar ve yeniden dene").assertIsDisplayed().performClick()
        compose.onNodeWithText("Okuma araçları").assertIsDisplayed()
        compose.onNodeWithTag("reader-notice").assertDoesNotExist()
        for (label in listOf("İçindekiler", "Yer imleri", "Kitapta ara", "Seçili metni göster", "Önceki", "Sonraki"))
            compose.onNodeWithText(label).assertDoesNotExist()
        compose.onNodeWithText("Kitaplığa dön").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tekrar kaydet").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { retries == 1 && controller.state.value.pending == null }
        assertEquals(0, sourceCommands)
        assertEquals(0, leaves)
        assertNull(controller.state.value.notice)
        assertNull(controller.state.value.activePanel)
    }

    @Test fun pdfRenderFailureDetailsReachOnlyThumbnailRecoveryThroughRealNativeFocus() {
        val controller = ReaderPresentationController().apply {
            attach("native-pdf-render-recovery")
            updateStatus(ReaderPhase.BlockingError(ReaderBlockingReason.RENDER), ReaderNoticeKind.RENDER)
        }
        var state by mutableStateOf(ready(BookFormat.PDF))
        var saveFailed by mutableStateOf(false)
        var loadFailed by mutableStateOf(false)
        var hasThumbnails by mutableStateOf(false)
        var thumbnailRequested = false
        var thumbnailCommands = 0
        var sourceCommands = 0
        val host = compose.activity
        val title = host.getString(R.string.pdf_thumbnails_title)
        val thumbnailAction: () -> Unit = {
            assertTrue(host.window.decorView.isAttachedToWindow)
            assertTrue(host.window.decorView.hasWindowFocus())
            assertTrue(host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            assertEquals(ReaderPhase.BlockingError(ReaderBlockingReason.RENDER), controller.state.value.phase)
            assertEquals(ReaderPanelKind.TOOLS, controller.state.value.pending!!.origin.kind)
            assertNull(controller.state.value.activePanel)
            thumbnailCommands++
            thumbnailRequested = true
        }
        compose.setContent { YaprakTheme {
            ReaderChrome(state, false, saveFailed, loadFailed, {}, {}, {}, {}, {},
                { sourceCommands++ }, { sourceCommands++ }, onContents = { sourceCommands++ },
                onBookmarks = { sourceCommands++ }, onPageJump = { sourceCommands++ },
                onBookNotes = { sourceCommands++ }, onBookSearch = { sourceCommands++ },
                onSelectedText = { sourceCommands++ },
                onPdfThumbnails = if (hasThumbnails) thumbnailAction else null,
                presentationController = controller,
                currentRequests = { if (thumbnailRequested) setOf(ReaderPanelKind.PDF_THUMBNAILS) else emptySet() })
        } }
        fun openRecoveryDetails() {
            compose.onNodeWithTag("reader-notice").assertIsDisplayed()
            compose.onNodeWithTag("reader-bottom-bar").assertDoesNotExist()
            compose.onNodeWithText("Ayrıntılar ve yeniden dene").assertIsDisplayed().performClick()
            compose.onNodeWithText("Okuma araçları").assertIsDisplayed()
            for (label in listOf("İçindekiler", "Yer imleri", "Sayfaya git", "Notlar", "Kitapta ara",
                "Seçili metni göster", "Önceki", "Sonraki")) compose.onNodeWithText(label).assertDoesNotExist()
        }
        openRecoveryDetails()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.runOnIdle { hasThumbnails = true }
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { thumbnailCommands == 1 && controller.state.value.pending == null &&
            controller.state.value.activePanel?.kind == ReaderPanelKind.PDF_THUMBNAILS }
        compose.onNodeWithText("Okuma araçları").assertDoesNotExist()
        assertEquals(1, thumbnailCommands)
        assertEquals(0, sourceCommands)
        assertEquals(ReaderPhase.BlockingError(ReaderBlockingReason.RENDER), controller.state.value.phase)
        assertTrue(controller.state.value.blocksReading)

        listOf(BookFormat.PDF to ReaderBlockingReason.SOURCE, BookFormat.PDF to ReaderBlockingReason.SAVE,
            BookFormat.CBZ to ReaderBlockingReason.RENDER, BookFormat.CBR to ReaderBlockingReason.RENDER)
            .forEach { (format, reason) ->
                compose.runOnIdle {
                    thumbnailRequested = false
                    state = ready(format)
                    saveFailed = reason == ReaderBlockingReason.SAVE
                    loadFailed = reason == ReaderBlockingReason.SOURCE
                    controller.attach("native-recovery-$format-$reason")
                    controller.updateStatus(ReaderPhase.BlockingError(reason), when (reason) {
                        ReaderBlockingReason.SOURCE -> ReaderNoticeKind.LOAD
                        ReaderBlockingReason.SAVE -> ReaderNoticeKind.SAVE
                        else -> ReaderNoticeKind.RENDER
                    })
                }
                openRecoveryDetails()
                compose.onNodeWithText(title).assertDoesNotExist()
                assertFalse(controller.hasRequest(ReaderPanelKind.PDF_THUMBNAILS))
            }
        assertEquals(1, thumbnailCommands)
        assertEquals(0, sourceCommands)
    }

    @Test fun sourceFailureDisablesPassageWritesWhileDirtyEditorAndDeleteCanStillCancel() {
        val source = com.yaprak.reader.reader.SourceTextAnchor("component.xhtml", 0, 21, "a".repeat(64))
        val entry = com.yaprak.reader.domain.passages.EpubPassage("component-passage", "Component source text", source,
            1, com.yaprak.reader.domain.passages.EpubHighlightColor.YELLOW, "Stored note")
        val rows = MutableStateFlow(listOf(entry))
        var writes = 0
        val repository = object : com.yaprak.reader.domain.passages.EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = rows
            override suspend fun add(bookId: String, contentSha256: String, text: String,
                anchor: com.yaprak.reader.reader.EpubSourceAnchor) { writes++ }
            override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
            override suspend fun updateAnnotation(bookId: String, contentSha256: String,
                expected: com.yaprak.reader.domain.passages.EpubPassage,
                highlightColor: com.yaprak.reader.domain.passages.EpubHighlightColor?, attachedNote: String?): com.yaprak.reader.domain.passages.EpubPassage {
                writes++; return expected
            }
        }
        val book = Book("component", "Owned component", null, BookFormat.EPUB,
            "component.epub", "component.epub", 1, "a".repeat(64), 1)
        var writable by mutableStateOf(true)
        var open by mutableStateOf(true)
        compose.setContent { YaprakTheme { if (open) com.yaprak.reader.feature.passages.EpubPassagesRoute(
            book, repository, { open = false }, canMutate = { writable }, canInteract = { true }) } }
        val tag = "epub-annotation-edit-${entry.id}"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag("epub-annotation-note").performScrollTo().performTextReplacement("Dirty input remains editable")
        compose.runOnIdle { writable = false }
        compose.onNodeWithTag("epub-annotation-save").assertIsNotEnabled()
        compose.onNodeWithText(com.yaprak.reader.feature.passages.readerUnavailableMessage).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("epub-annotation-note").performScrollTo().assertEditableTextExactly("Dirty input remains editable")
        compose.onNodeWithTag("epub-annotation-cancel").assertIsEnabled().performClick()
        compose.onNodeWithTag("epub-passages-list").assertIsDisplayed()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Kapat").assertIsFocused(); true }.getOrDefault(false) }
        compose.runOnIdle { writable = true }
        val deleteTag = "epub-annotation-delete-${entry.id}"
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag(deleteTag))
        compose.onNodeWithTag(deleteTag).performClick()
        compose.runOnIdle { writable = false }
        compose.onNodeWithText("Pasajı sil").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsEnabled().performClick()
        compose.onNodeWithText("Kapat").assertIsEnabled().performClick()
        assertFalse(open)
        assertEquals(listOf(entry), rows.value)
        assertEquals(0, writes)
    }

    @Test fun passageChapterSourceLossAndPauseReleaseTheirOwnedBusyStateWithoutLateDismissal() {
        val source = com.yaprak.reader.reader.SourceTextAnchor("component.xhtml", 0, 21, "a".repeat(64))
        val entry = com.yaprak.reader.domain.passages.EpubPassage("component-passage", "Component source text", source, 1)
        val rows = MutableStateFlow(listOf(entry))
        var writes = 0
        val repository = object : com.yaprak.reader.domain.passages.EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = rows
            override suspend fun add(bookId: String, contentSha256: String, text: String,
                anchor: com.yaprak.reader.reader.EpubSourceAnchor) { writes++ }
            override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
            override suspend fun updateAnnotation(bookId: String, contentSha256: String,
                expected: com.yaprak.reader.domain.passages.EpubPassage,
                highlightColor: com.yaprak.reader.domain.passages.EpubHighlightColor?, attachedNote: String?): com.yaprak.reader.domain.passages.EpubPassage {
                writes++; return expected
            }
        }
        val book = Book("component", "Owned component", null, BookFormat.EPUB,
            "component.epub", "component.epub", 1, "a".repeat(64), 1)
        var result = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var writable by mutableStateOf(true)
        var closes = 0
        var cancelled = false
        var noncooperative = true
        compose.setContent { YaprakTheme { com.yaprak.reader.feature.passages.EpubPassagesRoute(book, repository,
            { closes++ }, onOpenChapter = { _, complete ->
                val pending = result
                val accepted = if (noncooperative) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pending.await() }
                else try { pending.await() } finally { if (!pending.isCompleted) cancelled = true }
                accepted && complete()
            },
            canMutate = { writable }, canInteract = { true }) } }
        val description = "1. pasaj: Component source text, bölümünü aç"
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasContentDescription(description))
        val openChapter = compose.onNodeWithContentDescription(description).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        val openEditor = compose.onNodeWithTag("epub-annotation-edit-${entry.id}").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        val openDelete = compose.onNodeWithTag("epub-annotation-delete-${entry.id}").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        // All callbacks are captured before navigation; no recomposition can repair a missing route gate.
        compose.runOnIdle { openChapter(); openEditor(); openDelete() }
        compose.onNodeWithTag("epub-annotation-note").assertDoesNotExist()
        compose.onNodeWithText("Pasaj silinsin mi?").assertDoesNotExist()
        compose.onNodeWithText("Kapat").assertIsNotEnabled()
        assertEquals(0, writes)
        compose.runOnIdle { writable = false }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Kapat").assertIsEnabled(); true }.getOrDefault(false) }
        assertEquals(0, closes)
        compose.runOnIdle {
            writable = true
            result.complete(true)
            noncooperative = false
            result = kotlinx.coroutines.CompletableDeferred()
        }
        compose.waitForIdle()
        assertEquals("A cancelled noncooperative proof must not dismiss the returning form", 0, closes)
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).performClick()
        compose.onNodeWithText("Kapat").assertIsNotEnabled()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { cancelled }
        compose.onNodeWithText("Kapat").assertIsEnabled().performClick()
        assertEquals(1, closes)
        assertEquals(listOf(entry), rows.value)
        assertEquals(0, writes)
    }

    @Test fun pdfListChildCancelRetainsScrollAndReturnsFocusWithoutAnyProofOrMutation() {
        // Explicit component DTOs: these coordinates are never offered to an actual renderer or data layer.
        val anchor = PdfSourceAnchor(0, 300, 500, PdfPagePoint(20, 80), PdfPagePoint(250, 100),
            listOf(PdfTextBounds(20.0, 75.0, 250.0, 100.0)))
        val records = (1..16).map { PdfAnnotation("component-$it", "Owned component text $it", anchor,
            it.toLong(), PdfHighlightColor.YELLOW, "Saved component note $it") }
        val rows = MutableStateFlow(records)
        var writes = 0
        var proofs = 0
        val repository = object : PdfAnnotationRepository {
            override fun observe(bookId: String, contentSha256: String) = rows
            override suspend fun create(bookId: String, contentSha256: String, text: String, anchor: PdfSourceAnchor,
                highlightColor: PdfHighlightColor?, attachedNote: String?): PdfAnnotation { writes++; error("Cancel cannot create") }
            override suspend fun update(bookId: String, contentSha256: String, expected: PdfAnnotation,
                highlightColor: PdfHighlightColor?, attachedNote: String?): PdfAnnotation { writes++; error("Cancel cannot update") }
            override suspend fun remove(bookId: String, contentSha256: String, expected: PdfAnnotation) { writes++; error("Cancel cannot delete") }
            override suspend fun verifyExisting(bookId: String, contentSha256: String, expected: List<PdfAnnotation>) { proofs++; error("Cancel cannot claim source proof") }
        }
        val window = PdfAnnotationsWindow("component", "a".repeat(64), true)
        val tag = "pdf-annotation-edit-${records[12].id}"
        var writable by mutableStateOf(true)
        compose.setContent { YaprakTheme { PdfAnnotationsRoute(window, repository, { writable },
            { error("No source navigation") }, {}, { error("No clipboard action") }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val before = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag("pdf-annotation-body").assertIsDisplayed()
        compose.onNodeWithTag("pdf-annotations-body").assertDoesNotExist()
        compose.onNodeWithTag("pdf-annotation-note").performScrollTo().performTextReplacement("Uncommitted component note")
        compose.onNodeWithTag("pdf-annotation-cancel").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { runCatching {
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsFocused()
            kotlin.math.abs(compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top - before) <= 1f
        }.getOrDefault(false) }
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag("pdf-annotation-body").assertIsDisplayed()
        compose.runOnIdle { writable = false }
        compose.onNodeWithTag("pdf-annotation-cancel").performClick()
        compose.waitUntil(10_000) { runCatching {
            compose.onNodeWithTag("pdf-annotations-close").assertIsFocused(); true
        }.getOrDefault(false) }
        compose.onNodeWithTag(tag).assertIsNotEnabled()
        assertEquals(records, rows.value)
        assertEquals(0, writes)
        assertEquals(0, proofs)
        compose.runOnIdle { window.close() }
    }
}

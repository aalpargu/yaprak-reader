package com.yaprak.reader.feature

import android.app.Application
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.R
import com.yaprak.reader.domain.passages.EpubPassageRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.passages.EpubPassagesDialog
import com.yaprak.reader.feature.passages.EpubPassagesRoute
import com.yaprak.reader.feature.passages.EpubPassagesState
import com.yaprak.reader.feature.passages.PassageDraft
import com.yaprak.reader.feature.passages.RecoveredPassageEdit
import com.yaprak.reader.feature.passages.EpubPassagesViewModel
import com.yaprak.reader.feature.reader.copyPlainSelectedText
import com.yaprak.reader.feature.reader.ReaderDraftSnapshot
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
class EpubPassagesDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val anchor = SourceTextAnchor("chapter.xhtml", 0, 4, "a".repeat(64))

    @Test fun matchingPassageEditorDisplaysItsIndependentRecoveryAcrossActualRouteReleaseAndRebindWithoutWriting() {
        val book = Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100, "hash", 1)
        val row = EpubPassage("one", "Leaf", anchor, 1, EpubHighlightColor.YELLOW, "Stored", 3)
        val records = MutableStateFlow(listOf(row))
        var writes = 0
        val repository = object : EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = records
            override suspend fun add(bookId: String, contentSha256: String, text: String, anchor: com.yaprak.reader.reader.EpubSourceAnchor) { writes++ }
            override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
            override suspend fun updateAnnotation(bookId: String, contentSha256: String, expected: EpubPassage,
                highlightColor: EpubHighlightColor?, attachedNote: String?): EpubPassage { writes++; error("No write is authorized by restoration") }
        }
        lateinit var model: EpubPassagesViewModel
        lateinit var restored: android.os.Bundle
        compose.runOnUiThread {
            model = EpubPassagesViewModel(book.id, book.sha256, repository)
            compose.activity.viewModelStore.put("epub-passages-${book.id}-${book.sha256}", model)
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { !model.state.value.loading }
        compose.runOnUiThread {
            model.startSession("seed"); model.requestEdit(row); model.editNote("  Exact editor B  ")
            assertEquals("The synthetic editor must be admitted before capture", row, model.state.value.pendingEdit)
            assertEquals("  Exact editor B  ", model.state.value.editNote)
            restored = checkNotNull(model.snapshot(0, 0).copy(secondaryDraft = "  Protected recovery A  ", recoveredDraft = "PINK").toBundle())
            model.clearSession()
        }
        val decoded = checkNotNull(ReaderDraftSnapshot.fromBundle(restored))
        assertEquals(row.id, decoded.editId)
        assertEquals("  Exact editor B  ", decoded.editDraft)
        val beforeRouteSession = model.state.value.sessionId
        var composedOwner: ViewModelStoreOwner? = null
        var composedGeneration = -1
        val entered = mutableListOf<String>()
        val disposed = mutableListOf<String>()
        var generation by mutableStateOf(0)
        compose.setContent { YaprakTheme {
            val renderedGeneration = generation
            key(renderedGeneration) {
                val owner = LocalViewModelStoreOwner.current
                EpubPassagesRoute(book, repository, {}, restoration = restored)
                DisposableEffect(renderedGeneration) {
                    if (entered.size < 4) entered += "$renderedGeneration:${model.state.value.sessionId}"
                    onDispose { if (disposed.size < 4) disposed += "$renderedGeneration:${model.state.value.sessionId}" }
                }
                SideEffect { composedOwner = owner; composedGeneration = renderedGeneration }
            }
        } }
        compose.waitForIdle()
        var lastBodyProblem: Throwable? = null
        fun diagnostic(stage: String, originalSession: String? = null): String {
            val current = model.state.value
            val generationBeforeSemantics = composedGeneration
            val enteredBeforeSemantics = entered.toList()
            val disposedBeforeSemantics = disposed.toList()
            val clockBeforeSemantics = compose.mainClock.currentTime
            val semantics = runCatching {
                val nodes = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
                    .take(48).map { it to 0 }.toMutableList<Pair<SemanticsNode, Int>>()
                var index = 0
                val result = mutableListOf<String>()
                while (index < nodes.size) {
                    val (node, depth) = nodes[index++]
                    val tag = if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag].take(72) else ""
                    val textLength = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].sumOf { it.length } else 0
                    val editLength = if (node.config.contains(SemanticsProperties.EditableText)) node.config[SemanticsProperties.EditableText].length else 0
                    result += "$depth:${node.id}:$tag:text=$textLength:edit=$editLength"
                    if (depth < 5) nodes += node.children.take(48 - nodes.size).map { it to depth + 1 }
                }
                result.joinToString(" | ").take(6000)
            }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
            return "Passage $stage; generation=$generation, composedBefore=$generationBeforeSemantics, composedAfter=$composedGeneration, " +
                "enteredBefore=$enteredBeforeSemantics, enteredAfter=$entered, disposedBefore=$disposedBeforeSemantics, disposedAfter=$disposed, " +
                "autoAdvance=${compose.mainClock.autoAdvance}, clockBefore=$clockBeforeSemantics, clockAfter=${compose.mainClock.currentTime}, ownerPresent=${composedOwner != null}, " +
                "ownerStoreSame=${composedOwner?.viewModelStore === compose.activity.viewModelStore}, " +
                "ownerModelSame=${composedOwner?.viewModelStore?.get("epub-passages-${book.id}-${book.sha256}") === model}, " +
                "originalSession=$originalSession, sessionBefore=${current.sessionId}, sessionAfter=${model.state.value.sessionId}, " +
                "sessionChanged=${current.sessionId != beforeRouteSession}, loading=${current.loading}, loadFailed=${current.loadFailed}, busy=${current.busy}, " +
                "rowMatches=${current.entries.singleOrNull { it.id == row.id } == row}, editId=${current.pendingEdit?.id}, " +
                "editLength=${current.editNote.length}, recoveryLength=${current.recoveredNote?.length}, additionalLength=${current.additionalRecoveredEdit?.note?.length}, " +
                "decodedId=${decoded.editId}, fingerprintPresent=${decoded.expectedFingerprint != null}, decodedEditLength=${decoded.editDraft.length}, " +
                "decodedRecoveryLength=${decoded.secondaryDraft.length}, writes=$writes, " +
                "lastBodyProblem=${lastBodyProblem?.javaClass?.simpleName}:${lastBodyProblem?.message?.lineSequence()?.firstOrNull()?.take(240)}, " +
                "semantics=[$semantics]"
        }
        try {
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("epub-annotation-body").assertExists() }
                .onFailure { lastBodyProblem = it }.isSuccess }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(diagnostic("initial editor missing"), timeout)
        }
        compose.runOnIdle {
            assertSame("The composed route must use the Activity's seeded ViewModelStore", compose.activity.viewModelStore, composedOwner?.viewModelStore)
            assertSame("The composed route must resolve the seeded model", model,
                composedOwner?.viewModelStore?.get("epub-passages-${book.id}-${book.sha256}"))
            assertEquals(row, model.state.value.pendingEdit)
            assertEquals(0, composedGeneration)
        }
        compose.onNodeWithTag("epub-passage-recovered-note").performScrollTo().assertIsDisplayed().assertTextEquals("  Protected recovery A  ")
        val retiredDiscard = compose.onNodeWithTag("epub-passage-recovered-discard").performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val originalSession = model.state.value.sessionId
        compose.runOnIdle { generation++ }
        try {
            // runOnIdle waits before the mutation; drain its keyed recomposition before polling the model.
            compose.waitForIdle()
            compose.waitUntil(5_000) { model.state.value.sessionId != originalSession }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(diagnostic("route did not rebind", originalSession), timeout)
        }
        compose.runOnIdle {
            assertEquals(1, composedGeneration)
            assertEquals(true, entered.any { it.startsWith("1:") })
            assertEquals(true, disposed.any { it.startsWith("0:") })
            assertSame(model, composedOwner?.viewModelStore?.get("epub-passages-${book.id}-${book.sha256}"))
        }
        compose.runOnIdle { retiredDiscard() }
        compose.onNodeWithTag("epub-passage-recovered-note").performScrollTo().assertIsDisplayed().assertTextEquals("  Protected recovery A  ")
        compose.onNodeWithTag("epub-annotation-note").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("  Exact editor B  ")))
        compose.onNodeWithTag("epub-annotation-save").assertIsEnabled()
        assertEquals(row, model.state.value.pendingEdit); assertEquals(0, writes)
        compose.runOnIdle { records.value = listOf(row.copy(revision = 4, attachedNote = "Changed row")) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { model.state.value.editChanged }
        compose.onNodeWithTag("epub-annotation-save").assertIsNotEnabled()
        compose.onNodeWithTag("epub-annotation-cancel").performClick()
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-passage-recovered-note"))
        compose.onNodeWithTag("epub-passage-recovered-note").assertTextEquals("  Protected recovery A  ")
        assertEquals("Changed row", records.value.single().attachedNote); assertEquals(0, writes)
    }

    @Test fun bothProtectedPassageEditsRemainVisibleWithIndependentDiscardsAndRetiredSecondaryActionsAreRejected() {
        val row = EpubPassage("one", "Leaf", anchor, 1)
        val second = RecoveredPassageEdit("Exact note B", "GREEN")
        var state by mutableStateOf(EpubPassagesState(entries = listOf(row), loading = false,
            recoveredNote = "Exact note A", recoveredColor = "PINK", recoveryRevision = 1, additionalRecoveredEdit = second))
        var discarded = 0
        compose.setContent { YaprakTheme { EpubPassagesDialog(state, {}, {}, {}, {}, {}, {}, onEdit = {},
            onDiscardRecoveredNote = { note, revision -> if (state.recoveredNote == note && state.recoveryRevision == revision) {
                discarded++; state = state.copy(recoveredNote = null, recoveredColor = "", recoveryRevision = state.recoveryRevision + 1)
            } }, onDiscardAdditionalRecoveredEdit = { expected -> if (state.additionalRecoveredEdit === expected) {
                discarded++; state = state.copy(additionalRecoveredEdit = null)
            } }) } }
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-passage-additional-recovered-discard"))
        val retired = compose.onNodeWithTag("epub-passage-additional-recovered-discard").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(additionalRecoveredEdit = RecoveredPassageEdit(second.note, second.color)) }
        compose.waitForIdle(); compose.runOnIdle { retired() }; assertEquals(0, discarded)
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-passage-recovered-discard"))
        compose.onNodeWithTag("epub-passage-recovered-discard").performClick(); assertEquals(1, discarded)
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-passage-additional-recovered-note"))
        compose.onNodeWithTag("epub-passage-additional-recovered-note").assertIsDisplayed().assertTextEquals("Exact note B")
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-annotation-edit-${row.id}"))
        compose.onNodeWithTag("epub-annotation-edit-${row.id}").assertIsNotEnabled()
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-passage-additional-recovered-discard"))
        compose.onNodeWithTag("epub-passage-additional-recovered-discard").performClick(); assertEquals(2, discarded)
        assertEquals(listOf(row), state.entries)
    }

    @Test fun protectedPassageNoteBlocksAnotherEditAndAnOldDiscardCannotConsumeANewerRecoveryOfTheSameText() {
        val saved = EpubPassage("one", "Leaf", anchor, 1)
        val note = "  Exact protected note  "
        var state by mutableStateOf(EpubPassagesState(entries = listOf(saved), loading = false,
            recoveredNote = note, recoveryRevision = 1))
        var edits = 0
        var discarded = 0
        compose.setContent { YaprakTheme { EpubPassagesDialog(state, {}, {}, {}, {}, {}, {}, onEdit = { edits++ },
            onDiscardRecoveredNote = { expected, revision ->
                if (state.recoveredNote == expected && state.recoveryRevision == revision) {
                    discarded++; state = state.copy(recoveredNote = null, recoveryRevision = state.recoveryRevision + 1)
                }
            }) } }
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-passage-recovered-note"))
        compose.onNodeWithTag("epub-passage-recovered-note").assertIsDisplayed().assertTextEquals(note)
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-passage-recovered-discard"))
        val retired = compose.onNodeWithTag("epub-passage-recovered-discard").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(recoveryRevision = 2) }
        compose.waitForIdle(); compose.runOnIdle { retired() }
        assertEquals(0, discarded); assertEquals(note, state.recoveredNote)
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-annotation-edit-${saved.id}"))
        compose.onNodeWithTag("epub-annotation-edit-${saved.id}").assertIsNotEnabled()
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-passage-recovered-discard"))
        compose.onNodeWithTag("epub-passage-recovered-discard").performClick()
        assertEquals(1, discarded); assertEquals(null, state.recoveredNote)
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-annotation-edit-${saved.id}"))
        compose.onNodeWithTag("epub-annotation-edit-${saved.id}").assertIsEnabled().performClick()
        assertEquals(1, edits); assertEquals(listOf(saved), state.entries)
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large text keeps explicit save and close reachable while duplicate is blocked`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        var state by mutableStateOf(EpubPassagesState(
            draft = PassageDraft("Leaf", anchor), loading = false))
        var saves = 0
        var closes = 0
        compose.setContent { YaprakTheme { EpubPassagesDialog(state, { saves++ }, {}, {}, {}, {}, { closes++ }) } }
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasTestTag("epub-passage-save"))
        compose.onNodeWithTag("epub-passage-save").assertIsDisplayed().performClick()
        assertEquals(1, saves)
        compose.runOnIdle { state = state.copy(entries = listOf(EpubPassage("one", "Leaf", anchor, 1))) }
        compose.onNodeWithText("Bu pasaj zaten kayıtlı.").assertExists()
        compose.onNodeWithTag("epub-passage-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Kapat").assertIsDisplayed().performClick()
        assertEquals(1, closes)
    }

    @Test fun `delete requires confirmation and keeps entry visible until callback succeeds`() {
        val saved = EpubPassage("one", "Leaf", anchor, 1)
        var state by mutableStateOf(EpubPassagesState(entries = listOf(saved), loading = false))
        var deletes = 0
        compose.setContent { YaprakTheme { EpubPassagesDialog(state, {}, {},
            { id -> state = state.copy(pendingDelete = state.entries.firstOrNull { it.id == id }) },
            { deletes++; state = state.copy(entries = emptyList(), pendingDelete = null) },
            { state = state.copy(pendingDelete = null) }, {}) } }
        compose.onNodeWithTag("epub-passages-list")
            .performScrollToNode(hasContentDescription("1. pasaj: Leaf, sil"))
        compose.onNodeWithContentDescription("1. pasaj: Leaf, sil").performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("Pasaj silinsin mi?").assertIsDisplayed()
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("Pasaj silinsin mi?").assertDoesNotExist()
        compose.onNodeWithContentDescription("1. pasaj: Leaf, sil").performClick()
        compose.onNodeWithText("Pasajı sil").performClick()
        assertEquals(1, deletes)
        compose.onNodeWithText("Bu kitap için henüz pasaj kaydedilmedi.").assertExists()
    }

    @Test fun `closing route discards transferred selection before reopening`() {
        val book = Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100,
            "hash", 1)
        val entries = MutableStateFlow<List<EpubPassage>>(emptyList())
        var adds = 0
        val repository = object : EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = entries
            override suspend fun add(bookId: String, contentSha256: String, text: String,
                anchor: com.yaprak.reader.reader.EpubSourceAnchor) { adds++ }
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
        }
        var open by mutableStateOf(true)
        var prefillText by mutableStateOf<String?>("Leaf")
        var prefillAnchor by mutableStateOf<SourceTextAnchor?>(anchor)
        compose.setContent { YaprakTheme {
            if (open) EpubPassagesRoute(book, repository,
                onDismiss = { open = false; prefillText = null; prefillAnchor = null },
                prefillText = prefillText, prefillAnchor = prefillAnchor)
        } }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("epub-passage-draft").assertExists() }.isSuccess
        }
        compose.onNodeWithTag("epub-passage-save").assertIsDisplayed()
        assertEquals(0, adds)
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { open = true }
        compose.onNodeWithTag("epub-passage-draft").assertDoesNotExist()
        compose.onNodeWithTag("epub-passage-save").assertDoesNotExist()
        assertEquals(0, adds)
    }

    @Test fun `chapter action keeps dialog on failure and opens only selected entry on success`() {
        val book = Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100,
            "hash", 1)
        val first = EpubPassage("first", "Leaf", anchor, 1)
        val second = EpubPassage("second", "Leaf", anchor.copy(start = 4, end = 8), 2)
        val entries = MutableStateFlow(listOf(first, second))
        val repository = object : EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = entries
            override suspend fun add(bookId: String, contentSha256: String, text: String,
                anchor: com.yaprak.reader.reader.EpubSourceAnchor) = Unit
            override suspend fun remove(bookId: String, contentSha256: String, id: String) = Unit
        }
        var open by mutableStateOf(true)
        var succeed = false
        var selected: String? = null
        var closes = 0
        compose.setContent { YaprakTheme {
            if (open) EpubPassagesRoute(book, repository, onDismiss = { closes++; open = false },
                onOpenChapter = { passage, complete -> selected = passage.id; succeed && complete() })
        } }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithContentDescription("1. pasaj: Leaf, bölümünü aç")
                .assertExists() }.isSuccess
        }
        val help = compose.activity.getString(R.string.annotations_list_help)
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasText(help))
        compose.onNodeWithText(help).assertIsDisplayed()
        compose.onNodeWithTag("epub-passages-list")
            .performScrollToNode(hasContentDescription("2. pasaj: Leaf, bölümünü aç"))
        compose.onNodeWithContentDescription("2. pasaj: Leaf, bölümünü aç").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("epub-passages-list")
                .performScrollToNode(hasText(
                    "Bölüm doğrulanamadı veya açılamadı. Pasajın tam yeri gösterilemez.")) }.isSuccess
        }
        assertEquals("second", selected)
        assertEquals(0, closes)
        compose.onNodeWithTag("epub-passages-list")
            .performScrollToNode(hasContentDescription("2. pasaj: Leaf, bölümünü aç"))
        compose.onNodeWithContentDescription("2. pasaj: Leaf, bölümünü aç").assertIsDisplayed()
        succeed = true
        compose.onNodeWithContentDescription("2. pasaj: Leaf, bölümünü aç").performClick()
        compose.waitUntil(5_000) { closes == 1 }
        assertEquals("second", selected)
        compose.onNodeWithText("Vurgular ve notlar").assertDoesNotExist()
    }

    @Test fun `cancelled chapter proof cannot complete after source recovery or dismiss a newer request`() {
        val book = Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100, "hash", 1)
        val entry = EpubPassage("one", "Leaf", anchor, 1)
        val entries = MutableStateFlow(listOf(entry))
        var writes = 0
        val repository = object : EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = entries
            override suspend fun add(bookId: String, contentSha256: String, text: String,
                anchor: com.yaprak.reader.reader.EpubSourceAnchor) { writes++ }
            override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
        }
        val oldResult = CompletableDeferred<Boolean>()
        val currentResult = CompletableDeferred<Boolean>()
        val completions = mutableListOf<() -> Boolean>()
        val outcomes = mutableListOf<Boolean>()
        var writable by mutableStateOf(true)
        var open by mutableStateOf(true)
        var closes = 0
        compose.setContent { YaprakTheme {
            if (open) EpubPassagesRoute(book, repository, onDismiss = { closes++; open = false },
                onOpenChapter = { _, complete ->
                    val pending = if (completions.isEmpty()) oldResult else currentResult
                    completions += complete
                    val accepted = withContext(NonCancellable) { pending.await() }
                    (accepted && complete()).also { outcomes += it }
                }, canMutate = { writable }, canInteract = { true })
        } }
        val description = "1. pasaj: Leaf, bölümünü aç"
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithContentDescription(description).assertExists() }.isSuccess
        }
        fun openChapter() {
            compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasContentDescription(description))
            compose.onNodeWithContentDescription(description).assertIsEnabled().performClick()
        }
        openChapter()
        compose.waitUntil(5_000) { completions.size == 1 }
        compose.onNodeWithText("Kapat").assertIsNotEnabled()
        compose.runOnIdle { writable = false }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Kapat").assertIsEnabled() }.isSuccess
        }
        compose.runOnIdle { writable = true }
        openChapter()
        compose.waitUntil(5_000) { completions.size == 2 }
        compose.onNodeWithText("Kapat").assertIsNotEnabled()
        compose.runOnIdle {
            // A retained callback is revoked before its noncooperative proof has even returned.
            assertFalse(completions.first()())
            oldResult.complete(true)
        }
        compose.waitUntil(5_000) { outcomes.size == 1 }
        assertEquals(listOf(false), outcomes)
        assertEquals(0, closes)
        compose.onNodeWithText("Vurgular ve notlar").assertIsDisplayed()
        compose.onNodeWithText("Kapat").assertIsNotEnabled()
        compose.runOnIdle { currentResult.complete(true) }
        compose.waitUntil(5_000) { closes == 1 }
        assertEquals(listOf(false, true), outcomes)
        compose.onNodeWithText("Vurgular ve notlar").assertDoesNotExist()
        compose.runOnIdle { assertFalse(completions.first()()) }
        assertEquals(1, closes)
        assertEquals(listOf(entry), entries.value)
        assertEquals(0, writes)
    }

    @Test fun `copy sends only the selected saved text on tap and retries a failed clipboard write`() {
        val book = Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", "stored.epub", 100, "hash", 1)
        val first = EpubPassage("first", "Leaf", anchor, 1)
        val text = "  Second\npassage.  "
        val second = EpubPassage("second", text, anchor.copy(end = text.length), 2)
        val entries = MutableStateFlow(listOf(first, second))
        var writes = 0
        val repository = object : EpubPassageRepository {
            override fun observe(bookId: String, contentSha256: String) = entries
            override suspend fun add(bookId: String, contentSha256: String, text: String,
                anchor: com.yaprak.reader.reader.EpubSourceAnchor) { writes++ }
            override suspend fun remove(bookId: String, contentSha256: String, id: String) { writes++ }
        }
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)!!
        clipboard.clearPrimaryClip()
        var failCopy = true
        var copies = 0
        compose.setContent { YaprakTheme {
            EpubPassagesRoute(book, repository, {}, prefillText = "Draft",
                prefillAnchor = anchor.copy(end = 5), onCopy = { copiedText ->
                    copies++
                    check(!failCopy) { "Expected clipboard failure" }
                    copyPlainSelectedText(compose.activity, copiedText)
                })
        } }
        compose.waitForIdle()
        val description = "2. pasaj: ${text.replace('\n', ' ').take(40)}, kopyala"
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasContentDescription(description))
        assertFalse(clipboard.hasPrimaryClip())
        assertEquals(0, copies)
        compose.onNodeWithContentDescription(description).performClick()
        compose.onNodeWithText("Pasaj kopyalanamadı. Yeniden deneyebilirsin.").assertExists()
        assertFalse(clipboard.hasPrimaryClip())
        failCopy = false
        compose.onNodeWithContentDescription(description).performClick()
        compose.onNodeWithText("Pasaj panoya kopyalandı.").assertExists()
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(2, copies)
        assertEquals(0, writes)
        compose.onNodeWithTag("epub-passages-list")
            .performScrollToNode(hasTestTag("epub-passage-draft"))
        compose.onNodeWithTag("epub-passage-draft").assertExists()
        compose.runOnIdle { entries.value = listOf(first) }
        compose.onNodeWithText("Pasaj panoya kopyalandı.").assertDoesNotExist()
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(0, writes)
    }

    @Test fun `copy is blocked while writing or navigating and unavailable before a valid load`() {
        val saved = EpubPassage("one", "Leaf", anchor, 1)
        var state by mutableStateOf(EpubPassagesState(entries = listOf(saved), loading = false, busy = true))
        var openingId by mutableStateOf<String?>(null)
        val copied = mutableListOf<String>()
        compose.setContent { YaprakTheme {
            EpubPassagesDialog(state, {}, {}, {}, {}, {}, {}, openingChapterId = openingId,
                onCopy = { copied += it })
        } }
        val description = "1. pasaj: Leaf, kopyala"
        compose.onNodeWithTag("epub-passages-list").performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), copied)
        compose.runOnIdle { state = state.copy(busy = false); openingId = saved.id }
        compose.onNodeWithContentDescription(description).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), copied)
        compose.runOnIdle { openingId = null }
        compose.onNodeWithContentDescription(description).performClick()
        assertEquals(listOf(saved.id), copied)
        compose.runOnIdle { state = state.copy(loadFailed = true) }
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = true, loadFailed = false) }
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
        assertEquals(listOf(saved.id), copied)
    }
}

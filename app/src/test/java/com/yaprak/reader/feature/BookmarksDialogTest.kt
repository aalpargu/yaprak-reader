package com.yaprak.reader.feature

import com.yaprak.reader.readerMethod
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import com.yaprak.reader.domain.bookmarks.*
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.bookmarks.*
import com.yaprak.reader.feature.reader.ReaderPanelHost
import com.yaprak.reader.feature.reader.ReaderPanelKind
import com.yaprak.reader.feature.reader.ReaderPresentationController
import com.yaprak.reader.reader.BookmarkNavigator
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test fun bothProtectedBookmarkLabelsHaveIndependentCurrentDiscardActionsAndTheSecondRemainsVisibleAlone() {
        val first = RecoveredBookmarkRename("Exact label A")
        val second = RecoveredBookmarkRename("Exact label B")
        var state by mutableStateOf(BookmarksState(entries = listOf(entry), loading = false, recoveredRename = first, additionalRecoveredRename = second))
        var discarded = 0
        compose.setContent { YaprakTheme { BookmarksDialog(state, {}, {}, {}, {}, {}, { false }, {}, {}, {}, {}, {},
            onDiscardRecoveredRename = { expected -> when {
                state.recoveredRename === expected -> { discarded++; state = state.copy(recoveredRename = null) }
                state.additionalRecoveredRename === expected -> { discarded++; state = state.copy(additionalRecoveredRename = null) }
                else -> Unit
            } }) } }
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-additional-recovered-discard"))
        val retired = compose.onNodeWithTag("bookmark-additional-recovered-discard").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(additionalRecoveredRename = RecoveredBookmarkRename(second.label)) }
        compose.waitForIdle(); compose.runOnIdle { retired() }; assertEquals(0, discarded)
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-recovered-discard"))
        compose.onNodeWithTag("bookmark-recovered-discard").performClick(); assertEquals(1, discarded)
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-additional-recovered-label"))
        compose.onNodeWithTag("bookmark-additional-recovered-label").assertIsDisplayed().assertTextEquals("Exact label B")
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-rename-${entry.id}"))
        compose.onNodeWithTag("bookmark-rename-${entry.id}").assertIsNotEnabled()
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-additional-recovered-discard"))
        compose.onNodeWithTag("bookmark-additional-recovered-discard").performClick(); assertEquals(2, discarded)
        assertNull(state.recoveredRename); assertNull(state.additionalRecoveredRename); assertEquals(listOf(entry), state.entries)
    }

    @Test fun recoveredLabelRemainsVisibleDuringAnotherRenameAndOldDiscardCannotConsumeANewerRecovery() {
        val firstRecovery = RecoveredBookmarkRename("  Exact recovered label  ")
        var state by mutableStateOf(BookmarksState(entries = listOf(entry), loading = false,
            recoveredRename = firstRecovery, message = "Transient status"))
        var discarded = 0
        compose.setContent { YaprakTheme { BookmarksDialog(state, {}, {}, {}, {}, {}, { false }, {},
            { state = state.copy(pendingRename = entry, renameDraft = entry.target.label, message = null) },
            { state = state.copy(renameDraft = it, message = null) }, {}, {},
            onDiscardRecoveredRename = { expected ->
                if (state.recoveredRename === expected) { discarded++; state = state.copy(recoveredRename = null) }
            }) } }
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-recovered-discard"))
        val retiredDiscard = compose.onNodeWithTag("bookmark-recovered-discard").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        val replacement = RecoveredBookmarkRename(firstRecovery.label)
        compose.runOnIdle { state = state.copy(recoveredRename = replacement) }
        compose.waitForIdle(); compose.runOnIdle { retiredDiscard() }
        assertEquals(0, discarded); assertEquals(replacement, state.recoveredRename)
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-rename-${entry.id}"))
        compose.onNodeWithTag("bookmark-rename-${entry.id}").assertIsNotEnabled()
        // Recovery is also visible if the caller already owns a separate admitted editor.
        compose.runOnIdle { state = state.copy(pendingRename = entry, renameDraft = entry.target.label, message = null) }
        compose.onNodeWithTag("bookmarks-list").assertDoesNotExist()
        compose.onNodeWithTag("bookmark-rename-field").performTextReplacement("Current rename input")
        assertNull(state.message)
        compose.onNodeWithTag("bookmark-recovered-label").performScrollTo().assertIsDisplayed().assertTextEquals(firstRecovery.label)
        compose.onNodeWithTag("bookmark-recovered-discard").performScrollTo().performClick()
        assertEquals(1, discarded); assertNull(state.recoveredRename)
        assertEquals(entry, state.pendingRename); assertEquals("Current rename input", state.renameDraft)
        assertEquals(listOf(entry), state.entries)
    }

    @Test fun acceptedNavigationClosesOnceBeforeItsOwnEpochChangeWhileFailedAndRevokedActionsKeepTheirCaller() {
        val book=Book("atomic", "Atomic route", null, BookFormat.EPUB, "atomic.epub", "atomic.epub", 1, "a".repeat(64), 1)
        val records=MutableStateFlow(listOf(entry))
        val repository=object : BookmarkRepository {
            override fun observe(bookId: String, contentSha256: String)=records
            override suspend fun add(bookId: String, contentSha256: String, target: BookmarkTarget)=error("Navigation cannot add")
            override suspend fun rename(bookId: String, contentSha256: String, bookmarkId: String, label: String)=error("Navigation cannot rename")
            override suspend fun remove(bookId: String, contentSha256: String, bookmarkId: String)=error("Navigation cannot delete")
        }
        val navigator=object : BookmarkNavigator {
            override fun capture(): BookmarkTarget?=null
            override fun goTo(location: ReadingLocation)=error("The controlled route boundary owns navigation")
        }
        val controller=ReaderPresentationController()
        val sourceReady=AtomicBoolean(true)
        var mode="reject"; var navigations=0; var closes=0
        compose.setContent { YaprakTheme {
            val shown=controller.state.collectAsState().value
            ReaderPanelHost(shown,ReaderPanelKind.BOOKMARKS) {
                BookmarksRoute(book,repository,navigator,{ _, complete ->
                    navigations++
                    when(mode) {
                        "reject" -> false
                        "throw" -> throw IllegalStateException("Rejected native target")
                        else -> {
                            when(mode) {
                                "source" -> sourceReady.set(false)
                                "pause" -> controller.pause()
                                "entry" -> { controller.request(ReaderPanelKind.BOOKMARKS,false); controller.request(ReaderPanelKind.BOOKMARKS,true) }
                            }
                            val accepted=complete()
                            if(accepted) controller.setControls(false)
                            accepted
                        }
                    }
                },{ closes++; controller.request(ReaderPanelKind.BOOKMARKS,false) },
                    canMutate={ sourceReady.get() }, canInteract={
                        controller.state.value.activePanel==shown.activePanel && controller.state.value.epoch==shown.epoch &&
                            controller.state.value.owner==shown.owner
                    },presentationEpoch=shown.epoch)
            }
        } }
        val description="1. yer imi: ${target.label}, konuma git"
        for(next in listOf("reject","throw","source","pause","entry","accept")) {
            compose.runOnIdle {
                mode=next; navigations=0; closes=0; sourceReady.set(true)
                controller.attach("owner-$next"); controller.setControls(true); controller.request(ReaderPanelKind.BOOKMARKS,true)
            }
            compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasContentDescription(description))
            val old=bookmarkApplicationClick(description)
            compose.runOnIdle { old() }
            compose.waitForIdle()
            assertEquals(1,navigations)
            if(next=="accept") {
                compose.onNodeWithText("Yer imleri").assertDoesNotExist()
                assertEquals(1,closes)
                compose.runOnIdle { old() }
                assertEquals(1,navigations); assertEquals(1,closes)
            } else {
                compose.onNodeWithText("Yer imleri").assertIsDisplayed()
                assertEquals(0,closes)
            }
            assertEquals(listOf(entry),records.value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun bookmarkApplicationClick(description: String): () -> Unit {
        val semantics=compose.onNodeWithContentDescription(description).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val node=semantics.javaClass.getDeclaredField("this\$0").apply { isAccessible=true }.get(semantics)
        val base=generateSequence<Class<*>>(node.javaClass) { it.superclass }
            .first { it.name=="androidx.compose.foundation.AbstractClickableNode" }
        return base.readerMethod("getOnClick").apply { isAccessible=true }.invoke(node) as () -> Unit
    }

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

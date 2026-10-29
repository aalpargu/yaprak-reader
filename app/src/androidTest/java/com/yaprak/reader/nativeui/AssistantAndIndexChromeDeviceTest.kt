package com.yaprak.reader.nativeui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaprak.reader.R
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.feature.search.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Compose rendering of controlled, test-owned DTOs; no book or provider IO. */
@RunWith(AndroidJUnit4::class)
class AssistantAndIndexChromeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun prefixPreviewExcludesFutureTextAndRejectsOldBoundaryAndSendCallbacks() {
        var state by mutableStateOf(fixture.assistant())
        var rendered by mutableStateOf(true)
        var choices = 0
        var sends = 0
        compose.setContent {
            YaprakTheme {
                if (rendered) BookAssistantScreen(state, {}, {}, {}, {}, {}, { sends++ }, {}, { _, _ -> }, {},
                    onChapter = { _, _ -> choices++ })
            }
        }
        assistantScroll("assistant-source-S1")
        compose.onNodeWithText(fixture.reference.text).assertIsDisplayed()
        compose.onNodeWithText(fixture.raw).assertDoesNotExist()
        compose.onNodeWithText("LATER_SECRET", substring = true).assertDoesNotExist()
        capture("prefix-preview")
        assistantScroll("assistant-chapter-0")
        val oldChoice = clickAction("assistant-chapter-0")
        assistantScroll("assistant-send")
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
        val oldSend = clickAction("assistant-send")
        assertEquals(0, choices)
        assertEquals(0, sends)
        compose.runOnIdle { state = state.copy(revision = 8, busy = true) }
        compose.runOnIdle { oldChoice(); oldSend() }
        assertEquals(0, choices)
        assertEquals(0, sends)
        compose.runOnIdle { rendered = false }
        compose.runOnIdle { oldChoice(); oldSend() }
        assertEquals(0, choices)
        assertEquals(0, sends)
    }

    @Test fun scopedIndexLabelsOutcomesAndOldAbaOrDisposedCallbacksStayHonest() {
        var state by mutableStateOf(fixture.search())
        var rendered by mutableStateOf(true)
        var builds = 0
        compose.setContent {
            YaprakTheme {
                if (rendered) LibraryTextSearchScreen(state, {}, { _, _ -> }, {}, { builds++ }, {}, {}, {})
            }
        }
        indexScroll("library-text-index-tab")
        compose.onNodeWithTag("library-text-index-tab").performClick()
        indexScroll("library-text-rebuild")
        compose.onNodeWithText(string(R.string.library_text_selected_rebuild)).assertIsDisplayed()
        val old = clickAction("library-text-rebuild")
        compose.runOnIdle { state = state.copy(scope = null, revision = 8) }
        indexScroll("library-text-rebuild")
        compose.onNodeWithText(string(R.string.library_text_rebuild)).assertIsDisplayed()
        compose.runOnIdle { old() }
        compose.runOnIdle { state = state.copy(scope = fixture.scope, revision = 9) }
        compose.runOnIdle { old() }
        assertEquals(0, builds)
        for ((outcome, message) in listOf(
            LibraryTextBookState.NO_TEXT to R.string.library_text_selected_no_text,
            LibraryTextBookState.UNSUPPORTED to R.string.library_text_selected_unsupported,
            LibraryTextBookState.LIMIT to R.string.library_text_selected_limit,
            LibraryTextBookState.FAILED to R.string.library_text_selected_failed,
        )) {
            compose.runOnIdle {
                state = state.copy(build = LibraryTextBuildPhase.Finished(1, false, outcome), revision = state.revision + 1,
                    result = state.result!!.copy(books = listOf(LibraryTextBookStatus(fixture.book, outcome))))
            }
            compose.onNodeWithTag("library-text-list").performScrollToNode(hasText(string(message)))
            compose.onNodeWithText(string(message)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.library_text_selected_ready)).assertDoesNotExist()
            if (outcome == LibraryTextBookState.NO_TEXT) capture("index-no-text")
        }
        compose.runOnIdle { rendered = false }
        compose.runOnIdle { old() }
        assertEquals(0, builds)
    }

    @Test fun prefixControlsAndEvidenceWrapAt320DpWithDoubleFontScale() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    YaprakTheme { BookAssistantScreen(fixture.assistant(), {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}) }
                }
            }
        }
        assistantText(string(R.string.assistant_prefix_scope))
        assistantText(string(R.string.assistant_prefix_choose_help))
        assistantScroll("assistant-chapter-0")
        readable(string(R.string.assistant_prefix_selected))
        assistantText(compose.activity.resources.getQuantityString(R.plurals.assistant_prefix_source_count, 1, 1, 1))
        assistantScroll("assistant-source-S1")
        readable(fixture.reference.text)
        compose.onNodeWithText("LATER_SECRET", substring = true).assertDoesNotExist()
        capture("prefix-font2-320dp")
        assistantScroll("assistant-study-summary")
        readable(string(R.string.assistant_prefix_summary))
        assistantScroll("assistant-send-help")
        readable(string(R.string.assistant_prefix_send_help))
        assistantScroll("assistant-send")
        readable(string(R.string.assistant_send))
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
    }

    @Test fun selectedIndexHelpAndFullButtonWrapAt320DpWithDoubleFontScale() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    YaprakTheme { LibraryTextSearchScreen(fixture.search(), {}, { _, _ -> }, {}, {}, {}, {}, {}) }
                }
            }
        }
        indexScroll("library-text-index-tab")
        compose.onNodeWithTag("library-text-index-tab").performClick()
        compose.onNodeWithTag("library-text-list").performScrollToNode(hasText(string(R.string.library_text_selected_rebuild_help)))
        readable(string(R.string.library_text_selected_rebuild_help))
        indexScroll("library-text-rebuild")
        readable(string(R.string.library_text_selected_rebuild))
        compose.onNodeWithTag("library-text-rebuild").assertIsEnabled()
        capture("index-font2-320dp")
    }


    @Test fun keywordPrefixPreviewShowsOnlyMatchingSlicesAndEditingQueryDisablesOldSend() {
        val original=fixture.assistant()
        val querySources=PreparedBookSources(UUID.randomUUID().toString(),fixture.scope,"Güneş",1,listOf(fixture.reference),
            prefixQuery=BookSourcePrefixQuery(original.chapter!!,original.sources!!.prefix!!.start,65))
        var state by mutableStateOf(original.copy(sourceMode=BookAssistantSourceMode.PREFIX_KEYWORDS,keywords="Güneş",sources=querySources))
        var sends=0
        compose.setContent {YaprakTheme {
            BookAssistantScreen(state, {state=state.copy(keywords=it,sources=null,revision=state.revision+1)}, {}, {}, {}, {}, {sends++}, {}, {_,_->}, {})
        }}
        assertFalse(state.canStudy)
        assistantScroll("assistant-source-S1")
        compose.onNodeWithText(fixture.reference.text).assertIsDisplayed()
        compose.onNodeWithText("LATER_SECRET",substring=true).assertDoesNotExist()
        capture("prefix-query-preview")
        assistantScroll("assistant-send")
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
        val old=clickAction("assistant-send")
        assistantScroll("assistant-keywords")
        compose.onNodeWithTag("assistant-keywords").performTextReplacement("Giriş")
        compose.runOnIdle {old()}
        assertEquals(0,sends);assertNull(state.sources);assertFalse(state.canSend)
        assertEquals(original.chapter,state.chapter)
        assistantScroll("assistant-send")
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
    }

    private fun string(id: Int) = compose.activity.getString(id)
    private fun assistantScroll(tag: String) = compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag(tag))
    private fun indexScroll(tag: String) = compose.onNodeWithTag("library-text-list").performScrollToNode(hasTestTag(tag))
    private fun assistantText(text: String) {
        compose.onNodeWithTag("assistant-list").performScrollToNode(hasText(text))
        readable(text)
    }
    private fun clickAction(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!

    private fun readable(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertFalse("Owned text must wrap without overflow", layout.hasVisualOverflow)
        for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
    }

    private fun capture(name: String) {
        val root = requireNotNull(compose.activity.getExternalFilesDir(null))
        val directory = File(root, "yaprak-native-ui-0.173").apply { assertTrue(isDirectory || mkdirs()) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(directory, "$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(file.length() > 0)
    }

    private object fixture {
        val book = Book("owned-ui-book", "Kaynak kitap 🌿", "Deniz", BookFormat.EPUB,
            "owned-ui.epub", "owned-ui.epub", 12, "a".repeat(64), 1)
        val scope = BookTextScope(book.id, book.sha256)
        const val raw = "Giriş. Güneş doğdu. LATER_SECRET"
        private val until = raw.indexOf(" LATER_SECRET")
        private val generation = UUID.randomUUID().toString()
        private val hit = LibraryTextHit(1, generation, book, LibraryTextTarget(book.id, book.sha256,
            "EPUB/chapter.xhtml", "b".repeat(64)), "Kaynak bölüm", raw, "Güneş doğdu.", 0, 0)
        private val beginning = BookChapterBoundary(0, hit.target.resourceHref, hit.target.resourceSha256, 0)
        private val chapter = BookSourceChapter(scope, generation, "c".repeat(64), 0, "Seçilen sınır", 0,
            beginning.copy(textOffset = raw.indexOf("Güneş")), beginning.copy(textOffset = until), 1)
        val reference = BookSourceReference("S1", hit, BookSourceExcerpt(0, 0, until))
        private val sources = PreparedBookSources(UUID.randomUUID().toString(), scope, "", 1, listOf(reference),
            prefix = BookSourcePrefix(chapter, beginning, 1))
        fun assistant() = BookAssistantState(active = true, book = book, revision = 7,
            sourceMode = BookAssistantSourceMode.PREFIX, chapters = listOf(chapter), chaptersLoaded = true,
            chapter = chapter, sources = sources, question = "Bu metinde ne oldu?")
        fun search() = LibraryTextSearchState(active = true, loading = false, scope = scope, revision = 7,
            result = LibraryTextSearchResult("", emptyList(), 0, listOf(LibraryTextBookStatus(book, LibraryTextBookState.READY, chunkCount = 1)), scope))
    }
}

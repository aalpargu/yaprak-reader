package com.yaprak.reader.feature

import android.content.ComponentName
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import com.yaprak.reader.R
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.LibraryTextBookState
import com.yaprak.reader.feature.reader.IndexedChapterLaunch
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.search.LibraryTextBuildPhase
import com.yaprak.reader.feature.search.LibraryTextSearchActivity
import com.yaprak.reader.feature.search.LibraryTextSearchViewModel
import com.yaprak.reader.fixtures.TestBooks
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real EPUB bytes, current library metadata and derived Room/FTS cache; no simulated extraction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryTextSearchActivityTest {
    @get:Rule val compose = createAndroidComposeRule<LibraryTextSearchActivity>()

    @Suppress("DEPRECATION")
    @Test fun `explicit rebuild indexes actual FB2 then search uses current metadata and sends only verified chapter target`() {
        waitForScreen()
        val container = (compose.activity.application as YaprakApplication).container
        val directory = File(compose.activity.filesDir, "books").apply { mkdirs() }
        val file = File(directory, "actual-indexed.fb2").apply { writeText("<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\"><description><title-info><book-title>Güneş</book-title><lang>tr</lang></title-info></description><body><section><p>Güneş sözcüğünü içeren özgün FB2.</p></section></body></FictionBook>") }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val book = Book("actual-indexed-fb2-book", "Özgün kitap", "Deniz", BookFormat.FB2,
            "source.fb2", file.name, file.length(), hash, 1)
        runBlocking { container.library.add(book) }
        waitForState { it.result?.books?.any { status -> status.book.id == book.id } == true }
        assertEquals(LibraryTextBookState.NOT_INDEXED, model().state.value.result!!.books.single { it.book.id == book.id }.state)
        assertNull(runBlocking { container.reading.resume(book.id, hash) })
        scrollTag("library-text-index-tab").performClick()
        scrollTag("library-text-rebuild").performClick()
        waitForState { it.build is LibraryTextBuildPhase.Finished && !it.loading }
        assertEquals(LibraryTextBookState.READY, model().state.value.result!!.books.single { it.book.id == book.id }.state)
        assertNull(runBlocking { container.reading.resume(book.id, hash) })
        runBlocking { assertTrue(container.library.updateMetadata(book.id, hash, "Güncel başlık 🌿", "Yeni yazar")) }
        scrollTag("library-text-results-tab").performClick()
        scrollTag("library-text-query").performTextReplacement("Güneş")
        scrollTag("library-text-search").performClick()
        waitForState { !it.loading && it.result?.hits?.isNotEmpty() == true }
        val hit = model().state.value.result!!.hits.first()
        assertEquals("Güncel başlık 🌿", hit.book.title)
        assertTrue(hit.snippet.contains("Güneş"))
        assertEquals("Text/b1-p1.xhtml", hit.target.resourceHref)
        val before = compose.activity
        compose.activityRule.scenario.recreate()
        assertNotSame(before, compose.activity)
        waitForScreen()
        waitForState { !it.loading && it.result?.hits?.isNotEmpty() == true }
        scrollTag("library-text-query").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Güneş")))
        scrollTag("library-text-open-${hit.id}").assertIsDisplayed()
        compose.onAllNodesWithText(hit.target.resourceHref, substring = true).assertCountEquals(0)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(bitmap))
                val output = File(System.getProperty("yaprak.screenshotDir"), "fb2-library-index-0.151.0").apply { mkdirs() }
                File(output, "fb2-library-result.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        scrollTag("library-text-open-${hit.id}").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("library-text-list").fetchSemanticsNodes()
            shadowOf(compose.activity).peekNextStartedActivity() != null
        }
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(ReaderActivity::class.java.name, intent.component!!.className)
        assertEquals(hit.target, IndexedChapterLaunch.read(intent)!!.target)
        assertFalse(intent.extras!!.keySet().any { key -> intent.extras!!.get(key) == hit.snippet })
        assertNull(runBlocking { container.reading.resume(book.id, hash) })
        assertTrue(file.isFile)
        assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
    }

    @Test fun `explicit rebuild indexes actual EPUB then search uses current metadata and sends only verified chapter target`() {
        waitForScreen()
        val container = (compose.activity.application as YaprakApplication).container
        val directory = File(compose.activity.filesDir, "books").apply { mkdirs() }
        val file = TestBooks.epub(File(directory, "actual-indexed.epub"))
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val book = Book("actual-indexed-book", "Özgün kitap", "Deniz", BookFormat.EPUB,
            "source.epub", file.name, file.length(), hash, 1)
        runBlocking { container.library.add(book) }
        waitForState { it.result?.books?.any { status -> status.book.id == book.id } == true }
        assertEquals(LibraryTextBookState.NOT_INDEXED, model().state.value.result!!.books.single { it.book.id == book.id }.state)
        assertNull(runBlocking { container.reading.resume(book.id, hash) })
        scrollTag("library-text-index-tab").performClick()
        scrollTag("library-text-rebuild").performClick()
        waitForState { it.build is LibraryTextBuildPhase.Finished && !it.loading }
        assertEquals(LibraryTextBookState.READY, model().state.value.result!!.books.single { it.book.id == book.id }.state)
        assertNull(runBlocking { container.reading.resume(book.id, hash) })
        runBlocking { assertTrue(container.library.updateMetadata(book.id, hash, "Güncel başlık 🌿", "Yeni yazar")) }
        scrollTag("library-text-results-tab").performClick()
        scrollTag("library-text-query").performTextReplacement("Güneş")
        scrollTag("library-text-search").performClick()
        waitForState { !it.loading && it.result?.hits?.isNotEmpty() == true }
        val hit = model().state.value.result!!.hits.first()
        assertEquals("Güncel başlık 🌿", hit.book.title)
        assertTrue(hit.snippet.contains("Güneş"))
        assertEquals("EPUB/chapter.xhtml", hit.target.resourceHref)
        val before = compose.activity
        compose.activityRule.scenario.recreate()
        assertNotSame(before, compose.activity)
        waitForScreen()
        waitForState { !it.loading && it.result?.hits?.isNotEmpty() == true }
        scrollTag("library-text-query").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Güneş")))
        scrollTag("library-text-open-${hit.id}").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("library-text-list").fetchSemanticsNodes()
            shadowOf(compose.activity).peekNextStartedActivity() != null
        }
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(ReaderActivity::class.java.name, intent.component!!.className)
        assertEquals(hit.target, IndexedChapterLaunch.read(intent)!!.target)
        assertFalse(intent.extras!!.keySet().any { key -> intent.extras!!.get(key) == hit.snippet })
        assertNull(runBlocking { container.reading.resume(book.id, hash) })
        assertTrue(file.isFile)
        assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
    }

    @Test fun `recovery gate removes text UI cancels its ownership and retry reopens safe local source`() {
        waitForScreen()
        val container = (compose.activity.application as YaprakApplication).container
        val retained = model()
        compose.runOnIdle { container.startup.requireRecovery() }
        compose.onNodeWithText(compose.activity.getString(R.string.library_text_startup_failed)).assertIsDisplayed()
        compose.onNodeWithTag("library-text-list").assertDoesNotExist()
        assertFalse(retained.state.value.active)
        compose.onNodeWithText(compose.activity.getString(R.string.library_text_retry)).performClick()
        waitForScreen()
        waitForState { it.active && !it.loading }
        compose.onNodeWithTag("library-text-list").assertExists()
    }

    @Test fun `search activity intent is internal and takes no query source or chapter extras`() {
        val intent = LibraryTextSearchActivity.intent(compose.activity)
        assertEquals(LibraryTextSearchActivity::class.java.name, intent.component!!.className)
        assertNull(intent.extras)
        val info = compose.activity.packageManager.getActivityInfo(ComponentName(compose.activity, LibraryTextSearchActivity::class.java), 0)
        assertFalse(info.exported)
    }

    @Test fun `real activity chooses book scope retains it across recreate and uses production source proof without progress`() {
        waitForScreen()
        val container = (compose.activity.application as YaprakApplication).container
        val directory = File(compose.activity.filesDir, "books").apply { mkdirs() }
        val file = TestBooks.epub(File(directory, "scoped-source.epub"))
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val book = Book("scoped-source", "Seçili kaynak kitap", "Deniz", BookFormat.EPUB,
            "source.epub", file.name, file.length(), hash, 1)
        val scope = com.yaprak.reader.domain.search.BookTextScope(book.id, hash)
        runBlocking { container.library.add(book) }
        waitForState { it.result?.books?.any { status -> status.book.id == book.id } == true }
        scrollTag("library-text-scope-pick").performClick()
        compose.onNodeWithTag("library-text-scope-options").performScrollToNode(hasTestTag("library-text-scope-option-${book.id}"))
        compose.onNodeWithTag("library-text-scope-option-${book.id}").performClick()
        waitForState { !it.loading && it.scope == scope }
        assertNull(model().state.value.searchedQuery)
        assertEquals(LibraryTextBookState.NOT_INDEXED, model().state.value.result!!.books.single().state)
        scrollTag("library-text-index-tab").performClick()
        scrollTag("library-text-rebuild").performClick()
        waitForState { it.build is LibraryTextBuildPhase.Finished && !it.loading }
        scrollTag("library-text-results-tab").performClick()
        scrollTag("library-text-query").performTextReplacement("Güneş")
        scrollTag("library-text-search").performClick()
        waitForState { !it.loading && it.result?.hits?.size == 1 }
        assertEquals(scope, model().state.value.result!!.scope)
        val prepared = runBlocking { container.bookSources.prepare(scope, "Güneş") }
        assertEquals(model().state.value.result!!.hits.single(), prepared.references.single().hit)
        assertNull(runBlocking { container.reading.resume(book.id, hash) })
        scrollTag("library-text-assistant").performClick()
        compose.waitUntil(10_000) { shadowOf(compose.activity).peekNextStartedActivity() != null }
        val assistantIntent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(com.yaprak.reader.feature.assistant.BookAssistantActivity::class.java.name, assistantIntent.component!!.className)
        assertEquals(setOf("assistant.book", "assistant.hash", "assistant.keywords"), assistantIntent.extras!!.keySet())
        assertEquals(scope.bookId, assistantIntent.getStringExtra("assistant.book"))
        assertEquals(scope.contentSha256, assistantIntent.getStringExtra("assistant.hash"))
        assertFalse(assistantIntent.extras!!.keySet().any { assistantIntent.extras!!.get(it) == prepared.references.single().hit.text })
        compose.activityRule.scenario.recreate()
        waitForScreen(); waitForState { !it.loading && it.scope == scope }
        assertEquals(scope, model().state.value.result!!.scope)
        scrollTag("library-text-scope-clear").performClick()
        waitForState { !it.loading && it.scope == null }
        assertNull(model().state.value.searchedQuery); assertTrue(model().state.value.result!!.hits.isEmpty())
        assertNull(runBlocking { container.reading.resume(book.id, hash) }); assertTrue(file.isFile)
    }

    private fun model() = ViewModelProvider(compose.activity)[LibraryTextSearchViewModel::class.java]
    private fun waitForScreen() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("library-text-query").fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitForState(predicate: (com.yaprak.reader.feature.search.LibraryTextSearchState) -> Boolean) = compose.waitUntil(20_000) {
        compose.onAllNodesWithTag("library-text-list").fetchSemanticsNodes()
        predicate(model().state.value)
    }
    private fun scrollTag(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("library-text-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
}

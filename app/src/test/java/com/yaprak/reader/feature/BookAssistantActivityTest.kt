package com.yaprak.reader.feature

import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.assistant.BookAnswerFailure
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.BookTextScope
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.feature.search.LibraryTextSearchActivity
import com.yaprak.reader.fixtures.TestBooks
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual composition root + imported EPUB/FB2 + Room/FTS + lifecycle, without any network service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookAssistantActivityTest {
    @get:Rule val compose = createAndroidComposeRule<LibraryTextSearchActivity>()
    @Test fun `production FB2 source preview and unconfigured send write no progress and pause or recovery withdraws sources`() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("library-text-query").fetchSemanticsNodes().isNotEmpty() }
        val container = (compose.activity.application as YaprakApplication).container
        val directory = File(compose.activity.filesDir, "books").apply { mkdirs() }
        val file = File(directory, "assistant-real.fb2").apply { writeText("<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\"><description><title-info><book-title>Özgün kaynak</book-title><lang>tr</lang></title-info></description><body><section><title><p>Gün doğumu</p></title><p>Güneş doğdu.</p></section></body></FictionBook>") }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val book = Book("assistant-real-fb2-book", "Özgün AI kaynak kitabı", "Deniz", BookFormat.FB2, "source.fb2", file.name, file.length(), hash, 1)
        runBlocking { container.library.add(book); container.libraryTextIndex.rebuild {} }
        val intent = BookAssistantActivity.intent(compose.activity, BookTextScope(book.id, hash), "Güneş")
        assertFalse(compose.activity.packageManager.getActivityInfo(ComponentName(compose.activity, BookAssistantActivity::class.java), 0).exported)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val scenario = ActivityScenario.launch<BookAssistantActivity>(intent)
        lateinit var activity: BookAssistantActivity
        scenario.onActivity { activity = it }
        fun model() = ViewModelProvider(activity)[BookAssistantViewModel::class.java]
        fun waitFor(predicate: (BookAssistantState) -> Boolean) = compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("assistant-list").fetchSemanticsNodes(); predicate(model().state.value)
        }
        fun scroll(tag: String): SemanticsNodeInteraction {
            compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag(tag)); return compose.onNodeWithTag(tag)
        }
        try {
            waitFor { it.canPrepare }; assertNull(model().state.value.sources)
            scroll("assistant-prepare").performClick(); waitFor { !it.busy && it.sources != null }
            assertTrue(model().state.value.sources!!.references.single().hit.text.contains("Güneş doğdu."))
            scroll("assistant-question").performTextReplacement("Bu metinde ne oldu?")
            scroll("assistant-send").performClick(); waitFor { it.failure == BookAnswerFailure.CONFIGURATION }
            assertNull(model().state.value.answer); assertNull(runBlocking { container.reading.resume(book.id, hash) })
            scenario.moveToState(Lifecycle.State.CREATED); assertFalse(model().state.value.active); assertNull(model().state.value.sources)
            scenario.moveToState(Lifecycle.State.RESUMED); waitFor { it.canPrepare }; assertNull(model().state.value.sources)
            scroll("assistant-prepare").performClick(); waitFor { !it.busy && it.sources != null }
            compose.runOnIdle { container.startup.requireRecovery() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("assistant-list").fetchSemanticsNodes().isEmpty() }
            assertFalse(model().state.value.active); assertNull(model().state.value.sources)
            assertNull(runBlocking { container.reading.resume(book.id, hash) }); assertEquals(hash,
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
        } finally { scenario.close() }
    }
    @Test fun `production source preview and unconfigured send write no progress and pause or recovery withdraws sources`() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("library-text-query").fetchSemanticsNodes().isNotEmpty() }
        val container = (compose.activity.application as YaprakApplication).container
        val directory = File(compose.activity.filesDir, "books").apply { mkdirs() }
        val file = TestBooks.epub(File(directory, "assistant-real.epub"))
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val book = Book("assistant-real-book", "Özgün AI kaynak kitabı", "Deniz", BookFormat.EPUB, "source.epub", file.name, file.length(), hash, 1)
        runBlocking { container.library.add(book); container.libraryTextIndex.rebuild {} }
        val intent = BookAssistantActivity.intent(compose.activity, BookTextScope(book.id, hash), "Güneş")
        assertFalse(compose.activity.packageManager.getActivityInfo(ComponentName(compose.activity, BookAssistantActivity::class.java), 0).exported)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val scenario = ActivityScenario.launch<BookAssistantActivity>(intent)
        lateinit var activity: BookAssistantActivity
        scenario.onActivity { activity = it }
        fun model() = ViewModelProvider(activity)[BookAssistantViewModel::class.java]
        fun waitFor(predicate: (BookAssistantState) -> Boolean) = compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("assistant-list").fetchSemanticsNodes(); predicate(model().state.value)
        }
        fun scroll(tag: String): SemanticsNodeInteraction {
            compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag(tag)); return compose.onNodeWithTag(tag)
        }
        try {
            waitFor { it.canPrepare }; assertNull(model().state.value.sources)
            scroll("assistant-prepare").performClick(); waitFor { !it.busy && it.sources != null }
            assertTrue(model().state.value.sources!!.references.single().hit.text.contains("Güneş doğdu."))
            scroll("assistant-question").performTextReplacement("Bu metinde ne oldu?")
            scroll("assistant-send").performClick(); waitFor { it.failure == BookAnswerFailure.CONFIGURATION }
            assertNull(model().state.value.answer); assertNull(runBlocking { container.reading.resume(book.id, hash) })
            scenario.moveToState(Lifecycle.State.CREATED); assertFalse(model().state.value.active); assertNull(model().state.value.sources)
            scenario.moveToState(Lifecycle.State.RESUMED); waitFor { it.canPrepare }; assertNull(model().state.value.sources)
            scroll("assistant-prepare").performClick(); waitFor { !it.busy && it.sources != null }
            compose.runOnIdle { container.startup.requireRecovery() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("assistant-list").fetchSemanticsNodes().isEmpty() }
            assertFalse(model().state.value.active); assertNull(model().state.value.sources)
            assertNull(runBlocking { container.reading.resume(book.id, hash) }); assertEquals(hash,
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
        } finally { scenario.close() }
    }
}

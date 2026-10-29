package com.yaprak.reader.reader

import android.graphics.Color
import androidx.room.Room
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.formats.pdf.PdfDocument
import com.yaprak.reader.formats.pdf.PdfPosition
import com.yaprak.reader.formats.pdf.PdfReaderFragment
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Uses Android's real native PDF engine. Requires a connected device or emulator. */
@RunWith(AndroidJUnit4::class)
class PdfReaderDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun native_page_contains_ink_and_closed_document_rejects_render() = runBlocking {
        val file = File(instrumentation.targetContext.cacheDir, "${UUID.randomUUID()}.pdf")
        copyFixture(file)
        val document = withContext(Dispatchers.IO) { PdfDocument.open(file) }
        try {
            assertEquals(2, document.pageCount)
            val bitmap = document.render(0, 800)
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Native render must contain actual page marks", pixels.any { it != Color.WHITE })
                assertTrue(bitmap.width <= 2048 && bitmap.height <= 2048)
            } finally { bitmap.recycle() }
            document.close()
            document.close()
            assertTrue(runCatching { document.render(0, 800) }.exceptionOrNull() is IllegalStateException)
        } finally { document.close(); file.delete() }
    }

    @Test fun PDF_recreates_reopens_and_navigates_to_persistent_page_bookmark() = runBlocking {
        val context = instrumentation.targetContext
        val container = (context.applicationContext as YaprakApplication).container
        withTimeout(30_000) { container.startup.awaitReady() }
        val id = UUID.randomUUID().toString()
        val file = File(context.filesDir, "books/$id.pdf").apply { parentFile!!.mkdirs() }
        copyFixture(file)
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val book = Book(id, "PDF reader test", null, BookFormat.PDF, "test.pdf", file.name, file.length(), hash, System.currentTimeMillis(), 2)
        container.library.add(book)
        try {
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                await { rendered(scenario) == 0 }
                scenario.onActivity { activity ->
                    val pdf = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().single()
                    assertFalse(pdf.move(-1))
                    assertTrue(pdf.move(1))
                    assertNull(pdf.bookmarkPage)
                    assertFalse(pdf.move(1))
                    assertFalse(pdf.goTo(-1))
                    assertFalse(pdf.goTo(2))
                }
                await { rendered(scenario) == 1 }
                await { runBlocking { PdfPosition.decode(container.reading.resume(id, hash), 2) == 1 } }
                compose.onNodeWithText("Yer imleri").performClick()
                compose.onNodeWithText("Bu konumu kaydet").performClick()
                await { runBlocking { container.bookmarks.observe(id, hash).first().size == 1 } }
                compose.onNodeWithText("Kapat").performClick()
                scenario.recreate()
                await { rendered(scenario) == 1 }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            }
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                await { rendered(scenario) == 1 }
                compose.onNodeWithText("Sayfaya git").performClick()
                compose.onNodeWithText("Sayfa numarası").performTextReplacement("3")
                compose.onNodeWithText("Git").assertIsNotEnabled()
                assertEquals(1, rendered(scenario))
                compose.onNodeWithText("Sayfa numarası").performTextReplacement("1")
                compose.onNodeWithText("Git").performClick()
                await { rendered(scenario) == 0 }
                compose.onNodeWithText("Yer imleri").performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithText("Sayfa 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Sayfa 2").performScrollTo().performClick()
                await { rendered(scenario) == 1 }
                compose.onNodeWithText("Yer imleri").performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithText("Sil").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Sil").performScrollTo().performClick()
                compose.onNodeWithText("Vazgeç").performClick()
                assertEquals(1, container.bookmarks.observe(id, hash).first().size)
                compose.onNodeWithText("Sil").performScrollTo().performClick()
                compose.onNodeWithText("Yer imini sil").performClick()
                await { runBlocking { container.bookmarks.observe(id, hash).first().isEmpty() } }
            }
        } finally {
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7, LibraryDatabase.MIGRATION_7_8, LibraryDatabase.MIGRATION_8_9, LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19).build()
            try {
                assertTrue(db.reading().observed(id, hash).isEmpty())
            } finally {
                db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = ?", arrayOf(id))
                db.close()
                file.delete()
            }
        }
    }

    private fun rendered(scenario: ActivityScenario<ReaderActivity>): Int? {
        var page: Int? = null
        scenario.onActivity { page = it.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().firstOrNull()?.renderedPage?.value }
        return page
    }

    private fun copyFixture(file: File) = instrumentation.context.assets.open("books/two-pages.pdf").use { input ->
        file.outputStream().use { input.copyTo(it) }
    }

    private fun await(condition: () -> Boolean) {
        // Android painting and the interop Compose surface both need to make progress.
        compose.waitUntil(20_000) { condition() }
    }
}

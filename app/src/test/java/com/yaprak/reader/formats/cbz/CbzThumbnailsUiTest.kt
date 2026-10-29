package com.yaprak.reader.formats.cbz

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.R
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.reader.PdfThumbnailsDialog
import com.yaprak.reader.fixtures.CoverTestImages
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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

/** Native dialog uses real ZIP pixels and source validation; navigation is a controlled UI boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CbzThumbnailsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun doubleTypeActualComicPreviewPixelsAndCloseFitWithoutWritingReadingState() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "books").apply { mkdirs() }; val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("10.png", "2.png")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(CoverTestImages.png(480, 960)); zip.closeEntry()
            }
        }
        val bytes = file.readBytes()
        val book = Book(id, "Çizgi", null, BookFormat.CBZ, "Çizgi.cbz", file.name, bytes.size.toLong(), cbzHash(bytes), 1, 2)
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val library = RoomLibraryRepository(db.books())
        val archive = runBlocking { library.add(book); withContext(Dispatchers.IO) { CbzArchive.read(file) } }
        val document = CbzDocument(archive, book, VerifiedBookFile(dir), library, Mutex(), validateRead = {})
        val source = CbzThumbnailsSource(document); val open = mutableStateOf(true); val selected = mutableListOf<Int>()
        try {
            compose.setContent { YaprakTheme {
                if (open.value) PdfThumbnailsDialog("actual-comic", source.pageCount, 0,
                    source::render, { open.value }, { selected += it; true }, { open.value = false })
            } }
            compose.onNodeWithTag("pdf-thumbnails-grid").performScrollToIndex(1)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("pdf-thumbnail-image-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val cached = runBlocking { source.render(0) }
            assertEquals(listOf("2.png", "10.png"), archive.pages.map { it.entry.name })
            for (label in listOf(context.getString(R.string.pdf_thumbnails_title), context.getString(R.string.pdf_thumbnails_close),
                context.getString(R.string.pdf_thumbnail_page, 1))) {
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val layout = results.single(); assertFalse("Text overflow: $label", layout.hasVisualOverflow)
                assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            }
            compose.onNodeWithTag("pdf-thumbnails-close").assertIsDisplayed()
            val grid = compose.onNodeWithTag("pdf-thumbnails-grid").fetchSemanticsNode().boundsInRoot
            val close = compose.onNodeWithTag("pdf-thumbnails-close").fetchSemanticsNode().boundsInRoot
            assertTrue(grid.bottom <= close.top)
            compose.runOnIdle {
                val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
                val capture = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(capture)); val pixels = IntArray(capture.width * capture.height)
                    capture.getPixels(pixels, 0, capture.width, 0, 0, capture.width, capture.height)
                    assertTrue("Actual comic pixels must be painted", pixels.count { it == Color.rgb(23, 98, 65) } > 500)
                    val output = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
                    File(output, "cbz-thumbnails-double-type.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { capture.recycle() }
            }
            assertTrue(selected.isEmpty()); assertNull(runBlocking { RoomReadingStateRepository(db).resume(book.id, book.sha256) })
            compose.onNodeWithTag("pdf-thumbnail-0").performClick(); assertEquals(listOf(0), selected)
            compose.waitForIdle(); assertFalse(open.value); assertFalse(cached.isRecycled)
            assertArrayEquals(bytes, file.readBytes()); assertNull(runBlocking { RoomReadingStateRepository(db).resume(book.id, book.sha256) })
        } finally { compose.runOnIdle { open.value = false }; source.close(); document.close(); db.close(); file.delete() }
    }
}

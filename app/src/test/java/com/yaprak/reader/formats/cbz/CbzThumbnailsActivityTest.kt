package com.yaprak.reader.formats.cbz

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.reader.PdfThumbnailsRequest
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.ReaderViewModel
import com.yaprak.reader.fixtures.CoverTestImages
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real comic document, native fragments, actual Room and Activity window ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CbzThumbnailsActivityTest {
    private class Fixture(val app: YaprakApplication, val book: Book, val bytes: ByteArray, val file: File,
        val controller: ActivityController<ReaderActivity>) : java.io.Closeable {
        val activity get() = controller.get()
        override fun close() { controller.pause().stop().destroy(); file.delete() }
    }
    private fun fixture(): Fixture {
        val app = ApplicationProvider.getApplicationContext<YaprakApplication>(); val container = app.container
        runBlocking { container.startup.awaitReady() }
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            for (name in listOf("10.png", "2.png")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(CoverTestImages.png(80, 120)); zip.closeEntry()
            }
        } }.toByteArray()
        val id = UUID.randomUUID().toString()
        val book = Book(id, "Çizgi", null, BookFormat.CBZ, "pages.cbz", "$id.cbz", bytes.size.toLong(), cbzHash(bytes), 1, 2)
        val file = File(app.filesDir, "books/${book.storageName}").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        runBlocking { container.library.add(book) }
        return Fixture(app, book, bytes, file, Robolectric.buildActivity(ReaderActivity::class.java,
            ReaderActivity.intent(app, id)).setup().visible().windowFocusChanged(true))
    }
    private fun idleUntil(condition: () -> Boolean) {
        val until = System.nanoTime() + 15_000_000_000
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("Actual comic did not settle", condition())
    }
    private fun native(activity: ReaderActivity) = activity.supportFragmentManager.fragments.filterIsInstance<CbzReaderFragment>().singleOrNull()
    private fun loaded(activity: ReaderActivity) = native(activity)?.view?.let { root ->
        (root as FrameLayout).getChildAt(1).let { (it as ProgressBar).visibility == View.GONE } && !native(activity)!!.renderFailed.value
    } == true
    private fun draw(activity: ReaderActivity) {
        val image = (native(activity)!!.requireView() as FrameLayout).getChildAt(0) as CbzPageView
        image.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
        image.layout(0, 0, 411, 891)
        val bitmap = Bitmap.createBitmap(411, 891, Bitmap.Config.ARGB_8888)
        try { image.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }
    private fun property(activity: ReaderActivity, getter: String) = activity.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(activity)
    private fun request(activity: ReaderActivity) = property(activity, "getPdfThumbnailsRequest") as PdfThumbnailsRequest?
    private fun open(activity: ReaderActivity) { activity.javaClass.getDeclaredMethod("openPdfThumbnails").apply { isAccessible = true }.invoke(activity) }
    private fun dismiss(activity: ReaderActivity, expected: PdfThumbnailsRequest) {
        activity.javaClass.getDeclaredMethod("dismissPdfThumbnails", PdfThumbnailsRequest::class.java).apply { isAccessible = true }.invoke(activity, expected)
    }
    private fun current(activity: ReaderActivity, expected: PdfThumbnailsRequest) = activity.javaClass.getDeclaredMethod("isPdfThumbnailsCurrent", PdfThumbnailsRequest::class.java)
        .apply { isAccessible = true }.invoke(activity, expected) as Boolean
    private fun select(activity: ReaderActivity, expected: PdfThumbnailsRequest, page: Int) = activity.javaClass.getDeclaredMethod("selectPdfThumbnail", PdfThumbnailsRequest::class.java,
        Int::class.javaPrimitiveType!!).apply { isAccessible = true }.invoke(activity, expected, page) as Boolean

    @Test fun actualPreviewBrowserPreservesSourceAndProgressUntilSelectedPageReallyDraws() {
        fixture().use { f ->
            idleUntil { loaded(f.activity) }; val model = ViewModelProvider(f.activity)[ReaderViewModel::class.java]
            open(f.activity); assertNull(request(f.activity)) // Decoded but never drawn is not an openable reader.
            draw(f.activity); idleUntil { model.latestLocation?.resourceHref == "cbz/2.png" }
            idleUntil { runBlocking { f.app.container.reading.resume(f.book.id, f.book.sha256)?.resourceHref == "cbz/2.png" } }
            assertTrue(f.activity.window.decorView.hasWindowFocus())
            f.controller.windowFocusChanged(false); open(f.activity); assertNull(request(f.activity))
            f.controller.windowFocusChanged(true)
            open(f.activity); val window = requireNotNull(request(f.activity)); assertTrue(current(f.activity, window))
            assertEquals(2, window.pageCount); assertEquals(0, window.currentPage)
            val image = runBlocking { window.source.render(1) }; assertEquals(80, image.width); assertEquals(120, image.height)
            assertEquals("cbz/2.png", model.latestLocation!!.resourceHref)
            assertFalse(select(f.activity, window, 2)); assertSame(window, request(f.activity))
            assertTrue(select(f.activity, window, 1)); assertNull(request(f.activity))
            idleUntil { loaded(f.activity) }; assertEquals("cbz/2.png", model.latestLocation!!.resourceHref)
            draw(f.activity); idleUntil { model.latestLocation?.resourceHref == "cbz/10.png" }
            assertFalse(current(f.activity, window)); assertFalse(select(f.activity, window, 0)); assertFalse(image.isRecycled)
            assertArrayEquals(f.bytes, f.file.readBytes())
        }
    }
    @Test fun reopenPauseAndActivityRecreationRevokeOldWindowAndNeverReplaySelection() {
        fixture().use { f ->
            idleUntil { loaded(f.activity) }; draw(f.activity)
            val model = ViewModelProvider(f.activity)[ReaderViewModel::class.java]
            idleUntil { model.latestLocation != null }; open(f.activity); val old = requireNotNull(request(f.activity))
            dismiss(f.activity, old); f.controller.windowFocusChanged(true); open(f.activity); val next = requireNotNull(request(f.activity))
            assertNotEquals(old.id, next.id); assertFalse(current(f.activity, old)); assertFalse(select(f.activity, old, 1))
            dismiss(f.activity, old); assertSame(next, request(f.activity))
            f.controller.pause(); assertNull(request(f.activity)); assertFalse(current(f.activity, next)); assertFalse(select(f.activity, next, 1))
            f.controller.resume().visible().windowFocusChanged(true); idleUntil { loaded(f.activity) }; draw(f.activity)
            idleUntil { native(f.activity)!!.stablePage == 0 }; assertNull(request(f.activity))
            open(f.activity); val beforeRotation = requireNotNull(request(f.activity)); f.controller.recreate().visible().windowFocusChanged(true)
            idleUntil { loaded(f.activity) }; draw(f.activity)
            assertSame(model, ViewModelProvider(f.activity)[ReaderViewModel::class.java])
            assertNull(request(f.activity)); assertFalse(current(f.activity, beforeRotation)); assertFalse(select(f.activity, beforeRotation, 1))
            assertEquals(0, native(f.activity)!!.stablePage); assertEquals("cbz/2.png", model.latestLocation!!.resourceHref)
            assertArrayEquals(f.bytes, f.file.readBytes())
        }
    }
}

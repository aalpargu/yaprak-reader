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
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.ReaderState
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real AppContainer/ReaderActivity/retained VM/native comic/Room, not a fake reader boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CbzActivityTest {
    private fun idleUntil(condition: () -> Boolean) {
        val until = System.nanoTime() + 15_000_000_000
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("Actual reader did not settle", condition())
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
    @Test fun realActivityDrawRecordsOnlyOriginalComicLocationsAndRecreationRetainsActualPage() {
        val app = ApplicationProvider.getApplicationContext<YaprakApplication>(); val container = app.container
        runBlocking { container.startup.awaitReady() }
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            for (name in listOf("10.png", "2.png")) { zip.putNextEntry(ZipEntry(name)); zip.write(CoverTestImages.png()); zip.closeEntry() }
        } }.toByteArray()
        val id = UUID.randomUUID().toString()
        val book = Book(id, "Çizgi", null, BookFormat.CBZ, "pages.cbz", "$id.cbz", bytes.size.toLong(), cbzHash(bytes), 1, 2)
        val file = File(app.filesDir, "books/${book.storageName}").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        runBlocking { container.library.add(book) }
        val controller = Robolectric.buildActivity(ReaderActivity::class.java, ReaderActivity.intent(app, id)).setup().visible()
        try {
            idleUntil { loaded(controller.get()) }
            val first = controller.get(); val model = ViewModelProvider(first)[ReaderViewModel::class.java]
            assertTrue(model.state.value is ReaderState.Ready)
            assertNull(runBlocking { container.reading.resume(id, book.sha256) })
            draw(first)
            idleUntil { model.latestLocation?.resourceHref == "cbz/2.png" }
            val select = ReaderActivity::class.java.getDeclaredMethod("selectPage", Int::class.javaPrimitiveType).apply { isAccessible = true }
            assertEquals(true, select.invoke(first, 1)); idleUntil { loaded(first) }; draw(first)
            idleUntil { model.latestLocation?.resourceHref == "cbz/10.png" }
            controller.recreate().visible()
            idleUntil { loaded(controller.get()) }; draw(controller.get())
            assertSame(model, ViewModelProvider(controller.get())[ReaderViewModel::class.java])
            assertEquals(1, native(controller.get())!!.stablePage)
            assertEquals("cbz/10.png", model.latestLocation!!.resourceHref)
            idleUntil { runBlocking { container.reading.resume(id, book.sha256)?.resourceHref == "cbz/10.png" } }
            assertArrayEquals(bytes, file.readBytes())
        } finally { controller.pause().stop().destroy(); file.delete() }
    }
}

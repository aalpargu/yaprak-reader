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
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.ReaderSurface
import com.yaprak.reader.data.persistence.LibraryDatabase
import kotlinx.coroutines.flow.first
import java.io.*
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w900dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CbzSpreadActivityTest {
    private class Fixture(val app: YaprakApplication, val book: Book, val bytes: ByteArray, val file: File,
        val controller: ActivityController<ReaderActivity>) : Closeable {
        val activity get() = controller.get()
        override fun close() { controller.pause().stop().destroy(); file.delete() }
    }
    private fun fixture(): Fixture {
        val app = ApplicationProvider.getApplicationContext<YaprakApplication>(); runBlocking { app.container.startup.awaitReady() }
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            repeat(5) { index -> zip.putNextEntry(ZipEntry("${index + 1}.png"))
                zip.write(CbzSpreadFixture.png(80, 120, CbzSpreadFixture.colors[index])); zip.closeEntry() }
        } }.toByteArray()
        val id = UUID.randomUUID().toString()
        val book = Book(id, "Çizgi", null, BookFormat.CBZ, "pages.cbz", "$id.cbz", bytes.size.toLong(), cbzHash(bytes), 1, 5)
        val file = File(app.filesDir, "books/${book.storageName}").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        runBlocking { app.container.library.add(book) }
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
    private fun preferences(activity: ReaderActivity) = ViewModelProvider(activity)[ComicBookViewPreferencesViewModel::class.java]
    private fun settled(activity: ReaderActivity) = loaded(activity) && preferences(activity).state.value.status == ComicBookViewStatus.READY
    private fun draw(activity: ReaderActivity) {
        val image = (native(activity)!!.requireView() as FrameLayout).getChildAt(0) as CbzPageView
        image.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        image.layout(0, 0, 900, 600); idleUntil { loaded(activity) }
        val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        try { image.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }
    private fun surface(activity: ReaderActivity) = activity.javaClass.getDeclaredField("surface").apply { isAccessible = true }.get(activity) as ReaderSurface
    private fun flag(activity: ReaderActivity, setter: String, value: Boolean) {
        activity.javaClass.getDeclaredMethod(setter, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, value)
    }
    private fun toggle(activity: ReaderActivity, owner: ReaderSurface, expected: Boolean) {
        activity.javaClass.getDeclaredMethod("toggleComicSpread", ReaderSurface::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, owner, expected)
    }
    @Test fun actualRoomAndRetainedActivityKeepRightAnchorAndRejectOldOwnerOrExpectedChoice() {
        fixture().use { f ->
            idleUntil { settled(f.activity) }; draw(f.activity)
            val model = ViewModelProvider(f.activity)[ReaderViewModel::class.java]; val old = surface(f.activity)
            assertTrue(old.pageNavigator!!.goTo(1)); draw(f.activity)
            idleUntil { model.latestLocation?.resourceHref == "cbz/2.png" }
            toggle(f.activity, old, false); idleUntil { old.comicSpread!!.preferred.value && settled(f.activity) }
            assertNull(old.pageNavigator!!.captureStablePage()); assertEquals("cbz/2.png", model.latestLocation!!.resourceHref)
            draw(f.activity); idleUntil { model.latestLocation?.resourceHref == "cbz/2.png" }
            toggle(f.activity, old, false); assertTrue(old.comicSpread!!.preferred.value) // stale double-enable cannot disable
            f.controller.recreate().visible().windowFocusChanged(true); idleUntil { settled(f.activity) }; draw(f.activity)
            assertSame(model, ViewModelProvider(f.activity)[ReaderViewModel::class.java])
            val fresh = surface(f.activity); assertNotSame(old, fresh)
            assertTrue(fresh.comicSpread!!.preferred.value); assertEquals(1, fresh.pageNavigator!!.captureStablePage())
            assertEquals(listOf(0, 1), native(f.activity)!!.drawnPages.value)
            toggle(f.activity, old, true); assertTrue(fresh.comicSpread!!.preferred.value)
            toggle(f.activity, fresh, true); idleUntil { !fresh.comicSpread!!.preferred.value && settled(f.activity) }; draw(f.activity)
            assertEquals(1, fresh.pageNavigator!!.captureStablePage()); assertEquals(listOf(1), native(f.activity)!!.drawnPages.value)
            idleUntil { runBlocking { f.app.container.reading.resume(f.book.id, f.book.sha256)?.resourceHref == "cbz/2.png" } }
            assertArrayEquals(f.bytes, f.file.readBytes())
        }
    }
    @Test fun focusLifecycleHiddenChromeAndCompetingToolsBlockActualToggle() {
        fixture().use { f ->
            idleUntil { settled(f.activity) }; draw(f.activity); val owner = surface(f.activity)
            for (setter in listOf("setContentsOpen", "setBookNotesOpen", "setSavedWordsOpen", "setBookAppearanceOpen",
                "setBrightnessOpen", "setLoadFailed", "setSaveFailed")) {
                flag(f.activity, setter, true); toggle(f.activity, owner, false); assertFalse("Admitted $setter", owner.comicSpread!!.preferred.value)
                flag(f.activity, setter, false)
            }
            flag(f.activity, "setChrome", false); toggle(f.activity, owner, false); assertFalse(owner.comicSpread!!.preferred.value)
            flag(f.activity, "setChrome", true); f.controller.windowFocusChanged(false)
            toggle(f.activity, owner, false); assertFalse(owner.comicSpread!!.preferred.value)
            f.controller.windowFocusChanged(true); f.controller.pause(); toggle(f.activity, owner, false); assertFalse(owner.comicSpread!!.preferred.value)
            f.controller.resume().visible().windowFocusChanged(true); idleUntil { settled(f.activity) }; draw(f.activity)
            toggle(f.activity, owner, false); idleUntil { owner.comicSpread!!.preferred.value && settled(f.activity) }; draw(f.activity)
            assertEquals(0, owner.pageNavigator!!.captureStablePage())
        }
    }
    @Test fun newReaderSessionLoadsSavedChoiceKeepsRightAnchorAndNarrowFallbackDoesNotChangeStoredTrue() {
        val f = fixture(); var reopened: ActivityController<ReaderActivity>? = null
        try {
            idleUntil { settled(f.activity) }; draw(f.activity); val originalSurface = surface(f.activity)
            assertTrue(originalSurface.pageNavigator!!.goTo(1)); draw(f.activity)
            toggle(f.activity, originalSurface, false); idleUntil { originalSurface.comicSpread!!.preferred.value && settled(f.activity) }
            draw(f.activity)
            idleUntil { runBlocking { f.app.container.reading.resume(f.book.id, f.book.sha256)?.resourceHref == "cbz/2.png" } }
            assertTrue(runBlocking { f.app.container.bookComicViewPreferences.observe(f.book.id, f.book.sha256).first().doublePagePreferred })
            val oldReader = ViewModelProvider(f.activity)[ReaderViewModel::class.java]
            f.controller.pause().stop().destroy()
            reopened = Robolectric.buildActivity(ReaderActivity::class.java, ReaderActivity.intent(f.app, f.book.id)).setup().visible().windowFocusChanged(true)
            val activity = reopened.get(); idleUntil { settled(activity) }; draw(activity)
            assertNotSame(oldReader, ViewModelProvider(activity)[ReaderViewModel::class.java])
            val fresh = surface(activity); assertNotSame(originalSurface, fresh)
            assertTrue(fresh.comicSpread!!.preferred.value); assertEquals(1, fresh.pageNavigator!!.captureStablePage())
            assertEquals(listOf(0, 1), native(activity)!!.drawnPages.value)
            val image = (native(activity)!!.requireView() as FrameLayout).getChildAt(0) as CbzPageView
            image.layoutParams = image.layoutParams.apply { width = 400; height = 600 }
            image.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            image.layout(0, 0, 400, 600); idleUntil { loaded(activity) }
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            try { image.draw(Canvas(bitmap)); assertEquals(android.graphics.Color.BLUE, bitmap.getPixel(200, 300)) }
            finally { bitmap.recycle() }
            assertEquals(listOf(1), native(activity)!!.drawnPages.value); assertEquals(1, fresh.pageNavigator!!.captureStablePage())
            assertTrue(fresh.comicSpread!!.preferred.value)
            assertTrue(runBlocking { f.app.container.bookComicViewPreferences.observe(f.book.id, f.book.sha256).first().doublePagePreferred })
            toggle(activity, originalSurface, true); assertTrue(fresh.comicSpread!!.preferred.value)
            toggle(activity, fresh, true); idleUntil { !fresh.comicSpread!!.preferred.value && settled(activity) }
            assertFalse(runBlocking { f.app.container.bookComicViewPreferences.observe(f.book.id, f.book.sha256).first().doublePagePreferred })
            assertArrayEquals(f.bytes, f.file.readBytes())
        } finally { reopened?.pause()?.stop()?.destroy(); if (reopened == null) f.controller.pause().stop().destroy(); f.file.delete() }
    }
    @Test fun actualRoomAbortLeavesNativeSingleAndOldCallbackCannotRetryButFreshRetryAppliesSavedPair() {
        fixture().use { f ->
            idleUntil { settled(f.activity) }; draw(f.activity); val owner = surface(f.activity)
            val vm = preferences(f.activity); val old = vm.state.value
            val database = f.app.container.javaClass.getDeclaredField("database").apply { isAccessible = true }.get(f.app.container) as LibraryDatabase
            runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                database.openHelper.writableDatabase.execSQL("CREATE TRIGGER refuse_comic BEFORE INSERT ON book_cbz_view_preferences BEGIN SELECT RAISE(ABORT, 'controlled'); END")
            } }
            toggle(f.activity, owner, false); idleUntil { vm.state.value.status == ComicBookViewStatus.SAVE_FAILED }
            assertFalse(owner.comicSpread!!.preferred.value); assertEquals(0, owner.pageNavigator!!.captureStablePage())
            assertFalse(runBlocking { f.app.container.bookComicViewPreferences.observe(f.book.id, f.book.sha256).first().doublePagePreferred })
            runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("DROP TRIGGER refuse_comic") } }
            fun retry(lease: ComicBookViewPreferencesState) {
                f.activity.javaClass.getDeclaredMethod("retryComicBookView", ReaderSurface::class.java, ComicBookViewPreferencesState::class.java)
                    .apply { isAccessible = true }.invoke(f.activity, owner, lease)
            }
            retry(old); assertEquals(ComicBookViewStatus.SAVE_FAILED, vm.state.value.status)
            retry(vm.state.value); idleUntil { owner.comicSpread!!.preferred.value && settled(f.activity) }; draw(f.activity)
            assertEquals(listOf(0, 1), native(f.activity)!!.drawnPages.value); assertEquals(0, owner.pageNavigator!!.captureStablePage())
            assertTrue(runBlocking { f.app.container.bookComicViewPreferences.observe(f.book.id, f.book.sha256).first().doublePagePreferred })
        }
    }
    @Test fun nativePositionFlowsIntoActualActivityOnlyAfterDrawAndLastPageDoesNotInventReadPrefix() {
        fixture().use { f ->
            fun displayed(): Int? = f.activity.javaClass.getDeclaredMethod("getBookPositionPercent")
                .apply { isAccessible = true }.invoke(f.activity) as Int?
            idleUntil { settled(f.activity) }; val owner = surface(f.activity)
            assertNull(runBlocking { owner.bookPositionPercent.first() }); assertNull(displayed())
            draw(f.activity); idleUntil { displayed() == 20 }
            assertTrue(owner.pageNavigator!!.goTo(1)); idleUntil { loaded(f.activity) && displayed() == null }
            assertNull(runBlocking { owner.bookPositionPercent.first() }); draw(f.activity); idleUntil { displayed() == 40 }
            toggle(f.activity, owner, false); idleUntil { owner.comicSpread!!.preferred.value && settled(f.activity) && displayed() == null }
            draw(f.activity); idleUntil { displayed() == 40 }; assertEquals(1, owner.pageNavigator!!.captureStablePage())
            f.controller.pause(); assertNull(runBlocking { owner.bookPositionPercent.first() })
            f.controller.resume().visible().windowFocusChanged(true); idleUntil { settled(f.activity) && displayed() == null }
            draw(f.activity); idleUntil { displayed() == 40 }
            f.file.appendBytes(byteArrayOf(0)); assertTrue(owner.pageNavigator!!.goTo(4))
            idleUntil { native(f.activity)!!.renderFailed.value && displayed() == null }
            assertNull(runBlocking { owner.bookPositionPercent.first() })
            f.file.writeBytes(f.bytes); assertTrue(owner.pageNavigator!!.goTo(4)); draw(f.activity); idleUntil { displayed() == 100 }
            idleUntil { runBlocking { f.app.container.reading.resume(f.book.id, f.book.sha256)?.resourceHref == "cbz/5.png" } }
            assertEquals(BookReadingStatus.UNSET, runBlocking { f.app.container.library.findById(f.book.id) }!!.organization.readingStatus)
            val database = f.app.container.javaClass.getDeclaredField("database").apply { isAccessible = true }.get(f.app.container) as LibraryDatabase
            val observed = runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM observed_text WHERE bookId=?", arrayOf(f.book.id))
                    .use { assertTrue(it.moveToFirst()); it.getLong(0) }
            } }
            assertEquals(0L, observed); assertArrayEquals(f.bytes, f.file.readBytes())
        }
    }

    @Test fun actualChangedSourceCannotCreateAdjacentProgressOnToggleAndNativeRetryRestoresPair() {
        fixture().use { f ->
            idleUntil { settled(f.activity) }; draw(f.activity)
            val owner = surface(f.activity); val model = ViewModelProvider(f.activity)[ReaderViewModel::class.java]
            idleUntil { model.latestLocation?.resourceHref == "cbz/1.png" }
            f.file.appendBytes(byteArrayOf(0)); toggle(f.activity, owner, false)
            idleUntil { native(f.activity)!!.renderFailed.value }
            assertTrue(owner.comicSpread!!.preferred.value); assertNull(owner.pageNavigator!!.captureStablePage())
            assertEquals("cbz/1.png", model.latestLocation!!.resourceHref)
            toggle(f.activity, owner, true); assertTrue(owner.comicSpread!!.preferred.value)
            f.file.writeBytes(f.bytes); assertTrue(owner.pageNavigator!!.goTo(0)); draw(f.activity)
            assertEquals(listOf(0, 1), native(f.activity)!!.drawnPages.value); assertEquals(0, owner.pageNavigator!!.captureStablePage())
            assertArrayEquals(f.bytes, f.file.readBytes())
        }
    }
}

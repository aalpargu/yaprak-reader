package com.yaprak.reader.formats.cbz

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.importing.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.storage.LocalBookFiles
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.formats.BookMetadataReader
import com.yaprak.reader.fixtures.CoverTestImages
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import java.io.File
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CbzReaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val originals get() = File(context.filesDir, "books").apply { mkdirs() }
    private fun open(validate: suspend () -> Unit = {}): Pair<LibraryDatabase, ReaderSession> = runBlocking {
        val source = temp.newFile("pages.cbz")
        ZipOutputStream(source.outputStream()).use { zip ->
            for ((name, bytes) in listOf("2.png" to CoverTestImages.png(), "10.png" to CoverTestImages.png(12, 8))) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val library = RoomLibraryRepository(db.books())
        val importer = DefaultBookImporter(DocumentProvider { SourceDocument("pages.cbz") { source.inputStream() } },
            LocalBookFiles(originals), BookMetadataReader { file, _ -> CbzMetadataReader().read(file) }, library)
        assertEquals(ImportResult.Added, importer.import("content://comic"))
        db to CbzReaderLoader(context, library, RoomReadingStateRepository(db), validateRead = validate).open(library.observeBooks().first().single().id)
    }
    private fun idleUntil(condition: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("Native page did not settle", condition())
    }
    private fun image(surface: ReaderSurface) = (surface.fragment.requireView() as FrameLayout).getChildAt(0) as CbzPageView
    private fun loaded(surface: ReaderSurface): Boolean = (surface.fragment.view as? FrameLayout)?.let { root ->
        (root.getChildAt(1) as ProgressBar).visibility == View.GONE && !(surface.fragment as CbzReaderFragment).renderFailed.value
    } == true
    private fun draw(surface: ReaderSurface): Bitmap {
        val view = image(surface)
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 320, 480)
        val result = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(result)); return result
    }
    @Test fun onlyActualVisibleDrawGrantsLocationBookmarkAndTimePauseAndTurnInvalidateIt() {
        val (db, session) = open(); val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
        try {
            val activity = controller.get(); val root = FrameLayout(activity).apply { id = 1001 }
            activity.setContentView(root)
            val surface = session.createSurface(null, false, {}, {})
            assertFalse(runBlocking { surface.readingVisible.first() }); assertNull(surface.bookmarkNavigator!!.capture())
            activity.supportFragmentManager.beginTransaction().replace(1001, surface.fragment).commitNow()
            idleUntil { loaded(surface) }
            assertNull(surface.pageNavigator!!.captureStablePage()) // Decoding without a draw is insufficient.
            draw(surface).also { assertEquals(Color.rgb(23, 98, 65), it.getPixel(160, 240)); it.recycle() }
            assertTrue(runBlocking { surface.readingVisible.first() })
            assertEquals(0, surface.pageNavigator!!.captureStablePage()); assertEquals("cbz/2.png", surface.bookmarkNavigator!!.capture()!!.location.resourceHref)
            assertTrue(surface.forward()); assertNull(surface.pageNavigator!!.captureStablePage()); assertFalse(runBlocking { surface.readingVisible.first() })
            idleUntil { loaded(surface) }; draw(surface).recycle()
            assertEquals(1, surface.pageNavigator!!.captureStablePage()); assertFalse(surface.forward())
            val bookmark = surface.bookmarkNavigator!!.capture()!!
            controller.pause(); assertFalse(runBlocking { surface.readingVisible.first() }); assertNull(surface.bookmarkNavigator!!.capture())
            assertFalse(surface.bookmarkNavigator!!.goTo(bookmark.location))
            controller.resume(); idleUntil { loaded(surface) }; draw(surface).recycle()
            assertEquals(1, surface.pageNavigator!!.captureStablePage())
            session.close(); assertFalse(runBlocking { surface.readingVisible.first() }); assertFalse(surface.backward())
        } finally { controller.pause().stop().destroy(); session.close(); db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun invisibleOrClippedImageAndReplacedSurfaceCannotClaimObservedContent() {
        val (db, session) = open(); val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
        try {
            val activity = controller.get(); activity.setContentView(FrameLayout(activity).apply { id = 1002 })
            val first = session.createSurface(null, false, {}, {})
            activity.supportFragmentManager.beginTransaction().replace(1002, first.fragment).commitNow()
            idleUntil { loaded(first) }; draw(first).recycle(); assertTrue(runBlocking { first.readingVisible.first() })
            image(first).visibility = View.INVISIBLE; assertFalse(runBlocking { first.readingVisible.first() })
            image(first).visibility = View.VISIBLE; draw(first).recycle(); assertTrue(runBlocking { first.readingVisible.first() })
            image(first).alpha = 0f; image(first).viewTreeObserver.dispatchOnPreDraw()
            assertFalse(runBlocking { first.readingVisible.first() })
            image(first).alpha = 1f; draw(first).recycle(); assertTrue(runBlocking { first.readingVisible.first() })
            image(first).translationX = 10000f; image(first).viewTreeObserver.dispatchOnPreDraw()
            assertFalse(runBlocking { first.readingVisible.first() })
            image(first).translationX = 0f; draw(first).recycle()
            val second = session.createSurface(first.bookmarkNavigator!!.capture()!!.location, false, {}, {})
            assertFalse(runBlocking { first.readingVisible.first() }); assertNull(first.bookmarkNavigator!!.capture()); assertFalse(first.forward())
            assertFalse(runBlocking { second.readingVisible.first() })
            activity.supportFragmentManager.beginTransaction().replace(1002, second.fragment).commitNow()
            idleUntil { loaded(second) }; draw(second).recycle(); assertEquals(0, second.pageNavigator!!.currentPage)
            assertFalse(second.contentsNavigator!!.goTo("cbz-page:999"))
            assertTrue(second.contentsNavigator!!.goTo("cbz-page:1")); assertFalse(runBlocking { second.readingVisible.first() })
        } finally { controller.pause().stop().destroy(); session.close(); db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun cancelledLatePageCannotPublishAfterPauseAndRecoveryFailureHasRealNativeRetry() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        var gate: CompletableDeferred<Unit>? = null; val started = CompletableDeferred<Unit>(); var recoveryReady = true
        val (db, session) = open {
            check(recoveryReady)
            gate?.let { pending -> started.complete(Unit); withContext(NonCancellable) { pending.await() } }
        }
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
        try {
            val activity = controller.get(); activity.setContentView(FrameLayout(activity).apply { id = 1003 })
            val surface = session.createSurface(null, false, {}, {})
            gate = CompletableDeferred()
            activity.supportFragmentManager.beginTransaction().replace(1003, surface.fragment).commitNow()
            idleUntil { started.isCompleted }; controller.pause(); gate!!.complete(Unit); gate = null
            idleUntil { !(surface.fragment as CbzReaderFragment).drawnPage.value.let { it != null } }
            assertFalse(runBlocking { surface.readingVisible.first() }); assertNull(surface.bookmarkNavigator!!.capture())
            recoveryReady = false; controller.resume()
            idleUntil { (surface.fragment as CbzReaderFragment).renderFailed.value }
            assertFalse(runBlocking { surface.readingVisible.first() })
            val error = (surface.fragment.requireView() as FrameLayout).getChildAt(2) as android.widget.LinearLayout
            val nativeRoot = surface.fragment.requireView()
            nativeRoot.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            nativeRoot.layout(0, 0, 320, 480)
            val message = error.getChildAt(0) as android.widget.TextView
            val layout = message.layout!!
            assertEquals(message.text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue(error.bottom <= 480); assertTrue(error.getChildAt(1).height > 0)
            val proof = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
            try {
                nativeRoot.draw(Canvas(proof))
                val file = File("build/outputs/cbz-reader/cbz-error-double-type.png"); file.parentFile!!.mkdirs()
                file.outputStream().use { assertTrue(proof.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { proof.recycle() }
            assertEquals(View.VISIBLE, error.visibility); recoveryReady = true
            error.getChildAt(1).performClick(); idleUntil { loaded(surface) }; draw(surface).recycle()
            assertTrue(runBlocking { surface.readingVisible.first() }); assertEquals(0, surface.pageNavigator!!.currentPage)
        } finally { gate?.complete(Unit); controller.pause().stop().destroy(); session.close(); db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
}

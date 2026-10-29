package com.yaprak.reader.formats.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.yaprak.reader.reader.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Model seam tests prove ownership and error handling, not recognition quality. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BundledLatinPdfOcrRecognizerTest {
    private class Client(var text: String? = "  İstanbul\nEnglish 🌿  ") : PdfOcrNativeClient {
        var closes = 0
        var processes = 0
        var result: () -> String? = { text }
        override fun process(bitmap: Bitmap): PdfOcrNativeTask {
            processes++
            assertFalse(bitmap.isRecycled)
            return PdfOcrNativeTask { result() }
        }
        override fun close() { closes++ }
    }
    private fun image() = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    private fun failure(error: Throwable?, expected: PdfOcrFailure) {
        assertTrue(error is PdfOcrException); assertEquals(expected, (error as PdfOcrException).reason)
    }

    @Test fun constructionDoesNotCreateAClientAndExactUnicodeResultBorrowsTheBitmap() = runTest {
        val client = Client(); var factories = 0
        val model = BundledLatinPdfOcrRecognizer({ factories++; client }, StandardTestDispatcher(testScheduler))
        assertEquals(0, factories)
        val image = image()
        try {
            assertEquals(client.text, model.recognize(image))
            assertEquals(1, factories); assertEquals(1, client.processes); assertEquals(1, client.closes)
            assertFalse(image.isRecycled); assertEquals(Color.WHITE, image.getPixel(1, 1))
        } finally { image.recycle() }
    }
    @Test fun emptyOutputHasNoInventedTextAndStillClosesTheClient() = runTest {
        val client = Client(); val model = BundledLatinPdfOcrRecognizer({ client }, StandardTestDispatcher(testScheduler))
        val image = image()
        try {
            for (text in listOf(null, "", " \r\n\t")) {
                client.text = text; assertNull(model.recognize(image))
            }
            assertEquals(3, client.closes); assertFalse(image.isRecycled)
        } finally { image.recycle() }
    }
    @Test fun fullPageBudgetIsExactAndNeverReturnsTruncatedText() = runTest {
        val client = Client("a".repeat(5000)); val model = BundledLatinPdfOcrRecognizer({ client }, StandardTestDispatcher(testScheduler))
        val image = image()
        try {
            assertEquals(client.text, model.recognize(image))
            client.text = "a".repeat(5001); failure(runCatching { model.recognize(image) }.exceptionOrNull(), PdfOcrFailure.LIMIT)
            client.text = "retry"; assertEquals("retry", model.recognize(image)); assertEquals(3, client.closes)
        } finally { image.recycle() }
    }
    @Test fun invalidUtf16AndNulAreRejectedButLiteralControlsRemainExact() = runTest {
        val client = Client(); val model = BundledLatinPdfOcrRecognizer({ client }, StandardTestDispatcher(testScheduler))
        val image = image()
        try {
            for (text in listOf("a\u0000b", "a\uD800", "\uDC00b")) {
                client.text = text; failure(runCatching { model.recognize(image) }.exceptionOrNull(), PdfOcrFailure.MODEL)
            }
            client.text = "\tquote \"🌿\"\r\n  exact  "; assertEquals(client.text, model.recognize(image))
            assertEquals(4, client.closes)
        } finally { image.recycle() }
    }
    @Test fun invalidBitmapNeverCreatesAModelOrTakesOwnership() = runTest {
        var factories = 0; val model = BundledLatinPdfOcrRecognizer({ factories++; Client() }, StandardTestDispatcher(testScheduler))
        val images = listOf(Bitmap.createBitmap(2049, 1, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(1, 2049, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(20, 30, Bitmap.Config.RGB_565), image().apply { recycle() })
        try {
            images.forEach { failure(runCatching { model.recognize(it) }.exceptionOrNull(), PdfOcrFailure.SOURCE) }
            assertEquals(0, factories); assertFalse(images[0].isRecycled)
        } finally { images.filterNot { it.isRecycled }.forEach { it.recycle() } }
    }
    @Test fun factoryFailureReleasesTheGlobalLeaseForRetry() = runTest {
        var fail = true; val client = Client()
        val model = BundledLatinPdfOcrRecognizer({ if (fail) throw IOException("factory"); client }, StandardTestDispatcher(testScheduler))
        val image = image()
        try {
            failure(runCatching { model.recognize(image) }.exceptionOrNull(), PdfOcrFailure.MODEL)
            fail = false; assertEquals(client.text, model.recognize(image)); assertEquals(1, client.closes)
        } finally { image.recycle() }
    }
    @Test fun nativeTaskFailureClosesOnceAndDoesNotRecycleBorrowedBitmap() = runTest {
        val client = Client().apply { result = { throw IOException("native") } }
        val model = BundledLatinPdfOcrRecognizer({ client }, StandardTestDispatcher(testScheduler)); val image = image()
        try {
            failure(runCatching { model.recognize(image) }.exceptionOrNull(), PdfOcrFailure.MODEL)
            assertEquals(1, client.closes); assertFalse(image.isRecycled)
            client.result = { "retry" }; assertEquals("retry", model.recognize(image))
        } finally { image.recycle() }
    }
    @Test fun processAndCloseFailuresReleaseTheLeaseAndProduceTypedFailure() = runTest {
        val image = image(); var failsProcess = true; var closes = 0
        val model = BundledLatinPdfOcrRecognizer({ object : PdfOcrNativeClient {
            override fun process(bitmap: Bitmap): PdfOcrNativeTask {
                if (failsProcess) throw IOException("process")
                return PdfOcrNativeTask { "text" }
            }
            override fun close() { closes++; throw IOException("close") }
        } }, StandardTestDispatcher(testScheduler))
        try {
            failure(runCatching { model.recognize(image) }.exceptionOrNull(), PdfOcrFailure.MODEL)
            failsProcess = false; failure(runCatching { model.recognize(image) }.exceptionOrNull(), PdfOcrFailure.MODEL)
            assertEquals(2, closes); assertFalse(image.isRecycled)
        } finally { image.recycle() }
    }
    @Test fun cancellationBeforeQueuedModelWorkDoesNotInitializeTheSdk() = runTest {
        var factories = 0; val model = BundledLatinPdfOcrRecognizer({ factories++; Client() }, StandardTestDispatcher(testScheduler))
        val image = image(); val job = async { model.recognize(image) }
        job.cancel(); runCurrent()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, factories); assertFalse(image.isRecycled); image.recycle()
    }
    @Test fun cancellationCannotReleaseOrRecycleANativeBorrowBeforeCompletionAndAnotherCallIsBusy() = runBlocking {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val closes = AtomicInteger()
        val first = image(); val second = image()
        val model = BundledLatinPdfOcrRecognizer({ object : PdfOcrNativeClient {
            override fun process(bitmap: Bitmap) = PdfOcrNativeTask {
                entered.countDown(); check(finish.await(10, TimeUnit.SECONDS))
                assertFalse(bitmap.isRecycled); "late"
            }
            override fun close() { closes.incrementAndGet() }
        } })
        val job = async { model.recognize(first) }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            job.cancel(); yield()
            assertFalse(job.isCompleted); assertFalse(first.isRecycled); assertEquals(0, closes.get())
            failure(runCatching { model.recognize(second) }.exceptionOrNull(), PdfOcrFailure.BUSY)
            finish.countDown(); withTimeout(5000) { job.join() }
            assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
            assertFalse(first.isRecycled); assertEquals(1, closes.get())
            assertEquals("late", model.recognize(second)); assertEquals(2, closes.get())
        } finally { finish.countDown(); job.join(); first.recycle(); second.recycle() }
    }
}

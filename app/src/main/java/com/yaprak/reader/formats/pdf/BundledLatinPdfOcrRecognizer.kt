package com.yaprak.reader.formats.pdf

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.yaprak.reader.reader.PdfOcrException
import com.yaprak.reader.reader.PdfOcrFailure
import com.yaprak.reader.reader.validatedPdfOcrText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Borrows its bitmap until native work finishes, including when the caller cancels. */
fun interface PdfOcrBitmapRecognizer {
    suspend fun recognize(bitmap: Bitmap): String?
}

internal fun interface PdfOcrNativeTask { fun awaitResult(): String? }
internal interface PdfOcrNativeClient : AutoCloseable {
    fun process(bitmap: Bitmap): PdfOcrNativeTask
}

/** App-wide singleton. Constructing it creates no model; only explicit OCR creates the client. */
class BundledLatinPdfOcrRecognizer internal constructor(
    private val clientFactory: () -> PdfOcrNativeClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PdfOcrBitmapRecognizer {
    constructor() : this({ MlKitLatinOcrClient() })
    private val nativeLease = Mutex()

    override suspend fun recognize(bitmap: Bitmap): String? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        if (bitmap.isRecycled || bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.width !in 1..2048 ||
            bitmap.height !in 1..2048 || bitmap.width.toLong() * bitmap.height > 2048L * 2048)
            throw PdfOcrException(PdfOcrFailure.SOURCE)
        if (!nativeLease.tryLock()) throw PdfOcrException(PdfOcrFailure.BUSY)
        try {
            // Tasks.await blocks one IO worker, never the UI. The task cannot cancel native inference.
            // Keep the borrow/lease alive until completion so callers may safely recycle in finally.
            val result = withContext(NonCancellable + io) {
                caller.ensureActive()
                val client = clientFactory()
                try { validatedPdfOcrText(client.process(bitmap).awaitResult()) }
                finally { client.close() }
            }
            caller.ensureActive()
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PdfOcrException) {
            throw failure
        } catch (_: Exception) {
            caller.ensureActive()
            throw PdfOcrException(PdfOcrFailure.MODEL)
        } finally {
            nativeLease.unlock()
        }
    }
}

private class MlKitLatinOcrClient : PdfOcrNativeClient {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    override fun process(bitmap: Bitmap): PdfOcrNativeTask {
        val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
        return PdfOcrNativeTask { Tasks.await(task).text }
    }
    override fun close() = recognizer.close()
}

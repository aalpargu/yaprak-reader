package com.yaprak.reader.formats.fb2

import android.graphics.Bitmap
import com.yaprak.reader.formats.CustomCoverBitmapDecoder
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Embedded original cover only; caller reverified the imported file. */
internal class Fb2CoverReader {
    suspend fun read(file: File): Bitmap? {
        val document = StrictFb2.read(file)
        val binary = document.binaries.firstOrNull { it.id == document.coverId } ?: return null
        val bitmap = CustomCoverBitmapDecoder.decode(binary.bytes)
        try { currentCoroutineContext().ensureActive() }
        catch (failure: Throwable) { bitmap?.recycle(); throw failure }
        return bitmap
    }
}

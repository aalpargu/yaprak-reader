package com.yaprak.reader.formats.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.yaprak.reader.covers.thumbnailSize
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** First-page artwork only; no reading session or page-progress writes. Caller runs on IO. */
internal class PdfCoverReader {
    suspend fun read(file: File): Bitmap? {
        var allocated: Bitmap? = null
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) {
                descriptor.close()
                throw error
            }
            renderer.use {
                if (it.pageCount <= 0) return null
                it.openPage(0).use { page ->
                    val size = thumbnailSize(page.width, page.height) ?: return null
                    val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
                    allocated = bitmap
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    currentCoroutineContext().ensureActive()
                }
            }
            return allocated
        } catch (error: Throwable) {
            allocated?.recycle()
            throw error
        }
    }
}

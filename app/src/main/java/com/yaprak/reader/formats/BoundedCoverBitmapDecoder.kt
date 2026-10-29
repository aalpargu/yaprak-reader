package com.yaprak.reader.formats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yaprak.reader.covers.COVER_HEIGHT
import com.yaprak.reader.covers.COVER_WIDTH
import com.yaprak.reader.covers.coverSampleSize

internal const val MAX_COVER_BYTES = 4 * 1024 * 1024

/** Handles only raster bytes, never SVG/HTML WebView rendering. */
internal object BoundedCoverBitmapDecoder {
    fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
        var allocated: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = coverSampleSize(bounds.outWidth, bounds.outHeight) ?: return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            allocated = decoded
            if (decoded.width > COVER_WIDTH || decoded.height > COVER_HEIGHT ||
                decoded.allocationByteCount.toLong() > COVER_WIDTH.toLong() * COVER_HEIGHT * 4) {
                decoded.recycle()
                return null
            }
            return decoded
        } catch (_: RuntimeException) {
            allocated?.takeUnless { it.isRecycled }?.recycle()
            return null
        } catch (error: OutOfMemoryError) {
            allocated?.takeUnless { it.isRecycled }?.recycle()
            throw error
        }
    }
}

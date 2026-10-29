package com.yaprak.reader.formats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.yaprak.reader.covers.COVER_HEIGHT
import com.yaprak.reader.covers.COVER_WIDTH
import com.yaprak.reader.covers.coverSampleSize
import com.yaprak.reader.covers.thumbnailSize
import com.yaprak.reader.domain.library.MAX_CUSTOM_COVER_BYTES
import java.io.ByteArrayInputStream

data class CoverImageMetadata(val mediaType: String, val width: Int, val height: Int)

/** Static raster only. Decode allocation is bounded before EXIF transforms are applied. */
object CustomCoverBitmapDecoder {
    fun validate(bytes: ByteArray): CoverImageMetadata? {
        return try {
            val metadata = bounds(bytes) ?: return null
            val bitmap = decode(bytes) ?: return null
            bitmap.recycle()
            metadata
        } catch (_: Exception) { null }
        catch (_: OutOfMemoryError) { null }
    }

    fun decode(bytes: ByteArray): Bitmap? {
        var owned: Bitmap? = null
        try {
            val metadata = bounds(bytes) ?: return null
            val sample = coverSampleSize(metadata.width, metadata.height) ?: return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            owned = decoded
            if (decoded.width > COVER_WIDTH || decoded.height > COVER_HEIGHT ||
                decoded.allocationByteCount.toLong() > COVER_WIDTH.toLong() * COVER_HEIGHT * 4) return null
            val orientation = ByteArrayInputStream(bytes).use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            if (!matrix.isIdentity) {
                val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (oriented !== decoded) { owned = oriented; decoded.recycle() }
            }
            val current = owned!!
            val size = thumbnailSize(current.width, current.height) ?: return null
            if (size.first != current.width || size.second != current.height) {
                val fitted = Bitmap.createScaledBitmap(current, size.first, size.second, true)
                if (fitted !== current) { owned = fitted; current.recycle() }
            }
            val output = owned!!
            if (output.width > COVER_WIDTH || output.height > COVER_HEIGHT ||
                output.allocationByteCount.toLong() > COVER_WIDTH.toLong() * COVER_HEIGHT * 4) return null
            owned = null // Caller owns the published result.
            return output
        } catch (_: Exception) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        } finally {
            owned?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    private fun bounds(bytes: ByteArray): CoverImageMetadata? {
        if (bytes.isEmpty() || bytes.size > MAX_CUSTOM_COVER_BYTES) return null
        val mime = when {
            bytes.size >= 20 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) &&
                bytes.copyOfRange(bytes.size - 8, bytes.size - 4).contentEquals("IEND".toByteArray(Charsets.US_ASCII)) -> "image/png"
            bytes.size >= 4 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() &&
                bytes[bytes.size - 2] == (-1).toByte() && bytes.last() == (-39).toByte() -> "image/jpeg"
            bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII)) &&
                (0..3).sumOf { (bytes[4 + it].toLong() and 255L) shl (8 * it) } + 8L == bytes.size.toLong() -> "image/webp"
            else -> return null
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outMimeType != mime || coverSampleSize(options.outWidth, options.outHeight) == null) return null
        return CoverImageMetadata(mime, options.outWidth, options.outHeight)
    }
}

package com.yaprak.reader.formats.cbz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class CbzImage(val mime: String, val width: Int, val height: Int)

/** Called only on IO. Static frames, full container integrity, bounded source and bitmap allocations. */
internal object CbzImages {
    const val MAX_DIMENSION = 16384
    const val MAX_SOURCE_PIXELS = 40_000_000L
    const val MAX_BITMAP_PIXELS = 4_000_000L

    suspend fun validate(bytes: ByteArray, extension: String): CbzImage {
        val image = bounds(bytes, extension)
        decode(bytes, image).recycle()
        return image
    }

    private suspend fun bounds(bytes: ByteArray, extension: String): CbzImage {
        currentCoroutineContext().ensureActive()
        if (bytes.isEmpty()) cbzFail()
        if (bytes.size > CbzArchive.MAX_IMAGE_BYTES) cbzFail(CbzFailure.LIMIT)
        val mime = when (extension) {
            "png" -> { png(bytes); "image/png" }
            "jpg", "jpeg" -> { jpeg(bytes); "image/jpeg" }
            "webp" -> { webp(bytes); "image/webp" }
            else -> cbzFail(CbzFailure.UNSUPPORTED)
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outMimeType != mime || options.outWidth <= 0 || options.outHeight <= 0) cbzFail()
        checkDimensions(options.outWidth, options.outHeight)
        return CbzImage(mime, options.outWidth, options.outHeight)
    }

    suspend fun decode(bytes: ByteArray, image: CbzImage, maxBitmapPixels: Long = MAX_BITMAP_PIXELS): Bitmap {
        require(maxBitmapPixels in 1..MAX_BITMAP_PIXELS)
        currentCoroutineContext().ensureActive()
        checkDimensions(image.width, image.height)
        var sample = 1
        while (((image.width.toLong() + sample - 1) / sample) * ((image.height.toLong() + sample - 1) / sample) > maxBitmapPixels) sample *= 2
        var owned: Bitmap? = null
        try {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: cbzFail()
            owned = decoded
            if (decoded.width.toLong() * decoded.height > maxBitmapPixels ||
                decoded.allocationByteCount.toLong() > maxBitmapPixels * 4) cbzFail(CbzFailure.LIMIT)
            currentCoroutineContext().ensureActive()
            val orientation = ByteArrayInputStream(bytes).use { ExifInterface(it)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
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
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                owned = rotated
                if (rotated !== decoded) decoded.recycle()
            }
            currentCoroutineContext().ensureActive()
            val result = owned!!; owned = null
            return result
        } catch (_: OutOfMemoryError) { cbzFail(CbzFailure.LIMIT)
        } finally { owned?.takeUnless { it.isRecycled }?.recycle() }
    }

    private fun checkDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) cbzFail()
        if (width > MAX_DIMENSION || height > MAX_DIMENSION || width.toLong() * height > MAX_SOURCE_PIXELS) cbzFail(CbzFailure.LIMIT)
    }

    private suspend fun png(bytes: ByteArray) {
        if (bytes.size < 57 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) cbzFail()
        var at = 8; var data = false; var afterData = false; var expected = 0L; var expanded = 0L
        val inflater = Inflater(); val buffer = ByteArray(32768)
        try {
            while (at < bytes.size) {
                currentCoroutineContext().ensureActive()
                if (at + 12L > bytes.size) cbzFail()
                val size = bytes.big32(at)
                if (size > Int.MAX_VALUE || at + 12L + size > bytes.size) cbzFail()
                val count = size.toInt(); val kind = String(bytes, at + 4, 4, Charsets.US_ASCII)
                if (CRC32().apply { update(bytes, at + 4, count + 4) }.value != bytes.big32(at + count + 8)) cbzFail()
                if (at == 8 && kind != "IHDR" || kind == "IHDR" && at != 8) cbzFail()
                when (kind) {
                    "IHDR" -> {
                        if (count != 13) cbzFail()
                        val width = bytes.big32(at + 8); val height = bytes.big32(at + 12)
                        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) cbzFail(CbzFailure.LIMIT)
                        checkDimensions(width.toInt(), height.toInt())
                        val depth = bytes[at + 16].toInt() and 255; val color = bytes[at + 17].toInt() and 255
                        val channels = when (color) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> cbzFail() }
                        val validDepths = when (color) { 0 -> setOf(1, 2, 4, 8, 16); 3 -> setOf(1, 2, 4, 8); else -> setOf(8, 16) }
                        if (depth !in validDepths || bytes[at + 18].toInt() != 0 || bytes[at + 19].toInt() != 0) cbzFail()
                        fun rows(w: Long, h: Long): Long = if (w <= 0 || h <= 0) 0 else (((w * depth * channels + 7) / 8) + 1) * h
                        expected = when (bytes[at + 20].toInt()) {
                            0 -> rows(width, height)
                            1 -> listOf(intArrayOf(0,0,8,8), intArrayOf(4,0,8,8), intArrayOf(0,4,4,8),
                                intArrayOf(2,0,4,4), intArrayOf(0,2,2,4), intArrayOf(1,0,2,2), intArrayOf(0,1,1,2))
                                .sumOf { p -> rows((width - p[0] + p[2] - 1).coerceAtLeast(0) / p[2],
                                    (height - p[1] + p[3] - 1).coerceAtLeast(0) / p[3]) }
                            else -> cbzFail()
                        }
                    }
                    "IDAT" -> {
                        if (afterData || inflater.finished() && count > 0) cbzFail()
                        data = true; inflater.setInput(bytes, at + 8, count)
                        while (!inflater.needsInput() && !inflater.finished()) {
                            currentCoroutineContext().ensureActive()
                            val decoded = inflater.inflate(buffer); expanded += decoded
                            if (expanded > expected || decoded == 0 && !inflater.needsInput() && !inflater.finished()) cbzFail()
                        }
                        if (inflater.finished() && inflater.remaining != 0) cbzFail()
                    }
                    "IEND" -> {
                        if (count != 0 || !data || !inflater.finished() || expanded != expected || at + 12 != bytes.size) cbzFail()
                        return
                    }
                    "acTL", "fcTL", "fdAT" -> cbzFail(CbzFailure.UNSUPPORTED)
                    else -> {
                        if (data) afterData = true
                        if (kind != "PLTE" && kind.firstOrNull()?.isUpperCase() == true) cbzFail(CbzFailure.UNSUPPORTED)
                    }
                }
                at += count + 12
            }
            cbzFail()
        } finally { inflater.end() }
    }

    private suspend fun jpeg(bytes: ByteArray) {
        if (bytes.size < 4 || bytes.u16(0) != 0xd8ff) cbzFail()
        var at = 2; var frame = false; var scan = false
        while (at < bytes.size) {
            currentCoroutineContext().ensureActive()
            if (bytes[at++].toInt() and 255 != 255) cbzFail()
            while (at < bytes.size && bytes[at].toInt() and 255 == 255) at++
            if (at >= bytes.size) cbzFail()
            val marker = bytes[at++].toInt() and 255
            if (marker == 0xd9) { if (!frame || !scan || at != bytes.size) cbzFail(); return }
            if (marker == 0 || marker == 0xd8 || marker in 0xd0..0xd7 || at + 2 > bytes.size) cbzFail()
            val count = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
            if (count < 2 || at + count > bytes.size) cbzFail()
            if (marker in setOf(0xc0, 0xc1, 0xc2)) {
                if (frame || count < 8) cbzFail()
                checkDimensions(((bytes[at + 5].toInt() and 255) shl 8) or (bytes[at + 6].toInt() and 255),
                    ((bytes[at + 3].toInt() and 255) shl 8) or (bytes[at + 4].toInt() and 255))
                frame = true
            }
            at += count
            if (marker == 0xda) {
                if (!frame) cbzFail(); scan = true
                while (at < bytes.size) {
                    if (at and 32767 == 0) currentCoroutineContext().ensureActive()
                    if (bytes[at].toInt() and 255 == 255) {
                        if (at + 1 >= bytes.size) cbzFail()
                        val next = bytes[at + 1].toInt() and 255
                        if (next == 0 || next in 0xd0..0xd7) { at += 2; continue }
                        break
                    }
                    at++
                }
            }
        }
        cbzFail()
    }

    private suspend fun webp(bytes: ByteArray) {
        if (bytes.size < 20 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP" || bytes.u32(4) + 8 != bytes.size.toLong()) cbzFail()
        var at = 12; var frame = false
        while (at < bytes.size) {
            currentCoroutineContext().ensureActive()
            if (at + 8 > bytes.size) cbzFail()
            val kind = String(bytes, at, 4, Charsets.US_ASCII); val size = bytes.u32(at + 4)
            val next = at + 8L + size + size % 2
            if (next > bytes.size || size > Int.MAX_VALUE) cbzFail()
            when (kind) {
                "ANIM", "ANMF" -> cbzFail(CbzFailure.UNSUPPORTED)
                "VP8X" -> if (at != 12 || size != 10L || bytes[at + 8].toInt() and 2 != 0) cbzFail(CbzFailure.UNSUPPORTED)
                "VP8 ", "VP8L" -> { if (frame || size < 5) cbzFail(); frame = true }
                "ALPH", "ICCP", "EXIF", "XMP " -> Unit
                else -> cbzFail(CbzFailure.UNSUPPORTED)
            }
            at = next.toInt()
        }
        if (!frame || at != bytes.size) cbzFail()
    }

    private fun ByteArray.big32(at: Int): Long = (0..3).sumOf { (this[at + it].toLong() and 255) shl (8 * (3 - it)) }
}

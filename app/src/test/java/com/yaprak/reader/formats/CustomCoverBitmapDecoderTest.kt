package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import com.yaprak.reader.domain.library.MAX_CUSTOM_COVER_BYTES
import com.yaprak.reader.fixtures.CoverTestImages
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomCoverBitmapDecoderTest {
    @get:Rule val temp = TemporaryFolder()
    private fun raster(width: Int = 40, height: Int = 60) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) setPixel(x, y, when {
            x < width / 2 && y < height / 2 -> Color.RED
            y < height / 2 -> Color.GREEN
            x < width / 2 -> Color.BLUE
            else -> Color.WHITE
        })
    }
    private fun encoded(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray = ByteArrayOutputStream().use {
        assertTrue(bitmap.compress(format, 100, it)); it.toByteArray()
    }

    @Suppress("DEPRECATION")
    @Test fun `real supported formats`() {
        val raster = raster()
        try {
            val formats = listOf(Bitmap.CompressFormat.PNG to "image/png", Bitmap.CompressFormat.JPEG to "image/jpeg",
                Bitmap.CompressFormat.WEBP to "image/webp")
            for ((format, mime) in formats) {
                val bytes = encoded(raster, format)
                assertEquals(CoverImageMetadata(mime, 40, 60), CustomCoverBitmapDecoder.validate(bytes))
                val bitmap = CustomCoverBitmapDecoder.decode(bytes)!!
                assertEquals(40, bitmap.width); assertEquals(60, bitmap.height); bitmap.recycle()
            }
        } finally { raster.recycle() }
    }

    @Test fun `malformed and huge bounds rejected`() {
        val png = CoverTestImages.png()
        assertNull(CustomCoverBitmapDecoder.validate(ByteArray(0)))
        assertNull(CustomCoverBitmapDecoder.validate("<svg></svg>".toByteArray()))
        assertNull(CustomCoverBitmapDecoder.validate(png.copyOf(png.size / 2)))
        assertNull(CustomCoverBitmapDecoder.decode(ByteArray(MAX_CUSTOM_COVER_BYTES + 1)))
        val bomb = png.clone()
        fun changeInt(offset: Int, value: Int) { for (i in 0..3) bomb[offset + i] = (value ushr (24 - i * 8)).toByte() }
        changeInt(16, 16_385)
        val crc = CRC32().apply { update(bomb, 12, 17) }.value.toInt()
        changeInt(29, crc)
        assertNull(CustomCoverBitmapDecoder.validate(bomb))
        val tinyCorrupt = png.clone().also { it[0] = 0 }
        assertNull(CustomCoverBitmapDecoder.decode(tinyCorrupt))
    }

    @Test fun `all exif orientation transforms`() {
        val raster = raster()
        val file = temp.newFile("orientation.jpg")
        val expectedTopLeft = listOf(Color.RED, Color.GREEN, Color.WHITE, Color.BLUE, Color.RED, Color.BLUE, Color.WHITE, Color.GREEN)
        try {
            for (orientation in 1..8) {
                file.writeBytes(encoded(raster, Bitmap.CompressFormat.JPEG))
                ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                val image = CustomCoverBitmapDecoder.decode(file.readBytes())!!
                try {
                    assertEquals(if (orientation >= 5) 60 else 40, image.width)
                    assertEquals(if (orientation >= 5) 40 else 60, image.height)
                    val color = image.getPixel(image.width / 4, image.height / 4)
                    val expected = expectedTopLeft[orientation - 1]
                    assertTrue("EXIF $orientation red", kotlin.math.abs(Color.red(expected) - Color.red(color)) < 20)
                    assertTrue("EXIF $orientation green", kotlin.math.abs(Color.green(expected) - Color.green(color)) < 20)
                    assertTrue("EXIF $orientation blue", kotlin.math.abs(Color.blue(expected) - Color.blue(color)) < 20)
                } finally { image.recycle() }
            }
        } finally { raster.recycle() }
    }

    @Test fun `rotation fits thumbnail bounds`() {
        val raster = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val file = temp.newFile("large.jpg")
        try {
            file.writeBytes(encoded(raster, Bitmap.CompressFormat.JPEG))
            ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
            assertEquals(CoverImageMetadata("image/jpeg", 800, 1200), CustomCoverBitmapDecoder.validate(file.readBytes()))
            val image = CustomCoverBitmapDecoder.decode(file.readBytes())!!
            try {
                assertTrue(image.width in 1..384 && image.height in 1..512)
                assertTrue(image.allocationByteCount <= 384 * 512 * 4)
                assertTrue(image.width > image.height)
            } finally { image.recycle() }
        } finally { raster.recycle() }
    }
}

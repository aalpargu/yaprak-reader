package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.fixtures.CoverTestImages
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBitmapFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BoundedCoverBitmapDecoderTest {
    @Before fun requireValidRasterBytes() {
        // The LEGACY shadow defaults to a fabricated 100x100 bitmap for invalid bytes.
        // Require its real ImageIO decode result for malformed-raster checks.
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    @After fun restoreShadowDefaults() { ShadowBitmapFactory.setAllowInvalidImageData(true) }

    @Test fun `real raster decode preserves small cover dimensions`() {
        val cover = BoundedCoverBitmapDecoder.decode(CoverTestImages.png())!!
        try { assertEquals(8, cover.width); assertEquals(12, cover.height) } finally { cover.recycle() }
    }

    @Test fun `large real raster is sampled before decoding`() {
        val cover = BoundedCoverBitmapDecoder.decode(CoverTestImages.png(800, 1200))!!
        try {
            assertTrue(cover.width in 1..384 && cover.height in 1..512)
            assertTrue(cover.allocationByteCount <= 384 * 512 * 4)
        } finally { cover.recycle() }
    }

    @Test fun `empty corrupt and oversized raster bytes produce no image`() {
        assertNull(BoundedCoverBitmapDecoder.decode(ByteArray(0)))
        assertNull(BoundedCoverBitmapDecoder.decode(byteArrayOf(1, 2, 3)))
        assertNull(BoundedCoverBitmapDecoder.decode(ByteArray(MAX_COVER_BYTES + 1)))
    }
}

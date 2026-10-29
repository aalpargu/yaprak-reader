package com.yaprak.reader.formats.pdf

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfCoverReaderDeviceTest {
    @get:Rule val temporary = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)

    @Test fun rendersBoundedOpaquePageAndReleasesFile() = runBlocking {
        val file = fixture("two-pages.pdf")
        val bitmap = PdfCoverReader().read(file)
        assertNotNull(bitmap)
        requireNotNull(bitmap)
        try {
            assertTrue(bitmap.width in 1..384 && bitmap.height in 1..512)
            // The owned fixture is300x400 points. Library covers fit the budget without upscaling.
            assertEquals(300, bitmap.width)
            assertEquals(400, bitmap.height)
            assertTrue(Color.alpha(bitmap.getPixel(0, 0)) == 255)
            assertTrue(bitmap.getPixel(0, 0) == Color.WHITE)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("The fixture contains visible page text", pixels.any { it != Color.WHITE })
            assertTrue("The reader must close its native descriptor before returning", file.delete())
        } finally {
            // This test owns the returned bitmap; no UI or cache can retain it.
            bitmap.recycle()
        }
    }

    @Test fun rejectsProtectedAndInvalidPdfAndReleasesDescriptors() = runBlocking {
        val files = listOf(fixture("password.pdf"), temporary.newFile("invalid.pdf").apply {
            writeText("Not a PDF")
        })
        for (file in files) {
            val failure = runCatching { PdfCoverReader().read(file) }.exceptionOrNull()
            assertNotNull("Protected or malformed input must not produce artwork", failure)
            assertTrue("A failed constructor must close its native descriptor", file.delete())
        }
    }

    private fun fixture(name: String): File = temporary.newFile(name).apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("books/$name").use { input ->
            outputStream().use { output -> input.copyTo(output) }
        }
    }
}

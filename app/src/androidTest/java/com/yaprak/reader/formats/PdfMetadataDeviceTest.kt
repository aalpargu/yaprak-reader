package com.yaprak.reader.formats

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.formats.pdf.PdfMetadataReader
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's real PDF engine. Robolectric 4.15.1 has no PDF native implementation. */
@RunWith(AndroidJUnit4::class)
class PdfMetadataDeviceTest {
    private lateinit var directory: File

    @Before fun createDirectory() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pdf-tests")
        check(directory.mkdirs() || directory.isDirectory)
    }

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun readsRealPageCount() {
        assertEquals(2, PdfMetadataReader().read(fixture("two-pages.pdf")).pageCount)
    }

    @Test fun rejectsPasswordProtectedPdf() {
        val error = try { PdfMetadataReader().read(fixture("password.pdf")); null }
        catch (error: BookImportException) { error }
        assertEquals(ImportFailure.PROTECTED, error?.reason)
    }

    @Test fun rejectsCorruptPdf() {
        val file = File(directory, "broken.pdf").apply { writeText("%PDF-broken") }
        val error = try { PdfMetadataReader().read(file); null }
        catch (error: BookImportException) { error }
        assertEquals(ImportFailure.INVALID, error?.reason)
    }

    private fun fixture(name: String): File {
        val file = File(directory, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("books/$name").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }
}

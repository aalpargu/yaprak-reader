package com.yaprak.reader.reader

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaprak.reader.formats.docx.*
import com.yaprak.reader.formats.epub.EpubBookSearch
import com.yaprak.reader.formats.epub.EpubPublicationSource
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android decoder, original DOCX and Readium publication. This does not create a reader Activity. */
@RunWith(AndroidJUnit4::class)
class DocxImagesDeviceTest {
    private suspend fun owned(block: suspend (Context,File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir,"docx-images-device-"+UUID.randomUUID()).apply { check(mkdirs()) }
        try { block(context,root) }
        finally {
            check(root.canonicalFile.parentFile==context.cacheDir.canonicalFile)
            check(root.name.startsWith("docx-images-device-"))
            root.deleteRecursively()
        }
    }
    private fun source(root: File): File = File(root,"owned.docx").apply {
        val testContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        testContext.assets.open("docx/python-docx-image.docx").use { input -> outputStream().use { input.copyTo(it) } }
    }

    @Test fun actualOriginalImageDecoderThumbnailAndReadiumResourceOpen() = runBlocking {
        owned { context,root ->
            val original = source(root); val bytes = original.readBytes()
            val document = StrictDocx.read(original)
            assertEquals(1,document.images.size); assertEquals("Owned image DOCX",document.metadata.title)
            val image = document.images.single()
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size))
            try { assertEquals(6,bitmap.width); assertEquals(4,bitmap.height) } finally { bitmap.recycle() }
            val cover = DocxCoverReader().read(original)
            assertNotNull(cover); cover!!.recycle()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val view = File(root,"view.epub"); DocxEpub.write(view,hash,document)
            ZipFile(view).use { zip -> assertArrayEquals(image.bytes,zip.getInputStream(zip.getEntry(image.href)).readBytes()) }
            EpubPublicationSource(context.contentResolver).open(view).use { opened ->
                assertEquals("Text/document.xhtml",EpubBookSearch(opened.publication).find("After picture unique tail").hits.single().location.resourceHref)
            }
            assertArrayEquals(bytes,original.readBytes())
        }
    }

    @Test fun corruptEmbeddedImageIsRefusedBeforeMetadataConversionCommit() = runBlocking {
        owned { _,root ->
            val original = source(root)
            val invalid = File(root,"invalid.docx")
            ZipFile(original).use { source -> invalid.outputStream().use { output -> ZipOutputStream(output).use { zip ->
                for(entry in source.entries()) {
                    zip.putNextEntry(ZipEntry(entry.name))
                    val bytes = source.getInputStream(entry).use { it.readBytes() }
                    zip.write(if(entry.name=="word/media/image1.png") bytes.copyOf(24) else bytes)
                    zip.closeEntry()
                }
            } } }
            assertTrue(runCatching { StrictDocx.read(invalid) }.exceptionOrNull() is DocxException)
            assertTrue(runCatching { DocxMetadataReader().read(invalid) }.isFailure)
            assertEquals(setOf("owned.docx","invalid.docx"),root.listFiles()!!.map { it.name }.toSet())
        }
    }

    @Test fun thumbnailCompatibilityRejectsMultipleNulsNonzeroAndConcatenatedTrailersWithoutChangingOriginals() = runBlocking {
        owned { _, root ->
            val original = source(root)
            val originalBytes = original.readBytes()
            val jpeg = ZipFile(original).use { zip ->
                zip.getInputStream(zip.getEntry("docProps/thumbnail.jpeg")).use { it.readBytes() }
            }
            assertEquals(0.toByte(), jpeg.last())
            val exact = jpeg.copyOf(jpeg.size - 1)
            for ((index, trailer) in listOf(byteArrayOf(0, 0), byteArrayOf(1), byteArrayOf(-1, -40, -1, -39)).withIndex()) {
                val file = File(root, "bad-thumbnail-$index.docx")
                ZipFile(original).use { source -> file.outputStream().use { output -> ZipOutputStream(output).use { zip ->
                    for (entry in source.entries()) {
                        zip.putNextEntry(ZipEntry(entry.name))
                        zip.write(if (entry.name == "docProps/thumbnail.jpeg") exact + trailer
                            else source.getInputStream(entry).use { it.readBytes() })
                        zip.closeEntry()
                    }
                } } }
                val before = file.readBytes()
                val failure = runCatching { DocxCoverReader().read(file) }.exceptionOrNull()
                assertTrue(failure is DocxException)
                assertEquals(DocxFailure.INVALID, (failure as DocxException).reason)
                assertArrayEquals(before, file.readBytes())
            }
            assertArrayEquals(originalBytes, original.readBytes())
        }
    }
}

package com.yaprak.reader.formats.docx

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocxCoverTest {
    @get:Rule val temp = TemporaryFolder()
    private val raster: DocxRasterValidator = { _, _ -> DocxRaster(6,4) }
    private suspend fun thumbnail(parts: Map<String,ByteArray>, validate: DocxRasterValidator = raster): DocxBinary? {
        val file = temp.newFile().apply { writeBytes(DocxFixtures.zipBytes(parts)) }
        return DocxCoverReader(validate).thumbnail(file)
    }
    private suspend fun refused(reason: DocxFailure = DocxFailure.INVALID, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected DOCX refusal, got "+failure, failure is DocxException)
        assertEquals(reason, (failure as DocxException).reason)
    }

    @Test fun onlyActualPackageThumbnailRelationshipSuppliesCoverBytes() = runBlocking {
        var calls = 0
        val parts = DocxPictureFixtures.parts(rootExtra = DocxPictureFixtures.thumbnail())
        val image = thumbnail(parts) { bytes, extension -> calls++; assertEquals("png",extension); assertArrayEquals(DocxPictureFixtures.png(),bytes); DocxRaster(6,4) }
        assertEquals(1,calls); assertEquals("word/media/owned.png",image!!.part); assertEquals("image/png",image.mime)
        assertArrayEquals(DocxPictureFixtures.png(),image.bytes)
    }

    @Test fun bodyPictureAndThumbnailNamedFileWithoutRootRelationshipAreNeverGuessedAsCover() = runBlocking {
        val parts = DocxPictureFixtures.parts()
        parts["docProps/thumbnail.png"] = DocxPictureFixtures.png()
        assertNull(thumbnail(parts) { _, _ -> error("No declared cover") })
    }

    @Test fun thumbnailTargetUsesItsDeclaredPartRatherThanAnAssumedFilename() = runBlocking {
        val parts = DocxPictureFixtures.parts(rootExtra = DocxPictureFixtures.thumbnail(target = "assets/actual.png"))
        parts["assets/actual.png"] = DocxPictureFixtures.png()
        assertEquals("assets/actual.png",thumbnail(parts)!!.part)
    }

    @Test fun duplicateRemoteAndDanglingThumbnailRelationsAreRejected() = runBlocking {
        refused { thumbnail(DocxPictureFixtures.parts(rootExtra = DocxPictureFixtures.thumbnail()+DocxPictureFixtures.thumbnail("second"))) }
        refused(DocxFailure.UNSUPPORTED) { thumbnail(DocxPictureFixtures.parts(rootExtra = DocxPictureFixtures.thumbnail(target = "https://example.invalid/cover.png",external = true))) }
        refused { thumbnail(DocxPictureFixtures.parts(rootExtra = DocxPictureFixtures.thumbnail(target = "missing.png"))) }
    }

    @Test fun unsupportedThumbnailMimeReturnsAbsenceWithoutCallingDecoder() = runBlocking {
        assertNull(thumbnail(DocxPictureFixtures.parts(rootExtra = DocxPictureFixtures.thumbnail(),mime = "image/svg+xml")) { _, _ -> error("Unsupported cover") })
    }

    @Test fun thumbnailSourceAndPixelBudgetsApplyBeforePublication() = runBlocking {
        var calls = 0
        refused(DocxFailure.LIMIT) { thumbnail(DocxPictureFixtures.parts(rootExtra = DocxPictureFixtures.thumbnail(),images = mapOf("word/media/owned.png" to ByteArray(4*1024*1024+1)))) { _, _ -> calls++; DocxRaster(6,4) } }
        assertEquals(0,calls)
        refused(DocxFailure.LIMIT) { thumbnail(DocxPictureFixtures.parts(rootExtra = DocxPictureFixtures.thumbnail())) { _, _ -> DocxRaster(2001,2000) } }
    }

    @Test fun cancellationAndInvalidRasterCannotBecomeACachedSuccessfulAbsence() = runBlocking {
        val cancellation = CancellationException("owned")
        assertSame(cancellation,runCatching { thumbnail(DocxPictureFixtures.parts(rootExtra = DocxPictureFixtures.thumbnail())) { _, _ -> throw cancellation } }.exceptionOrNull())
        refused { thumbnail(DocxPictureFixtures.parts(rootExtra = DocxPictureFixtures.thumbnail())) { _, _ -> DocxRaster(-1,4) } }
    }

    @Test fun independentProducerThumbnailIsResolvedThroughItsActualRootRelationship() = runBlocking {
        val file = temp.newFile().apply { writeBytes(DocxPictureFixtures.original()) }
        var calls = 0
        val cover = DocxCoverReader { _, extension -> calls++; assertEquals("jpg",extension); DocxRaster(256,256) }.thumbnail(file)
        assertEquals(1,calls); assertEquals("docProps/thumbnail.jpeg",cover!!.part); assertEquals("image/jpeg",cover.mime)
        assertTrue(cover.bytes.size>100)
    }

    @Test fun independentProducerSingleNulJpegIsNormalizedOnlyForValidationWithoutChangingThePartOrPackage() = runBlocking {
        val original = DocxPictureFixtures.original()
        val file = temp.newFile().apply { writeBytes(original) }
        val declared = java.util.zip.ZipFile(file).use { zip ->
            zip.getInputStream(zip.getEntry("docProps/thumbnail.jpeg")).use { it.readBytes() }
        }
        assertEquals(0.toByte(), declared.last())
        var calls = 0
        val cover = DocxCoverReader { bytes, extension ->
            calls++; assertEquals("jpg", extension)
            assertArrayEquals(declared.copyOf(declared.size - 1), bytes)
            assertEquals((-1).toByte(), bytes[bytes.size - 2]); assertEquals((-39).toByte(), bytes.last())
            DocxRaster(395, 512)
        }.thumbnail(file)!!
        assertEquals(1, calls)
        assertArrayEquals(declared, cover.bytes)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun compatibilityDoesNotTrimNormalJpegOtherFormatsOrAnyDifferentTrailer() {
        val jpeg = byteArrayOf(-1, -40, -1, -32, 0, 2, -1, -39)
        for (extension in listOf("jpg", "jpeg")) {
            val padded = jpeg + byteArrayOf(0)
            assertArrayEquals(jpeg, docxThumbnailRasterBytes(padded, extension))
            assertEquals(0.toByte(), padded.last())
            for (unchanged in listOf(jpeg, jpeg + byteArrayOf(0, 0), jpeg + byteArrayOf(1),
                jpeg + byteArrayOf(-1, -40, -1, -39), byteArrayOf(), byteArrayOf(-1, -39, 0))) {
                assertSame(unchanged, docxThumbnailRasterBytes(unchanged, extension))
            }
        }
        val png = DocxPictureFixtures.png()
        for (extension in listOf("png", "webp", "", "JPG")) {
            val padded = jpeg + byteArrayOf(0)
            assertSame(padded, docxThumbnailRasterBytes(padded, extension))
        }
        assertSame(png, docxThumbnailRasterBytes(png, "jpg"))
    }
}

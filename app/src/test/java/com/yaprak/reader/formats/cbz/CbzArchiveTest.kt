package com.yaprak.reader.formats.cbz

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.yaprak.reader.fixtures.CoverTestImages
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
class CbzArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    internal fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): File {
        val file = temp.newFile()
        ZipOutputStream(file.outputStream()).use { output -> entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            if (stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
            output.putNextEntry(entry); output.write(bytes); output.closeEntry()
        } }
        return file
    }
    private suspend fun rejected(file: File, reason: CbzFailure = CbzFailure.INVALID) {
        try { CbzArchive.read(file); fail("Accepted malformed archive") }
        catch (error: CbzException) { assertEquals(reason, error.reason) }
    }
    @Test fun entireStoredAndDeflatedArchivesHaveNaturalOriginalOrderAndExactHashes() = runBlocking(Dispatchers.IO) {
        for (stored in listOf(false, true)) {
            val png = CoverTestImages.png()
            val file = zip("pages/" to byteArrayOf(), "pages/10.png" to png, "ComicInfo.xml" to "<ComicInfo/>".toByteArray(),
                "pages/2.png" to png, "pages/1.png" to png, stored = stored)
            val original = file.readBytes(); val archive = CbzArchive.read(file)
            assertEquals(listOf("pages/1.png", "pages/2.png", "pages/10.png"), archive.pages.map { it.entry.name })
            assertTrue(archive.pages.all { it.sha256 == cbzHash(png) && it.image == CbzImage("image/png", 8, 12) })
            assertArrayEquals(original, file.readBytes()); assertEquals(1, temp.root.listFiles()!!.count { it == file })
        }
    }
    @Suppress("DEPRECATION")
    @Test fun everyRealStaticRasterDecodesIncludingFinalPageAndExifOrientation() = runBlocking(Dispatchers.IO) {
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            val pages = listOf(Bitmap.CompressFormat.PNG to "png", Bitmap.CompressFormat.JPEG to "jpg", Bitmap.CompressFormat.WEBP to "webp")
                .mapIndexed { i, (format, extension) ->
                    val bytes = ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 100, it)) }.toByteArray()
                    "${i + 1}.$extension" to bytes
                }
            val file = zip(*pages.toTypedArray()); val archive = CbzArchive.read(file)
            assertEquals(listOf("image/png", "image/jpeg", "image/webp"), archive.pages.map { it.image.mime })
            pages.zip(archive.pages).forEach { (entry, page) ->
                CbzImages.decode(entry.second, page.image).also { assertEquals(40, it.width); assertEquals(60, it.height); it.recycle() }
            }
            val jpg = temp.newFile("rotated.jpg").apply { writeBytes(pages[1].second) }
            androidx.exifinterface.media.ExifInterface(jpg).apply { setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
            val image = CbzImages.validate(jpg.readBytes(), "jpg")
            CbzImages.decode(jpg.readBytes(), image).also { assertEquals(60, it.width); assertEquals(40, it.height); it.recycle() }
            Unit
        } finally { bitmap.recycle() }
    }
    @Test fun malformedFinalImageRefusesWholeArchiveDespiteValidFirstPage() = runBlocking(Dispatchers.IO) {
        val png = CoverTestImages.png()
        rejected(zip("1.png" to png, "2.png" to png.copyOf(png.size - 1)))
        rejected(zip("1.png" to png, "2.jpg" to png))
        rejected(zip("1.png" to png, "2.gif" to "GIF89a".toByteArray()), CbzFailure.UNSUPPORTED)
        rejected(zip("ComicInfo.xml" to "metadata only".toByteArray()))
    }
    @Test fun traversalAliasesCaseAndUnicodeDuplicatesAreRefusedWithoutExtraction() = runBlocking(Dispatchers.IO) {
        for (name in listOf("../page.png", "a/../p.png", "/p.png", "C:/p.png", "a\\p.png", "a//p.png", "a/%2e.png", "a/#p.png", " a.png", "p\u0000.png"))
            rejected(zip(name to CoverTestImages.png()))
        rejected(zip("P.png" to CoverTestImages.png(), "p.png" to CoverTestImages.png()))
        rejected(zip("é.png" to CoverTestImages.png(), "e\u0301.png" to CoverTestImages.png()))
        assertFalse(File(temp.root, "page.png").exists())
    }
    @Test fun corruptCrcEncryptedAndMismatchedLocalCentralHeadersAreRefused() = runBlocking(Dispatchers.IO) {
        val png = CoverTestImages.png()
        val crc = zip("1.png" to png, stored = true); val raw = crc.readBytes()
        raw[30 + "1.png".length + 45] = (raw[30 + "1.png".length + 45].toInt() xor 1).toByte(); crc.writeBytes(raw); rejected(crc)
        val encrypted = zip("1.png" to png); val data = encrypted.readBytes(); val central = (0 until data.size - 4).first { data.u32(it) == 0x02014b50L }
        data[6] = (data[6].toInt() or 1).toByte(); data[central + 8] = (data[central + 8].toInt() or 1).toByte()
        encrypted.writeBytes(data); rejected(encrypted, CbzFailure.PROTECTED)
        val mismatched = zip("1.png" to png); val different = mismatched.readBytes(); different[30] = '2'.code.toByte()
        mismatched.writeBytes(different); rejected(mismatched)
    }
    @Test fun trailingZipBytesOverlappingOffsetsAndTruncatedDirectoryAreRefused() = runBlocking(Dispatchers.IO) {
        val trailing = zip("1.png" to CoverTestImages.png()); trailing.appendBytes(byteArrayOf(1)); rejected(trailing)
        val truncated = zip("1.png" to CoverTestImages.png()); truncated.writeBytes(truncated.readBytes().dropLast(10).toByteArray()); rejected(truncated)
        val alias = zip("1.png" to CoverTestImages.png(), "2.png" to CoverTestImages.png()); val raw = alias.readBytes()
        val headers = (0 until raw.size - 46).filter { raw.u32(it) == 0x02014b50L }; repeat(4) { raw[headers[1] + 42 + it] = 0 }
        alias.writeBytes(raw); rejected(alias)
    }
    @Test fun entryAndPageQuotasAreCheckedBeforeAnyImageIsAdmitted() = runBlocking(Dispatchers.IO) {
        val pages = (0..CbzArchive.MAX_PAGES).map { "$it.png" to CoverTestImages.png() }
        rejected(zip(*pages.toTypedArray()), CbzFailure.LIMIT)
        val directories = (0..CbzArchive.MAX_ENTRIES).map { "$it/" to byteArrayOf() }
        rejected(zip(*directories.toTypedArray()), CbzFailure.LIMIT)
    }
    @Test fun sourceDimensionsAndSampledAllocationAreBoundedUsingRealDecoder() = runBlocking(Dispatchers.IO) {
        val png = CoverTestImages.png(3200, 2000); val image = CbzImages.validate(png, "png")
        val decoded = CbzImages.decode(png, image)
        try { assertEquals(1600, decoded.width); assertEquals(1000, decoded.height); assertTrue(decoded.allocationByteCount <= 16_000_000) }
        finally { decoded.recycle() }
        rejected(zip("1.png" to CoverTestImages.png(16385, 1)), CbzFailure.LIMIT)
    }
    @Test fun pngFullChunkCrcZlibAndStaticFramesAreRequired() = runBlocking(Dispatchers.IO) {
        val png = CoverTestImages.png()
        val crc = png.clone(); crc[45] = (crc[45].toInt() xor 1).toByte(); rejected(zip("1.png" to crc))
        rejected(zip("1.png" to (png + byteArrayOf(0))))
        val idat = png.clone(); val count = idat.u32(33).toInt() // overwritten below using big-endian chunk length
        assertTrue(count > 0)
        val length = ((idat[33].toInt() and 255) shl 24) or ((idat[34].toInt() and 255) shl 16) or ((idat[35].toInt() and 255) shl 8) or (idat[36].toInt() and 255)
        idat[41] = 0
        val value = CRC32().apply { update(idat, 37, 4 + length) }.value
        repeat(4) { idat[41 + length + it] = (value ushr (24 - it * 8)).toByte() }
        rejected(zip("1.png" to idat))
    }
    @Test fun cancelledValidationDoesNotBecomeInvalidOrPublishAPartialArchive() = runBlocking {
        val file = zip("1.png" to CoverTestImages.png()); val job = Job().apply { cancel() }
        try { withContext(Dispatchers.IO + job) { CbzArchive.read(file) }; fail("Ignored cancellation") }
        catch (_: kotlinx.coroutines.CancellationException) { assertTrue(file.exists()) }
    }
    @Test fun positionsBindBothOriginalEntryAndNaturalIndexAndRefuseForeignEvidence() = runBlocking(Dispatchers.IO) {
        val archive = CbzArchive.read(zip("2.png" to CoverTestImages.png(), "10.png" to CoverTestImages.png()))
        val location = CbzPosition.encode(1, archive); assertEquals(1, CbzPosition.decode(location, archive))
        assertNull(CbzPosition.decode(location.copy(resourceHref = "pdf"), archive))
        assertNull(CbzPosition.decode(location.copy(locatorVersion = 2), archive))
        val json = org.json.JSONObject(location.locatorJson).put("pageIndex", "1").toString()
        assertNull(CbzPosition.decode(location.copy(locatorJson = json), archive))
        assertNull(CbzPosition.decode(location.copy(locatorJson = org.json.JSONObject(location.locatorJson).put("extra", true).toString()), archive))
        assertNull(CbzPosition.decode(location.copy(locatorJson = org.json.JSONObject(location.locatorJson).put("entry", "2.png").toString()), archive))
    }
    @Test fun naturalOrderHandlesHugeNumericRunsAndLeadingZerosDeterministically() {
        val names = listOf("page10.png", "page002.png", "page2.png", "page1.png", "page" + "9".repeat(500) + ".png")
        assertEquals(listOf(names[3], names[2], names[1], names[0], names[4]), names.sortedWith(::cbzNaturalOrder))
    }
}

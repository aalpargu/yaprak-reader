package com.yaprak.reader.formats

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.fixtures.CoverTestImages
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.formats.epub.EpubCoverReader
import com.yaprak.reader.formats.epub.localRasterCover
import com.yaprak.reader.formats.epub.readBoundedCover
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class EpubCoverReaderTest {
    @get:Rule val temp = TemporaryFolder()

    private class CoverResource(private val data: ByteArray, private val hint: Long = data.size.toLong(), private val cancel: Boolean = false) : Resource {
        override val sourceUrl: AbsoluteUrl? = null
        var requested: LongRange? = null
        var reads = 0
        var closed = false
        override suspend fun properties(): Try<Resource.Properties, ReadError> = Try.success(Resource.Properties())
        override suspend fun length(): Try<Long, ReadError> = Try.success(hint)
        override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
            requested = range; reads++
            if (cancel) throw CancellationException("Cancelled resource read")
            return Try.success(data)
        }
        override fun close() { closed = true }
    }

    @Test fun `bounded read does not trust size hint and always closes resource`() = runBlocking {
        val resource = CoverResource(ByteArray(MAX_COVER_BYTES + 1), hint = 1)
        assertNull(readBoundedCover(resource))
        assertEquals(0L..MAX_COVER_BYTES.toLong(), resource.requested)
        assertTrue(resource.closed)
        val oversizedHint = CoverResource(byteArrayOf(1), hint = MAX_COVER_BYTES.toLong() + 1)
        assertNull(readBoundedCover(oversizedHint))
        assertEquals(0, oversizedHint.reads)
        assertTrue(oversizedHint.closed)
    }

    @Test fun `cancelled resource read propagates cancellation and closes resource`() = runBlocking {
        val resource = CoverResource(byteArrayOf(1), cancel = true)
        assertTrue(runCatching { readBoundedCover(resource) }.exceptionOrNull() is CancellationException)
        assertTrue(resource.closed)
    }

    @Test fun `only local explicit raster covers are eligible`() {
        fun link(href: String, type: MediaType = MediaType.PNG, cover: Boolean = true) =
            Link(Url(href)!!, mediaType = type, rels = if (cover) setOf("cover") else emptySet())
        val local = link("EPUB/cover.png")
        val rejected = listOf(link("https://example.org/cover.png"), link("//example.org/cover.png"),
            link("EPUB/cover.png?other=1"), link("EPUB/cover.png#part"), link("/cover.png"),
            link("EPUB/cover.svg", MediaType.SVG), link("EPUB/image.png", cover = false))
        assertNull(localRasterCover(rejected))
        assertEquals(local, localRasterCover(rejected + local))
    }

    @Test fun `actual EPUB manifest cover resolves to raster and closes the book`() = runBlocking {
        val base = TestBooks.epub(temp.newFile("base.epub"))
        val covered = temp.newFile("covered.epub")
        ZipFile(base).use { source ->
            ZipOutputStream(covered.outputStream()).use { target ->
                source.entries().asSequence().forEach { entry ->
                    var bytes = source.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == "EPUB/package.opf") bytes = bytes.toString(Charsets.UTF_8)
                        .replace("<manifest>", "<manifest><item id=\"cover\" href=\"cover.png\" media-type=\"image/png\" properties=\"cover-image\"/>")
                        .toByteArray(Charsets.UTF_8)
                    val copied = ZipEntry(entry.name)
                    if (entry.name == "mimetype") {
                        copied.method = ZipEntry.STORED
                        copied.size = bytes.size.toLong()
                        copied.compressedSize = bytes.size.toLong()
                        copied.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    }
                    target.putNextEntry(copied); target.write(bytes); target.closeEntry()
                }
                target.putNextEntry(ZipEntry("EPUB/cover.png")); target.write(CoverTestImages.png()); target.closeEntry()
            }
        }
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val image = EpubCoverReader(resolver).read(covered)!!
        try { assertEquals(8, image.width); assertEquals(12, image.height) } finally { image.recycle() }
        // Readium closes archive handles asynchronously; a real handle leak must fail this check.
        val probe = File(covered.parentFile, "close-probe.epub")
        withTimeout(5_000) { while (!covered.renameTo(probe)) delay(10) }
        assertTrue(probe.renameTo(covered))
    }
}

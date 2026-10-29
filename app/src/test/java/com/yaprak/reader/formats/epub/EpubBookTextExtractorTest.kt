@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats.epub

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.TestBooks
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
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
class EpubBookTextExtractorTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val stored = mutableListOf<File>()
    @After fun cleanGeneratedPrivateBooks() { stored.forEach { it.delete() } }

    @Test fun `production extractor reads actual verified local EPUB body with complete original source proof`() = runBlocking {
        val body = "<h1>İstanbul</h1><p> A&amp;🌿B dependency <em>injection</em> 漢字 </p><script>SECRET</script>"
        val file = fixture(listOf(xhtml(body)))
        val book = store(file)
        val result = EpubBookTextExtractor(context).extract(book)
        assertEquals(book.id, result.bookId); assertEquals(book.sha256, result.contentSha256)
        assertEquals(LIBRARY_TEXT_EXTRACTION_VERSION, result.extractionVersion); assertEquals(1, result.resourceCount)
        val chunk = result.chunks.single()
        assertEquals("İstanbul\n A&🌿B dependency injection 漢字 \n", chunk.text)
        assertEquals("EPUB/chapter.xhtml", chunk.resourceHref); assertEquals(0, chunk.readingOrderIndex)
        assertEquals(0, chunk.chunkIndex); assertEquals(hash(xhtml(body).toByteArray()), chunk.resourceSha256)
        assertTrue(LibraryTextTarget(book.id, book.sha256, chunk.resourceHref, chunk.resourceSha256).isValid)
        assertTrue(result.chunks.sumOf { indexedChunkStringBytes(book.id, book.sha256, it) } <= MAX_INDEX_BOOK_STRING_BYTES)
    }

    @Test fun `real multiple chapters retain spine order source hashes and global contiguous chunk identities`() = runBlocking {
        val first = "a".repeat(1995) + "dependency injection" + " z🌿".repeat(1_000)
        val second = "A distinct final chapter"
        val xml = listOf(xhtml("<p>$first</p>"), xhtml("<p>$second</p>"))
        val file = fixture(xml); val book = store(file)
        val result = EpubBookTextExtractor(context).extract(book)
        assertEquals(2, result.resourceCount)
        assertEquals(result.chunks.indices.toList(), result.chunks.map { it.chunkIndex })
        assertTrue(result.chunks.size > 2)
        assertTrue(result.chunks.all { it.text.length <= MAX_INDEX_CHUNK_TEXT && validUtf16(it.text) })
        assertTrue(result.chunks.any { it.text.contains("dependency injection") })
        val firstChunks = result.chunks.filter { it.readingOrderIndex == 0 }
        val last = result.chunks.last()
        assertTrue(firstChunks.isNotEmpty()); assertTrue(firstChunks.all { it.resourceSha256 == hash(xml[0].toByteArray()) })
        assertEquals("EPUB/chapter1.xhtml", last.resourceHref); assertEquals(1, last.readingOrderIndex)
        assertEquals(second + "\n", last.text); assertEquals(hash(xml[1].toByteArray()), last.resourceSha256)
        assertEquals((first + "\n").take(2000), firstChunks.first().text)
    }

    @Test fun `emoji at chunk end and overlap boundaries is never split or replaced`() = runBlocking {
        val text = "a".repeat(1743) + "🌿" + "b".repeat(254) + "🌿" + "c".repeat(4000)
        val result = EpubBookTextExtractor(context).extract(store(fixture(listOf(xhtml("<p>$text</p>")))))
        assertTrue(result.chunks.size >= 3)
        assertTrue(result.chunks.all { validUtf16(it.text) && it.text.length <= MAX_INDEX_CHUNK_TEXT })
        assertFalse(result.chunks.any { it.text.contains('\uFFFD') })
        assertTrue(result.chunks.any { it.text.contains("b🌿c") })
    }

    @Test fun `later invalid chapter fails the whole book instead of returning the first chapter`() = runBlocking {
        val book = store(fixture(listOf(xhtml("<p>first valid</p>"), xhtml("<p>invalid</span>"))))
        assertFailure(TextExtractionFailure.INVALID) { EpubBookTextExtractor(context).extract(book) }
    }

    @Test fun `legitimate XHTML doctype remains explicitly unsupported by this initial extraction scope`() = runBlocking {
        val book = store(fixture(listOf("<!DOCTYPE html>" + xhtml("<p>valid source words</p>"))))
        val failure = runCatching { EpubBookTextExtractor(context).extract(book) }.exceptionOrNull()
        assertTrue(failure is TextExtractionException)
        assertTrue((failure as TextExtractionException).reason in setOf(TextExtractionFailure.INVALID, TextExtractionFailure.UNSUPPORTED))
    }

    @Test fun `oversized real XHTML fails even though its ZIP compresses to a small file`() = runBlocking {
        val file = fixture(listOf(xhtml("<p>" + "a".repeat(MAX_INDEX_RESOURCE_BYTES) + "</p>")))
        assertTrue(file.length() < MAX_INDEX_RESOURCE_BYTES)
        assertFailure(TextExtractionFailure.LIMIT) { EpubBookTextExtractor(context).extract(store(file)) }
    }

    @Test fun `book logical payload limit accounts for folded text and repeated source identity`() = runBlocking {
        val chapter = xhtml("<p>" + "a".repeat(1_500_000) + "</p>")
        val book = store(fixture(List(8) { chapter }))
        assertFailure(TextExtractionFailure.LIMIT) { EpubBookTextExtractor(context).extract(book) }
    }

    @Test fun `PDF does not open or hash a file while invalid book hash and changed bytes fail explicitly`() = runBlocking {
        val book = store(fixture(listOf(xhtml("<p>local</p>"))))
        val extractor = EpubBookTextExtractor(context)
        assertFailure(TextExtractionFailure.UNSUPPORTED) { extractor.extract(book.copy(format = BookFormat.PDF, storageName = "missing.pdf")) }
        assertFailure(TextExtractionFailure.INVALID) { extractor.extract(book.copy(sha256 = "A".repeat(64))) }
        File(context.filesDir, "books/${book.storageName}").appendBytes(byteArrayOf(0))
        assertFailure(TextExtractionFailure.SOURCE) { extractor.extract(book) }
        assertFailure(TextExtractionFailure.SOURCE) { extractor.extract(book.copy(storageName = "missing.epub")) }
    }

    @Test fun `mixed unsupported spine fixed layout duplicate unsafe and missing resources never produce partial results`() = runBlocking {
        val good = link("EPUB/chapter.xhtml")
        publication(listOf(good, link("EPUB/image.svg", MediaType.SVG))).usePublication {
            assertFailure(TextExtractionFailure.UNSUPPORTED) { extractIndexBookText(it, model()) }
        }
        publication(listOf(good), fixed = true).usePublication {
            assertFailure(TextExtractionFailure.UNSUPPORTED) { extractIndexBookText(it, model()) }
        }
        publication(listOf(good, good)).usePublication {
            assertFailure(TextExtractionFailure.INVALID) { extractIndexBookText(it, model()) }
        }
        for (href in listOf("../chapter.xhtml", "https://example.invalid/chapter.xhtml", "EPUB/chapter.xhtml?x=1", "EPUB/chapter.xhtml#id")) {
            publication(listOf(link(href))).usePublication {
                val failure = runCatching { extractIndexBookText(it, model()) }.exceptionOrNull()
                assertTrue(failure is TextExtractionException)
                assertTrue((failure as TextExtractionException).reason in setOf(TextExtractionFailure.INVALID, TextExtractionFailure.UNSUPPORTED))
            }
        }
        publication(listOf(good)).usePublication { assertFailure(TextExtractionFailure.SOURCE) { extractIndexBookText(it, model()) } }
    }

    @Test fun `resource count and empty reading order are rejected before any source read`() = runBlocking {
        publication(List(MAX_INDEX_BOOK_RESOURCES + 1) { link("EPUB/$it.xhtml") }).usePublication {
            assertFailure(TextExtractionFailure.LIMIT) { extractIndexBookText(it, model()) }
        }
        publication(emptyList()).usePublication { assertFailure(TextExtractionFailure.INVALID) { extractIndexBookText(it, model()) } }
    }

    @Test fun `navigation helper returns byte identical real XHTML and rejects nonspine source`() = runBlocking {
        val original = xhtml("<p>A&amp;🌿B</p>").toByteArray()
        EpubPublicationSource(context.contentResolver).open(fixture(listOf(original.toString(Charsets.UTF_8)))).use {
            assertArrayEquals(original, readIndexXhtml(it.publication, "EPUB/chapter.xhtml"))
            assertFailure(TextExtractionFailure.INVALID) { readIndexXhtml(it.publication, "EPUB/nav.xhtml") }
            assertFailure(TextExtractionFailure.INVALID) { readIndexXhtml(it.publication, "EPUB/chapter.xhtml#id") }
        }
    }

    @Test fun `bounded read ignores dishonest length hint closes exactly once and rejects empty or extra byte`() = runBlocking {
        val resource = TrackedResource(xhtml("<p>source</p>").toByteArray())
        assertArrayEquals(resource.bytes, readBoundedIndexXhtml(resource))
        assertEquals(0L..MAX_INDEX_RESOURCE_BYTES.toLong(), resource.range)
        assertEquals(0, resource.lengthReads); assertEquals(1, resource.closes)
        val oversized = TrackedResource(ByteArray(MAX_INDEX_RESOURCE_BYTES + 1))
        assertFailure(TextExtractionFailure.LIMIT) { readBoundedIndexXhtml(oversized) }
        assertEquals(1, oversized.closes)
        val empty = TrackedResource(byteArrayOf())
        assertFailure(TextExtractionFailure.INVALID) { readBoundedIndexXhtml(empty) }; assertEquals(1, empty.closes)
    }

    @Test fun `cancelled read keeps primary cancellation when cleanup also fails`() = runBlocking {
        val cancelled = CancellationException("Original cancellation")
        val cleanup = IOException("Close failed")
        val resource = TrackedResource(byteArrayOf(1)).apply { onRead = { throw cancelled }; onClose = { throw cleanup } }
        val failure = runCatching { readBoundedIndexXhtml(resource) }.exceptionOrNull()
        assertSame(cancelled, failure); assertEquals(1, resource.closes)
        assertTrue(failure!!.suppressed.contains(cleanup))
    }

    @Test fun `caller cancellation after bytes are ready closes source before publishing any result`() = runBlocking {
        val resource = TrackedResource(byteArrayOf(1)).apply { onRead = { currentCoroutineContext().cancel() } }
        val job = async { readBoundedIndexXhtml(resource) }
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, resource.closes)
    }

    @Test fun `read and close failures remain typed source failures and preserve cleanup evidence`() = runBlocking {
        val cleanup = IOException("Cleanup")
        val resource = TrackedResource(byteArrayOf(1)).apply { onRead = { throw IOException("Read") }; onClose = { throw cleanup } }
        val failure = runCatching { readBoundedIndexXhtml(resource) }.exceptionOrNull()
        assertEquals(TextExtractionFailure.SOURCE, (failure as TextExtractionException).reason)
        assertTrue(failure.suppressed.contains(cleanup)); assertEquals(1, resource.closes)
        val closeOnly = TrackedResource(byteArrayOf(1)).apply { onClose = { throw cleanup } }
        assertFailure(TextExtractionFailure.SOURCE) { readBoundedIndexXhtml(closeOnly) }
    }

    @Test fun `a cancelled caller remains cancelled when resource concurrently reports an IO failure`() = runBlocking {
        val resource = TrackedResource(byteArrayOf(1)).apply { onRead = {
            currentCoroutineContext().cancel()
            throw IOException("Concurrent IO failure")
        } }
        val job = async { readBoundedIndexXhtml(resource) }
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, resource.closes)
    }

    private suspend fun assertFailure(reason: TextExtractionFailure, block: suspend () -> Any?) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected $reason, got $failure", failure is TextExtractionException)
        assertEquals(reason, (failure as TextExtractionException).reason)
    }

    private fun store(file: File): Book {
        val id = UUID.randomUUID().toString()
        val target = File(context.filesDir, "books/$id.epub")
        target.parentFile!!.mkdirs(); file.copyTo(target); stored += target
        return model().copy(id = id, storageName = target.name, byteSize = target.length(), sha256 = hash(target.readBytes()))
    }

    private fun model() = Book("book", "Local EPUB", null, BookFormat.EPUB, "book.epub", "book.epub", 1, "a".repeat(64), 0)
    private fun link(href: String, type: MediaType = MediaType.XHTML) = Link(Url(href)!!, mediaType = type)
    private fun publication(order: List<Link>, fixed: Boolean = false) = Publication(Manifest(
        metadata = Metadata(localizedTitle = LocalizedString("Fixture"),
            otherMetadata = if (fixed) mapOf("presentation" to mapOf("layout" to "fixed")) else emptyMap()), readingOrder = order))

    // Readium Publication has close(), but does not implement java.io.Closeable.
    private inline fun <T> Publication.usePublication(block: (Publication) -> T): T =
        try { block(this) } finally { close() }

    private fun fixture(chapters: List<String>): File {
        val base = TestBooks.epub(temp.newFile())
        val result = temp.newFile()
        ZipFile(base).use { input -> ZipOutputStream(result.outputStream()).use { output ->
            fun write(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry); output.write(bytes); output.closeEntry()
            }
            input.entries().asSequence().forEach { entry ->
                if (entry.name == "EPUB/chapter.xhtml") return@forEach
                var bytes = input.getInputStream(entry).use { it.readBytes() }
                if (entry.name == "EPUB/package.opf") {
                    val addedItems = chapters.indices.drop(1).joinToString("") {
                        "<item id='chapter$it' href='chapter$it.xhtml' media-type='application/xhtml+xml'/>"
                    }
                    val addedSpine = chapters.indices.drop(1).joinToString("") { "<itemref idref='chapter$it'/>" }
                    bytes = bytes.toString(Charsets.UTF_8).replace("</manifest>", "$addedItems</manifest>")
                        .replace("</spine>", "$addedSpine</spine>").toByteArray()
                }
                write(entry.name, bytes)
            }
            chapters.forEachIndexed { index, text -> write(if (index == 0) "EPUB/chapter.xhtml" else "EPUB/chapter$index.xhtml", text.toByteArray()) }
        } }
        return result
    }

    private fun xhtml(body: String) = "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Not indexed</title></head><body>$body</body></html>"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun validUtf16(text: String): Boolean = text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == text

    private class TrackedResource(val bytes: ByteArray) : Resource {
        override val sourceUrl: AbsoluteUrl? = null
        var range: LongRange? = null
        var lengthReads = 0
        var closes = 0
        var onRead: suspend () -> Unit = {}
        var onClose: () -> Unit = {}
        override suspend fun properties(): Try<Resource.Properties, ReadError> = Try.success(Resource.Properties())
        override suspend fun length(): Try<Long, ReadError> { lengthReads++; return Try.success(1L) }
        override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
            this.range = range; onRead(); return Try.success(bytes)
        }
        override fun close() { closes++; onClose() }
    }
}

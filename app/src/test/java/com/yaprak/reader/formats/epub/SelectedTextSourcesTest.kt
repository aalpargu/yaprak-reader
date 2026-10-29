package com.yaprak.reader.formats.epub

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.fixtures.SelectedAssistantFixtures as F
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.reader.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectedTextSourcesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun realVerifiedBookSourceIsReopenedAndChangedFileRejected() = runBlocking {
        val file = privateBook()
        try {
            val book = F.book.copy(storageName = file.name, byteSize = file.length(), sha256 = bytesHash(file.readBytes()))
            val request = F.request().copy(bookId = book.id, contentSha256 = book.sha256)
            val source = EpubSelectedTextSources(context)
            assertEquals(F.text, source.read(book, request).joinToString("") { it.text })
            assertEquals(F.text, source.read(book, request).joinToString("") { it.text })
            file.appendBytes(byteArrayOf(0))
            assertEquals(BookAnswerFailure.SOURCE, (runCatching { source.read(book, request) }.exceptionOrNull() as BookAnswerException).reason)
        } finally { deleteClosedTestBook(file) }
    }
    @Test fun actualSourceMismatchAndProtectedPublicationAreRejected() = runBlocking {
        for (protected in listOf(false, true)) {
            val file = privateBook(protected)
            try {
                val book = F.book.copy(storageName = file.name, byteSize = file.length(), sha256 = bytesHash(file.readBytes()))
                val request = F.request().copy(contentSha256 = book.sha256,
                    anchor = (F.request().anchor as SourceTextAnchor).copy(nodeTextSha256 = "b".repeat(64)))
                assertEquals(BookAnswerFailure.SOURCE, (runCatching { EpubSelectedTextSources(context).read(book, request) }
                    .exceptionOrNull() as BookAnswerException).reason)
            } finally { deleteClosedTestBook(file) }
        }
    }
    @Test fun paragraphsAndOriginalWhitespaceStayDistinctFromNativePreview() {
        val anchor = SourceRangeAnchor("EPUB/chapter.xhtml", listOf(
            SourceRangeSegment("0/0", 0, 1, F.hash("A"), "A"),
            SourceRangeSegment("1", 0, 1, F.hash("\n"), "\n"),
            SourceRangeSegment("2/0", 0, 1, F.hash("B"), "B")))
        val request = F.request().copy(anchor = anchor, preview = "A\n\nB")
        val sources = prove("<p>A</p>\n<p>B</p>", request)
        assertEquals("A\nB", sources.joinToString("") { it.text })
        assertNotEquals(request.preview, sources.single().text)
        SelectedTextQuestionRequest.create(PreparedSelectedTextSources(request, F.book, sources), "Açıkla")
    }
    @Test fun repeatedNodesNeedActualSourcePathAndCannotBorrowLegacyUniqueness() {
        val anchor = SourceRangeAnchor("EPUB/chapter.xhtml", listOf(SourceRangeSegment("1/0", 0, 4, F.hash("Same"), "Same")))
        val request = F.request().copy(anchor = anchor, preview = "Same")
        assertEquals("Same", prove("<p>Same</p><p>Same</p>", request).single().text)
        val wrong = request.copy(anchor = anchor.copy(segments = listOf(anchor.segments.single().copy(path = "2/0"))))
        bad(BookAnswerFailure.SOURCE) { prove("<p>Same</p><p>Same</p>", wrong) }
        val legacy = F.request("Same")
        bad(BookAnswerFailure.SOURCE) { prove("<p>Same</p><p>Same</p>", legacy) }
    }
    @Test fun completeRawStreamSplitsWithoutLosingOrHalvingEmoji() {
        val text = "a".repeat(1999) + "🌿" + "b".repeat(40)
        val request = F.request(text)
        val references = prove("<p>$text</p>", request)
        assertEquals(listOf("S1", "S2"), references.map { it.label })
        assertEquals(1999, references.first().text.length)
        assertEquals(text, references.joinToString("") { it.text })
        assertTrue(references.all { validAnswerText(it.text, 2000) })
    }
    @Test fun sourceLimitAndBlankWireChunkNeverReturnPartialSuccess() {
        val full = "a".repeat(4097)
        val request = F.request("aaaaa").copy(anchor = SourceTextAnchor("EPUB/chapter.xhtml", 0, 5, F.hash(full)))
        // Legacy v1 does not bind an oversized original node; it is an unproved source.
        bad(BookAnswerFailure.SOURCE) { prove("<p>$full</p>", request) }
        val whitespace = " ".repeat(2000) + "word"
        bad(BookAnswerFailure.LIMIT) { prove("<p>$whitespace</p>", F.request(whitespace)) }
    }
    private fun prove(body: String, request: SelectedTextSourceRequest): List<SelectedTextReference> {
        val xml = "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Local</title></head><body>$body</body></html>"
        val anchor = requireNotNull(request.anchor)
        val proof = SourceTextAnchorVerifier.verifyXhtmlBatch(xml.toByteArray(), anchor.resourceHref,
            listOf(SourceAnchorRequest("selection", anchor, request.preview)))
        return selectedTextReferences(request, proof)
    }
    private fun privateBook(encrypted: Boolean = false): File {
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        return TestBooks.epub(File(dir, "selected-${UUID.randomUUID()}.epub"), encrypted)
    }
    private fun bytesHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private suspend fun deleteClosedTestBook(file: File) {
        // Readium 3.1.2 FileResource.close schedules its handle cleanup asynchronously.
        // Windows cannot unlink the file until that cleanup finishes; do not waive cleanup.
        val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (file.exists() && !file.delete() && System.nanoTime() < end) kotlinx.coroutines.delay(10)
        assertFalse("Closed test book must be released within five seconds", file.exists())
    }
    private fun bad(reason: BookAnswerFailure, block: () -> Unit) {
        assertEquals(reason, (runCatching(block).exceptionOrNull() as? BookAnswerException)?.reason)
    }
}

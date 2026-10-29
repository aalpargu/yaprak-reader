package com.yaprak.reader.formats.epub

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.reader.SelectionRangeCandidate
import com.yaprak.reader.reader.SourceTextAnchor
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SourceTextAnchorVerifierTest {
    @get:Rule val temp = TemporaryFolder()
    private val href = "EPUB/chapter.xhtml"

    @Test fun `one exact source text node yields transient anchor`() {
        val text = "Güneş doğdu. Yeni bir gün başladı."
        val candidate = candidate(text, 0, 5)
        assertEquals(SourceTextAnchor(href, 0, 5, candidate.nodeTextSha256),
            verify("<p>$text</p>", candidate, "Güneş"))
    }

    @Test fun `duplicate full text nodes are ambiguous even when live DOM path differs`() {
        val text = "Aynı pasaj"
        val candidate = candidate(text, 0, 4)
        assertNull(verify("<p>$text</p><p>$text</p>", candidate, "Aynı"))
        assertNull(verify("<p>$text</p><p>$text</p>", candidate.copy(path = "99/2"), "Aynı"))
    }

    @Test fun `deeply nested XHTML is checked without recursive traversal`() {
        val text = "Deep"
        val candidate = candidate(text, 0, text.length)
        val nested = "<span>".repeat(2_000) + text + "</span>".repeat(2_000)
        assertEquals(SourceTextAnchor(href, 0, text.length, candidate.nodeTextSha256),
            verify(nested, candidate, text))
    }

    @Test fun `changed text offset hash and markup fail closed`() {
        val candidate = candidate("Bir pasaj", 4, 9)
        assertNull(verify("<p>Bir başka pasaj</p>", candidate, "pasaj"))
        assertNull(verify("<p>Bir pasaj</p>", candidate, "yanlış"))
        assertNull(verify("<p>Bir pasaj</p>", candidate.copy(start = 3, end = 8), "pasaj"))
        assertNull(verify("<p>Bir <em>pasaj</em></p>", candidate, "pasaj"))
        assertNull(verify("<p>Bir pasaj</p>", candidate.copy(nodeTextSha256 = "0".repeat(64)), "pasaj"))
    }

    @Test fun `XML entities use decoded UTF16 node offsets`() {
        val text = "A&🌿B"
        val candidate = candidate(text, 2, 4)
        assertEquals(SourceTextAnchor(href, 2, 4, candidate.nodeTextSha256),
            verify("<p>A&amp;🌿B</p>", candidate, "🌿"))
    }

    @Test fun `head text and malicious XML never count as source match`() {
        val candidate = candidate("Title", 0, 5)
        assertNull(SourceTextAnchorVerifier.verifyXhtml(
            "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Title</title></head><body/></html>".toByteArray(),
            candidate, "Title"))
        assertNull(SourceTextAnchorVerifier.verifyXhtml(
            "<!DOCTYPE html [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>".plus(xhtml("<p>&secret;</p>")).toByteArray(),
            candidate, "Title"))
        assertNull(SourceTextAnchorVerifier.verifyXhtml(
            xhtml("<p>Title</p>").toByteArray() + ByteArray(2 * 1024 * 1024), candidate, "Title"))
    }

    @Test fun `actual Readium publication supplies only its reading order XHTML`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        EpubPublicationSource(context.contentResolver).open(TestBooks.epub(temp.newFile("fixture.epub"))).use { opened ->
            val text = "Güneş doğdu. Yeni bir gün başladı."
            val candidate = candidate(text, 0, 5)
            assertEquals(SourceTextAnchor(href, 0, 5, candidate.nodeTextSha256),
                SourceTextAnchorVerifier.verify(opened.publication, candidate, "Güneş"))
            val saved = SourceTextAnchor(href, 0, 5, candidate.nodeTextSha256)
            assertTrue(SourceTextAnchorVerifier.verifySaved(opened.publication, saved, "Güneş"))
            assertFalse(SourceTextAnchorVerifier.verifySaved(opened.publication, saved, "güneş"))
            assertFalse(SourceTextAnchorVerifier.verifySaved(opened.publication,
                saved.copy(start = 1, end = 6), "Güneş"))
            assertFalse(SourceTextAnchorVerifier.verifySaved(opened.publication,
                saved.copy(nodeTextSha256 = "0".repeat(64)), "Güneş"))
            assertFalse(SourceTextAnchorVerifier.verifySaved(opened.publication,
                saved.copy(resourceHref = "EPUB/nav.xhtml"), "Güneş"))
            assertFalse(SourceTextAnchorVerifier.verifySaved(opened.publication,
                saved.copy(resourceHref = "../chapter.xhtml"), "Güneş"))
            assertNull(SourceTextAnchorVerifier.verify(opened.publication,
                candidate.copy(resourceHref = "EPUB/nav.xhtml"), "Güneş"))
            assertNull(SourceTextAnchorVerifier.verify(opened.publication,
                candidate.copy(resourceHref = "../chapter.xhtml"), "Güneş"))
        }
    }

    private fun verify(body: String, candidate: SelectionRangeCandidate, selected: String) =
        SourceTextAnchorVerifier.verifyXhtml(xhtml(body).toByteArray(Charsets.UTF_8), candidate, selected)

    private fun xhtml(body: String) =
        "<html xmlns='http://www.w3.org/1999/xhtml'><head/><body>$body</body></html>"

    private fun candidate(nodeText: String, start: Int, end: Int) = SelectionRangeCandidate(
        href, "0/1/0", start, end, MessageDigest.getInstance("SHA-256")
            .digest(nodeText.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) },
    )

    @Test fun `batch proves independent ranges on one unique full node and rejects duplicates`() {
        val full = "A🌿B & hostile <text>"
        val hash = candidate(full, 0, 1).nodeTextSha256
        val first = SourceAnchorRequest("one", SourceTextAnchor(href, 1, 3, hash), "🌿")
        val second = SourceAnchorRequest("two", SourceTextAnchor(href, 3, 4, hash), "B")
        val bytes = xhtml("<p>A🌿B &amp; hostile &lt;text&gt;</p>").toByteArray()
        val proven = SourceTextAnchorVerifier.verifyXhtmlBatch(bytes, href, listOf(first, second))
        assertNull(proven.failure)
        assertEquals(mapOf("one" to full, "two" to full), proven.nodeTexts)
        assertTrue(SourceTextAnchorVerifier.verifyXhtmlBatch(
            xhtml("<p>A🌿B &amp; hostile &lt;text&gt;</p><p>A🌿B &amp; hostile &lt;text&gt;</p>").toByteArray(),
            href, listOf(first, second)).nodeTexts.isEmpty())
        assertTrue(SourceTextAnchorVerifier.verifyXhtmlBatch(bytes, href,
            listOf(first.copy(anchor = (first.anchor as SourceTextAnchor).copy(start = 1, end = 2), text = full.substring(1, 2))))
            .nodeTexts.isEmpty())
    }

    @Test fun `batch completes bounded scan and never publishes early proof after a limit`() {
        val text = "Unique"
        val anchor = candidate(text, 0, text.length).let { SourceTextAnchor(href, it.start, it.end, it.nodeTextSha256) }
        val request = SourceAnchorRequest("one", anchor, text)
        val overNodes = xhtml("<p>Unique</p>" + "<i/>".repeat(200_001)).toByteArray()
        val tooMany = SourceTextAnchorVerifier.verifyXhtmlBatch(overNodes, href, listOf(request))
        assertEquals(SourceProofFailure.LIMIT, tooMany.failure)
        assertTrue(tooMany.nodeTexts.isEmpty())
        assertEquals(SourceProofFailure.LIMIT, SourceTextAnchorVerifier.verifyXhtmlBatch(
            ByteArray(2 * 1024 * 1024 + 1), href, listOf(request)).failure)
        assertEquals(SourceProofFailure.LIMIT, SourceTextAnchorVerifier.verifyXhtmlBatch(
            xhtml("<p>Unique</p>").toByteArray(), href, List(1001) { request.copy(id = "$it") }).failure)
    }

    @Test fun `batch distinguishes unavailable source from unresolved records`() {
        val text = "Unique"
        val anchor = candidate(text, 0, text.length).let { SourceTextAnchor(href, it.start, it.end, it.nodeTextSha256) }
        val request = SourceAnchorRequest("one", anchor, text)
        val unavailable = SourceTextAnchorVerifier.verifyXhtmlBatch("<!DOCTYPE html>".plus(xhtml("<p>Unique</p>"))
            .toByteArray(), href, listOf(request))
        assertEquals(SourceProofFailure.UNAVAILABLE, unavailable.failure)
        val unresolved = SourceTextAnchorVerifier.verifyXhtmlBatch(xhtml("<p>Changed</p>").toByteArray(), href, listOf(request))
        assertNull(unresolved.failure)
        assertTrue(unresolved.nodeTexts.isEmpty())
        assertEquals(SourceProofFailure.UNAVAILABLE, SourceTextAnchorVerifier.verifyXhtmlBatch(
            xhtml("<p>Unique</p>").toByteArray(), href, listOf(request, request)).failure)
    }
}

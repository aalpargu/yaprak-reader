package com.yaprak.reader.formats.epub

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectionRangeCandidateTest {
    private fun result(
        href: String = "/publication/OEBPS/chapter.xhtml",
        path: String = "2/0",
        start: Any = 1,
        end: Any = 3,
        nodeText: String = "abc",
    ): String = JSONObject.quote(JSONObject()
        .put("href", href)
        .put("path", path)
        .put("start", start)
        .put("end", end)
        .put("nodeText", nodeText)
        .toString())

    @Test fun `matching single node candidate keeps only bounded anchor and hash`() {
        val candidate = SelectionRangeCandidateDecoder.decode(result(), "OEBPS/chapter.xhtml", "bc")!!
        assertEquals("OEBPS/chapter.xhtml", candidate.resourceHref)
        assertEquals("2/0", candidate.path)
        assertEquals(1, candidate.start)
        assertEquals(3, candidate.end)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", candidate.nodeTextSha256)
        assertFalse(candidate.toString().contains("nodeText="))
    }

    @Test fun `UTF-16 offsets require exact Readium highlight`() {
        val raw = result(start = 1, end = 3, nodeText = "A🌿B")
        assertEquals(3, SelectionRangeCandidateDecoder.decode(raw, "OEBPS/chapter.xhtml", "🌿")?.end)
        assertNull(SelectionRangeCandidateDecoder.decode(raw, "OEBPS/chapter.xhtml", "A🌿"))
        assertNull(SelectionRangeCandidateDecoder.decode(raw, "OEBPS/chapter.xhtml", "🌿B"))
    }

    @Test fun `selection may begin at the first UTF-16 unit`() {
        val candidate = SelectionRangeCandidateDecoder.decode(
            result(start = 0, end = 2), "OEBPS/chapter.xhtml", "ab")
        assertEquals(0, candidate?.start)
        assertEquals(2, candidate?.end)
    }

    @Test fun `half emoji boundaries are rejected even for an exact substring`() {
        val text = "A🌿B"
        assertNull(SelectionRangeCandidateDecoder.decode(result(start = 1, end = 2, nodeText = text),
            "OEBPS/chapter.xhtml", text.substring(1, 2)))
        assertNull(SelectionRangeCandidateDecoder.decode(result(start = 2, end = 3, nodeText = text),
            "OEBPS/chapter.xhtml", text.substring(2, 3)))
        assertEquals(3, SelectionRangeCandidateDecoder.decode(result(start = 1, end = 3, nodeText = text),
            "OEBPS/chapter.xhtml", "🌿")?.end)
    }

    @Test fun `other resource and malformed DOM paths fail closed`() {
        assertNull(SelectionRangeCandidateDecoder.decode(result(), "other.xhtml", "bc"))
        assertNull(SelectionRangeCandidateDecoder.decode(result(href = "/other/OEBPS/chapter.xhtml"), "OEBPS/chapter.xhtml", "bc"))
        for (path in listOf("", "../2", "2//0", "2/-1", "2/a", "0/")) {
            assertNull(SelectionRangeCandidateDecoder.decode(result(path = path), "OEBPS/chapter.xhtml", "bc"))
        }
        assertNull(SelectionRangeCandidateDecoder.decode(result(path = "1/".repeat(64) + "0"), "OEBPS/chapter.xhtml", "bc"))
        assertNull(SelectionRangeCandidateDecoder.decode(result(path = "1".repeat(1025)), "OEBPS/chapter.xhtml", "bc"))
    }

    @Test fun `invalid offsets and node text fail closed without truncating`() {
        for ((start, end) in listOf(1 to 1, 2 to 1, 1 to 4, -1 to 2)) {
            assertNull(SelectionRangeCandidateDecoder.decode(result(start = start, end = end), "OEBPS/chapter.xhtml", "bc"))
        }
        assertNull(SelectionRangeCandidateDecoder.decode(result(start = "1"), "OEBPS/chapter.xhtml", "bc"))
        assertNull(SelectionRangeCandidateDecoder.decode(result(end = "3"), "OEBPS/chapter.xhtml", "bc"))
        assertNull(SelectionRangeCandidateDecoder.decode(result(nodeText = "x".repeat(4097)), "OEBPS/chapter.xhtml", "xx"))
        assertNull(SelectionRangeCandidateDecoder.decode(result(), "OEBPS/chapter.xhtml", "b"))
        assertNull(SelectionRangeCandidateDecoder.decode(result(), "OEBPS/chapter.xhtml", ""))
    }

    @Test fun `missing or oversized WebView response fails closed`() {
        for (raw in listOf(null, "null", "undefined", "{}", JSONObject.quote("{}"), "x".repeat(65537))) {
            assertNull(SelectionRangeCandidateDecoder.decode(raw, "OEBPS/chapter.xhtml", "bc"))
        }
    }
}

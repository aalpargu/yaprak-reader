package com.yaprak.reader.formats.epub

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectionRangeV2CandidateTest {
    private val href = "EPUB/chapter.xhtml"
    private val namespace = "http://www.w3.org/1999/xhtml"
    private val token = "11111111-1111-4111-8111-111111111111:0:1"

    @Test fun `typed inventory and raw slices remain separate from native paragraph preview`() {
        val json = result("Birinci\n\nİkinci", listOf(slice("0/0", "Birinci"), slice("1/0", "İkinci")),
            listOf(listOf(1, namespace, "body", 2), listOf(1, namespace, "p", 1), listOf(3, "Birinci"),
                listOf(1, namespace, "p", 1), listOf(3, "İkinci")))
        val decoded = decode(json, "Birinci\n\nİkinci")!!
        assertEquals("Birinciİkinci", decoded.segments.joinToString("") { it.selectedText })
        assertEquals("Birinci\n\nİkinci", decoded.preview)
        assertEquals(token, decoded.selectionToken)
        assertEquals(listOf(1, namespace, "body", 2), decoded.bodyTopology.first())
    }

    @Test fun `current native preview resource and exact identity token are required`() {
        val json = result()
        assertNull(decode(json, "başka"))
        assertNull(SelectionRangeV2CandidateDecoder.decode(JSONObject.quote(json.toString()), "other.xhtml", "Bir"))
        assertNull(decode(result().put("href", "/other/EPUB/chapter.xhtml")))
        for (bad in listOf("", "foreign", "11111111-1111-4111-8111-111111111111:01:1",
            "11111111-1111-4111-8111-111111111111:9007199254740992:1", "11111111-1111-4111-8111-111111111111:1:-1"))
            assertNull(decode(result().put("selectionToken", bad)))
    }

    @Test fun `strict offsets and UTF16 boundaries reject half emoji and coercions`() {
        val full = "A🌿B"
        assertEquals("🌿", decode(result("🌿", listOf(slice("0/0", full, 1, 3)), topology(full)), "🌿")?.segments?.single()?.selectedText)
        for ((start, end) in listOf(1 to 2, 2 to 3, -1 to 2, 3 to 3, 1 to 5))
            assertNull(decode(result(full.substring(1, 2), listOf(slice("0/0", full, start, end)), topology(full)), full.substring(1, 2)))
        assertNull(decode(result(segments = listOf(slice("0/0", "Bir").put("start", "0")))))
        assertNull(decodeRaw(result().toString().replace("\"end\":3", "\"end\":3.0")))
        assertNull(decode(result(segments = listOf(slice("0/0", "Bir").put("end", 4294967299L)))))
    }

    @Test fun `canonical ordered nonduplicate paths and whole selection limits are enforced`() {
        for (path in listOf("", "0//0", "0/-1", "01/0", "2147483648", "200001", "0/".repeat(64) + "0"))
            assertNull(decode(result(segments = listOf(slice(path, "Bir")))))
        assertNull(decode(result(segments = listOf(slice("1/0", "Bir"), slice("0/0", "Bir")))))
        assertNull(decode(result(segments = listOf(slice("0/0", "Bir"), slice("0/0", "Bir")))))
        assertNull(decode(result(segments = List(65) { slice("$it/0", "x") })))
        assertNull(decode(result(segments = listOf(slice("0/0", "x".repeat(4097), 0, 1)))))
        assertNull(decode(result(segments = listOf(slice("0/0", "x".repeat(4096)), slice("1/0", "x")))))
    }

    @Test fun `flat typed tree is complete and bounded without forgiving unknown source node types`() {
        val variants = listOf(listOf(listOf(1, namespace, "body", 2), listOf(3, "Bir")),
            listOf(listOf(1, namespace, "body", 0), listOf(3, "Bir")),
            listOf(listOf(1, "urn:foreign", "body", 1), listOf(3, "Bir")),
            listOf(listOf(1, namespace, "html", 1), listOf(3, "Bir")),
            listOf(listOf(1, namespace, "body", 1), listOf(4, "Bir")),
            listOf(listOf(1, namespace, "body", 1), listOf(3, "Bir", "extra")))
        for (topology in variants) assertNull(decode(result(bodyTopology = topology)))
        // JSONObject writes integral Doubles as integers; preserve the actual decimal wire token.
        assertNull(decodeRaw(result().toString().replace("\"body\",1]", "\"body\",1.0]")))
        val comments = listOf(listOf(1, namespace, "body", 1), listOf(1, namespace, "p", 2), listOf(8, "source"), listOf(3, "Bir"))
        assertEquals(listOf(8, "source"), decode(result(segments = listOf(slice("0/1", "Bir")), bodyTopology = comments))?.bodyTopology?.get(2))
    }

    @Test fun `unknown shape missing fields invalid versions and trailing payloads fail closed`() {
        assertNull(decode(result().put("extra", true)))
        assertNull(decodeRaw(result().toString().replace("\"version\":2", "\"version\":2.0")))
        assertNull(decode(result().put("segments", JSONArray(listOf(slice("0/0", "Bir").put("extra", "x"))))))
        assertNull(decode(result().apply { remove("bodyTopology") }))
        assertNull(SelectionRangeV2CandidateDecoder.decode(JSONObject.quote(result().toString() + "garbage"), href, "Bir"))
        assertNull(SelectionRangeV2CandidateDecoder.decode(JSONObject.quote(result().toString()) + "garbage", href, "Bir"))
        assertNull(SelectionRangeV2CandidateDecoder.decode("x".repeat(2 * 1024 * 1024 + 1), href, "Bir"))
    }

    @Test fun `cheap identity remains available without any body proof and rejects forged mismatches`() {
        val json = JSONObject().put("version", 2).put("href", "/publication/$href").put("preview", "Bir").put("selectionToken", token)
        val raw = JSONObject.quote(json.toString())
        assertEquals(SelectionRangeIdentity(href, "Bir", token), SelectionRangeV2CandidateDecoder.decodeIdentity(raw, href, "Bir"))
        assertNull(SelectionRangeV2CandidateDecoder.decodeIdentity(raw, href, "başka"))
        assertNull(SelectionRangeV2CandidateDecoder.decodeIdentity(raw, "other.xhtml", "Bir"))
        assertNull(SelectionRangeV2CandidateDecoder.decode(raw, href, "Bir"))
        val long = "x".repeat(5000)
        json.put("preview", long)
        assertTrue(SelectionRangeV2CandidateDecoder.decodeIdentity(JSONObject.quote(json.toString()), href, long) != null)
        json.put("preview", long + "x")
        assertNull(SelectionRangeV2CandidateDecoder.decodeIdentity(JSONObject.quote(json.toString()), href, long + "x"))
    }

    private fun decode(json: JSONObject, preview: String = "Bir") = SelectionRangeV2CandidateDecoder.decode(JSONObject.quote(json.toString()), href, preview)
    private fun decodeRaw(json: String) = SelectionRangeV2CandidateDecoder.decode(JSONObject.quote(json), href, "Bir")
    private fun result(preview: String = "Bir", segments: List<JSONObject> = listOf(slice("0/0", "Bir")),
        bodyTopology: List<List<Any>> = topology("Bir")) = JSONObject().put("version", 2).put("href", "/publication/$href")
        .put("preview", preview).put("selectionToken", token).put("segments", JSONArray(segments)).put("bodyTopology", JSONArray(bodyTopology))
    private fun slice(path: String, text: String, start: Int = 0, end: Int = text.length) = JSONObject()
        .put("path", path).put("start", start).put("end", end).put("nodeText", text)
    private fun topology(text: String): List<List<Any>> = listOf(listOf(1, namespace, "body", 1), listOf(1, namespace, "p", 1), listOf(3, text))
}

package com.yaprak.reader.formats.epub

import android.app.Application
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.reader.EpubHighlightFailure
import com.yaprak.reader.reader.EpubHighlightStatus
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeSegment
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExactHighlightProtocolTest {
    private val sha = "a".repeat(64)
    private val token = "11111111-1111-1111-1111-111111111111"
    private val protocol = ExactHighlightProtocol("/*YAPRAK_REQUEST*/", "yaprak_test", sha)
    private val anchor = SourceTextAnchor("chapter.xhtml", 0, 5, sha)
    private fun reply() = JSONObject().put("owner", "yaprak_test").put("sourceHash", sha)
        .put("generation", 7).put("path", "/publication/chapter.xhtml").put("state", "ready")
        .put("documentToken", token).put("total", 1).put("resolved", 1)
    private fun decode(json: JSONObject) = protocol.decode(JSONObject.quote(json.toString()), 7, "/publication/chapter.xhtml", token)

    @Test fun `exact identity rejects old source owner generation document or resource replies`() {
        assertEquals("ready", decode(reply())?.state)
        for ((key, value) in listOf("owner" to "foreign", "sourceHash" to "b".repeat(64),
            "generation" to 6, "generation" to 7.5, "path" to "/publication/other.xhtml",
            "documentToken" to "22222222-2222-2222-2222-222222222222")) {
            assertNull(decode(reply().put(key, value)))
        }
        assertNull(protocol.decode("null", 7, "/publication/chapter.xhtml", token))
        assertNull(protocol.decode("x".repeat(8193), 7, "/publication/chapter.xhtml", token))
        // JSONObject canonicalizes 7.0 to 7 when serializing; preserve the actual fractional
        // wire type rather than accidentally testing a canonical integer response.
        val fractionalWire = reply().toString().replace("\"generation\":7", "\"generation\":7.0")
        assertNull(protocol.decode(JSONObject.quote(fractionalWire), 7, "/publication/chapter.xhtml", token))
    }

    @Test fun `proof payload deduplicates exact full node and literal hostile text`() {
        val full = "quote </script> ${'$'}(literal) ` 🌿 \u2028"
        val records = listOf(EpubPassage("one", "quote", anchor, 1, EpubHighlightColor.YELLOW),
            EpubPassage("two", "quote", anchor, 2, EpubHighlightColor.PINK),
            EpubPassage("unresolved", "quote", anchor, 3, EpubHighlightColor.BLUE))
        val json = JSONObject(protocol.request("apply", 7, "/publication/chapter.xhtml", token,
            records = records, proof = SourceAnchorBatch(mapOf("one" to full, "two" to full))))
        assertEquals(1, json.getJSONArray("nodes").length())
        assertEquals(full, json.getJSONArray("nodes").getString(0))
        assertEquals(2, json.getJSONArray("ranges").length())
        assertEquals(3, json.getInt("total"))
        assertEquals("PINK", json.getJSONArray("ranges").getJSONObject(1).getString("color"))
    }

    @Test fun `metadata counts and response state cannot fabricate paint success`() {
        assertEquals(EpubHighlightStatus.Ready(1), paintStatus(decode(reply()), 1))
        assertEquals(EpubHighlightStatus.Partial(1, 1), paintStatus(decode(reply().put("state", "partial").put("resolved", 0)), 1))
        for (json in listOf(reply().put("total", 2), reply().put("resolved", 2),
            reply().put("resolved", 0), reply().put("state", "begun"), reply().put("resolved", "1")))
            assertEquals(EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED), paintStatus(decode(json), 1))
        assertEquals(EpubHighlightStatus.Failed(EpubHighlightFailure.DOM_LIMIT),
            paintStatus(HighlightReply("limit", token, null, null), 1))
    }

    private val rangeAnchor = SourceRangeAnchor("chapter.xhtml", listOf(
        SourceRangeSegment("0/0", 1, 3, sha, "bc"),
        SourceRangeSegment("0/1/0", 0, 2, sha, "de"),
    ))
    private val topology: List<List<Any>> = listOf(
        listOf(1, "http://www.w3.org/1999/xhtml", "body", 1),
        listOf(1, "http://www.w3.org/1999/xhtml", "p", 2), listOf(3, "abc"),
        listOf(1, "http://www.w3.org/1999/xhtml", "em", 1), listOf(3, "def"),
    )
    private fun rangeRecord(id: String = "range") = EpubPassage(id, "bc\nde", rangeAnchor, 1,
        EpubHighlightColor.GREEN, revision = Long.MAX_VALUE)

    @Test fun `multi node group keeps exact slices separate from native display separators`() {
        val json = JSONObject(protocol.request("apply", 7, "/publication/chapter.xhtml", token,
            records = listOf(rangeRecord()), proof = SourceAnchorBatch(emptyMap(),
                rangeNodeTexts = mapOf("range" to listOf("abc", "def")), bodyTopology = topology)))
        assertEquals(0, json.getJSONArray("ranges").length())
        assertEquals(2, json.getJSONArray("rangeNodes").length())
        assertEquals(1, json.getInt("total"))
        val group = json.getJSONArray("groups").getJSONObject(0)
        assertEquals(Long.MAX_VALUE.toString(), group.getString("revision"))
        assertEquals("bc", group.getJSONArray("segments").getJSONObject(0).getString("text"))
        assertEquals("de", group.getJSONArray("segments").getJSONObject(1).getString("text"))
        assertEquals("def", json.getJSONArray("bodyTopology").getJSONArray(4).getString(1))
        assertFalse(group.has("text"))
    }

    @Test fun `shared original paths deduplicate across groups while legacy full texts remain independent`() {
        val records = listOf(EpubPassage("one", "quote", anchor, 1, EpubHighlightColor.YELLOW),
            rangeRecord("two"), rangeRecord("three"))
        val json = JSONObject(protocol.request("apply", 7, "/publication/chapter.xhtml", token,
            records = records, proof = SourceAnchorBatch(mapOf("one" to "quote"),
                rangeNodeTexts = mapOf("two" to listOf("abc", "def"), "three" to listOf("abc", "def")),
                bodyTopology = topology)))
        assertEquals(1, json.getJSONArray("nodes").length())
        assertEquals(1, json.getJSONArray("ranges").length())
        assertEquals(2, json.getJSONArray("rangeNodes").length())
        assertEquals(2, json.getJSONArray("groups").length())
        assertEquals(3, json.getInt("total"))
        assertEquals(0, json.getJSONArray("groups").getJSONObject(1).getJSONArray("segments").getJSONObject(0).getInt("node"))
    }

    @Test fun `missing incomplete or topology less proof cannot publish part of a group`() {
        for (proof in listOf(SourceAnchorBatch(emptyMap()),
            SourceAnchorBatch(emptyMap(), rangeNodeTexts = mapOf("range" to listOf("abc", "def"))),
            SourceAnchorBatch(emptyMap(), rangeNodeTexts = mapOf("range" to listOf("abc")), bodyTopology = topology))) {
            val json = JSONObject(protocol.request("apply", 7, "/publication/chapter.xhtml", token,
                records = listOf(rangeRecord()), proof = proof))
            assertEquals(1, json.getInt("total"))
            assertEquals(0, json.getJSONArray("ranges").length())
            assertFalse(json.has("groups")); assertFalse(json.has("rangeNodes")); assertFalse(json.has("bodyTopology"))
        }
    }

    @Test fun `inconsistent source path proofs fail before sending a payload`() {
        assertThrows(HighlightProofMismatchException::class.java) {
            protocol.request("apply", 7, "/publication/chapter.xhtml", token,
                records = listOf(rangeRecord("one"), rangeRecord("two")), proof = SourceAnchorBatch(emptyMap(),
                    rangeNodeTexts = mapOf("one" to listOf("abc", "def"), "two" to listOf("xyz", "def")),
                    bodyTopology = topology))
        }
    }

    @Test fun `source tree JSON bytes are bounded with no prefix payload success`() {
        assertThrows(HighlightPayloadLimitException::class.java) {
            protocol.request("apply", 7, "/publication/chapter.xhtml", token,
                records = listOf(rangeRecord()), proof = SourceAnchorBatch(emptyMap(),
                    rangeNodeTexts = mapOf("range" to listOf("abc", "def")),
                    bodyTopology = topology + listOf(listOf(8, "ç".repeat(1024 * 1024)))))
        }
    }
}

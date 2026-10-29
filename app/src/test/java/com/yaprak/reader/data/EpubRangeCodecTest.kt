package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.domain.passages.EpubRangeAnchorCodec
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeSegment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EpubRangeCodecTest {
    private val first = SourceRangeSegment("0/0", 2, 7, "a".repeat(64), "İğne ")
    private val second = SourceRangeSegment("0/1/0", 0, 5, "b".repeat(64), "🌿\n漢字")
    private val anchor = SourceRangeAnchor("OPS/first%20chapter.xhtml", listOf(first, second))

    @Test fun exactUnicodeRoundTrip() {
        val json = EpubRangeAnchorCodec.encode(anchor)
        val key = EpubRangeAnchorCodec.key(anchor)
        assertEquals(anchor, EpubRangeAnchorCodec.verify(anchor.resourceHref, json, key))
        assertEquals(json, EpubRangeAnchorCodec.encode(EpubRangeAnchorCodec.decode(anchor.resourceHref, json)))
        assertEquals(64, key.length)
        assertEquals("İğne 🌿\n漢字", anchor.segments.joinToString("") { it.selectedText })
    }

    @Test fun mutableInputIsDetached() {
        val input = mutableListOf(first, second)
        val snapshot = SourceRangeAnchor(anchor.resourceHref, input)
        val key = EpubRangeAnchorCodec.key(snapshot)
        input.clear()
        assertEquals(anchor, snapshot)
        assertEquals(key, EpubRangeAnchorCodec.key(snapshot))
        assertTrue(runCatching { (snapshot.segments as MutableList<SourceRangeSegment>).clear() }.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test fun everyIdentityFieldChangesKey() {
        val key = EpubRangeAnchorCodec.key(anchor)
        val variants = listOf(anchor.copy(resourceHref = "OPS/other.xhtml"),
            anchor.copy(segments = listOf(first.copy(path = "0/0/0"), second)),
            anchor.copy(segments = listOf(first.copy(start = 3, end = 8), second)),
            anchor.copy(segments = listOf(first.copy(nodeTextSha256 = "c".repeat(64)), second)),
            anchor.copy(segments = listOf(first.copy(selectedText = "İğNe "), second)))
        variants.forEach { assertNotEquals(key, EpubRangeAnchorCodec.key(it)) }
    }

    @Test fun forgedResourceAndKeyReject() {
        val json = EpubRangeAnchorCodec.encode(anchor)
        assertFalse(accepts("OPS/other.xhtml", json))
        assertTrue(runCatching { EpubRangeAnchorCodec.verify(anchor.resourceHref, json, "f".repeat(64)) }.isFailure)
        assertTrue(runCatching { EpubRangeAnchorCodec.verify(anchor.resourceHref, json, "A".repeat(64)) }.isFailure)
    }

    @Test fun ambiguousJsonShapeRejects() {
        val json = EpubRangeAnchorCodec.encode(anchor)
        val bad = listOf(json.replaceFirst("{", "{\"extra\":true,"),
            json.replaceFirst("{", "{\"resourceHref\":\"OPS/fake.xhtml\","),
            json.replaceFirst("\"start\":2", "\"start\":2,\"start\":2"),
            json.replaceFirst("\"start\":2", "\"start\":\"2\""),
            json.replaceFirst("\"start\":2", "\"start\":2.0"),
            json.replaceFirst("\"start\":2", "\"start\":2e0"),
            json.replaceFirst("\"start\":2", "\"start\":4294967298"),
            json.replaceFirst("\"start\":2", "\"start\":null"), json + "{}", " $json ")
        bad.forEach { assertFalse(it, accepts(anchor.resourceHref, it)) }
    }

    @Test fun segmentBoundsAndOrderReject() {
        val invalid = listOf(emptyList(), listOf(second, first), listOf(first, first),
            listOf(first.copy(path = "0/00")), listOf(first.copy(path = "0/-1")),
            listOf(first.copy(path = "0/200001")), listOf(first.copy(path = "0/" + "0/".repeat(64) + "0")),
            listOf(first.copy(start = -1)), listOf(first.copy(end = 4097)), listOf(first.copy(end = first.start)),
            listOf(first.copy(nodeTextSha256 = "A".repeat(64))),
            listOf(first.copy(selectedText = "shorter")), listOf(first.copy(start = 0, end = 1, selectedText = "\uD800")),
            (0..64).map { first.copy(path = "$it/0") })
        invalid.forEach { assertTrue(it.toString(), runCatching { EpubRangeAnchorCodec.encode(anchor.copy(segments = it)) }.isFailure) }
    }

    @Test fun selectedBudgetIsExact() {
        val maximal = SourceRangeAnchor(anchor.resourceHref, listOf(first.copy(start = 0, end = 2048,
            selectedText = "🌿".repeat(1024)), second.copy(start = 0, end = 2048, selectedText = "é".repeat(2048))))
        assertEquals(maximal, EpubRangeAnchorCodec.decode(maximal.resourceHref, EpubRangeAnchorCodec.encode(maximal)))
        assertTrue(runCatching { EpubRangeAnchorCodec.encode(maximal.copy(segments = maximal.segments +
            SourceRangeSegment("1/0", 0, 1, "c".repeat(64), "x"))) }.isFailure)
    }

    @Test fun manyDeepPathsRemainWithinCodecBudget() {
        val maximal = SourceRangeAnchor(anchor.resourceHref, (0..63).map { index ->
            SourceRangeSegment("$index/" + "200000/".repeat(62) + "200000", 0, 1, "a".repeat(64), "x")
        })
        val json = EpubRangeAnchorCodec.encode(maximal)
        assertTrue(json.length > 25000)
        assertEquals(maximal, EpubRangeAnchorCodec.verify(maximal.resourceHref, json, EpubRangeAnchorCodec.key(maximal)))
        assertFalse(accepts(anchor.resourceHref, "x".repeat(EpubRangeAnchorCodec.MAX_JSON_UNITS + 1)))
    }

    @Test fun unsafePublicationResourcesReject() {
        listOf("../a.xhtml", "OPS/%2e%2e/a.xhtml", "/a.xhtml", "OPS%2Fa.xhtml", "OPS%5Ca.xhtml",
            "https://example.test/a.xhtml", "OPS/a.xhtml#x", "OPS/a.xhtml?q=x", "OPS\\a.xhtml",
            "OPS/\uD800.xhtml").forEach { href ->
            assertTrue(href, runCatching { EpubRangeAnchorCodec.encode(anchor.copy(resourceHref = href)) }.isFailure)
        }
    }

    private fun accepts(href: String, json: String) = runCatching { EpubRangeAnchorCodec.decode(href, json) }.isSuccess
}

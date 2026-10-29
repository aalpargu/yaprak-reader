package com.yaprak.reader.formats.txt

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StrictTxtTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun utf8BomAndBothExplicitUtf16OrdersPreserveUnicodeWhitespaceAndNormalizeOnlyNewlines() {
        val text = " İstanbul 🌿 漢字\t A & <script>\r\nsecond\rthird\n"
        val expected = text.replace("\r\n", "\n").replace('\r', '\n')
        for (bytes in listOf(text.toByteArray(), byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + text.toByteArray(),
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xfe.toByte(), 0xff.toByte()) + text.toByteArray(Charsets.UTF_16BE))) {
            val parts = StrictTxt.decode(bytes)
            assertEquals(expected, parts.joinToString("") { it.text })
            assertEquals("Text/part0001.xhtml", parts.single().href)
            assertEquals("Metin 1", parts.single().title)
        }
    }
    @Test fun malformedEncodingAndUnmarkedUtf16BinaryAndXmlControlsAreRefused() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xe9.toByte()),
            "a".toByteArray(Charsets.UTF_16LE), byteArrayOf(0xff.toByte(), 0xfe.toByte(), 65),
            byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0xd8.toByte()),
            "a\u0000b".toByteArray(), "a\u000cb".toByteArray(), "a\u007fb".toByteArray(), "a\ufffeb".toByteArray())) {
            assertEquals(TxtFailure.INVALID, (runCatching { StrictTxt.decode(bytes) }.exceptionOrNull() as TxtException).reason)
        }
    }
    @Test fun emptyBomOnlyAndBlankSourcesAreRefused() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()), " \n\t".toByteArray())) {
            assertEquals(TxtFailure.INVALID, (runCatching { StrictTxt.decode(bytes) }.exceptionOrNull() as TxtException).reason)
        }
    }
    @Test fun fullContentSplittingNeverSplitsAnEmojiOrDropsTailOrInfersChapterNames() {
        val text = "a".repeat(TXT_PART_CHARACTERS - 1) + "🌿" + "b".repeat(TXT_PART_CHARACTERS) + "END"
        val parts = StrictTxt.decode(text.toByteArray())
        assertEquals(text, parts.joinToString("") { it.text })
        assertEquals(3, parts.size)
        assertEquals(TXT_PART_CHARACTERS - 1, parts.first().text.length)
        assertTrue(parts.all { it.text.length <= TXT_PART_CHARACTERS && !it.text.first().isLowSurrogate() && !it.text.last().isHighSurrogate() })
        assertEquals(parts.indices.toList(), parts.map { it.index })
        assertEquals(parts.map { it.href }, StrictTxt.decode(text.toByteArray()).map { it.href })
    }
    @Test fun exactCharacterLimitReturnsEveryPartAndExcessIsNeverPartialSuccess() {
        val accepted = "x".repeat(MAX_TXT_CHARACTERS)
        val parts = StrictTxt.decode(accepted.toByteArray())
        assertEquals(MAX_TXT_PARTS, parts.size); assertEquals(accepted, parts.joinToString("") { it.text })
        assertEquals(TxtFailure.LIMIT, (runCatching { StrictTxt.decode((accepted + "x").toByteArray()) }.exceptionOrNull() as TxtException).reason)
    }
    @Test fun oversizedOriginalAndLateCancellationPropagateWithoutAResult() = runBlocking {
        val file = temp.newFile("large.txt")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(MAX_TXT_BYTES.toLong() + 1) }
        assertEquals(TxtFailure.LIMIT, (runCatching { StrictTxt.read(file) }.exceptionOrNull() as TxtException).reason)
        var checks = 0
        val cancelled = runCatching { StrictTxt.decode("a".repeat(20_000).toByteArray()) {
            if (++checks == 4) throw CancellationException("controlled")
        } }.exceptionOrNull()
        assertTrue(cancelled is CancellationException); assertEquals(4, checks)
    }
    @Test fun boundedFileReadAndXmlEscapingTreatMarkupAndUrlsAsLiteralText() = runBlocking {
        val source = "<script src=\"https://example.com\">'&'</script> 🌿"
        val file = temp.newFile("book.txt").apply { writeText(source) }
        assertEquals(source, StrictTxt.read(file).single().text)
        val escaped = txtXml(source)
        assertFalse(escaped.contains("<script")); assertTrue(escaped.contains("&lt;script"))
        assertTrue(escaped.contains("&quot;")); assertTrue(escaped.contains("&apos;&amp;&apos;"))
    }
    @Test fun viewingFilesHaveDistinctOwnersAndStartupRecoversOnlyOwnArtifacts() {
        val folder = temp.newFolder(); val cache = TxtDerivedFiles(folder)
        val first = cache.create(); val second = cache.create(); val unrelated = File(folder, "keep.txt").apply { writeText("keep") }
        assertNotEquals(first, second); cache.release(first)
        assertFalse(first.exists()); assertTrue(second.exists()); assertTrue(unrelated.exists())
        cache.recoverAbandoned(); assertFalse(second.exists()); assertTrue(unrelated.exists())
        assertNotNull(runCatching { cache.release(temp.newFile("outside.epub")) }.exceptionOrNull())
    }
}

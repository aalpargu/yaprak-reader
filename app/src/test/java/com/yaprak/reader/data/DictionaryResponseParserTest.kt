package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.dictionary.*
import com.yaprak.reader.domain.dictionary.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DictionaryResponseParserTest {
    private val response = """[{"word":"leaf","phonetic":"/liːf/","meanings":[{"partOfSpeech":"noun","definitions":[{"definition":"A green part of a plant.","example":"A leaf fell. 🌿"}]}],"license":{"name":"CC BY-SA 3.0","url":"https://creativecommons.org/licenses/by-sa/3.0/"},"sourceUrls":["https://en.wiktionary.org/wiki/leaf"],"phonetics":[{"audio":"https://audio.invalid/file.mp3"}]}]"""
    private fun parse(value: String) = parseDictionaryResponse("leaf", value.toByteArray(Charsets.UTF_8))
    private fun failure(value: String) = (runCatching { parse(value) }.exceptionOrNull() as DictionaryException).reason
    @Test fun `real schema fields preserve literal Unicode definitions examples attribution and immutable lists`() {
        val result = parse(response)
        assertEquals("leaf", result.query)
        val entry = result.entries.single()
        assertEquals("/liːf/", entry.phonetic)
        assertEquals(DictionaryDefinition("noun", "A green part of a plant.", "A leaf fell. 🌿"), entry.definitions.single())
        assertEquals("CC BY-SA 3.0", entry.license!!.name)
        assertEquals(listOf("https://en.wiktionary.org/wiki/leaf"), entry.sources)
        assertTrue(runCatching { (result.entries as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (entry.definitions as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (entry.sources as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
    }
    @Test fun `optional pronunciation example and legacy attribution may be absent without invented values`() {
        val result = parse("""[{"word":"leaf","meanings":[{"partOfSpeech":"noun","definitions":[{"definition":"A plant part."}]}]}]""")
        assertNull(result.entries.single().phonetic); assertNull(result.entries.single().license)
        assertTrue(result.entries.single().sources.isEmpty()); assertNull(result.entries.single().definitions.single().example)
    }
    @Test fun `malformed lenient duplicate trailing HTML missing and wrongly typed fields fail closed`() {
        listOf("[]", "{}", "<html>failure</html>", response + " true", response.replace("\"leaf\"", "leaf"),
            response.replace("\"word\":\"leaf\"", "\"word\":42"),
            response.replace("\"word\":\"leaf\"", "\"word\":\"leaf\",\"word\":\"other\""),
            response.replace("\"meanings\"", "\"absent\""), response.replace("/liːf/", "\\uD800"),
            response.replace("/liːf/", "\\u0000")).forEach { assertEquals(DictionaryFailure.PROVIDER, failure(it)) }
        assertEquals(DictionaryFailure.PROVIDER, (runCatching { parseDictionaryResponse("leaf", byteArrayOf(0xC3.toByte(), 0x28)) }
            .exceptionOrNull() as DictionaryException).reason)
    }
    @Test fun `attribution URLs remain plaintext HTTPS without credentials alternate port or active schemes`() {
        listOf("http://en.wiktionary.org/wiki/leaf", "javascript:alert(1)", "https://user:secret@example.com", "https://example.com:444/x").forEach {
            assertEquals(DictionaryFailure.PROVIDER, failure(response.replace("https://en.wiktionary.org/wiki/leaf", it)))
        }
    }
    @Test fun `bytes strings entries definitions depth and unknown data have bounded failure without truncation`() {
        assertEquals(DictionaryFailure.TOO_LARGE, (runCatching { parseDictionaryResponse("leaf", ByteArray(MAX_DICTIONARY_RESPONSE_BYTES + 1)) }
            .exceptionOrNull() as DictionaryException).reason)
        assertEquals(DictionaryFailure.TOO_LARGE, failure(response.replace("A green part of a plant.", "x".repeat(4097))))
        val entry = response.substring(1, response.length - 1)
        assertEquals(DictionaryFailure.TOO_LARGE, failure("[" + List(17) { entry }.joinToString(",") + "]"))
        val definition = """{"definition":"A plant part."}"""
        assertEquals(DictionaryFailure.TOO_LARGE, failure("""[{"word":"leaf","meanings":[{"partOfSpeech":"noun","definitions":[${List(257) { definition }.joinToString(",")}]}]}]"""))
        assertEquals(DictionaryFailure.TOO_LARGE, failure("[".repeat(34) + "null" + "]".repeat(34)))
        assertEquals(DictionaryFailure.TOO_LARGE, failure(response.replace("\"phonetics\"", "\"unknown\"").replace("https://audio.invalid/file.mp3", "a".repeat(16385))))
    }
}

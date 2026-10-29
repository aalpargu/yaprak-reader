package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.assistant.*
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LongBookAnswerWireTest {
    private fun request(text: (Int) -> String = { "字".repeat(1990) + " piece $it" }): BookQuestionRequest {
        val generation = UUID.randomUUID().toString()
        val section = BookSourceSection(F.scope, generation, 0, F.hit.target.resourceHref,
            F.hit.target.resourceSha256, F.hit.chapterTitle, 0, 64)
        val sources = PreparedBookSources(UUID.randomUUID().toString(), F.scope, "", 64,
            (0 until 64).map { index -> BookSourceReference("S" + (index + 1),
                F.hit.copy(id = index + 1L, generation = generation, chunkIndex = index, text = text(index))) }, section)
        return BookQuestionRequest.create(sources, "Özetle")
    }
    private fun response(request: BookQuestionRequest, label: String = "S64", quote: String = "piece 63"): ByteArray =
        JSONObject().put("request_id", request.sources.requestId).put("status", "answered").put("message", "")
            .put("statements", JSONArray().put(JSONObject().put("text", "Son kaynaktan açıklama.")
                .put("citations", JSONArray().put(JSONObject().put("source", label).put("quote", quote)))))
            .toString().toByteArray(Charsets.UTF_8)

    @Test fun sixtyFourUnicodeChunksUseTheSeparateBoundAndPreserveAllWireSources() {
        val request = request()
        val bytes = bookQuestionBytes(request)
        assertTrue(bytes.size > MAX_BOOK_REQUEST_BYTES)
        assertTrue(bytes.size <= MAX_COMPLETE_BOOK_REQUEST_BYTES)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(64, root.getJSONArray("sources").length())
        assertEquals("S64", root.getJSONArray("sources").getJSONObject(63).getString("id"))
        assertEquals(request.sources.references.last().hit.text,
            root.getJSONArray("sources").getJSONObject(63).getString("text"))
    }
    @Test fun escapedControlCharactersCannotBypassOrExceedTheUtf8RequestBound() {
        val request = request { "\u0001".repeat(2000) }
        val bytes = bookQuestionBytes(request)
        assertTrue(bytes.size > 700_000)
        assertTrue(bytes.size <= MAX_COMPLETE_BOOK_REQUEST_BYTES)
        val decoded = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("sources")
        assertEquals(64, decoded.length())
        assertEquals("\u0001".repeat(2000), decoded.getJSONObject(63).getString("text"))
    }
    @Test fun threeCharacterLastSourceLabelsParseAndUnknownOrForgedQuotesRefuse() {
        val request = request()
        val answer = parseBookAnswer(request, response(request))
        validateBookAnswer(request, answer)
        assertSame(request.sources.references.last(), answer.statements.single().citations.single().source)
        for ((label, quote, expected) in listOf(
            Triple("S65", "piece 63", BookAnswerFailure.INVALID_RESPONSE),
            Triple("S64", "piece 64", BookAnswerFailure.INVALID_RESPONSE),
            Triple("S100", "piece 63", BookAnswerFailure.LIMIT))) {
            try { parseBookAnswer(request, response(request, label, quote)); fail("Forged last citation") }
            catch (failure: BookAnswerException) { assertEquals(expected, failure.reason) }
        }
    }
    @Test fun largerWireStillExcludesBookMetadataPathsAndHistory() {
        val request = request()
        val raw = bookQuestionBytes(request).toString(Charsets.UTF_8)
        val root = JSONObject(raw)
        assertEquals(setOf("request_id", "question", "sources"), root.keys().asSequence().toSet())
        assertFalse(raw.contains(F.book.id)); assertFalse(raw.contains(F.book.sha256))
        assertFalse(raw.contains(F.book.title)); assertFalse(raw.contains(F.hit.target.resourceHref))
        val source = root.getJSONArray("sources").getJSONObject(63)
        assertEquals(setOf("id", "text"), source.keys().asSequence().toSet())
    }
}

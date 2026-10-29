package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.assistant.*
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as A
import com.yaprak.reader.fixtures.SelectedAssistantFixtures as F
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectedTextAnswerJsonTest {
    @Test fun pdfWireCarriesOnlyExactSelectedTextAndRequestBoundAnswerNeverNativeEvidence() {
        val fixture = com.yaprak.reader.fixtures.PdfSelectedAssistantFixtures
        val request = SelectedTextQuestionRequest.create(fixture.sources(), "Ne oldu?")
        val raw = selectedTextQuestionBytes(request).toString(Charsets.UTF_8)
        val root = JSONObject(raw)
        assertEquals(setOf("request_id", "question", "sources"), root.keys().asSequence().toSet())
        val source = root.getJSONArray("sources").getJSONObject(0)
        assertEquals(setOf("id", "text"), source.keys().asSequence().toSet())
        assertEquals(request.sources.references.single().text, source.getString("text"))
        for (sensitive in listOf(fixture.book.id, fixture.book.sha256, fixture.book.title,
            request.sources.request.selectionId, request.sources.request.sourceSelectionId!!, "pageIndex", "pageWidth", "bounds", "pdfSourceAnchor"))
            assertFalse(raw.contains(sensitive))
        val answer = parseSelectedTextAnswer(request, A.response(request.sources.requestId).toByteArray())
        validateSelectedTextAnswer(request, answer)
        assertSame(request.sources.references.single(), answer.statements.single().citations.single().source)
    }
    @Test fun selectionWireExcludesBookLocatorAnchorPreviewAndCredentials() {
        val request = F.question()
        val raw = selectedTextQuestionBytes(request).toString(Charsets.UTF_8)
        val root = JSONObject(raw)
        assertEquals(setOf("request_id", "question", "sources"), root.keys().asSequence().toSet())
        assertEquals(request.sources.requestId, root.getString("request_id"))
        assertEquals(F.text, root.getJSONArray("sources").getJSONObject(0).getString("text"))
        for (sensitive in listOf(F.book.id, F.book.sha256, F.book.title, "EPUB/chapter.xhtml", "nodeTextSha256", "preview", A.connection().accessToken))
            assertFalse(raw.contains(sensitive))
    }
    @Test fun exactQuoteResolvesOnlyThisTypedSelectionSnapshot() {
        val request = F.question()
        val answer = parseSelectedTextAnswer(request, A.response(request.sources.requestId).toByteArray())
        validateSelectedTextAnswer(request, answer)
        assertSame(request.sources.references.single(), answer.statements.single().citations.single().source)
        assertEquals("Güneş doğdu.", answer.statements.single().citations.single().quote)
    }
    @Test fun forgedRequestLabelAndQuoteAreRejected() {
        val request = F.question(); val good = A.response(request.sources.requestId)
        for (invalidWire in listOf(good.replace(request.sources.requestId, java.util.UUID.randomUUID().toString()),
            good.replace("\"source\":\"S1\"", "\"source\":\"S2\""), good.replace("Güneş doğdu.", "Invented")))
            bad(BookAnswerFailure.INVALID_RESPONSE) { parseSelectedTextAnswer(request, invalidWire.toByteArray()) }
    }
    @Test fun duplicateTrailingTypesUtf8AndByteBudgetRemainStrict() {
        val request = F.question(); val good = A.response(request.sources.requestId)
        for (raw in listOf(good + "trailing", good.replace("\"status\":\"answered\"", "\"status\":\"answered\",\"status\":\"answered\""),
            good.replace("\"message\":\"\"", "\"message\":null")))
            bad(BookAnswerFailure.INVALID_RESPONSE) { parseSelectedTextAnswer(request, raw.toByteArray()) }
        bad(BookAnswerFailure.INVALID_RESPONSE) { parseSelectedTextAnswer(request, byteArrayOf(0xC3.toByte(), 0x28)) }
        bad(BookAnswerFailure.LIMIT) { parseSelectedTextAnswer(request, ByteArray(MAX_BOOK_ANSWER_BYTES + 1)) }
    }
    private fun bad(reason: BookAnswerFailure, block: () -> Unit) {
        assertEquals(reason, (runCatching(block).exceptionOrNull() as? BookAnswerException)?.reason)
    }
}

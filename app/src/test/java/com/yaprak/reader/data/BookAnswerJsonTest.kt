package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.assistant.*
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookAnswerJsonTest {
    @Test fun `literal answer resolves labels only to the original local references`() {
        val request = F.request()
        val result = parseBookAnswer(request, F.response(request.sources.requestId).toByteArray())
        assertSame(request.sources.references.single(), result.statements.single().citations.single().source)
        assertEquals("Güneş doğdu.", result.statements.single().citations.single().quote)
    }
    @Test fun `wire sends question source text and random request ID without book metadata or locators`() {
        val request = F.request()
        val wire = String(bookQuestionBytes(request), Charsets.UTF_8)
        assertTrue(wire.contains(request.question)); assertTrue(wire.contains(F.hit.text))
        for (secret in listOf(F.book.id, F.book.title, F.book.sha256, F.hit.target.resourceHref, F.hit.target.resourceSha256,
            F.hit.chapterTitle!!, F.book.authors!!, request.sources.query + "\"", "accessToken", "history")) assertFalse(wire.contains(secret))
    }
    @Test fun `old unknown empty duplicate and normalized but inexact citations are rejected`() {
        val request = F.request(); val valid = F.response(request.sources.requestId)
        val failures = listOf(valid.replace(request.sources.requestId, F.request().sources.requestId),
            valid.replace("\"S1\"", "\"S2\""), valid.replace("\"quote\":\"Güneş doğdu.\"", "\"quote\":\"\""),
            valid.replace("\"quote\":\"Güneş doğdu.\"", "\"quote\":\"güneş doğdu.\""),
            valid.replace("\"quote\":\"Güneş doğdu.\"", "\"quote\":\"Güneş  doğdu.\""),
            valid.replace("[{\"source\":\"S1\",\"quote\":\"Güneş doğdu.\"}]",
                "[{\"source\":\"S1\",\"quote\":\"Güneş doğdu.\"},{\"source\":\"S1\",\"quote\":\"Güneş doğdu.\"}]"),
            valid.replace("[{\"source\":\"S1\",\"quote\":\"Güneş doğdu.\"}]", "[]"))
        failures.forEach { assertEquals(BookAnswerFailure.INVALID_RESPONSE, failure { parseBookAnswer(request, it.toByteArray()) }) }
    }
    @Test fun `strict malformed duplicate extra trailing number null and unicode JSON fails`() {
        val request = F.request(); val valid = F.response(request.sources.requestId)
        for (bad in listOf(valid + valid, valid.dropLast(1), valid.replace("\"message\":\"\"", "\"message\":null"),
            valid.replace("\"message\":\"\"", "\"message\":\"\",\"message\":\"\""),
            valid.replace("\"message\":\"\"", "\"message\":\"\",\"url\":\"https://evil.example\""),
            valid.replace("\"quote\":\"Güneş doğdu.\"", "\"quote\":123"),
            valid.replace("\"quote\":\"Güneş doğdu.\"", "\"quote\":\"\\ud800\""),
            valid.replace("\"quote\":\"Güneş doğdu.\"", "\"quote\":\"\\u0000\"")))
            assertEquals(BookAnswerFailure.INVALID_RESPONSE, failure { parseBookAnswer(request, bad.toByteArray()) })
        assertEquals(BookAnswerFailure.INVALID_RESPONSE, failure { parseBookAnswer(request, byteArrayOf(0xc3.toByte(), 0x28)) })
    }
    @Test fun `insufficient remains scoped to sent excerpts and cannot carry uncited answer statements`() {
        val request = F.request()
        val valid = """{"request_id":"${request.sources.requestId}","status":"insufficient","statements":[],"message":"Parçalar yeterli değil."}"""
        assertEquals(BookAnswerStatus.INSUFFICIENT, parseBookAnswer(request, valid.toByteArray()).status)
        assertEquals(BookAnswerFailure.INVALID_RESPONSE, failure { parseBookAnswer(request,
            F.response(request.sources.requestId).replace("answered", "insufficient").toByteArray()) })
        assertEquals(BookAnswerFailure.INVALID_RESPONSE, failure { parseBookAnswer(request, valid.replace("Parçalar yeterli değil.", "").toByteArray()) })
    }
    @Test fun `response bytes nesting and statement budgets never silently truncate`() {
        val request = F.request()
        assertEquals(BookAnswerFailure.LIMIT, failure { parseBookAnswer(request, ByteArray(MAX_BOOK_ANSWER_BYTES + 1)) })
        assertEquals(BookAnswerFailure.LIMIT, failure { parseBookAnswer(request, ("[".repeat(15) + "[]" + "]".repeat(15)).toByteArray()) })
        assertEquals(BookAnswerFailure.LIMIT, failure { parseBookAnswer(request,
            F.response(request.sources.requestId).replace("\"text\":\"Güneş doğdu.\"", "\"text\":\"${"x".repeat(1001)}\"").toByteArray()) })
    }
    private fun failure(block: () -> Unit) = (runCatching(block).exceptionOrNull() as BookAnswerException).reason
}

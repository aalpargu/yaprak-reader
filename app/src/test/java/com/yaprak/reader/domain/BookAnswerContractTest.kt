package com.yaprak.reader.domain

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import org.junit.Assert.*
import org.junit.Test

class BookAnswerContractTest {
    @Test fun `valid PDF page target cannot borrow EPUB metadata to enter the answer transport`() {
        val target = F.hit.target.copy(resourceHref = "", sourceFormat = com.yaprak.reader.domain.library.BookFormat.PDF, pdfPageIndex = 0)
        assertTrue(target.isValid)
        for (book in listOf(F.hit.book, F.hit.book.copy(format = com.yaprak.reader.domain.library.BookFormat.PDF))) {
            assertEquals(BookAnswerFailure.SOURCE,
                failure { BookQuestionRequest.create(F.sources(F.hit.copy(book = book, target = target)), "Soru") })
        }
    }
    @Test fun `question limits are full unicode not a silently truncated paragraph`() {
        val sources = F.sources()
        assertEquals("Ne oldu? 🌿", BookQuestionRequest.create(sources, " Ne oldu? 🌿 ").question)
        for (text in listOf("", "  ", "a".repeat(1001), "bad\u0000", "bad\ud800", "bad\udc00")) {
            assertEquals(BookAnswerFailure.INVALID_INPUT, failure { BookQuestionRequest.create(sources, text) })
        }
    }
    @Test fun `request refuses missing foreign duplicate or invalid local evidence before networking`() {
        val sources = F.sources()
        val wrong = listOf(
            PreparedBookSources(sources.requestId, F.scope, "Güneş", 0, emptyList()),
            PreparedBookSources("not-a-uuid", F.scope, "Güneş", 1, sources.references),
            PreparedBookSources(sources.requestId, F.scope, "Güneş", 2, sources.references + sources.references),
            F.sources(F.hit.copy(target = F.hit.target.copy(bookId = "other"))),
            F.sources(F.hit.copy(text = "x".repeat(2001))), F.sources(F.hit.copy(chunkIndex = -1)),
        )
        wrong.forEach { assertEquals(BookAnswerFailure.SOURCE, failure { BookQuestionRequest.create(it, "Soru") }) }
    }
    @Test fun `only exact HTTPS backend and server access credential accepted`() {
        assertEquals("assistant.example", F.connection().endpoint.host)
        for (url in listOf("http://assistant.example/v1/book-answer", "https://u:p@assistant.example/v1/book-answer",
            "https://assistant.example/v1/book-answer?x=y", "https://assistant.example/v1/book-answer#x",
            "https://assistant.example:8443/v1/book-answer", "https://api.openai.com/v1/responses", "invalid")) {
            assertEquals(BookAnswerFailure.CONFIGURATION, failure { BookAnswerConnection.create(url, F.connection().accessToken) })
        }
        for (token in listOf("", "short", "a".repeat(257), "bad\r\nheader-injection", "sk-provider-secret-placeholder"))
            assertEquals(BookAnswerFailure.CONFIGURATION, failure { BookAnswerConnection.create(F.connection().endpoint.toString(), token) })
        assertFalse(F.connection().toString().contains(F.connection().accessToken))
    }
    @Test fun `answer lists are detached and cross request or invented quote fails`() {
        val request = F.request()
        val citations = mutableListOf(BookAnswerCitation(request.sources.references.single(), "Güneş doğdu."))
        val statement = BookAnswerStatement("Güneş doğdu.", citations)
        val statements = mutableListOf(statement)
        val answer = BookAnswer(request.sources.requestId, BookAnswerStatus.ANSWERED, statements, "")
        citations.clear(); statements.clear(); validateBookAnswer(request, answer)
        assertEquals(1, answer.statements.size); assertEquals(1, statement.citations.size)
        assertEquals(BookAnswerFailure.INVALID_RESPONSE, failure { validateBookAnswer(F.request(), answer) })
        assertEquals(BookAnswerFailure.INVALID_RESPONSE, failure { validateBookAnswer(request, BookAnswer(request.sources.requestId,
            BookAnswerStatus.ANSWERED, listOf(BookAnswerStatement("Invented", listOf(BookAnswerCitation(request.sources.references.single(), "Ay doğdu.")))), "")) })
    }
    private fun failure(block: () -> Unit) = (runCatching(block).exceptionOrNull() as BookAnswerException).reason
}

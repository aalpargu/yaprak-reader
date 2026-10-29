package com.yaprak.reader.fixtures

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import java.util.UUID

object AssistantFixtures {
    val book = Book("assistant-book", "Kaynak kitap 🌿", "Deniz", BookFormat.EPUB,
        "source.epub", "source.epub", 12, "a".repeat(64), 1)
    val scope = BookTextScope(book.id, book.sha256)
    val hit = LibraryTextHit(1, "generation", book, LibraryTextTarget(book.id, book.sha256,
        "EPUB/chapter.xhtml", "b".repeat(64)), "Kaynak bölüm", "Güneş doğdu. Yeni bir gün başladı. 🌿", "Güneş doğdu.", 0, 0)
    fun sources(hit: LibraryTextHit = this.hit, query: String = "Güneş", id: String = UUID.randomUUID().toString()) =
        PreparedBookSources(id, scope, query, 1, listOf(BookSourceReference("S1", hit)))
    fun request() = BookQuestionRequest.create(sources(), "Ne oldu?")
    fun answer(request: BookQuestionRequest) = BookAnswer(request.sources.requestId, BookAnswerStatus.ANSWERED,
        listOf(BookAnswerStatement("Güneş doğdu.", listOf(BookAnswerCitation(request.sources.references.single(), "Güneş doğdu.")))), "")
    fun connection() = BookAnswerConnection.create("https://assistant.example/v1/book-answer", "controlled-server-access-code-1234")
    fun response(id: String) = """{"request_id":"$id","status":"answered","statements":[{"text":"Güneş doğdu.","citations":[{"source":"S1","quote":"Güneş doğdu."}]}],"message":""}"""
}

package com.yaprak.reader.fixtures

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.reader.SourceTextAnchor
import java.security.MessageDigest
import java.util.UUID

object SelectedAssistantFixtures {
    val book = AssistantFixtures.book
    val text = "Güneş doğdu. Yeni bir gün başladı."
    fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun request(text: String = this.text, id: String = UUID.randomUUID().toString()) =
        SelectedTextSourceRequest(book.id, book.sha256, id, text,
            SourceTextAnchor("EPUB/chapter.xhtml", 0, text.length, hash(text)))
    fun sources(request: SelectedTextSourceRequest = request()) =
        PreparedSelectedTextSources(request, book, listOf(SelectedTextReference("S1", request.preview)))
    fun question() = SelectedTextQuestionRequest.create(sources(), "Ne oldu?")
    fun answer(request: SelectedTextQuestionRequest) = SelectedTextAnswer(request.sources.requestId, BookAnswerStatus.ANSWERED,
        listOf(SelectedTextStatement("Güneş doğdu.", listOf(SelectedTextCitation(request.sources.references.first(), "Güneş doğdu.")))), "")
}

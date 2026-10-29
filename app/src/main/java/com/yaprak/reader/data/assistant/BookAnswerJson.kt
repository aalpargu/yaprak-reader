package com.yaprak.reader.data.assistant

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.yaprak.reader.domain.assistant.*
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException

/** Wire payload excludes book ID/hash/title/href, cached query, history and the original book file. */
internal fun bookQuestionBytes(request: BookQuestionRequest): ByteArray {
    return questionBytes(request.sources.requestId, request.question, request.sources.references.map { it.label to it.text },
        if (request.sources.section == null && request.sources.chapter == null && request.sources.prefix == null) MAX_BOOK_REQUEST_BYTES else MAX_COMPLETE_BOOK_REQUEST_BYTES)
}

internal fun selectedTextQuestionBytes(request: SelectedTextQuestionRequest): ByteArray =
    questionBytes(request.sources.requestId, request.question, request.sources.references.map { it.label to it.text }, MAX_BOOK_REQUEST_BYTES)

private fun questionBytes(requestId: String, question: String, sources: List<Pair<String, String>>, maximumBytes: Int): ByteArray {
    val output = StringWriter()
    JsonWriter(output).use { writer ->
        writer.beginObject().name("request_id").value(requestId)
            .name("question").value(question).name("sources").beginArray()
        sources.forEach { (label, text) ->
            writer.beginObject().name("id").value(label).name("text").value(text).endObject()
        }
        writer.endArray().endObject()
    }
    return output.toString().toByteArray(Charsets.UTF_8).also {
        // Escaped valid text can use six bytes per UTF-16 code unit.
        if (it.size > maximumBytes) failAnswer(BookAnswerFailure.LIMIT)
    }
}

/** Strict JSON and exact quote checks prevent forged references, not semantic mistakes by a model. */
internal fun parseBookAnswer(request: BookQuestionRequest, bytes: ByteArray): BookAnswer {
    val parsed = parseAnswerWire(request.sources.requestId, request.sources.references, { it.label }, { it.text }, bytes)
    return BookAnswer(request.sources.requestId, parsed.status, parsed.statements.map { statement ->
        BookAnswerStatement(statement.text, statement.citations.map { (source, quote) -> BookAnswerCitation(source, quote) })
    }, parsed.message)
}

internal fun parseSelectedTextAnswer(request: SelectedTextQuestionRequest, bytes: ByteArray): SelectedTextAnswer {
    val parsed = parseAnswerWire(request.sources.requestId, request.sources.references, { it.label }, { it.text }, bytes)
    return SelectedTextAnswer(request.sources.requestId, parsed.status, parsed.statements.map { statement ->
        SelectedTextStatement(statement.text, statement.citations.map { (source, quote) -> SelectedTextCitation(source, quote) })
    }, parsed.message)
}

private class AnswerWire<T>(val status: BookAnswerStatus, val statements: List<StatementWire<T>>, val message: String)
private class StatementWire<T>(val text: String, val citations: List<Pair<T, String>>)

/** Both origins use the same bounded strict wire, but retain their actual typed local source identity. */
private fun <T : Any> parseAnswerWire(requestId: String, sources: List<T>, labelOf: (T) -> String,
    textOf: (T) -> String, bytes: ByteArray): AnswerWire<T> {
    if (bytes.size > MAX_BOOK_ANSWER_BYTES) failAnswer(BookAnswerFailure.LIMIT)
    try {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            val fields = AnswerJsonReader(reader).read().fields(setOf("request_id", "status", "statements", "message"))
            if (reader.peek() != JsonToken.END_DOCUMENT) badAnswer()
            fields
        }
        if (root.text("request_id", 36) != requestId) badAnswer()
        val status = when (root.text("status", 12)) {
            "answered" -> BookAnswerStatus.ANSWERED
            "insufficient" -> BookAnswerStatus.INSUFFICIENT
            else -> badAnswer()
        }
        val message = root.text("message", 500, allowEmpty = true)
        val rawStatements = root["statements"] as? List<*> ?: badAnswer()
        if (rawStatements.size > 8) failAnswer(BookAnswerFailure.LIMIT)
        if (status == BookAnswerStatus.INSUFFICIENT) {
            if (rawStatements.isNotEmpty() || message.isBlank()) badAnswer()
            return AnswerWire(status, emptyList(), message)
        }
        if (rawStatements.isEmpty() || message.isNotEmpty()) badAnswer()
        var totalText = 0
        val statements = rawStatements.map { raw ->
            val statement = raw.fields(setOf("text", "citations"))
            val body = statement.text("text", 1000)
            totalText += body.length
            if (totalText > 4000) failAnswer(BookAnswerFailure.LIMIT)
            val rawCitations = statement["citations"] as? List<*> ?: badAnswer()
            if (rawCitations.size !in 1..4) badAnswer()
            val seen = mutableSetOf<Pair<String, String>>()
            val citations = rawCitations.map { entry ->
                val citation = entry.fields(setOf("source", "quote"))
                val label = citation.text("source", 3)
                val quote = citation.text("quote", 500)
                val source = sources.singleOrNull { labelOf(it) == label } ?: badAnswer()
                if (!seen.add(label to quote) || !textOf(source).contains(quote)) badAnswer()
                source to quote
            }
            StatementWire(body, citations)
        }
        return AnswerWire(status, statements, "")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: BookAnswerException) { throw failure }
    catch (_: Exception) { badAnswer() }
}

private fun Any?.fields(names: Set<String>): Map<*, *> {
    val fields = this as? Map<*, *> ?: badAnswer()
    if (fields.keys != names) badAnswer()
    return fields
}
private fun Map<*, *>.text(name: String, limit: Int, allowEmpty: Boolean = false): String {
    val text = this[name] as? String ?: badAnswer()
    if (text.length > limit) failAnswer(BookAnswerFailure.LIMIT)
    if (!validAnswerText(text, limit, allowEmpty)) badAnswer()
    return text
}
private fun badAnswer(): Nothing = failAnswer(BookAnswerFailure.INVALID_RESPONSE)

private class AnswerJsonReader(private val reader: JsonReader) {
    private var nodes = 0
    fun read(depth: Int = 0): Any? {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
        if (depth > 12 || ++nodes > 512) failAnswer(BookAnswerFailure.LIMIT)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val map = linkedMapOf<String, Any?>()
                while (reader.hasNext()) {
                    if (map.size >= 8) failAnswer(BookAnswerFailure.LIMIT)
                    val name = reader.nextName()
                    if (!validAnswerText(name, 40) || map.containsKey(name)) badAnswer()
                    map[name] = read(depth + 1)
                }
                reader.endObject(); map
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val array = mutableListOf<Any?>()
                while (reader.hasNext()) {
                    if (array.size >= 8) failAnswer(BookAnswerFailure.LIMIT)
                    array += read(depth + 1)
                }
                reader.endArray(); array
            }
            JsonToken.STRING -> reader.nextString().also {
                if (it.length > 4000) failAnswer(BookAnswerFailure.LIMIT)
                if (!validAnswerText(it, 4000, allowEmpty = true)) badAnswer()
            }
            // No valid answer field is a number, boolean or null.
            else -> badAnswer()
        }
    }
}

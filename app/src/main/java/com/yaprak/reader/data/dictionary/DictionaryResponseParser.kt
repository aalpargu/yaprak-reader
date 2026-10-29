package com.yaprak.reader.data.dictionary

import android.util.JsonReader
import android.util.JsonToken
import com.yaprak.reader.domain.dictionary.*
import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections

internal const val MAX_DICTIONARY_RESPONSE_BYTES = 1024 * 1024
private const val MAX_DEFINITIONS = 256

/** Strict bounded JSON. Provider HTML/audio are neither rendered nor fetched. */
internal fun parseDictionaryResponse(query: String, bytes: ByteArray): DictionaryResult {
    if (bytes.size > MAX_DICTIONARY_RESPONSE_BYTES) tooLarge()
    try {
        val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = JsonReader(StringReader(source)).use { reader ->
            reader.isLenient = false
            val value = BoundedDictionaryJson(reader).read()
            if (reader.peek() != JsonToken.END_DOCUMENT) badProvider()
            value as? List<*> ?: badProvider()
        }
        if (root.isEmpty()) badProvider()
        if (root.size > 16) tooLarge()
        var totalDefinitions = 0
        val entries = root.map { raw ->
            val entry = raw.objectValue()
            val word = entry.requiredText("word", 100)
            val phonetic = entry.optionalText("phonetic", 200)
            val definitions = entry.requiredList("meanings").flatMap { meaningRaw ->
                val meaning = meaningRaw.objectValue()
                val part = meaning.requiredText("partOfSpeech", 100)
                val values = meaning.requiredList("definitions")
                if (values.isEmpty()) badProvider()
                values.map { definitionRaw ->
                    if (++totalDefinitions > MAX_DEFINITIONS) tooLarge()
                    val definition = definitionRaw.objectValue()
                    DictionaryDefinition(part, definition.requiredText("definition", 4096), definition.optionalText("example", 4096))
                }
            }
            if (definitions.isEmpty()) badProvider()
            val license = entry["license"]?.let { value ->
                val fields = value.objectValue()
                DictionaryLicense(fields.requiredText("name", 200), fields.optionalText("url", 2048)?.also(::requireHttpsUrl))
            }
            val sources = entry["sourceUrls"]?.let { value ->
                val urls = value as? List<*> ?: badProvider()
                if (urls.size > 16) tooLarge()
                urls.map { (it as? String ?: badProvider()).also(::requireHttpsUrl) }
            }.orEmpty()
            DictionaryEntry(word, phonetic, immutable(definitions), license, immutable(sources))
        }
        return DictionaryResult(query, immutable(entries))
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: DictionaryException) { throw failure }
    catch (_: Exception) { badProvider() }
}

private fun Any?.objectValue(): Map<*, *> = this as? Map<*, *> ?: badProvider()
private fun Map<*, *>.requiredList(key: String): List<*> = this[key] as? List<*> ?: badProvider()
private fun Map<*, *>.requiredText(key: String, limit: Int) = optionalText(key, limit) ?: badProvider()
private fun Map<*, *>.optionalText(key: String, limit: Int): String? {
    val value = this[key] ?: return null
    val text = value as? String ?: badProvider()
    if (text.length > limit) tooLarge()
    return text.takeIf { it.isNotBlank() }
}
private fun requireHttpsUrl(value: String) {
    if (value.length > 2048) tooLarge()
    val uri = URI(value)
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) badProvider()
}
private fun <T> immutable(items: List<T>): List<T> = Collections.unmodifiableList(items.toList())
private fun badProvider(): Nothing = throw DictionaryException(DictionaryFailure.PROVIDER)
private fun tooLarge(): Nothing = throw DictionaryException(DictionaryFailure.TOO_LARGE)

private class BoundedDictionaryJson(private val reader: JsonReader) {
    private var nodes = 0
    fun read(depth: Int = 0): Any? {
        if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException()
        if (depth > 32 || ++nodes > 10000) tooLarge()
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val values = mutableListOf<Any?>()
                while (reader.hasNext()) { if (values.size >= 512) tooLarge(); values += read(depth + 1) }
                reader.endArray(); values
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val values = linkedMapOf<String, Any?>()
                while (reader.hasNext()) {
                    if (values.size >= 128) tooLarge()
                    val name = checkedText(reader.nextName(), 512)
                    if (values.containsKey(name)) badProvider()
                    values[name] = read(depth + 1)
                }
                reader.endObject(); values
            }
            JsonToken.STRING -> checkedText(reader.nextString(), 16384)
            JsonToken.NUMBER -> NumberToken(reader.nextString())
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); null }
            else -> badProvider()
        }
    }
    private fun checkedText(value: String, limit: Int): String {
        if (value.length > limit) tooLarge()
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character == '\u0000' || character.isLowSurrogate()) badProvider()
            if (character.isHighSurrogate() && (index >= value.length || !value[index++].isLowSurrogate())) badProvider()
        }
        return value
    }
    private data class NumberToken(val raw: String)
}

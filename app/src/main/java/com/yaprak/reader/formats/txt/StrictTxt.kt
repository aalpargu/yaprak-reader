package com.yaprak.reader.formats.txt

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val MAX_TXT_BYTES = 16 * 1024 * 1024
internal const val MAX_TXT_CHARACTERS = 4 * 1024 * 1024
internal const val MAX_TXT_PARTS = 64
internal const val TXT_PART_CHARACTERS = 64 * 1024

internal enum class TxtFailure { INVALID, LIMIT }
internal class TxtException(val reason: TxtFailure) : Exception(reason.name)

internal data class TxtPart(val index: Int, val text: String) {
    val href: String get() = "Text/part" + (index + 1).toString().padStart(4, '0') + ".xhtml"
    val title: String get() = "Metin " + (index + 1)
    val sha256: String get() = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Original bytes are authoritative. Never guess a legacy codepage, replace malformed text or truncate. */
internal object StrictTxt {
    suspend fun read(file: File): List<TxtPart> {
        currentCoroutineContext().ensureActive()
        if (file.length() > MAX_TXT_BYTES) throw TxtException(TxtFailure.LIMIT)
        val bytes = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                if (count > MAX_TXT_BYTES - bytes.size()) throw TxtException(TxtFailure.LIMIT)
                bytes.write(buffer, 0, count)
            }
        }
        val caller = currentCoroutineContext()
        return decode(bytes.toByteArray()) { caller.ensureActive() }
    }

    internal fun decode(bytes: ByteArray, checkActive: () -> Unit = {}): List<TxtPart> {
        checkActive()
        if (bytes.size > MAX_TXT_BYTES) throw TxtException(TxtFailure.LIMIT)
        if (bytes.isEmpty()) throw TxtException(TxtFailure.INVALID)
        val first = bytes[0].toInt() and 255
        val second = bytes.getOrNull(1)?.toInt()?.and(255)
        val third = bytes.getOrNull(2)?.toInt()?.and(255)
        val (charset, offset) = when {
            first == 0xef && second == 0xbb && third == 0xbf -> Charsets.UTF_8 to 3
            first == 0xff && second == 0xfe -> Charsets.UTF_16LE to 2
            first == 0xfe && second == 0xff -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        val decoded = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: CharacterCodingException) { throw TxtException(TxtFailure.INVALID) }
        checkActive()
        if (decoded.length > MAX_TXT_CHARACTERS) throw TxtException(TxtFailure.LIMIT)
        // Canonicalize line endings only; spaces, tabs, punctuation and every character remain literal.
        val text = decoded.replace("\r\n", "\n").replace('\r', '\n')
        var at = 0
        while (at < text.length) {
            if (at % 4096 == 0) checkActive()
            val codepoint = text.codePointAt(at)
            if (!txtXmlCodepoint(codepoint)) throw TxtException(TxtFailure.INVALID)
            at += Character.charCount(codepoint)
        }
        if (text.isBlank()) throw TxtException(TxtFailure.INVALID)
        val parts = ArrayList<TxtPart>()
        var start = 0
        while (start < text.length) {
            checkActive()
            if (parts.size == MAX_TXT_PARTS) throw TxtException(TxtFailure.LIMIT)
            var end = minOf(text.length, start + TXT_PART_CHARACTERS)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            parts += TxtPart(parts.size, text.substring(start, end))
            start = end
        }
        checkActive()
        return parts.toList()
    }
}

internal fun txtXmlCodepoint(value: Int): Boolean = (value == 9 || value == 10 || value in 0x20..0x10ffff) &&
    value !in 0x7f..0x9f && value !in 0xd800..0xdfff && (value and 0xffff) !in 0xfffe..0xffff

internal fun txtXml(value: String): String = buildString {
    for (character in value) append(when (character) {
        '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"; else -> character.toString()
    })
}

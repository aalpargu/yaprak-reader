package com.yaprak.reader.formats.fb2

import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser

internal const val MAX_FB2_BYTES = 16 * 1024 * 1024
internal const val FB2_NAMESPACE = "http://www.gribuser.ru/xml/fictionbook/2.0"
internal const val FB2_LINK_NAMESPACE = "http://www.w3.org/1999/xlink"
internal enum class Fb2Failure { INVALID, LIMIT, UNSUPPORTED }
internal class Fb2Exception(val reason: Fb2Failure) : Exception(reason.name)
internal sealed interface Fb2Content
internal data class Fb2Text(val value: String) : Fb2Content
internal data class Fb2Element(val name: String, val attributes: Map<String, String>, val children: List<Fb2Content>) : Fb2Content {
    fun attribute(name: String): String? = attributes[name]
    val href get() = attributes["{$FB2_LINK_NAMESPACE}href"]
    val elements get() = children.filterIsInstance<Fb2Element>()
    fun text(): String = children.joinToString("") { if (it is Fb2Text) it.value else (it as Fb2Element).text() }
}
internal data class Fb2Binary(val id: String, val mimeType: String, val bytes: ByteArray)
internal data class Fb2Document(val title: String?, val authors: String?, val language: String,
    val description: Fb2Element, val bodies: List<Fb2Element>, val binaries: List<Fb2Binary>, val coverId: String?)

/** Original bounded XML, never an original EPUB or evidence of observed generated XHTML. */
internal object StrictFb2 {
    private val bodyElements = setOf("body", "section", "title", "p", "empty-line", "epigraph", "cite", "poem",
        "stanza", "v", "text-author", "date", "subtitle", "image", "table", "tr", "th", "td", "strong",
        "emphasis", "strikethrough", "sub", "sup", "code", "style", "a", "annotation")

    suspend fun read(file: File): Fb2Document {
        val caller = currentCoroutineContext(); caller.ensureActive()
        if (file.length() > MAX_FB2_BYTES) fail(Fb2Failure.LIMIT)
        val output = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                caller.ensureActive(); val count = input.read(buffer)
                if (count < 0) break
                if (count > MAX_FB2_BYTES - output.size()) fail(Fb2Failure.LIMIT)
                output.write(buffer, 0, count)
            }
        }
        val original = output.toByteArray()
        val xml = if (hasFb2ZipSignature(original)) Fb2Zip.xml(original) { caller.ensureActive() } else original
        return decode(xml) { caller.ensureActive() }
    }

    suspend fun decode(bytes: ByteArray, checkActive: () -> Unit = {}): Fb2Document {
        val caller = currentCoroutineContext()
        caller.ensureActive(); checkActive()
        if (bytes.size > MAX_FB2_BYTES) fail(Fb2Failure.LIMIT)
        if (bytes.isEmpty()) fail()
        try {
            val xml = decodeXml(bytes)
            if (xml.length > 8 * 1024 * 1024) fail(Fb2Failure.LIMIT)
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setInput(StringReader(xml))
            }
            data class Frame(val name: String, val attributes: Map<String, String>, val children: MutableList<Fb2Content> = ArrayList())
            val stack = ArrayDeque<Frame>(); var root: Fb2Element? = null; var nodes = 0; var characters = 0L
            while (true) {
                caller.ensureActive(); checkActive()
                when (parser.nextToken()) {
                    XmlPullParser.DOCDECL -> fail()
                    XmlPullParser.START_TAG -> {
                        if (parser.namespace != FB2_NAMESPACE) fail(Fb2Failure.UNSUPPORTED)
                        if (++nodes > 200_000 || stack.size >= 48 || parser.attributeCount > 32) fail(Fb2Failure.LIMIT)
                        val attributes = LinkedHashMap<String, String>()
                        for (i in 0 until parser.attributeCount) {
                            val value = parser.getAttributeValue(i).also(::validateXmlText)
                            if (value.length > 8192) fail(Fb2Failure.LIMIT)
                            val ns = parser.getAttributeNamespace(i).orEmpty()
                            if (ns !in setOf("", FB2_LINK_NAMESPACE, "http://www.w3.org/XML/1998/namespace")) fail(Fb2Failure.UNSUPPORTED)
                            val key = if (ns.isEmpty()) parser.getAttributeName(i) else "{$ns}${parser.getAttributeName(i)}"
                            if (attributes.put(key, value) != null) fail()
                        }
                        if (stack.isEmpty() && (root != null || parser.name != "FictionBook")) fail()
                        stack.addLast(Frame(parser.name, attributes.toMap()))
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE -> {
                        val value = (parser.text ?: fail()).also(::validateXmlText)
                        characters += value.length
                        if (characters > 8L * 1024 * 1024 || ++nodes > 200_000) fail(Fb2Failure.LIMIT)
                        if (stack.isEmpty()) { if (value.isNotBlank()) fail() }
                        else stack.last().children.add(Fb2Text(value))
                    }
                    XmlPullParser.END_TAG -> {
                        val frame = stack.removeLastOrNull() ?: fail()
                        if (frame.name != parser.name) fail()
                        val element = Fb2Element(frame.name, frame.attributes, frame.children.toList())
                        if (stack.isEmpty()) { if (root != null) fail(); root = element }
                        else stack.last().children.add(element)
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            if (stack.isNotEmpty()) fail()
            val result = document(root ?: fail()) { caller.ensureActive(); checkActive() }
            for (binary in result.binaries) {
                checkActive(); currentCoroutineContext().ensureActive()
                // Reuse the existing full PNG/JPEG/WebP container and actual bounded decoder checks.
                val image = try { com.yaprak.reader.formats.cbz.CbzImages.validate(binary.bytes,
                    when (binary.mimeType) { "image/png" -> "png"; "image/jpeg" -> "jpeg"; else -> "webp" }) }
                catch (failure: com.yaprak.reader.formats.cbz.CbzException) {
                    fail(when (failure.reason) {
                        com.yaprak.reader.formats.cbz.CbzFailure.LIMIT -> Fb2Failure.LIMIT
                        com.yaprak.reader.formats.cbz.CbzFailure.UNSUPPORTED -> Fb2Failure.UNSUPPORTED
                        else -> Fb2Failure.INVALID
                    })
                }
                // Readium receives original images; unlike the CBZ canvas it cannot use our sampled bitmap.
                if (image.width.toLong() * image.height > 4_000_000L) fail(Fb2Failure.LIMIT)
            }
            checkActive(); currentCoroutineContext().ensureActive()
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Fb2Exception) { throw failure }
        catch (_: Exception) { fail() }
    }

    private fun document(root: Fb2Element, checkActive: () -> Unit): Fb2Document {
        if (root.elements.any { it.name !in setOf("stylesheet", "description", "body", "binary") } ||
            root.children.filterIsInstance<Fb2Text>().any { it.value.isNotBlank() }) fail(Fb2Failure.UNSUPPORTED)
        val description = root.elements.singleOrNull { it.name == "description" } ?: fail()
        val info = description.elements.singleOrNull { it.name == "title-info" } ?: fail()
        val bodies = root.elements.filter { it.name == "body" }
        if (bodies.size !in 1..8 || bodies.any { body -> body.elements.none { it.name == "section" } }) fail()
        val ids = HashSet<String>()
        fun visit(node: Fb2Element, action: (Fb2Element) -> Unit) {
            checkActive(); action(node); node.elements.forEach { visit(it, action) }
        }
        visit(root) { node -> node.attribute("id")?.let { id ->
            if (id.length !in 1..256 || id.any { it.isWhitespace() || it.isISOControl() } || !ids.add(id)) fail()
        } }
        var bodyCharacters = 0L
        var readable = false
        bodies.forEach { body -> visit(body) { node ->
            if (node.name !in bodyElements) fail(Fb2Failure.UNSUPPORTED)
            if (node.name in setOf("image", "empty-line") && node.children.any { it is Fb2Element || it is Fb2Text && it.value.isNotBlank() }) fail()
            bodyCharacters += node.children.filterIsInstance<Fb2Text>().sumOf { it.value.length.toLong() }
            if (node.name == "image" || node.children.filterIsInstance<Fb2Text>().any { it.value.isNotBlank() }) readable = true
            if (bodyCharacters > 4L * 1024 * 1024) fail(Fb2Failure.LIMIT)
            if (node.name == "a") node.href?.let { href ->
                if (href.startsWith("#")) { if (fb2InternalId(href) !in ids) fail() }
                else if (!href.startsWith("https://", true) && !href.startsWith("http://", true)) fail(Fb2Failure.UNSUPPORTED)
            }
        } }
        if (!readable) fail()
        var binaryBytes = 0L
        val binaries = root.elements.filter { it.name == "binary" }.map { node ->
            checkActive(); if (node.elements.isNotEmpty()) fail()
            val id = node.attribute("id") ?: fail()
            val mime = node.attribute("content-type")?.lowercase(java.util.Locale.ROOT) ?: fail()
            if (mime !in setOf("image/png", "image/jpeg", "image/webp")) fail(Fb2Failure.UNSUPPORTED)
            val raw = node.text()
            if (raw.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it !in "+/= \t\r\n" }) fail()
            val encoded = raw.filterNot { it in " \t\r\n" }
            if (encoded.isEmpty() || encoded.length % 4 != 0) fail()
            if (encoded.length > 6 * 1024 * 1024) fail(Fb2Failure.LIMIT)
            val decoded = Base64.getDecoder().decode(encoded)
            binaryBytes += decoded.size
            if (decoded.isEmpty() || decoded.size > 4 * 1024 * 1024 || binaryBytes > 8 * 1024 * 1024) fail(Fb2Failure.LIMIT)
            Fb2Binary(id, mime, decoded)
        }
        val imageIds = binaries.map { it.id }.toSet()
        visit(root) { node -> if (node.name == "image") {
            val href = node.href ?: fail()
            if (!href.startsWith("#") || fb2InternalId(href) !in imageIds) fail()
        } }
        fun normalized(node: Fb2Element) = node.text().replace(Regex("[\\s\\p{Z}]+"), " ").trim().also {
            if (it.length > 8192) fail(Fb2Failure.LIMIT)
        }
        val title = info.elements.singleOrNull { it.name == "book-title" }?.let(::normalized)?.takeIf(String::isNotEmpty)
        val authors = info.elements.filter { it.name == "author" }.also { if (it.size > 32) fail(Fb2Failure.LIMIT) }.mapNotNull { author ->
            val full = author.elements.filter { it.name in setOf("first-name", "middle-name", "last-name") }.joinToString(" ", transform = ::normalized).trim()
            full.takeIf(String::isNotEmpty) ?: author.elements.firstOrNull { it.name == "nickname" }?.let(::normalized)?.takeIf(String::isNotEmpty)
        }.joinToString(", ").also { if (it.length > 8192) fail(Fb2Failure.LIMIT) }.takeIf(String::isNotEmpty)
        val language = info.elements.singleOrNull { it.name == "lang" }?.let(::normalized)?.takeIf {
            it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
        } ?: "und"
        val cover = info.elements.singleOrNull { it.name == "coverpage" }?.elements?.firstOrNull { it.name == "image" }?.href?.let(::fb2InternalId)
        return Fb2Document(title, authors, language, description, bodies, binaries, cover)
    }

    private fun decodeXml(bytes: ByteArray): String {
        fun byte(i: Int) = bytes.getOrNull(i)?.toInt()?.and(255)
        if ((byte(0) == 255 && byte(1) == 254 && byte(2) == 0 && byte(3) == 0) ||
            (byte(0) == 0 && byte(1) == 0 && byte(2) == 254 && byte(3) == 255)) fail(Fb2Failure.UNSUPPORTED)
        val (inferred, offset) = when {
            byte(0) == 239 && byte(1) == 187 && byte(2) == 191 -> Charsets.UTF_8 to 3
            byte(0) == 255 && byte(1) == 254 -> Charsets.UTF_16LE to 2
            byte(0) == 254 && byte(1) == 255 -> Charsets.UTF_16BE to 2
            byte(0) == 60 && byte(1) == 0 -> Charsets.UTF_16LE to 0
            byte(0) == 0 && byte(1) == 60 -> Charsets.UTF_16BE to 0
            else -> null to 0
        }
        val prefix = String(bytes, offset, minOf(512, bytes.size - offset), inferred ?: Charsets.ISO_8859_1)
        val declaration = Regex("^<\\?xml\\s+[^?]*\\?>").find(prefix)?.value
        val encoding = declaration?.let { Regex("encoding\\s*=\\s*['\"]([^'\"]+)['\"]").find(it)?.groupValues?.get(1) }
        val declared = encoding?.let { when (it.uppercase(java.util.Locale.ROOT)) {
            "UTF-8" -> Charsets.UTF_8; "UTF-16" -> inferred?.takeIf { c -> c in setOf(Charsets.UTF_16LE, Charsets.UTF_16BE) } ?: fail()
            "UTF-16LE" -> Charsets.UTF_16LE; "UTF-16BE" -> Charsets.UTF_16BE
            "WINDOWS-1251" -> Charset.forName("windows-1251")
            else -> fail(Fb2Failure.UNSUPPORTED)
        } }
        if (inferred != null && declared != null && inferred != declared) fail()
        val charset = inferred ?: declared ?: Charsets.UTF_8
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    }
    private fun validateXmlText(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                if (index >= value.length || !value[index++].isLowSurrogate()) fail()
            } else if (char.isLowSurrogate() || !(char == '\t' || char == '\n' || char == '\r' ||
                    char.code in 0x20..0xD7FF || char.code in 0xE000..0xFFFD)) fail()
        }
    }
    private fun fail(reason: Fb2Failure = Fb2Failure.INVALID): Nothing = throw Fb2Exception(reason)
}

/** Strict UTF-8 fragment decoding preserves literal plus and never replaces malformed encoded bytes. */
internal fun fb2InternalId(href: String): String? = runCatching {
    if (!href.startsWith("#")) return@runCatching null
    val raw = java.net.URI(href).rawFragment?.takeIf { it.isNotEmpty() } ?: return@runCatching null
    val bytes = ByteArrayOutputStream(); var index = 0
    while (index < raw.length) {
        if (raw[index] == '%') {
            if (index + 2 >= raw.length) return@runCatching null
            val high = raw[index + 1].digitToIntOrNull(16) ?: return@runCatching null
            val low = raw[index + 2].digitToIntOrNull(16) ?: return@runCatching null
            bytes.write(high * 16 + low); index += 3
        } else {
            val point = raw.codePointAt(index)
            bytes.write(String(Character.toChars(point)).toByteArray(Charsets.UTF_8))
            index += Character.charCount(point)
        }
    }
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
}.getOrNull()

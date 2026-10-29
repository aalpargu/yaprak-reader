package com.yaprak.reader.formats.docx

import android.util.Xml
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser

internal data class DocxName(val namespace: String, val local: String)
internal sealed interface DocxXmlNode
internal data class DocxXmlText(val value: String) : DocxXmlNode
internal data class DocxXmlElement(val name: DocxName, val attributes: Map<DocxName, String>,
    val children: List<DocxXmlNode>) : DocxXmlNode {
    val elements get() = children.filterIsInstance<DocxXmlElement>()
    fun attribute(local: String, namespace: String = ""): String? = attributes[DocxName(namespace, local)]
    fun named(local: String, namespace: String = name.namespace): List<DocxXmlElement> =
        elements.filter { it.name == DocxName(namespace, local) }
    fun single(local: String, namespace: String = name.namespace): DocxXmlElement? =
        named(local, namespace).also { if (it.size > 1) docxFail() }.singleOrNull()
    fun text(): String = buildString {
        fun appendNode(node: DocxXmlNode) {
            when (node) {
                is DocxXmlText -> append(node.value)
                is DocxXmlElement -> node.children.forEach(::appendNode)
            }
        }
        children.forEach(::appendNode)
    }
    fun requireElementContent() {
        if (children.filterIsInstance<DocxXmlText>().any { !it.value.isDocxXmlWhitespace() }) docxFail()
    }
}

/** Namespace-preserving XML 1.0 tree. No DTD, external entities, or replacement decoding. */
internal object DocxXml {
    suspend fun read(bytes: ByteArray): DocxXmlElement {
        val caller = currentCoroutineContext(); caller.ensureActive()
        if (bytes.size > MAX_DOCX_ENTRY_BYTES) docxFail(DocxFailure.LIMIT)
        if (bytes.isEmpty()) docxFail()
        try {
            val xml = decode(bytes)
            validateDocxXmlText(xml)
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setInput(StringReader(xml))
            }
            data class Frame(val name: DocxName, val attributes: Map<DocxName, String>,
                val children: MutableList<DocxXmlNode> = ArrayList())
            val stack = ArrayDeque<Frame>(); var root: DocxXmlElement? = null; var nodes = 0
            fun name(namespace: String?, local: String): DocxName {
                if (local.length > 256 || namespace.orEmpty().length > 1024) docxFail(DocxFailure.LIMIT)
                return DocxName(namespace.orEmpty(), local)
            }
            while (true) {
                caller.ensureActive()
                val token = parser.nextToken()
                if (++nodes > 200_000) docxFail(DocxFailure.LIMIT)
                when (token) {
                    XmlPullParser.DOCDECL -> docxFail()
                    XmlPullParser.START_TAG -> {
                        if (stack.size >= 64 || parser.attributeCount > 64 ||
                            parser.getNamespaceCount(parser.depth) - parser.getNamespaceCount(parser.depth - 1) > 64)
                            docxFail(DocxFailure.LIMIT)
                        if (stack.isEmpty() && root != null) docxFail()
                        val attributes = LinkedHashMap<DocxName, String>()
                        for (i in 0 until parser.attributeCount) {
                            val value = parser.getAttributeValue(i)
                            if (value.length > 8192) docxFail(DocxFailure.LIMIT)
                            validateDocxXmlText(value)
                            if (attributes.put(name(parser.getAttributeNamespace(i), parser.getAttributeName(i)), value) != null)
                                docxFail()
                        }
                        stack.addLast(Frame(name(parser.namespace, parser.name), attributes.toMap()))
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE -> {
                        val value = parser.text ?: docxFail()
                        validateDocxXmlText(value)
                        if (stack.isEmpty()) { if (!value.isDocxXmlWhitespace()) docxFail() }
                        else stack.last().children.add(DocxXmlText(value))
                    }
                    XmlPullParser.END_TAG -> {
                        val frame = stack.removeLastOrNull() ?: docxFail()
                        if (frame.name != name(parser.namespace, parser.name)) docxFail()
                        val element = DocxXmlElement(frame.name, frame.attributes, frame.children.toList())
                        if (stack.isEmpty()) { if (root != null) docxFail(); root = element }
                        else stack.last().children.add(element)
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            if (stack.isNotEmpty()) docxFail()
            caller.ensureActive()
            return root ?: docxFail()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DocxException) { throw failure }
        catch (_: Exception) { docxFail() }
    }

    private fun decode(bytes: ByteArray): String {
        fun byte(i: Int) = bytes.getOrNull(i)?.toInt()?.and(255)
        if ((byte(0) == 255 && byte(1) == 254 && byte(2) == 0 && byte(3) == 0) ||
            (byte(0) == 0 && byte(1) == 0 && byte(2) == 254 && byte(3) == 255)) docxFail(DocxFailure.UNSUPPORTED)
        val (charset, offset) = when {
            byte(0) == 239 && byte(1) == 187 && byte(2) == 191 -> Charsets.UTF_8 to 3
            byte(0) == 255 && byte(1) == 254 -> Charsets.UTF_16LE to 2
            byte(0) == 254 && byte(1) == 255 -> Charsets.UTF_16BE to 2
            byte(0) == 60 && byte(1) == 0 -> Charsets.UTF_16LE to 0
            byte(0) == 0 && byte(1) == 60 -> Charsets.UTF_16BE to 0
            else -> Charsets.UTF_8 to 0
        }
        val xml = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        val declaration = Regex("^<\\?xml\\s+[^?]*\\?>").find(xml)?.value
        val encoding = declaration?.let { Regex("encoding\\s*=\\s*['\"]([^'\"]+)['\"]").find(it)?.groupValues?.get(1) }
        if (encoding != null) {
            val expected = when (encoding.uppercase(Locale.ROOT)) {
                "UTF-8" -> Charsets.UTF_8
                "UTF-16LE" -> Charsets.UTF_16LE
                "UTF-16BE" -> Charsets.UTF_16BE
                "UTF-16" -> charset.takeIf { it == Charsets.UTF_16LE || it == Charsets.UTF_16BE } ?: docxFail()
                else -> docxFail(DocxFailure.UNSUPPORTED)
            }
            if (charset != expected) docxFail()
        }
        val version = declaration?.let { Regex("version\\s*=\\s*['\"]([^'\"]+)['\"]").find(it)?.groupValues?.get(1) }
        if (version != null && version != "1.0") docxFail(DocxFailure.UNSUPPORTED)
        return xml
    }
}

internal fun String.isDocxXmlWhitespace() = all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
internal fun validateDocxXmlText(value: String) {
    var cursor = 0
    while (cursor < value.length) {
        val first = value[cursor++]
        val point = if (first.isHighSurrogate()) {
            val second = value.getOrNull(cursor++)?.takeIf { it.isLowSurrogate() } ?: docxFail()
            Character.toCodePoint(first, second)
        } else { if (first.isLowSurrogate()) docxFail(); first.code }
        if (!(point == 9 || point == 10 || point == 13 || point in 0x20..0x10ffff) ||
            point in 0xfdd0..0xfdef || point and 0xffff in setOf(0xfffe, 0xffff)) docxFail()
    }
}

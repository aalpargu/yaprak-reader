package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.search.MAX_INDEX_RESOURCE_BYTES
import com.yaprak.reader.domain.search.TextExtractionException
import com.yaprak.reader.domain.search.TextExtractionFailure
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Locale
import java.util.Collections
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.CancellationException
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.XMLReader
import org.xml.sax.ext.DefaultHandler2

/** Original XML-decoded body characters, with newlines only as block/br extraction delimiters.
 * These offsets are never DOM/navigation anchors or reading progress. Chapter extraction may
 * bind visible unique IDs to this same text, only with a fresh original TOC/source proof.
 */
internal object BoundedXhtmlTextParser {
    internal const val MAX_XML_EVENTS = 200_000
    internal const val MAX_XML_DEPTH = 256
    private const val XHTML = "http://www.w3.org/1999/xhtml"
    private val excluded = setOf("head", "script", "style", "template", "noscript")
    private val blocks = setOf("address", "article", "aside", "blockquote", "br", "caption", "dd", "div", "dl",
        "dt", "figcaption", "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li",
        "main", "nav", "ol", "p", "pre", "section", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul")

    fun parse(bytes: ByteArray, checkActive: () -> Unit = {}): String = parseAnchored(bytes, emptySet(), checkActive).text

    internal class AnchoredText(val text: String, anchors: Map<String, Int?>) {
        val anchors: Map<String, Int?> = Collections.unmodifiableMap(LinkedHashMap(anchors))
    }

    /** Missing, hidden and duplicate requested IDs never acquire a guessed position. */
    fun parseAnchored(bytes: ByteArray, wantedIds: Set<String>, checkActive: () -> Unit = {}): AnchoredText {
        if (wantedIds.size > 5000 || wantedIds.any { it.isEmpty() || it.length > 2048 }) fail(TextExtractionFailure.LIMIT)
        if (bytes.size > MAX_INDEX_RESOURCE_BYTES) fail(TextExtractionFailure.LIMIT)
        if (bytes.isEmpty()) fail(TextExtractionFailure.INVALID)
        checkActive()
        val handler = BodyTextHandler(checkActive, wantedIds.toSet())
        try {
            val sourceEncoding = preflight(bytes, checkActive)
            val reader = configuredReader(SAXParserFactory.newInstance(), handler)
            ByteArrayInputStream(bytes).use { input ->
                reader.parse(InputSource(input).apply { encoding = sourceEncoding })
            }
            handler.rethrowRecordedFailure()
            checkActive()
            return handler.result()
        } catch (failure: Exception) {
            // SAX implementations may wrap exceptions thrown by a callback. Only a failure
            // recorded by our own handler may recover its typed/cancellation semantics.
            handler.rethrowRecordedFailure()
            if (failure is CancellationException || failure is TextExtractionException) throw failure
            // No parser diagnostic or original source content is printed or persisted.
            fail(TextExtractionFailure.INVALID)
        }
    }

    /** Every guard is mandatory. Android Expat does not implement Xerces/JAXP security flags. */
    internal fun configuredReader(factory: SAXParserFactory, handler: DefaultHandler2): XMLReader {
        factory.isNamespaceAware = true
        factory.isValidating = false
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        return factory.newSAXParser().xmlReader.apply {
            contentHandler = handler
            errorHandler = handler
            entityResolver = handler
            setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        }
    }

    /** Reject DTD before native XML_Parse can expand even the remainder of its current input buffer.
     * EPUB XHTML uses UTF-8 or UTF-16. Decoding is strict and only used for this bounded preflight;
     * SAX still consumes the unchanged bytes and performs normal XML newline/entity decoding.
     */
    private fun preflight(bytes: ByteArray, checkActive: () -> Unit): String {
        fun byte(index: Int) = bytes.getOrNull(index)?.toInt()?.and(255)
        val encoding = when {
            byte(0) == 0 && byte(1) == 0 && (byte(2) == 254 || byte(2) == 0) -> fail(TextExtractionFailure.UNSUPPORTED)
            byte(0) == 255 && byte(1) == 254 && byte(2) == 0 && byte(3) == 0 -> fail(TextExtractionFailure.UNSUPPORTED)
            byte(0) == 254 && byte(1) == 255 -> "UTF-16"
            byte(0) == 255 && byte(1) == 254 -> "UTF-16"
            byte(0) == 0 && byte(1) == 60 && byte(2) == 0 && byte(3) == 63 -> "UTF-16BE"
            byte(0) == 60 && byte(1) == 0 && byte(2) == 63 && byte(3) == 0 -> "UTF-16LE"
            else -> "UTF-8"
        }
        val source = java.nio.charset.Charset.forName(encoding).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        checkActive()
        if (source.startsWith("<?xml") && source.getOrNull(5)?.isWhitespace() == true) {
            val end = source.indexOf("?>", 5)
            if (end < 0) fail(TextExtractionFailure.INVALID)
            val declared = Regex("""(?:^|\s)encoding\s*=\s*(['"])([^'"]+)\1""")
                .find(source.substring(5, end))?.groupValues?.get(2)?.uppercase(Locale.ROOT)
            if (declared != null) {
                val supported = when (encoding) {
                    "UTF-8" -> setOf("UTF-8", "UTF8")
                    "UTF-16" -> setOf("UTF-16", if (byte(0) == 254) "UTF-16BE" else "UTF-16LE")
                    else -> setOf("UTF-16", encoding)
                }
                if (declared !in supported) fail(TextExtractionFailure.UNSUPPORTED)
            }
        }
        var offset = 0
        while (true) {
            checkActive()
            val start = source.indexOf('<', offset)
            if (start < 0) return encoding
            val endToken = when {
                source.startsWith("<!--", start) -> "-->"
                source.startsWith("<![CDATA[", start) -> "]]>"
                source.startsWith("<?", start) -> "?>"
                source.startsWith("<!DOCTYPE", start) -> fail(TextExtractionFailure.UNSUPPORTED)
                else -> null
            }
            if (endToken == null) offset = start + 1 else {
                val end = source.indexOf(endToken, start + 2)
                if (end < 0) fail(TextExtractionFailure.INVALID)
                offset = end + endToken.length
            }
        }
    }

    private class BodyTextHandler(private val checkActive: () -> Unit, private val wantedIds: Set<String>) : DefaultHandler2() {
        private data class Frame(val skipped: Boolean, val boundary: Boolean, val body: Boolean)
        private val stack = ArrayDeque<Frame>()
        private val text = StringBuilder()
        private val anchors = linkedMapOf<String, Int?>()
        private var events = 0
        private var bodies = 0
        private var inBody = false
        private var rootSeen = false
        private var primaryFailure: Exception? = null

        private fun event() {
            rethrowRecordedFailure()
            try { checkActive() } catch (cancelled: CancellationException) {
                primaryFailure = cancelled
                throw cancelled
            }
            if (++events > MAX_XML_EVENTS) fail(TextExtractionFailure.LIMIT)
        }

        fun rethrowRecordedFailure() { primaryFailure?.let { throw it } }

        private fun fail(reason: TextExtractionFailure): Nothing {
            val failure = primaryFailure ?: TextExtractionException(reason).also { primaryFailure = it }
            throw failure
        }

        override fun startElement(uri: String, localName: String, qName: String, attributes: org.xml.sax.Attributes) {
            event()
            if (stack.size == MAX_XML_DEPTH) fail(TextExtractionFailure.LIMIT)
            if (stack.isEmpty()) {
                if (rootSeen || uri != XHTML || localName != "html") fail(TextExtractionFailure.INVALID)
                rootSeen = true
            }
            val body = uri == XHTML && localName == "body"
            if (body) {
                if (stack.size != 1 || ++bodies != 1) fail(TextExtractionFailure.INVALID)
                inBody = true
            }
            val name = localName.lowercase(Locale.ROOT)
            val skipped = stack.peekLast()?.skipped == true ||
                (inBody && (uri != XHTML || name in excluded || attributes.getIndex("hidden") >= 0))
            val boundary = inBody && !skipped && name in blocks
            if (boundary) delimiter()
            if (wantedIds.isNotEmpty()) {
                val ids = setOfNotNull(attributes.getValue("", "id"),
                    attributes.getValue("http://www.w3.org/XML/1998/namespace", "id"))
                for (id in ids) if (id in wantedIds) {
                    anchors[id] = if (anchors.containsKey(id) || !inBody || skipped) null else text.length
                }
            }
            stack.addLast(Frame(skipped, boundary, body))
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            event()
            val frame = stack.pollLast() ?: fail(TextExtractionFailure.INVALID)
            if (frame.boundary) delimiter()
            if (frame.body) inBody = false
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            event()
            if (inBody && stack.peekLast()?.skipped == false) text.append(chars, start, length)
        }

        override fun ignorableWhitespace(chars: CharArray, start: Int, length: Int) = characters(chars, start, length)
        override fun processingInstruction(target: String, data: String) { event() }
        override fun comment(chars: CharArray, start: Int, length: Int) { event() }
        override fun startCDATA() { event() }
        override fun endCDATA() { event() }
        override fun skippedEntity(name: String) { fail(TextExtractionFailure.INVALID) }
        override fun startDTD(name: String, publicId: String?, systemId: String?) { fail(TextExtractionFailure.UNSUPPORTED) }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = fail(TextExtractionFailure.INVALID)
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource =
            fail(TextExtractionFailure.INVALID)
        override fun warning(error: SAXParseException) { /* No source-bearing parser output. */ }
        override fun error(error: SAXParseException) { fail(TextExtractionFailure.INVALID) }
        override fun fatalError(error: SAXParseException) { fail(TextExtractionFailure.INVALID) }

        private fun delimiter() {
            if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
        }

        fun result(): AnchoredText {
            if (!rootSeen || bodies != 1 || stack.isNotEmpty() || inBody) fail(TextExtractionFailure.INVALID)
            return AnchoredText(text.toString(), anchors)
        }
    }

    private fun fail(reason: TextExtractionFailure): Nothing = throw TextExtractionException(reason)
}

package com.yaprak.reader.formats.epub

import java.io.ByteArrayInputStream
import java.util.ArrayDeque
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.CancellationException
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

/** Original XML nodes built without Android's recursive DOM parser or unsupported JAXP flags. */
internal object BoundedSourceXhtmlDocument {
    internal const val MAX_DEPTH = 4096

    fun parse(bytes: ByteArray, checkActive: () -> Unit = {}): Document {
        checkActive()
        if (bytes.size > SourceTextAnchorVerifier.MAX_RESOURCE_BYTES) throw SourceBodyLimitException()
        val encoding = BoundedXhtmlTextParser.preflight(bytes, checkActive)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().newDocument()
        val handler = DocumentHandler(document, checkActive)
        try {
            val reader = BoundedXhtmlTextParser.configuredReader(SAXParserFactory.newInstance(), handler)
            ByteArrayInputStream(bytes).use { input -> reader.parse(InputSource(input).apply { this.encoding = encoding }) }
            handler.rethrowRecordedFailure()
            checkActive()
            return handler.result()
        } catch (failure: Exception) {
            // Native SAX can wrap callback failures. Only our recorded failures recover their type.
            handler.rethrowRecordedFailure()
            if (failure is CancellationException) throw failure
            throw SAXException("Original XHTML unavailable")
        }
    }

    private class DocumentHandler(private val document: Document, private val checkActive: () -> Unit) : DefaultHandler2() {
        private val parents = ArrayDeque<Node>().apply { addLast(document) }
        private val text = StringBuilder()
        private var cdata = false
        private var nodes = 0
        private var complete = false
        private var primaryFailure: Exception? = null

        fun rethrowRecordedFailure() { primaryFailure?.let { throw it } }

        private fun check() {
            rethrowRecordedFailure()
            try { checkActive() } catch (cancelled: CancellationException) { reject(cancelled) }
        }

        private fun reject(failure: Exception = SAXException("Original XHTML unavailable")): Nothing {
            val original = primaryFailure ?: failure.also { primaryFailure = it }
            throw original
        }

        /** Count the whole document before allocating each retained node, including head content. */
        private fun append(create: () -> Node): Node {
            check()
            if (nodes == SourceTextAnchorVerifier.MAX_XML_NODES) reject(SourceBodyLimitException())
            nodes++
            return create().also { checkNotNull(parents.peekLast()).appendChild(it) }
        }

        private fun flushText(forceCdata: Boolean = false) {
            if (text.isEmpty() && !forceCdata) return
            val value = text.toString()
            text.setLength(0)
            if (parents.size == 1) {
                // DOM excludes XML whitespace outside its root; SAX validates that document grammar.
                if (forceCdata || value.any { it != ' ' && it != '\t' && it != '\r' && it != '\n' }) reject()
                return
            }
            append { if (forceCdata) document.createCDATASection(value) else document.createTextNode(value) }
        }

        override fun startDocument() { check() }

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            check()
            flushText()
            if (parents.size > MAX_DEPTH) reject(SourceBodyLimitException())
            val element = append { document.createElementNS(uri.takeIf { it.isNotEmpty() }, qName.ifEmpty { localName }) }
            parents.addLast(element)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            check()
            flushText()
            if (parents.size <= 1) reject()
            parents.removeLast()
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            check()
            // SAX may split a single original text node at buffer, predefined or numeric entities.
            text.append(chars, start, length)
        }

        override fun ignorableWhitespace(chars: CharArray, start: Int, length: Int) = characters(chars, start, length)

        override fun comment(chars: CharArray, start: Int, length: Int) {
            check()
            flushText()
            append { document.createComment(String(chars, start, length)) }
        }

        override fun processingInstruction(target: String, data: String) {
            check()
            flushText()
            append { document.createProcessingInstruction(target, data) }
        }

        override fun startCDATA() {
            check()
            flushText()
            if (cdata) reject()
            cdata = true
        }

        override fun endCDATA() {
            check()
            if (!cdata) reject()
            flushText(forceCdata = true)
            cdata = false
        }

        override fun endDocument() {
            check()
            flushText()
            if (parents.size != 1 || document.documentElement == null || cdata) reject()
            complete = true
        }

        override fun skippedEntity(name: String) { reject() }
        override fun startDTD(name: String, publicId: String?, systemId: String?) { reject() }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = reject()
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource = reject()
        override fun warning(error: SAXParseException) { /* Never print source-bearing diagnostics. */ }
        override fun error(error: SAXParseException) { reject() }
        override fun fatalError(error: SAXParseException) { reject() }

        fun result(): Document {
            rethrowRecordedFailure()
            if (!complete) reject()
            return document
        }
    }
}

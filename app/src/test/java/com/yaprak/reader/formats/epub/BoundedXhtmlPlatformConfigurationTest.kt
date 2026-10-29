package com.yaprak.reader.formats.epub

import android.app.Application
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xml.sax.Parser
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.XMLReader
import org.xml.sax.ext.DefaultHandler2
import org.xml.sax.helpers.XMLFilterImpl
import org.xml.sax.helpers.XMLReaderAdapter

/** Checks the actual Android factory/reader classes, not the host JDK's Xerces implementation.
 * Native Expat parsing still requires the separate Android device fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
class BoundedXhtmlPlatformConfigurationTest {
    @Test fun `actual Android Expat accepts every mandatory production guard`() {
        val factory = Class.forName("org.apache.harmony.xml.parsers.SAXParserFactoryImpl")
            .getDeclaredConstructor().newInstance() as SAXParserFactory
        val handler = DefaultHandler2()
        val reader = BoundedXhtmlTextParser.configuredReader(factory, handler)
        assertEquals("org.apache.harmony.xml.ExpatReader", reader.javaClass.name)
        assertTrue(reader.getFeature("http://xml.org/sax/features/namespaces"))
        assertFalse(reader.getFeature("http://xml.org/sax/features/validation"))
        assertFalse(reader.getFeature("http://xml.org/sax/features/external-general-entities"))
        assertFalse(reader.getFeature("http://xml.org/sax/features/external-parameter-entities"))
        assertSame(handler, reader.getProperty("http://xml.org/sax/properties/lexical-handler"))
        assertSame(handler, reader.entityResolver)
    }

    @Test fun `a reader without mandatory lexical events is rejected before it can parse`() {
        val underlying = SAXParserFactory.newInstance()
        val factory = object : SAXParserFactory() {
            override fun getFeature(name: String): Boolean = underlying.getFeature(name)
            override fun setFeature(name: String, value: Boolean) = underlying.setFeature(name, value)
            override fun setNamespaceAware(value: Boolean) { underlying.isNamespaceAware = value }
            override fun setValidating(value: Boolean) { underlying.isValidating = value }
            override fun newSAXParser(): SAXParser {
                val parser = underlying.newSAXParser()
                val filter = object : XMLFilterImpl(parser.xmlReader) {
                    override fun setProperty(name: String, value: Any?) {
                        if (name == "http://xml.org/sax/properties/lexical-handler") throw SAXNotRecognizedException(name)
                        super.setProperty(name, value)
                    }
                }
                return object : SAXParser() {
                    override fun getParser(): Parser = XMLReaderAdapter(filter)
                    override fun getXMLReader(): XMLReader = filter
                    override fun isNamespaceAware(): Boolean = parser.isNamespaceAware
                    override fun isValidating(): Boolean = parser.isValidating
                    override fun getProperty(name: String): Any? = filter.getProperty(name)
                    override fun setProperty(name: String, value: Any?) = filter.setProperty(name, value)
                }
            }
        }
        val failure = runCatching { BoundedXhtmlTextParser.configuredReader(factory, DefaultHandler2()) }.exceptionOrNull()
        assertTrue(failure is SAXNotRecognizedException)
    }
}

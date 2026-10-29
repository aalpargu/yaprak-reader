package com.yaprak.reader.fixtures

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Original generated text. Two TOC fragments share the first XHTML resource. */
object TestLargeSourceBooks {
    fun epub(file: File, firstTextLength: Int): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val item = ZipEntry(name)
                if (name == "mimetype") {
                    item.method = ZipEntry.STORED; item.size = bytes.size.toLong(); item.compressedSize = bytes.size.toLong()
                    item.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(item); zip.write(bytes); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent())
            entry("EPUB/package.opf", """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:yaprak-long-source-fixture</dc:identifier>
                <dc:title>Generated long source</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata>
                <manifest><item id="p0" href="chapter0.xhtml" media-type="application/xhtml+xml"/>
                <item id="p1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="p0"/><itemref idref="p1"/></spine></package>""".trimIndent())
            entry("EPUB/chapter0.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>First file</title></head><body>
                <h1 id="first">First topic</h1><p>""" + "a".repeat(firstTextLength) + """</p>
                <h2 id="later">Later topic in the same file</h2><p>LAST FIRST DOCUMENT MARKER</p></body></html>""")
            entry("EPUB/chapter1.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Second file</title></head>
                <body><p>SEPARATE RESOURCE MUST NOT ENTER FIRST FILE SOURCES</p></body></html>""")
            entry("EPUB/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head>
                <body><nav epub:type="toc"><ol><li><a href="chapter0.xhtml#first">First topic</a></li>
                <li><a href="chapter0.xhtml#later">Later topic</a></li><li><a href="chapter1.xhtml">Second file</a></li></ol></nav></body></html>""")
        }
        return file
    }
}


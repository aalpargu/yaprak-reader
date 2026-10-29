package com.yaprak.reader.fixtures

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Only generated original fixture contents. TOC headings deliberately cross file boundaries. */
object TestChapterBooks {
    fun epub(file: File, firstLength: Int = 2500, nested: Boolean = false, navOverride: String? = null): File {
        TestLargeSourceBooks.epub(file, firstLength)
        val output = ByteArrayOutputStream()
        ZipFile(file).use { source -> ZipOutputStream(output).use { zip ->
            source.entries().asSequence().forEach { entry ->
                var bytes = source.getInputStream(entry).use { it.readBytes() }
                if (entry.name == "EPUB/chapter0.xhtml") bytes = (
                    "<html xmlns='http://www.w3.org/1999/xhtml'><body>" +
                    "<h1 id='first'>FIRST</h1><p>" + "a".repeat(firstLength) + "</p>" +
                    "<h2 id='later'>LATER ONLY 🌿</h2><p>EXCLUSIVE LATER CONTENT</p></body></html>").toByteArray()
                if (entry.name == "EPUB/chapter1.xhtml") bytes = (
                    "<html xmlns='http://www.w3.org/1999/xhtml'><body><p>BRIDGE CONTENT</p>" +
                    "<h1 id='last'>LAST ONLY</h1><p>LAST CHAPTER CONTENT</p></body></html>").toByteArray()
                if (entry.name == "EPUB/nav.xhtml") {
                    val navigation = navOverride ?: if (nested)
                        "<li><a href='chapter0.xhtml#first'>Parent</a><ol>" +
                        "<li><a href='chapter0.xhtml#first'>First child</a></li>" +
                        "<li><a href='chapter0.xhtml#later'>Later child</a></li></ol></li>" +
                        "<li><a href='chapter1.xhtml#last'>Last chapter</a></li>"
                    else "<li><a href='chapter0.xhtml#first'>First chapter</a></li>" +
                        "<li><a href='chapter0.xhtml#later'>Later chapter</a></li>" +
                        "<li><a href='chapter1.xhtml#last'>Last chapter</a></li>"
                    bytes = ("<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'>" +
                        "<head><title>Contents</title></head><body><nav epub:type='toc'><ol>" +
                        navigation + "</ol></nav></body></html>").toByteArray()
                }
                val next = ZipEntry(entry.name)
                if (entry.name == "mimetype") {
                    next.method = ZipEntry.STORED; next.size = bytes.size.toLong(); next.compressedSize = bytes.size.toLong()
                    next.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(next); zip.write(bytes); zip.closeEntry()
            }
        } }
        file.writeBytes(output.toByteArray())
        return file
    }
}

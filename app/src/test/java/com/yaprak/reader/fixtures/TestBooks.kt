package com.yaprak.reader.fixtures

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

/** Original tiny test content, generated locally; no downloaded or copyrighted books. */
object TestBooks {
    fun epub(file: File, encrypted: Boolean = false, additionalMetadata: String = ""): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, value: String) {
                val bytes = value.toByteArray()
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent())
            entry("EPUB/package.opf", """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">urn:uuid:yaprak-fixture</dc:identifier>
                    <dc:title>Bir Yaz Sabahı</dc:title><dc:creator>Deniz Yazar</dc:creator>
                    <dc:language>tr</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
                    ${additionalMetadata.trimIndent().replace("\n", "\n                    ")}
                  </metadata>
                  <manifest>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>
            """.trimIndent())
            entry("EPUB/chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Sabah</title></head><body><h1>Sabah</h1><p>Güneş doğdu. Yeni bir gün başladı.</p></body></html>""")
            entry("EPUB/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>İçindekiler</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Sabah</a></li></ol></nav></body></html>""")
            if (encrypted) entry("META-INF/encryption.xml", """
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                    <EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes256-cbc"/>
                    <CipherData><CipherReference URI="EPUB/chapter.xhtml"/></CipherData>
                  </EncryptedData>
                </encryption>
            """.trimIndent())
        }
        return file
    }

}

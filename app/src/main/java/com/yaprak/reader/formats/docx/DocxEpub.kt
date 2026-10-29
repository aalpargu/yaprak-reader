package com.yaprak.reader.formats.docx

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Deterministic private view; original DOCX bytes/hash remain the future library source identity. */
internal object DocxEpub {
    suspend fun write(file: File, contentSha256: String, document: DocxDocument) {
        require(contentSha256.matches(Regex("[0-9a-f]{64}")))
        val caller = currentCoroutineContext(); caller.ensureActive()
        fun markup() = DocxMarkup { caller.ensureActive() }
        val opf = markup().raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">urn:sha256:$contentSha256</dc:identifier><dc:title>")
            .text(document.metadata.title ?: "DOCX").raw("</dc:title><dc:language>").text(document.metadata.language).raw("</dc:language>")
        document.metadata.authors?.let { opf.raw("<dc:creator>").text(it).raw("</dc:creator>") }
        opf.raw("<meta property=\"dcterms:modified\">1980-01-02T00:00:00Z</meta></metadata><manifest><item id=\"body\" href=\"Text/document.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"nav\" href=\"nav.xhtml\" properties=\"nav\" media-type=\"application/xhtml+xml\"/>")
        for ((index, image) in document.images.withIndex()) {
            caller.ensureActive()
            opf.raw("<item").attribute("id", "image"+index).attribute("href", image.href)
                .attribute("media-type", image.mime).raw("/>")
        }
        opf.raw("</manifest><spine><itemref idref=\"body\"/></spine></package>")
        val nav = markup().raw("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>İçindekiler</title></head><body><nav epub:type=\"toc\"><ol>")
        if (document.headings.isEmpty()) nav.raw("<li><a href=\"Text/document.xhtml\">").text(document.metadata.title ?: "DOCX").raw("</a></li>")
        // Flat links retain actual heading order without inventing absent parent headings.
        for (heading in document.headings) {
            caller.ensureActive()
            nav.raw("<li><a").attribute("href", "Text/document.xhtml#${heading.id}").raw(">").text(heading.title).raw("</a></li>")
        }
        nav.raw("</ol></nav></body></html>")
        val html = markup().raw("<html xmlns=\"http://www.w3.org/1999/xhtml\"").attribute("xml:lang", document.metadata.language)
            .also { if (document.noteCount > 0) it.attribute("xmlns:epub", "http://www.idpf.org/2007/ops") }
            .raw("><head><title>").text(document.metadata.title ?: "DOCX")
            .raw("</title><style>body{overflow-wrap:anywhere}p,td,h1,h2,h3,h4,h5,h6{white-space:pre-wrap}table{max-width:100%;border-collapse:collapse}td{padding:.25em;border:1px solid currentColor}</style></head><body>")
            .raw(document.body).raw("</body></html>")
        var total = 0
        // Build/validate every bounded part before opening the output file.
        val parts = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>".toByteArray(),
            "package.opf" to opf.value().toByteArray(), "nav.xhtml" to nav.value().toByteArray(), "Text/document.xhtml" to html.value().toByteArray())
        for (image in document.images) {
            caller.ensureActive()
            if (!image.href.matches(Regex("Images/image[1-9][0-9]{0,2}\\.(png|jpg|webp)")) ||
                image.mime !in setOf("image/png", "image/jpeg", "image/webp") || parts.put(image.href, image.bytes) != null)
                docxFail()
        }
        for (bytes in parts.values) {
            caller.ensureActive()
            if (bytes.size > MAX_DOCX_ENTRY_BYTES || bytes.size > 24 * 1024 * 1024 - total) docxFail(DocxFailure.LIMIT)
            total += bytes.size
        }
        file.outputStream().use { output -> ZipOutputStream(output).use { zip ->
            for ((name, bytes) in parts) {
                caller.ensureActive()
                val entry = ZipEntry(name).apply {
                    time = 315619200000L
                    if (name == "mimetype") {
                        method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    }
                }
                zip.putNextEntry(entry)
                var cursor = 0
                while (cursor < bytes.size) {
                    caller.ensureActive(); val count = minOf(64 * 1024, bytes.size - cursor)
                    zip.write(bytes, cursor, count); cursor += count
                }
                zip.closeEntry()
            }
            zip.finish(); zip.flush(); output.fd.sync()
        } }
        caller.ensureActive()
    }
}

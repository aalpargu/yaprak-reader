package com.yaprak.reader.formats.txt

import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Private viewing artifact only. Stable body structure/addresses do not depend on edited library metadata. */
internal object TxtEpub {
    suspend fun write(file: File, contentSha256: String, parts: List<TxtPart>) {
        require(contentSha256.matches(Regex("[0-9a-f]{64}")))
        require(parts.isNotEmpty() && parts.size <= MAX_TXT_PARTS && parts.map { it.index } == parts.indices.toList())
        val caller = currentCoroutineContext()
        file.outputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(name: String, value: String, stored: Boolean = false) {
                    caller.ensureActive()
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    val entry = ZipEntry(name).apply {
                        // Inside the DOS date range in every timezone: no extended timestamp extra field on mimetype.
                        time = 315619200000L
                        if (stored) {
                            method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                            crc = CRC32().apply { update(bytes) }.value
                        }
                    }
                    zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
                }
                entry("mimetype", "application/epub+zip", stored = true)
                entry("META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
                val items = parts.joinToString("") { "<item id=\"part${it.index}\" href=\"${it.href}\" media-type=\"application/xhtml+xml\"/>" }
                val spine = parts.joinToString("") { "<itemref idref=\"part${it.index}\"/>" }
                val modified = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
                entry("package.opf", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">urn:sha256:$contentSha256</dc:identifier><dc:title>Metin</dc:title><dc:language>und</dc:language><meta property=\"dcterms:modified\">$modified</meta></metadata><manifest>$items<item id=\"nav\" href=\"nav.xhtml\" properties=\"nav\" media-type=\"application/xhtml+xml\"/></manifest><spine>$spine</spine></package>")
                val nav = parts.joinToString("") { "<li><a href=\"${it.href}\">${it.title}</a></li>" }
                entry("nav.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>Metin parçaları</title></head><body><nav epub:type=\"toc\"><ol>$nav</ol></nav></body></html>")
                parts.forEach { part ->
                    val body = txtXml(part.text).replace("\n\n", "</p><p>").replace("\n", "<br/>")
                    entry(part.href, "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>${part.title}</title><style>p { white-space: pre-wrap; overflow-wrap: anywhere; word-wrap: break-word; }</style></head><body><p>$body</p></body></html>")
                }
                zip.finish(); zip.flush(); output.fd.sync()
            }
        }
        caller.ensureActive()
    }
}

internal class TxtDerivedFiles(private val directory: File) {
    fun create(): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create text viewing directory")
        if ((directory.listFiles()?.size ?: 0) >= 64) throw IOException("Text viewing cache limit")
        return File.createTempFile("txt-view-", ".epub", directory)
    }
    fun release(file: File) {
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        file.delete() // Android unlinks closed resources. Failed cleanup is retried by startup recovery.
    }
    /** Startup gate runs before any reading session can own an artifact. Never call during active reading. */
    fun recoverAbandoned() {
        directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("txt-view-[A-Za-z0-9-]+\\.epub")) }?.forEach {
            if (it.canonicalFile.parentFile != directory.canonicalFile || !it.delete())
                throw IOException("Cannot clean interrupted text view")
        }
    }
}

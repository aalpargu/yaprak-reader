package com.yaprak.reader.formats.fb2

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.IdentityHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Deterministic private viewing artifact. Original FB2 bytes remain the library's source of truth. */
internal object Fb2Epub {
    private const val MAX_HTML = 24 * 1024 * 1024
    private const val MAX_PART = 8 * 1024 * 1024
    private data class Target(val href: String, val id: String) { val address get() = "$href#$id" }

    suspend fun write(file: File, contentSha256: String, document: Fb2Document) {
        require(contentSha256.matches(Regex("[0-9a-f]{64}")))
        val caller = currentCoroutineContext(); caller.ensureActive()
        val parts = fb2ViewingParts(document)
        val targets = IdentityHashMap<Fb2Element, Target>()
        val originalIds = HashMap<String, String>()
        val footnotes = HashSet<String>()
        val images = document.binaries.mapIndexed { index, binary -> binary.id to "Images/image${index + 1}.${when (binary.mimeType) {
            "image/png" -> "png"; "image/jpeg" -> "jpg"; else -> "webp"
        }}" }.toMap()
        var nextId = 0
        fun assign(node: Fb2Element, part: Fb2ViewingPart) {
            caller.ensureActive()
            if (node.name == "section" || node.attribute("id") != null) {
                val target = Target(part.href, "n${++nextId}")
                targets[node] = target
                node.attribute("id")?.let { originalIds[it] = target.address }
            }
            if (node.name == "a" && node.attribute("type") == "note") node.href?.let(::fb2InternalId)?.let(footnotes::add)
            node.elements.forEach { assign(it, part) }
        }
        parts.forEach { part ->
            if (!targets.containsKey(part.body) && part.body.attribute("id") != null) {
                val target = Target(part.href, "n${++nextId}"); targets[part.body] = target
                originalIds[part.body.attribute("id")!!] = target.address
            }
            part.nodes.filterIsInstance<Fb2Element>().forEach { assign(it, part) }
        }
        images.forEach { (id, path) -> originalIds[id] = path }
        var totalHtml = 0
        fun bounded(): Xml = Xml { length ->
            if (length > MAX_PART) throw Fb2Exception(Fb2Failure.LIMIT)
            caller.ensureActive()
        }
        fun link(href: String): String {
            if (href.startsWith("#")) return originalIds[fb2InternalId(href)]
                ?: throw Fb2Exception(Fb2Failure.UNSUPPORTED)
            val uri = try { URI(href) } catch (_: Exception) { throw Fb2Exception(Fb2Failure.INVALID) }
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null)
                throw Fb2Exception(Fb2Failure.UNSUPPORTED)
            return href
        }
        fun local(address: String) = if (address.startsWith("Text/")) address.removePrefix("Text/") else "../$address"
        fun render(node: Fb2Content, out: Xml, part: Fb2ViewingPart) {
            caller.ensureActive()
            if (node is Fb2Text) { out.text(node.value); return }
            node as Fb2Element
            val tag = when (node.name) {
                "section" -> "section"; "title" -> "header"; "p", "v", "text-author" -> "p"
                "subtitle" -> "h3"; "emphasis" -> "em"; "strikethrough" -> "s"
                "epigraph", "cite" -> "blockquote"; "poem", "stanza", "empty-line" -> "div"
                "style", "date" -> "span"; "annotation" -> "aside"
                "a" -> if (node.href == null) "span" else "a"
                "strong", "sub", "sup", "code", "table", "tr", "td", "th" -> node.name
                "image" -> "img"
                else -> throw Fb2Exception(Fb2Failure.UNSUPPORTED)
            }
            out.raw("<$tag")
            targets[node]?.let { out.attribute("id", it.id) }
            out.attribute("class", "fb2-${node.name}")
            node.attribute("{http://www.w3.org/XML/1998/namespace}lang")?.let { out.attribute("xml:lang", it) }
            if (node.attribute("id") in footnotes || !part.linear && node.name == "section") {
                out.attribute("epub:type", "footnote"); out.attribute("role", "doc-footnote")
            }
            if (node.name == "a" && node.href != null) {
                val address = link(node.href!!)
                out.attribute("href", if (node.href!!.startsWith("#")) local(address) else address)
                if (node.attribute("type") == "note" && node.href!!.startsWith("#")) out.attribute("epub:type", "noteref")
            }
            if (node.name in setOf("td", "th")) {
                listOf("colspan", "rowspan").forEach { key -> node.attribute(key)?.let { value ->
                    val count = value.toIntOrNull()?.takeIf { it in 1..100 } ?: throw Fb2Exception(Fb2Failure.INVALID)
                    out.attribute(key, count.toString())
                } }
                node.attribute("align")?.takeIf { it in setOf("left", "right", "center", "justify") }?.let { out.attribute("align", it) }
                node.attribute("valign")?.takeIf { it in setOf("top", "middle", "bottom", "baseline") }?.let { out.attribute("valign", it) }
            }
            if (node.name == "image") {
                val image = images[fb2InternalId(node.href.orEmpty())] ?: throw Fb2Exception(Fb2Failure.INVALID)
                out.attribute("src", "../$image"); out.attribute("alt", node.attribute("alt").orEmpty())
                node.attribute("title")?.let { out.attribute("title", it) }
                out.raw("/>"); return
            }
            out.raw(">")
            if (node.name == "empty-line") out.raw("<br/>")
            node.children.forEach { render(it, out, part) }
            out.raw("</$tag>")
        }
        fun label(node: Fb2Element?, fallback: String): String {
            val text = node?.elements?.firstOrNull { it.name == "title" }?.text()
                ?.replace(Regex("[\\s\\p{Z}]+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
            return text.substring(0, text.offsetByCodePoints(0, minOf(180, text.codePointCount(0, text.length))))
        }
        file.outputStream().use { output -> ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray, stored: Boolean = false) {
                caller.ensureActive()
                val item = ZipEntry(name).apply {
                    time = 315619200000L
                    if (stored) { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value }
                }
                zip.putNextEntry(item)
                var offset = 0
                while (offset < bytes.size) { caller.ensureActive(); val count = minOf(64 * 1024, bytes.size - offset); zip.write(bytes, offset, count); offset += count }
                zip.closeEntry()
            }
            fun html(name: String, value: Xml) {
                val bytes = value.value().toByteArray(Charsets.UTF_8)
                totalHtml += bytes.size
                if (bytes.size > MAX_PART || totalHtml > MAX_HTML) throw Fb2Exception(Fb2Failure.LIMIT)
                entry(name, bytes)
            }
            entry("mimetype", "application/epub+zip".toByteArray(), true)
            html("META-INF/container.xml", bounded().raw("<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"))
            val opf = bounded().raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">urn:sha256:$contentSha256</dc:identifier><dc:title>")
                .text(document.title ?: "FB2").raw("</dc:title><dc:language>").text(document.language).raw("</dc:language>")
            document.authors?.let { opf.raw("<dc:creator>").text(it).raw("</dc:creator>") }
            // Converter artifact timestamp, independent of wall clock and edited library metadata.
            opf.raw("<meta property=\"dcterms:modified\">1980-01-02T00:00:00Z</meta></metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" properties=\"nav\" media-type=\"application/xhtml+xml\"/>")
            parts.forEachIndexed { index, part -> opf.raw("<item id=\"part$index\" href=\"${part.href}\" media-type=\"application/xhtml+xml\"/>") }
            document.binaries.forEachIndexed { index, binary ->
                opf.raw("<item id=\"image$index\"").attribute("href", images.getValue(binary.id)).attribute("media-type", binary.mimeType)
                if (binary.id == document.coverId) opf.attribute("properties", "cover-image")
                opf.raw("/>")
            }
            opf.raw("</manifest><spine>")
            parts.forEachIndexed { index, _ -> opf.raw("<itemref idref=\"part$index\"/>") }
            html("package.opf", opf.raw("</spine></package>"))
            val nav = bounded().raw("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>İçindekiler</title></head><body><nav epub:type=\"toc\"><ol>")
            var sectionNumber = 0
            fun toc(node: Fb2Element) {
                caller.ensureActive()
                if (node.name != "section") { node.elements.forEach(::toc); return }
                nav.raw("<li><a").attribute("href", targets.getValue(node).address).raw(">").text(label(node, "Bölüm ${++sectionNumber}")).raw("</a>")
                val descendants = node.elements.filter { it.name == "section" }
                if (descendants.isNotEmpty()) { nav.raw("<ol>"); descendants.forEach(::toc); nav.raw("</ol>") }
                nav.raw("</li>")
            }
            parts.forEach { part ->
                if (part.nodes.none { it is Fb2Element && it.name == "section" }) nav.raw("<li><a").attribute("href", part.href).raw(">").text(if (part.linear) "Giriş" else "Notlar").raw("</a></li>")
                else part.nodes.filterIsInstance<Fb2Element>().forEach(::toc)
            }
            html("nav.xhtml", nav.raw("</ol></nav></body></html>"))
            parts.forEach { part ->
                val out = bounded().raw("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"").attribute("xml:lang", document.language)
                    .raw("><head><title>").text(label(part.nodes.filterIsInstance<Fb2Element>().firstOrNull { it.name == "section" }, document.title ?: "FB2"))
                    .raw("</title><style>body{overflow-wrap:anywhere}img{max-width:100%;height:auto}table{max-width:100%;border-collapse:collapse}td,th{padding:.25em;border:1px solid currentColor}.fb2-v,code{white-space:pre-wrap}.fb2-stanza{margin:1em 0}.fb2-text-author{text-align:right}.fb2-empty-line{height:1em}</style></head><body")
                targets[part.body]?.takeIf { it.href == part.href }?.let { out.attribute("id", it.id) }
                out.raw(">")
                part.nodes.forEach { render(it, out, part) }
                html(part.href, out.raw("</body></html>"))
            }
            document.binaries.forEach { entry(images.getValue(it.id), it.bytes) }
            zip.finish(); zip.flush(); output.fd.sync()
        } }
        caller.ensureActive()
    }

    private class Xml(private val check: (Int) -> Unit) {
        private val builder = StringBuilder()
        fun raw(value: String): Xml { if (value.length > MAX_PART - builder.length) throw Fb2Exception(Fb2Failure.LIMIT); builder.append(value); check(builder.length); return this }
        fun text(value: String): Xml {
            value.forEachIndexed { index, char ->
                when (char) { '&' -> raw("&amp;"); '<' -> raw("&lt;"); '>' -> raw("&gt;"); '"' -> raw("&quot;"); else -> { builder.append(char); if (builder.length > MAX_PART) throw Fb2Exception(Fb2Failure.LIMIT) } }
                if (index % 4096 == 0) check(builder.length)
            }
            check(builder.length); return this
        }
        fun attribute(key: String, value: String): Xml = raw(" $key=\"").text(value).raw("\"")
        fun value(): String = builder.toString()
    }
}

internal class Fb2DerivedFiles(private val directory: File) {
    fun create(): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create FB2 viewing directory")
        if ((directory.listFiles()?.size ?: 0) >= 64) throw IOException("FB2 viewing cache limit")
        return File.createTempFile("fb2-view-", ".epub", directory)
    }
    fun release(file: File) { require(file.canonicalFile.parentFile == directory.canonicalFile); file.delete() }
    /** Startup only, before active reader sessions own any viewing artifact. */
    fun recoverAbandoned() {
        directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("fb2-view-[A-Za-z0-9-]+\\.epub")) }?.forEach {
            if (it.canonicalFile.parentFile != directory.canonicalFile || !it.delete()) throw IOException("Cannot clean interrupted FB2 view")
        }
    }
}

package com.yaprak.reader.formats.docx

import java.io.File
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class DocxHeading(val id: String, val level: Int, val title: String)
internal data class DocxDocument(val metadata: DocxMetadata, val body: String, val headings: List<DocxHeading>, val images: List<DocxBinary> = emptyList(), val noteCount: Int = 0, val sourceText: String = "")

/** Main story and referenced notes, reflowed rather than Word page-layout emulation. Unsupported content fails explicitly. */
internal object StrictDocx {
    suspend fun read(file: File): DocxDocument = document(DocxOpc.read(DocxPackages.read(file)), ::validateDocxRaster)
    suspend fun decode(bytes: ByteArray, validateImage: DocxRasterValidator = ::validateDocxRaster): DocxDocument =
        document(DocxOpc.read(DocxPackages.decode(bytes)), validateImage)

    private suspend fun document(opc: DocxOpc, validateImage: DocxRasterValidator): DocxDocument {
        val caller = currentCoroutineContext(); caller.ensureActive()
        val styles = DocxStyleRules.read(opc)
        val numbering = DocxNumbering.read(opc)
        val ns = opc.wordNamespace
        val body = opc.main.single("body") ?: docxFail()
        if (opc.main.elements.any { it.name != DocxName(ns, "body") && it.name != DocxName(ns, "background") })
            docxFail(DocxFailure.UNSUPPORTED)
        val notes = DocxNotes.read(opc, body)
        val images = DocxImages.read(opc, body, validateImage)
        val main = story(opc, body, opc.mainPart, "", styles, numbering, images, notes, null)
        if (notes.notes.isEmpty()) return main
        val out = DocxMarkup { caller.ensureActive() }.raw(main.body)
        val sourceText = DocxSourceTextWriter { caller.ensureActive() }.also { it.append(main.sourceText) }
        for (note in notes.notes) {
            caller.ensureActive()
            val rendered = story(opc, note.body, note.owner, "n-"+note.target+"-", styles, null, null, null, note.kind)
            sourceText.boundary(); sourceText.append(rendered.sourceText)
            out.raw("<aside").attribute("id", note.target).attribute("epub:type", note.kind.element)
                .attribute("role", "doc-"+note.kind.element).raw("><p><strong>").text(note.label).raw("</strong></p>")
                .raw(rendered.body).raw("<p>")
            for ((index, backlink) in note.backlinks.withIndex()) {
                caller.ensureActive()
                if (index > 0) out.text(" · ")
                out.raw("<a").attribute("href", "#"+backlink).attribute("epub:type", "backlink")
                    .attribute("role", "doc-backlink").raw(">").text("Metne dön"+if (note.backlinks.size > 1) " "+(index+1) else "").raw("</a>")
            }
            out.raw("</p></aside>")
        }
        caller.ensureActive()
        return main.copy(body = out.value(), noteCount = notes.notes.size, sourceText = sourceText.value())
    }

    private suspend fun story(opc: DocxOpc, body: DocxXmlElement, owner: String, scope: String,
        styles: DocxStyleRules, numbering: DocxNumbering?, images: DocxImageSet?, notes: DocxNotesPlan?,
        noteKind: DocxNoteKind?): DocxDocument {
        val caller = currentCoroutineContext(); caller.ensureActive()
        val ns = opc.wordNamespace
        val bookmarks = LinkedHashMap<String, String>(); val bookmarkIds = HashSet<String>(); val bookmarkEnds = HashSet<String>()
        fun word(node: DocxXmlElement) { if (node.name.namespace != ns) docxFail(DocxFailure.UNSUPPORTED) }
        fun value(node: DocxXmlElement) = node.attribute("val", ns) ?: docxFail()
        fun scan(node: DocxXmlElement) {
            caller.ensureActive(); word(node)
            if (node.name.local == "drawing") { (images ?: docxFail(DocxFailure.UNSUPPORTED)).occurrence(node); return }
            if (node.name.local in setOf("del", "moveFrom")) return // Final saved revision view.
            if (node.name.local in setOf("pPr", "rPr", "tblPr", "trPr", "tcPr", "tblGrid", "sdtPr", "sdtEndPr", "sectPr")) return
            if (node.name.local == "bookmarkStart") {
                val name = node.attribute("name", ns)?.takeIf { it.length in 1..256 } ?: docxFail()
                val id = node.attribute("id", ns)?.takeIf { it.matches(Regex("[0-9]{1,10}")) } ?: docxFail()
                if (!bookmarkIds.add(id) || bookmarks.put(name, scope+"b"+(bookmarks.size + 1)) != null) docxFail()
            }
            if (node.name.local == "bookmarkEnd") {
                val id = node.attribute("id", ns) ?: docxFail()
                if (!bookmarkEnds.add(id)) docxFail()
            }
            node.elements.forEach(::scan)
        }
        scan(body)
        if (bookmarkIds != bookmarkEnds) docxFail()
        fun section(node: DocxXmlElement?) {
            if (node == null) return
            if (noteKind != null) docxFail(DocxFailure.UNSUPPORTED)
            // Headers/footers, text-box frames and embedded resources need their own reading-order support.
            if (node.elements.any { it.name.local in setOf("headerReference", "footerReference") }) docxFail(DocxFailure.UNSUPPORTED)
        }
        val headings = ArrayList<DocxHeading>(); var readable = false
        val out = DocxMarkup { caller.ensureActive() }
        val sourceText = DocxSourceTextWriter { caller.ensureActive() }
        fun children(node: DocxXmlElement, render: (DocxXmlElement) -> Unit) {
            node.requireElementContent(); node.elements.forEach { caller.ensureActive(); render(it) }
        }
        fun run(node: DocxXmlElement, base: DocxRunFormat) {
            word(node)
            val properties = node.single("rPr")
            val format = styles.run(base, properties)
            val tags = listOfNotNull(if (format.bold) "strong" else null, if (format.italic) "em" else null,
                if (format.underline) "u" else null, if (format.strike) "s" else null,
                when (format.vertical) { "subscript" -> "sub"; "superscript" -> "sup"; else -> null })
            tags.forEach { out.raw("<$it>") }
            children(node) { item ->
                word(item)
                when (item.name.local) {
                    "rPr" -> Unit
                    "t" -> {
                        if (item.elements.isNotEmpty()) docxFail()
                        val text = item.text(); out.text(text); sourceText.append(text)
                        if (text.isNotBlank()) readable = true
                    }
                    "drawing" -> {
                        val picture = (images ?: docxFail(DocxFailure.UNSUPPORTED)).occurrence(item)
                        out.raw("<img").attribute("src", "../"+picture.image.href).attribute("alt", picture.alt)
                            .attribute("style", "width:"+picture.widthCss+";max-width:100%;height:auto").raw("/>")
                        sourceText.gap()
                        readable = true
                    }
                    "footnoteReference", "endnoteReference" -> {
                        if (noteKind != null) docxFail(DocxFailure.UNSUPPORTED)
                        val reference = (notes ?: docxFail()).reference(item)
                        out.raw("<a").attribute("epub:type", "noteref").attribute("role", "doc-noteref")
                            .attribute("href", "#"+reference.target).attribute("id", reference.anchor)
                            .raw(">").text("["+reference.label+"]").raw("</a>")
                        sourceText.gap()
                        readable = true
                    }
                    "footnoteRef", "endnoteRef" -> {
                        val expected = if (noteKind == DocxNoteKind.FOOTNOTE) "footnoteRef" else if (noteKind == DocxNoteKind.ENDNOTE) "endnoteRef" else null
                        if (item.name.local != expected) docxFail(DocxFailure.UNSUPPORTED)
                        item.requireElementContent(); if (item.elements.isNotEmpty()) docxFail()
                        if (item.attributes.isNotEmpty()) docxFail(DocxFailure.UNSUPPORTED)
                        // The generated accessible note label replaces only the automatic Word marker.
                    }
                    "tab" -> { out.text("\t"); sourceText.gap() }
                    "br", "cr" -> { out.raw("<br/>"); sourceText.boundary() }
                    "noBreakHyphen" -> { out.text("\u2011"); sourceText.append("\u2011") }
                    "softHyphen" -> { out.text("\u00ad"); sourceText.append("\u00ad") }
                    "instrText", "fldChar", "lastRenderedPageBreak", "annotationRef" -> Unit // Cached field result only; never execute instructions.
                    else -> docxFail(DocxFailure.UNSUPPORTED)
                }
            }
            tags.asReversed().forEach { out.raw("</$it>") }
        }
        fun hyperlink(node: DocxXmlElement): String? {
            val id = node.attribute("id", opc.relationshipNamespace)
            val anchor = node.attribute("anchor", ns)
            if (id != null && anchor != null) docxFail(DocxFailure.UNSUPPORTED)
            if (anchor != null) return "#${bookmarks[anchor] ?: docxFail()}"
            if (id == null) return null
            val link = opc.relationship(owner, id)
            if (link.type != "${opc.relationshipNamespace}/hyperlink" || !link.external) docxFail(DocxFailure.UNSUPPORTED)
            val uri = try { URI(link.target) } catch (_: Exception) { docxFail() }
            if (uri.scheme?.lowercase(java.util.Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrEmpty() ||
                uri.rawUserInfo != null) docxFail(DocxFailure.UNSUPPORTED)
            return link.target
        }
        fun inline(node: DocxXmlElement, base: DocxRunFormat) {
            caller.ensureActive(); word(node)
            when (node.name.local) {
                "r" -> run(node, base)
                "hyperlink" -> {
                    val href = hyperlink(node)
                    if (href != null) out.raw("<a").attribute("href", href).raw(">")
                    children(node) { inline(it, base) }
                    if (href != null) out.raw("</a>")
                }
                "bookmarkStart" -> out.raw("<span").attribute("id", bookmarks.getValue(node.attribute("name", ns)!!)).raw("></span>")
                "bookmarkEnd", "proofErr", "commentRangeStart", "commentRangeEnd", "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd" -> Unit
                "del", "moveFrom" -> Unit
                "ins", "moveTo", "fldSimple" -> children(node) { inline(it, base) }
                "sdt" -> {
                    if (node.elements.any { it.name.local !in setOf("sdtPr", "sdtEndPr", "sdtContent") }) docxFail(DocxFailure.UNSUPPORTED)
                    children(node.single("sdtContent") ?: docxFail()) { inline(it, base) }
                }
                else -> docxFail(DocxFailure.UNSUPPORTED)
            }
        }
        fun headingText(node: DocxXmlElement): String = buildString {
            fun visit(item: DocxXmlElement) {
                if (item.name.local in setOf("del", "moveFrom", "instrText", "pPr", "rPr", "sdtPr", "sdtEndPr", "drawing")) return
                if (item.name.local == "t") append(item.text()) else if (item.name.local in setOf("tab", "br", "cr")) append(' ')
                else item.elements.forEach(::visit)
            }
            visit(node)
        }.replace(Regex("[\\s\\p{Z}]+"), " ").trim().let { text ->
            text.substring(0, text.offsetByCodePoints(0, minOf(180, text.codePointCount(0, text.length))))
        }
        fun blocks(node: DocxXmlElement) {
            caller.ensureActive(); word(node)
            when (node.name.local) {
                "p" -> {
                    sourceText.boundary()
                    val properties = node.single("pPr"); section(properties?.single("sectPr"))
                    if (properties?.single("framePr") != null) docxFail(DocxFailure.UNSUPPORTED)
                    val format = styles.paragraph(properties)
                    if (noteKind != null && format.list != null) docxFail(DocxFailure.UNSUPPORTED)
                    val list = numbering?.next(format.list)
                    val title = format.heading?.let { headingText(node) }?.takeIf { it.isNotEmpty() }?.let {
                        (list?.text.orEmpty() + it).replace(Regex("[\\s\\p{Z}]+"), " ").trim()
                    }
                    val tag = if (title != null) "h${minOf(6, format.heading!! + 1)}" else "p"
                    out.raw("<$tag")
                    list?.let { out.attribute("style", "margin-left:${it.level}em") }
                    if (title != null) {
                        if (headings.size >= 4096) docxFail(DocxFailure.LIMIT)
                        val heading = DocxHeading(scope+"h"+(headings.size + 1), format.heading!!, title)
                        headings += heading; out.attribute("id", heading.id)
                    }
                    out.raw(">")
                    list?.let { out.text(it.text) }
                    children(node) { if (it.name == DocxName(ns, "pPr")) Unit else inline(it, format.run) }
                    out.raw("</$tag>"); sourceText.boundary()
                }
                "tbl" -> {
                    out.raw("<table><tbody>")
                    children(node) { row ->
                        word(row)
                        when (row.name.local) {
                            "tblPr", "tblGrid" -> Unit
                            "tr" -> {
                                out.raw("<tr>"); var columns = 0
                                if (row.single("trPr")?.elements?.any { it.name.local in setOf("gridBefore", "gridAfter") } == true)
                                    docxFail(DocxFailure.UNSUPPORTED)
                                children(row) { cell ->
                                    word(cell)
                                    if (cell.name.local == "trPr") Unit
                                    else if (cell.name.local == "tc") {
                                        val properties = cell.single("tcPr")
                                        if (properties?.elements?.any { it.name.local in setOf("vMerge", "hMerge") } == true) docxFail(DocxFailure.UNSUPPORTED)
                                        val span = properties?.single("gridSpan")?.let(::value)?.toIntOrNull()?.takeIf { it in 1..100 }
                                            ?: if (properties?.single("gridSpan") != null) docxFail() else 1
                                        columns += span; if (columns > 100) docxFail(DocxFailure.LIMIT)
                                        out.raw("<td"); if (span > 1) out.attribute("colspan", span.toString()); out.raw(">")
                                        children(cell) { if (it.name == DocxName(ns, "tcPr")) Unit else blocks(it) }
                                        out.raw("</td>")
                                    } else docxFail(DocxFailure.UNSUPPORTED)
                                }
                                if (columns == 0) docxFail()
                                out.raw("</tr>")
                            }
                            else -> docxFail(DocxFailure.UNSUPPORTED)
                        }
                    }
                    out.raw("</tbody></table>")
                }
                "sectPr" -> section(node)
                "del", "moveFrom" -> Unit
                "ins", "moveTo" -> children(node, ::blocks)
                "sdt" -> {
                    if (node.elements.any { it.name.local !in setOf("sdtPr", "sdtEndPr", "sdtContent") }) docxFail(DocxFailure.UNSUPPORTED)
                    children(node.single("sdtContent") ?: docxFail(), ::blocks)
                }
                "bookmarkStart", "bookmarkEnd", "proofErr", "commentRangeStart", "commentRangeEnd" -> inline(node, DocxRunFormat())
                else -> docxFail(DocxFailure.UNSUPPORTED)
            }
        }
        children(body, ::blocks)
        if (!readable) docxFail()
        caller.ensureActive()
        return DocxDocument(opc.metadata, out.value(), headings.toList(), images?.binaries.orEmpty(), sourceText = sourceText.value())
    }
}

/** Only fixed markup uses raw(); all source text/attributes are escaped and bounded. */
internal class DocxMarkup(private val active: () -> Unit) {
    private val builder = StringBuilder()
    fun raw(value: String): DocxMarkup {
        if (value.length > MAX_DOCX_ENTRY_BYTES - builder.length) docxFail(DocxFailure.LIMIT)
        builder.append(value); active(); return this
    }
    fun text(value: String): DocxMarkup {
        value.forEachIndexed { index, char ->
            when (char) {
                '&' -> raw("&amp;"); '<' -> raw("&lt;"); '>' -> raw("&gt;"); '"' -> raw("&quot;")
                else -> { if (builder.length >= MAX_DOCX_ENTRY_BYTES) docxFail(DocxFailure.LIMIT); builder.append(char) }
            }
            if (index % 4096 == 0) active()
        }
        active(); return this
    }
    fun attribute(key: String, value: String): DocxMarkup = raw(" $key=\"").text(value).raw("\"")
    fun value(): String = builder.toString()
}

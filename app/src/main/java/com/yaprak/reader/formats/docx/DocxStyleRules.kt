package com.yaprak.reader.formats.docx

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class DocxRunFormat(val bold: Boolean = false, val italic: Boolean = false,
    val underline: Boolean = false, val strike: Boolean = false, val vertical: String? = null)
internal data class DocxParagraphFormat(val heading: Int?, val run: DocxRunFormat, val list: DocxListReference?)

/** Semantic paragraph/run styling; user reading typography remains in the reflowable navigator. */
internal class DocxStyleRules private constructor(private val namespace: String,
    private val styles: Map<String, DocxXmlElement>, private val defaultParagraph: String?,
    private val paragraphDefaults: DocxXmlElement?, private val runDefaults: DocxXmlElement?) {
    private fun DocxXmlElement.value() = attribute("val", namespace) ?: docxFail()
    private fun chain(id: String?, type: String): List<DocxXmlElement> {
        val result = ArrayList<DocxXmlElement>(); val seen = HashSet<String>(); var current = id
        while (current != null) {
            if (!seen.add(current)) docxFail()
            if (result.size >= 32) docxFail(DocxFailure.LIMIT)
            val node = styles[current] ?: docxFail()
            if (node.attribute("type", namespace) != type) docxFail(DocxFailure.UNSUPPORTED)
            result += node; current = node.single("basedOn")?.value()
        }
        return result.reversed()
    }
    fun paragraph(properties: DocxXmlElement?): DocxParagraphFormat {
        val inherited = chain(properties?.single("pStyle")?.value() ?: defaultParagraph, "paragraph")
        val paragraph = listOfNotNull(paragraphDefaults) + inherited.mapNotNull { it.single("pPr") } + listOfNotNull(properties)
        var number: Int? = null; var level = 0; var numbered = false
        for (layer in paragraph) layer.single("numPr")?.let { properties ->
            numbered = true
            properties.single("numId")?.let { number = it.value().toIntOrNull()?.takeIf { id -> id in 0..1_000_000 } ?: docxFail() }
            properties.single("ilvl")?.let { level = it.value().toIntOrNull()?.takeIf { index -> index in 0..8 } ?: docxFail() }
        }
        if (numbered && number == null) docxFail()
        val list = number?.takeIf { it != 0 }?.let { DocxListReference(it, level) }
        val outline = paragraph.mapNotNull { it.single("outlineLvl")?.value()?.toIntOrNull()?.takeIf { level -> level in 0..9 } ?: run {
            if (it.single("outlineLvl") != null) docxFail(); null
        } }.lastOrNull()
        var run = apply(DocxRunFormat(), runDefaults, false)
        for (style in inherited) run = apply(run, style.single("rPr"), true)
        return DocxParagraphFormat(outline?.takeIf { it < 9 }, run, list)
    }
    fun run(paragraph: DocxRunFormat, properties: DocxXmlElement?): DocxRunFormat {
        var format = paragraph
        for (style in chain(properties?.single("rStyle")?.value(), "character")) format = apply(format, style.single("rPr"), true)
        return apply(format, properties, false)
    }
    private fun apply(base: DocxRunFormat, properties: DocxXmlElement?, toggle: Boolean): DocxRunFormat {
        if (properties == null) return base
        fun bool(local: String, previous: Boolean): Boolean {
            val property = properties.single(local) ?: return previous
            val enabled = docxOnOff(property.attribute("val", namespace))
            return if (toggle) previous xor enabled else enabled
        }
        val underline = properties.single("u")?.let { (it.attribute("val", namespace) ?: "single") != "none" } ?: base.underline
        val vertical = properties.single("vertAlign")?.value()?.also {
            if (it !in setOf("baseline", "subscript", "superscript")) docxFail()
        } ?: base.vertical
        if (properties.single("vanish")?.let { docxOnOff(it.attribute("val", namespace)) } == true ||
            properties.single("specVanish")?.let { docxOnOff(it.attribute("val", namespace)) } == true)
            docxFail(DocxFailure.UNSUPPORTED)
        return DocxRunFormat(bool("b", base.bold), bool("i", base.italic), underline, bool("strike", base.strike), vertical)
    }
    companion object {
        suspend fun read(opc: DocxOpc): DocxStyleRules {
            val caller = currentCoroutineContext(); caller.ensureActive()
            val part = opc.officePart("styles")
            val namespace = opc.wordNamespace
            if (part == null) return DocxStyleRules(namespace, emptyMap(), null, null, null)
            if (opc.type(part) != "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml") docxFail()
            val root = opc.xml(part)
            if (root.name != DocxName(namespace, "styles")) docxFail()
            root.requireElementContent()
            val styles = LinkedHashMap<String, DocxXmlElement>(); var defaultParagraph: String? = null
            for (style in root.named("style")) {
                caller.ensureActive()
                val id = style.attribute("styleId", namespace)?.takeIf { it.length in 1..256 } ?: docxFail()
                if (styles.put(id, style) != null) docxFail()
                if (styles.size > 1024) docxFail(DocxFailure.LIMIT)
                if (style.attribute("type", namespace) == "paragraph" && style.attribute("default", namespace)?.let(::docxOnOff) == true) {
                    if (defaultParagraph != null) docxFail(); defaultParagraph = id
                }
            }
            val defaults = root.single("docDefaults")
            val rules = DocxStyleRules(namespace, styles, defaultParagraph,
                defaults?.single("pPrDefault")?.single("pPr"), defaults?.single("rPrDefault")?.single("rPr"))
            for ((id, style) in styles) {
                caller.ensureActive()
                val type = style.attribute("type", namespace) ?: docxFail()
                rules.chain(id, type)
            }
            return rules
        }
    }
}

internal fun docxOnOff(value: String?): Boolean = when (value) {
    null, "true", "1", "on" -> true
    "false", "0", "off" -> false
    else -> docxFail()
}

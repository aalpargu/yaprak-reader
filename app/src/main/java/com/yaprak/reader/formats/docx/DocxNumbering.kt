package com.yaprak.reader.formats.docx

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class DocxListReference(val number: Int, val level: Int)
internal data class DocxListLabel(val text: String, val level: Int)

/** Actual numbering instances/levels; preserves labels as text in the reflowed paragraph. */
internal class DocxNumbering private constructor(private val instances: Map<Int, Map<Int, Level>>) {
    private data class Level(val start: Int, val format: String, val text: String, val restart: Int?, val suffix: String)
    private val counters = HashMap<Int, IntArray>()
    fun next(reference: DocxListReference?): DocxListLabel? {
        if (reference == null) return null
        val levels = instances[reference.number] ?: docxFail()
        val level = levels[reference.level] ?: docxFail()
        val current = counters.getOrPut(reference.number) { IntArray(9) { -1 } }
        if (current[reference.level] < 0) current[reference.level] = level.start
        else if (current[reference.level] == 1_000_000) docxFail(DocxFailure.LIMIT)
        else current[reference.level]++
        for ((index, descendant) in levels) if (index > reference.level && descendant.restart == reference.level) current[index] = -1
        val text = if (level.format == "none") "" else if (level.format == "bullet") level.text else Regex("%([1-9])").replace(level.text) { match ->
            val index = match.groupValues[1].toInt() - 1
            if (index > reference.level) docxFail(DocxFailure.UNSUPPORTED)
            val referred = levels[index] ?: docxFail()
            val number = current[index].takeIf { it >= 0 } ?: referred.start
            format(number, referred.format)
        }
        if (text.length > 4096) docxFail(DocxFailure.LIMIT)
        if (level.format == "bullet" && text.any { it.code in 0xe000..0xf8ff }) docxFail(DocxFailure.UNSUPPORTED)
        return DocxListLabel(text + if (level.format == "none") "" else level.suffix, reference.level)
    }
    private fun format(number: Int, kind: String): String = when (kind) {
        "decimal" -> number.toString()
        "decimalZero" -> number.toString().padStart(2, '0')
        "none" -> ""
        "lowerLetter", "upperLetter" -> {
            if (number <= 0) docxFail(DocxFailure.UNSUPPORTED)
            val count = (number - 1) / 26 + 1
            if (count > 256) docxFail(DocxFailure.LIMIT)
            // Word repeats one letter after each alphabet: a..z, aa, bb..zz, aaa.
            val result = ('a' + ((number - 1) % 26)).toString().repeat(count)
            if (kind == "upperLetter") result.uppercase(java.util.Locale.ROOT) else result
        }
        "lowerRoman", "upperRoman" -> {
            if (number !in 1..3999) docxFail(DocxFailure.UNSUPPORTED)
            var remaining = number
            val result = buildString {
                for ((value, symbol) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
                    while (remaining >= value) { append(symbol); remaining -= value }
                }
            }
            if (kind == "lowerRoman") result.lowercase(java.util.Locale.ROOT) else result
        }
        else -> docxFail(DocxFailure.UNSUPPORTED)
    }
    companion object {
        suspend fun read(opc: DocxOpc): DocxNumbering {
            val caller = currentCoroutineContext(); caller.ensureActive()
            val part = opc.officePart("numbering") ?: return DocxNumbering(emptyMap())
            if (opc.type(part) != "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml") docxFail()
            val root = opc.xml(part); val ns = opc.wordNamespace
            if (root.name != DocxName(ns, "numbering")) docxFail()
            root.requireElementContent()
            fun integer(raw: String?, range: IntRange): Int = raw?.toIntOrNull()?.takeIf { it in range } ?: docxFail()
            fun value(node: DocxXmlElement) = node.attribute("val", ns) ?: docxFail()
            fun level(node: DocxXmlElement, index: Int): Level {
                caller.ensureActive()
                if (node.name != DocxName(ns, "lvl") || integer(node.attribute("ilvl", ns), 0..8) != index) docxFail()
                if (node.single("lvlPicBulletId") != null || node.single("isLgl")?.let { docxOnOff(it.attribute("val", ns)) } == true)
                    docxFail(DocxFailure.UNSUPPORTED)
                val format = node.single("numFmt")?.let(::value) ?: "decimal"
                if (format !in setOf("decimal", "decimalZero", "lowerLetter", "upperLetter", "lowerRoman", "upperRoman", "bullet", "none")) docxFail(DocxFailure.UNSUPPORTED)
                val text = node.single("lvlText")?.let(::value) ?: if (format == "none") "" else docxFail()
                if (text.length > 256) docxFail(DocxFailure.LIMIT)
                val start = node.single("start")?.let { integer(value(it), 0..1_000_000) } ?: 0
                val restartValue = node.single("lvlRestart")?.let { integer(value(it), 0..9) }
                // Omitted: previous level. Zero: never. Invalid later/self references: ignored per standard.
                val restart = if (restartValue == 0) null else if (restartValue == null || restartValue > index) (index - 1).takeIf { it >= 0 } else restartValue - 1
                val suffix = when (node.single("suff")?.let(::value)) { null, "tab" -> "\t"; "space" -> " "; "nothing" -> ""; else -> docxFail() }
                return Level(start, format, text, restart, suffix)
            }
            val abstracts = LinkedHashMap<Int, Map<Int, Level>>()
            for (definition in root.named("abstractNum")) {
                caller.ensureActive()
                val id = integer(definition.attribute("abstractNumId", ns), 0..1_000_000)
                if (definition.single("numStyleLink") != null || definition.single("styleLink") != null) docxFail(DocxFailure.UNSUPPORTED)
                val levels = LinkedHashMap<Int, Level>()
                for (node in definition.named("lvl")) {
                    val index = integer(node.attribute("ilvl", ns), 0..8)
                    if (levels.put(index, level(node, index)) != null) docxFail()
                }
                if (levels.isEmpty() || abstracts.put(id, levels.toMap()) != null) docxFail()
                if (abstracts.size > 1024) docxFail(DocxFailure.LIMIT)
            }
            val instances = LinkedHashMap<Int, Map<Int, Level>>()
            for (definition in root.named("num")) {
                caller.ensureActive()
                val id = integer(definition.attribute("numId", ns), 1..1_000_000)
                val abstract = integer(definition.single("abstractNumId")?.let(::value), 0..1_000_000)
                val levels = (abstracts[abstract] ?: docxFail()).toMutableMap(); val seen = HashSet<Int>()
                for (override in definition.named("lvlOverride")) {
                    val index = integer(override.attribute("ilvl", ns), 0..8)
                    if (!seen.add(index)) docxFail()
                    var selected = override.single("lvl")?.let { level(it, index) } ?: levels[index] ?: docxFail()
                    override.single("startOverride")?.let { selected = selected.copy(start = integer(value(it), 0..1_000_000)) }
                    levels[index] = selected
                }
                if (instances.put(id, levels.toMap()) != null) docxFail()
                if (instances.size > 1024) docxFail(DocxFailure.LIMIT)
            }
            return DocxNumbering(instances.toMap())
        }
    }
}

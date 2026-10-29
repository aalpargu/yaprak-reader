package com.yaprak.reader.formats.pdf

import com.tom_roush.pdfbox.cos.*
import java.util.Collections
import java.util.IdentityHashMap

internal data class PdfOutlineRow(val id: String, val title: String, val depth: Int, val pageIndex: Int?)
internal data class ParsedPdfOutline(val rows: List<PdfOutlineRow>, val truncated: Boolean)
internal enum class PdfOutlineProblem { LIMIT, MALFORMED }
internal class PdfOutlineParseException(val problem: PdfOutlineProblem) : Exception(problem.name)

/** Reads inert local page destinations only. Never executes PDF actions or follows an external URI. */
internal class BoundedPdfOutline(private val checkActive: () -> Unit = {}) {
    private var lookups = 0
    private fun fail(problem: PdfOutlineProblem): Nothing = throw PdfOutlineParseException(problem)
    private fun direct(value: COSBase?): COSBase? {
        var current = value
        val seen = IdentityHashMap<COSObject, Boolean>()
        repeat(32) {
            checkActive()
            if (++lookups > MAX_LOOKUPS) fail(PdfOutlineProblem.LIMIT)
            val ref = current as? COSObject ?: return current
            if (seen.put(ref, true) != null) fail(PdfOutlineProblem.MALFORMED)
            current = ref.`object`
        }
        fail(PdfOutlineProblem.LIMIT)
    }
    private fun field(node: COSDictionary, name: COSName): COSBase? = direct(node.getItem(name))

    fun read(catalog: COSDictionary, pageCount: Int): ParsedPdfOutline {
        lookups = 0
        checkActive()
        if (pageCount !in 1..MAX_PAGES) fail(PdfOutlineProblem.LIMIT)
        val rawOutline = field(catalog, COSName.OUTLINES) ?: return ParsedPdfOutline(emptyList(), false)
        val outline = rawOutline as? COSDictionary ?: fail(PdfOutlineProblem.MALFORMED)
        val first = field(outline, COSName.FIRST) ?: return ParsedPdfOutline(emptyList(), false)
        val pages = pageOrdinals(catalog, pageCount)
        val result = mutableListOf<PdfOutlineRow>()
        val seen = IdentityHashMap<COSDictionary, Boolean>()
        val pending = ArrayDeque<Pair<COSBase, Int>>()
        pending.addLast(first to 0)
        while (pending.isNotEmpty() && result.size < MAX_ENTRIES) {
            checkActive()
            val (raw, depth) = pending.removeLast()
            if (depth > MAX_DEPTH) fail(PdfOutlineProblem.LIMIT)
            val node = direct(raw) as? COSDictionary ?: fail(PdfOutlineProblem.MALFORMED)
            if (seen.put(node, true) != null) fail(PdfOutlineProblem.MALFORMED)
            val title = (field(node, COSName.TITLE) as? COSString)?.string.orEmpty()
                .take(500).dropLastWhile { it.isHighSurrogate() }
                .map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().ifBlank { "Adsız başlık" }
            val target = target(node, catalog, pages)
            result += PdfOutlineRow(result.size.toString(), title, depth, target)
            field(node, COSName.NEXT)?.let { pending.addLast(it to depth) }
            field(node, COSName.FIRST)?.let { pending.addLast(it to depth + 1) }
        }
        return ParsedPdfOutline(Collections.unmodifiableList(result), pending.isNotEmpty())
    }

    private fun pageOrdinals(catalog: COSDictionary, expected: Int): IdentityHashMap<COSDictionary, Int> {
        val root = field(catalog, COSName.PAGES) ?: fail(PdfOutlineProblem.MALFORMED)
        val result = IdentityHashMap<COSDictionary, Int>()
        val seen = IdentityHashMap<COSDictionary, Boolean>()
        val pending = ArrayDeque<Pair<COSBase, Int>>()
        pending.addLast(root to 0)
        while (pending.isNotEmpty()) {
            checkActive()
            val (raw, depth) = pending.removeLast()
            if (depth > MAX_DEPTH || seen.size >= MAX_PAGE_NODES) fail(PdfOutlineProblem.LIMIT)
            val node = direct(raw) as? COSDictionary ?: fail(PdfOutlineProblem.MALFORMED)
            if (seen.put(node, true) != null) fail(PdfOutlineProblem.MALFORMED)
            when (field(node, COSName.TYPE)) {
                COSName.PAGE -> {
                    if (result.size >= expected) fail(PdfOutlineProblem.MALFORMED)
                    result[node] = result.size
                }
                COSName.PAGES -> {
                    val children = field(node, COSName.KIDS) as? COSArray ?: fail(PdfOutlineProblem.MALFORMED)
                    if (children.size() + pending.size + seen.size > MAX_PAGE_NODES) fail(PdfOutlineProblem.LIMIT)
                    for (index in children.size() - 1 downTo 0) pending.addLast(children.get(index) to depth + 1)
                }
                else -> fail(PdfOutlineProblem.MALFORMED)
            }
        }
        if (result.size != expected) fail(PdfOutlineProblem.MALFORMED)
        return result
    }

    private fun target(node: COSDictionary, catalog: COSDictionary, pages: IdentityHashMap<COSDictionary, Int>): Int? {
        var destination = field(node, COSName.DEST)
        val action = field(node, COSName.A)
        if (destination != null && action != null) return null
        if (destination == null) {
            val dictionary = action as? COSDictionary ?: return null
            if (field(dictionary, COSName.S) != COSName.getPDFName("GoTo")) return null
            destination = field(dictionary, COSName.D)
        }
        val named = mutableSetOf<String>()
        val dictionaries = IdentityHashMap<COSDictionary, Boolean>()
        repeat(32) {
            checkActive()
            when (val value = direct(destination)) {
                is COSArray -> {
                    if (value.size() < 2) return null
                    val mode = direct(value.get(1)) as? COSName ?: return null
                    if (mode.name !in LOCAL_MODES) return null
                    // Local destinations refer to actual Page objects. Numeric remote-page guesses are unavailable.
                    val page = direct(value.get(0)) as? COSDictionary ?: return null
                    return pages[page]
                }
                is COSDictionary -> {
                    if (dictionaries.put(value, true) != null) return null
                    destination = field(value, COSName.D)
                }
                is COSString, is COSName -> {
                    val name = if (value is COSString) value.string else (value as COSName).name
                    if (name.isBlank() || name.length > 1024 || !named.add(name)) return null
                    destination = namedDestination(catalog, name)
                }
                else -> return null
            }
        }
        return null
    }

    private fun namedDestination(catalog: COSDictionary, name: String): COSBase? {
        val legacy = field(catalog, COSName.DESTS) as? COSDictionary
        val legacyValue = legacy?.let { field(it, COSName.getPDFName(name)) }
        val names = field(catalog, COSName.NAMES) as? COSDictionary ?: return legacyValue
        val root = field(names, COSName.DESTS) ?: return legacyValue
        var found: COSBase? = null
        val seen = IdentityHashMap<COSDictionary, Boolean>()
        val pending = ArrayDeque<Pair<COSBase, Int>>()
        pending.addLast(root to 0)
        while (pending.isNotEmpty()) {
            checkActive()
            val (raw, depth) = pending.removeLast()
            if (depth > MAX_DEPTH || seen.size >= MAX_NAME_NODES) fail(PdfOutlineProblem.LIMIT)
            val node = direct(raw) as? COSDictionary ?: return null
            if (seen.put(node, true) != null) return null
            val pairs = field(node, COSName.NAMES) as? COSArray
            if (pairs != null) {
                if (pairs.size() > MAX_NAME_PAIRS * 2) fail(PdfOutlineProblem.LIMIT)
                if (pairs.size() % 2 != 0) return null
                for (index in 0 until pairs.size() step 2) {
                    val key = direct(pairs.get(index)) as? COSString ?: return null
                    if (key.string == name) {
                        if (found != null || legacyValue != null) fail(PdfOutlineProblem.MALFORMED)
                        found = direct(pairs.get(index + 1)) ?: return null
                    }
                }
            }
            val children = field(node, COSName.KIDS) as? COSArray
            if (children != null) {
                if (children.size() + pending.size + seen.size > MAX_NAME_NODES) fail(PdfOutlineProblem.LIMIT)
                for (index in children.size() - 1 downTo 0) pending.addLast(children.get(index) to depth + 1)
            }
        }
        return found ?: legacyValue
    }

    companion object {
        const val MAX_ENTRIES = 5000
        const val MAX_PAGES = 10_000
        const val MAX_DEPTH = 64
        private const val MAX_PAGE_NODES = 20_000
        private const val MAX_NAME_NODES = 2000
        private const val MAX_NAME_PAIRS = 10_000
        private const val MAX_LOOKUPS = 200_000
        private val LOCAL_MODES = setOf("XYZ", "Fit", "FitH", "FitV", "FitR", "FitB", "FitBH", "FitBV")
    }
}

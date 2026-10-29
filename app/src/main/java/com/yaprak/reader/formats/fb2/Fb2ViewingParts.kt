package com.yaprak.reader.formats.fb2

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The exact original-node partition shared by conversion and original-text indexing. */
internal data class Fb2ViewingPart(val body: Fb2Element, val nodes: List<Fb2Content>, val href: String, val linear: Boolean)

internal suspend fun fb2ViewingParts(document: Fb2Document): List<Fb2ViewingPart> {
    val caller = currentCoroutineContext()
    val parts = ArrayList<Fb2ViewingPart>()
    document.bodies.forEachIndexed { bodyIndex, body ->
        var nodes = ArrayList<Fb2Content>()
        var partIndex = 0
        fun flush() {
            caller.ensureActive()
            if (nodes.any { it is Fb2Element || it is Fb2Text && it.value.isNotBlank() }) {
                parts.add(Fb2ViewingPart(body, nodes.toList(), "Text/b${bodyIndex + 1}-p${++partIndex}.xhtml", bodyIndex == 0))
                if (parts.size > 2048) throw Fb2Exception(Fb2Failure.LIMIT)
            }
            nodes = ArrayList()
        }
        body.children.forEach { node ->
            caller.ensureActive()
            if (node is Fb2Element && node.name == "section") flush()
            nodes.add(node)
        }
        flush()
    }
    if (parts.isEmpty()) throw Fb2Exception(Fb2Failure.INVALID)
    return parts
}

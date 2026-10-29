package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.reader.ContentsEntry

/** Branches come from the full original preorder, including headings without a destination. */
internal fun contentsBranchIds(entries: List<ContentsEntry>): Set<String> {
    val branches = linkedSetOf<String>()
    for (index in 0 until entries.lastIndex) {
        if (entries[index + 1].depth > entries[index].depth) branches += entries[index].id
    }
    return branches
}

/** Presentation only: descendants stay folded until the next same-depth or shallower entry. */
internal fun visibleContentsForFolds(entries: List<ContentsEntry>, collapsedIds: Set<String>): List<ContentsEntry> {
    if (entries.isEmpty() || collapsedIds.isEmpty()) return entries
    val branches = contentsBranchIds(entries)
    val visible = ArrayList<ContentsEntry>(entries.size)
    var hiddenBelowDepth: Int? = null
    for (entry in entries) {
        val foldedDepth = hiddenBelowDepth
        if (foldedDepth != null && entry.depth > foldedDepth) continue
        hiddenBelowDepth = null
        visible += entry
        if (entry.id in branches && entry.id in collapsedIds) hiddenBelowDepth = entry.depth
    }
    return if (visible.size == entries.size) entries else visible
}

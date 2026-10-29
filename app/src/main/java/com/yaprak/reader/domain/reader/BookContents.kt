package com.yaprak.reader.domain.reader

/** Session-local identity, never an arbitrary URI supplied by the UI. */
data class ContentsEntry(val id: String, val title: String, val depth: Int, val navigable: Boolean)

data class BookContents(val entries: List<ContentsEntry>, val truncated: Boolean = false)

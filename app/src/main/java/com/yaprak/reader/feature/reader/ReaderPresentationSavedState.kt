package com.yaprak.reader.feature.reader

import android.os.Bundle
import com.yaprak.reader.domain.library.Book

/** Bounded Android presentation memory. It never carries a location, selection, action or source lease. */
internal data class ReaderPresentationSavedState(
    val bookId: String,
    val hash: String,
    val controlsVisible: Boolean,
    val panels: List<ReaderPanelKind>,
    val drafts: Map<ReaderPanelKind, Bundle>,
) {
    fun matches(book: Book): Boolean = book.id == bookId && book.sha256 == hash

    fun save(outState: Bundle) {
        outState.putBundle(KEY, Bundle().apply {
            putInt("version", 1); putString("book", bookId); putString("hash", hash)
            putBoolean("controls", controlsVisible)
            putStringArrayList("panels", ArrayList(panels.map { it.name }))
            putBundle("drafts", Bundle().apply {
                drafts.forEach { (kind, draft) ->
                    ReaderDraftSnapshot.fromBundle(draft)?.toBundle()?.let { putBundle(kind.name, it) }
                }
            })
        })
    }

    companion object {
        private const val KEY = "reader.presentation.v1"
        val draftKinds = setOf(ReaderPanelKind.BOOKMARKS, ReaderPanelKind.NOTES, ReaderPanelKind.WORDS, ReaderPanelKind.PASSAGES)
        val restorableKinds = ReaderPanelKind.entries.filter { it.restorable }.toSet()

        fun capture(book: Book, state: ReaderPresentationState, drafts: Map<ReaderPanelKind, Bundle>): ReaderPresentationSavedState? {
            val panels = state.panels.map { it.kind }.filter { it in restorableKinds }
            if (panels.size > 8 || (!state.controlsVisible && panels.isEmpty())) return null
            return ReaderPresentationSavedState(book.id, book.sha256, state.controlsVisible, panels,
                drafts.filterKeys { it in panels && it in draftKinds }.mapNotNull { (kind, value) ->
                    ReaderDraftSnapshot.fromBundle(value)?.toBundle()?.let { kind to it }
                }.toMap())
        }

        @Suppress("DEPRECATION")
        fun restore(saved: Bundle?, expectedBookId: String): ReaderPresentationSavedState? {
            return runCatching {
            val bundle = saved?.getBundle(KEY) ?: return null
            if (bundle.keySet() != setOf("version", "book", "hash", "controls", "panels", "drafts") ||
                bundle.get("version") != 1) return null
            val bookId = bundle.get("book") as? String ?: return null
            val hash = bundle.get("hash") as? String ?: return null
            val controls = bundle.get("controls") as? Boolean ?: return null
            if (bookId != expectedBookId || bookId.length !in 1..128 || !hash.matches(Regex("[0-9a-f]{64}"))) return null
            val names = bundle.get("panels") as? ArrayList<*> ?: return null
            if (names.size > 8 || names.any { it !is String }) return null
            val panels = names.map { name -> ReaderPanelKind.entries.firstOrNull { it.name == name } ?: return null }
            if (panels.toSet().size != panels.size || panels.any { it !in restorableKinds } ||
                panels.count { it.isMenu } > 1 || panels.indexOfFirst { it.isMenu } > 0) return null
            val savedDrafts = bundle.get("drafts") as? Bundle ?: return null
            if (savedDrafts.keySet().any { name -> draftKinds.none { it.name == name } }) return null
            val drafts = savedDrafts.keySet().mapNotNull { name ->
                val kind = draftKinds.single { it.name == name }
                if (kind !in panels) return null
                val draft = ReaderDraftSnapshot.fromBundle(savedDrafts.get(name) as? Bundle)?.toBundle() ?: return null
                kind to draft
            }.toMap()
            ReaderPresentationSavedState(bookId, hash, controls, panels, drafts)
            }.getOrNull()
        }
    }
}

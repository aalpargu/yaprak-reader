package com.yaprak.reader.feature.reader

/** Presentation references only: neither a panel nor a transition proves a book location. */
internal enum class ReaderPanelKind {
    CONTENT, APPEARANCE, TOOLS,
    SETTINGS, BOOK_APPEARANCE, BOOK_RESET, RESET_REFRESH, BOOK_THEME, BOOK_FONT_SIZE, BOOK_FONT,
    BOOK_MARGINS, BOOK_LINE_HEIGHT, BOOK_SPACING, BOOK_ALIGNMENT, BOOK_READING_MODE,
    ORIENTATION, BRIGHTNESS, IMAGES, POSITION, TAP_ZONES, VOLUME_KEYS, SCREEN_AWAKE,
    SELECTION, ANNOTATION, PDF_ANNOTATION, PDF_ANNOTATIONS,
    CONTENTS, SEARCH, PAGE_JUMP, PDF_LINKS, PDF_THUMBNAILS, PDF_CROP,
    BOOKMARKS, WORDS, NOTES, PASSAGES;

    val isMenu: Boolean get() = this == CONTENT || this == APPEARANCE || this == TOOLS
    val restorable: Boolean get() = isMenu || this in setOf(BOOKMARKS, NOTES, WORDS, PASSAGES,
        BOOK_APPEARANCE, SETTINGS, BOOK_THEME, BOOK_FONT_SIZE, BOOK_FONT, BOOK_MARGINS,
        BOOK_LINE_HEIGHT, BOOK_SPACING, BOOK_ALIGNMENT, BOOK_READING_MODE, TAP_ZONES,
        VOLUME_KEYS, SCREEN_AWAKE, ORIENTATION)
}

internal enum class ReaderBlockingReason { OPEN, SOURCE, SAVE, RENDER }
internal sealed interface ReaderPhase {
    data object Loading : ReaderPhase
    data object Ready : ReaderPhase
    data class BlockingError(val reason: ReaderBlockingReason) : ReaderPhase
}

internal enum class ReaderNoticeKind { SAVE, LOAD, RENDER, DURATION, INDEXED_SOURCE, PDF_APPEARANCE, COMIC_APPEARANCE, ORIENTATION, PDF_CROP, PRESENTATION_RESTORE }
internal data class ReaderNotice(val id: Long, val kind: ReaderNoticeKind)
internal data class ReaderSelectionRef(val id: String)
internal data class ReaderReturnFocus(val id: Long, val epoch: Long, val key: String)
internal data class ReaderPanelEntry(val id: Long, val kind: ReaderPanelKind,
    val returnFocusKey: String? = null, val exposureId: Long = id)
internal data class ReaderPanelEffect(val id: Long, val epoch: Long, val origin: ReaderPanelEntry, val returnFocusKey: String? = null)

internal val ReaderPanelKind.focusKey: String get() = "reader-focus-${name.lowercase()}"
internal val ReaderPanelKind.menuFocusKey: String get() = "$focusKey-menu"
internal val ReaderPanelKind.defaultReturnFocusKey: String get() = when (this) {
    ReaderPanelKind.RESET_REFRESH -> ReaderPanelKind.BOOK_RESET.focusKey
    ReaderPanelKind.ANNOTATION -> ReaderPanelKind.PASSAGES.focusKey
    ReaderPanelKind.PDF_ANNOTATION -> ReaderPanelKind.PDF_ANNOTATIONS.focusKey
    else -> focusKey
}

internal sealed interface ReaderBackDecision {
    data object HideIme : ReaderBackDecision
    data object CancelDeferred : ReaderBackDecision
    data class ClosePanel(val entry: ReaderPanelEntry) : ReaderBackDecision
    data object ClearSelection : ReaderBackDecision
    data object HideControls : ReaderBackDecision
    data object Exit : ReaderBackDecision
}

internal data class ReaderPresentationState(
    val controlsVisible: Boolean = false,
    val owner: String? = null,
    val epoch: Long = 0,
    val phase: ReaderPhase = ReaderPhase.Loading,
    val selection: ReaderSelectionRef? = null,
    val notice: ReaderNotice? = null,
    val panels: List<ReaderPanelEntry> = emptyList(),
    val pending: ReaderPanelEffect? = null,
    val returnFocus: ReaderReturnFocus? = null,
) {
    val activePanel: ReaderPanelEntry? get() = if (pending == null) panels.lastOrNull() else null
    val blocksReading: Boolean get() = phase != ReaderPhase.Ready || panels.isNotEmpty() || pending != null
}

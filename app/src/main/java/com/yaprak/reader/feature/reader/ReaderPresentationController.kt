package com.yaprak.reader.feature.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread presentation owner. It grants no source, mutation or Android lifecycle authority. */
internal class ReaderPresentationController {
    private val mutable = MutableStateFlow(ReaderPresentationState())
    val state = mutable.asStateFlow()
    private var sequence = 0L
    private var consumedEffect: Long? = null

    fun attach(owner: String?) {
        if (state.value.owner == owner) return
        mutable.value = ReaderPresentationState(owner = owner, epoch = state.value.epoch + 1,
            phase = if (owner == null) ReaderPhase.Loading else ReaderPhase.Ready)
        consumedEffect = null
    }

    /** Blocking a source revokes unadmitted actions. Existing editors keep their write/draft ownership. */
    fun updateStatus(phase: ReaderPhase, notice: ReaderNoticeKind?) {
        val current = state.value
        val blocked = phase != ReaderPhase.Ready
        val invalidate = phase != current.phase && blocked
        val nextNotice = if (notice == current.notice?.kind) current.notice else notice?.let { ReaderNotice(++sequence, it) }
        mutable.value = current.copy(phase = phase, notice = nextNotice,
            panels = if (blocked) current.panels.filterNot { it.kind.isMenu && it.kind != ReaderPanelKind.TOOLS } else current.panels,
            selection = if (blocked) null else current.selection,
            pending = if (invalidate) null else current.pending,
            returnFocus = if (invalidate) null else current.returnFocus,
            epoch = current.epoch + if (invalidate) 1 else 0)
        if (invalidate) consumedEffect = null
    }

    fun setSelection(selection: ReaderSelectionRef?) {
        val current = state.value
        if (selection != null && (current.owner == null || current.phase != ReaderPhase.Ready)) return
        if (current.selection != selection) mutable.value = current.copy(selection = selection)
    }

    fun setControls(visible: Boolean) {
        val current = state.value
        if (visible) mutable.value = current.copy(controlsVisible = true)
        else {
            mutable.value = current.copy(controlsVisible = false, panels = current.panels.filterNot { it.kind.isMenu },
                pending = null, returnFocus = null, epoch = current.epoch + 1)
            consumedEffect = null
        }
    }

    /** Only recreated presentation references are admitted here; live request models still own drafts/work. */
    fun restorePanels(controlsVisible: Boolean, kinds: List<ReaderPanelKind>, requests: Set<ReaderPanelKind>): Boolean {
        val current = state.value
        if (current.owner == null || current.phase != ReaderPhase.Ready || current.pending != null ||
            kinds.size > 8 || kinds.any { !it.restorable } || kinds.toSet().size != kinds.size || kinds.count { it.isMenu } > 1 ||
            kinds.indexOfFirst { it.isMenu } > 0) return false
        val restored = kinds.filter { it.isMenu || it in requests }
        val additional = requests.filter { it !in restored }
        mutable.value = current.copy(controlsVisible = controlsVisible, epoch = current.epoch + 1,
            panels = (restored + additional).map { ReaderPanelEntry(++sequence, it, it.defaultReturnFocusKey) },
            pending = null, selection = null, returnFocus = null)
        consumedEffect = null
        return true
    }

    fun hasRequest(kind: ReaderPanelKind): Boolean = state.value.panels.any { it.kind == kind }

    private fun canPresent(kind: ReaderPanelKind, phase: ReaderPhase): Boolean = phase == ReaderPhase.Ready ||
        kind == ReaderPanelKind.PDF_THUMBNAILS && phase == ReaderPhase.BlockingError(ReaderBlockingReason.RENDER)

    fun request(kind: ReaderPanelKind, open: Boolean) {
        require(!kind.isMenu)
        val current = state.value
        if (open) {
            if (current.owner == null || !canPresent(kind, current.phase) || hasRequest(kind)) return
            mutable.value = current.copy(panels = current.panels + ReaderPanelEntry(++sequence, kind,
                current.pending?.returnFocusKey ?: kind.defaultReturnFocusKey), returnFocus = null)
        } else publishPanels(current.panels.filterNot { it.kind == kind })
    }

    fun openMenu(kind: ReaderPanelKind) {
        require(kind.isMenu)
        val current = state.value
        val recovery = kind == ReaderPanelKind.TOOLS && current.notice != null && current.phase is ReaderPhase.BlockingError
        if (current.owner == null || current.pending != null || (current.phase != ReaderPhase.Ready && !recovery)) return
        if (current.panels.any { !it.kind.isMenu }) return
        mutable.value = current.copy(panels = listOf(ReaderPanelEntry(++sequence, kind, kind.focusKey)), returnFocus = null)
    }

    /** Request models own work/drafts; this list alone decides the visible Window. */
    fun synchronizeRequests(requests: Set<ReaderPanelKind>) {
        require(requests.none { it.isMenu })
        val current = state.value
        if (current.owner == null) return
        val surviving = current.panels.filter { it.kind.isMenu || it.kind in requests }
        val additions = requests.filter { kind -> canPresent(kind, current.phase) && surviving.none { it.kind == kind } }
        val returnKey = current.pending?.returnFocusKey
        val panels = surviving + additions.map { ReaderPanelEntry(++sequence, it, returnKey ?: it.defaultReturnFocusKey) }
        if (panels != current.panels) publishPanels(panels)
    }

    private fun publishPanels(panels: List<ReaderPanelEntry>) {
        val current = state.value
        val oldTop = current.panels.lastOrNull()
        val removedTop = oldTop?.takeIf { old -> panels.none { it.id == old.id } }
        val added = panels.any { next -> current.panels.none { it.id == next.id } }
        val parent = panels.lastOrNull()
        // Keep the logical saveable/scroll key, while revoking the disposed Window's callbacks.
        val published = if (parent != null && parent.id != oldTop?.id && current.panels.any { it.id == parent.id })
            panels.dropLast(1) + parent.copy(exposureId = ++sequence) else panels
        val returnKey = removedTop?.returnFocusKey?.takeIf { current.controlsVisible || panels.isNotEmpty() }
        val restore = if (!added && current.pending == null && current.phase == ReaderPhase.Ready && returnKey != null)
            ReaderReturnFocus(++sequence, current.epoch, returnKey) else null
        mutable.value = current.copy(panels = published, returnFocus = if (added) null else restore ?: current.returnFocus)
    }

    fun closeMenu(expected: ReaderPanelEntry) {
        val current = state.value
        if (!expected.kind.isMenu || current.activePanel != expected) return
        publishPanels(current.panels.dropLast(1))
    }

    /** Dispose the old Window before waiting for real native host focus; state contains no action lambda. */
    fun defer(expected: ReaderPanelEntry, returnFocusKey: String? = null): ReaderPanelEffect? {
        val current = state.value
        if (!expected.kind.isMenu || current.activePanel != expected || current.owner == null) return null
        if (current.phase != ReaderPhase.Ready && expected.kind != ReaderPanelKind.TOOLS) return null
        return ReaderPanelEffect(++sequence, current.epoch, expected, returnFocusKey ?: expected.kind.menuFocusKey).also {
            mutable.value = current.copy(pending = it, returnFocus = null)
        }
    }

    fun consume(effect: ReaderPanelEffect): Boolean {
        val current = state.value
        if (current.pending != effect || current.epoch != effect.epoch || consumedEffect == effect.id) return false
        consumedEffect = effect.id
        return true
    }

    fun finish(effect: ReaderPanelEffect, requests: Set<ReaderPanelKind>) {
        val current = state.value
        if (current.pending != effect || current.epoch != effect.epoch || consumedEffect != effect.id) return
        synchronizeRequests(requests)
        val updated = state.value
        val hasChild = updated.panels.any { !it.kind.isMenu }
        mutable.value = updated.copy(pending = null, panels = if (hasChild) updated.panels else emptyList(),
            returnFocus = if (hasChild || !updated.controlsVisible) null else
                ReaderReturnFocus(++sequence, updated.epoch, effect.origin.returnFocusKey ?: effect.origin.kind.focusKey))
    }

    fun back(imeVisible: Boolean, nativeSelectionActive: Boolean): ReaderBackDecision {
        val current = state.value
        return when {
            imeVisible -> ReaderBackDecision.HideIme
            current.pending != null -> ReaderBackDecision.CancelDeferred
            current.activePanel != null -> ReaderBackDecision.ClosePanel(requireNotNull(current.activePanel))
            current.selection != null || nativeSelectionActive -> ReaderBackDecision.ClearSelection
            current.controlsVisible -> ReaderBackDecision.HideControls
            else -> ReaderBackDecision.Exit
        }
    }

    fun isReturnFocusCurrent(effect: ReaderReturnFocus): Boolean = state.value.returnFocus == effect && state.value.epoch == effect.epoch
    fun acknowledgeReturnFocus(effect: ReaderReturnFocus) {
        if (isReturnFocusCurrent(effect)) mutable.value = state.value.copy(returnFocus = null)
    }

    /** Pause revokes every deferred/focus effect, while admitted editor requests retain unsaved drafts. */
    fun pause() {
        val current = state.value
        mutable.value = current.copy(epoch = current.epoch + 1, pending = null, returnFocus = null)
        consumedEffect = null
    }
}

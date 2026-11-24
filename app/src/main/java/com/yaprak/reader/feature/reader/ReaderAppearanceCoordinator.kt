package com.yaprak.reader.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.feature.settings.*
import com.yaprak.reader.reader.EpubAppearanceController
import kotlinx.coroutines.CancellationException

/** Observed saved values, never editor drafts. Domain helpers keep global/book precedence unchanged. */
internal data class ReaderAppearanceInputs(
    val global: EpubSettingsState,
    val theme: BookThemeState,
    val fontSize: BookFontSizeState,
    val font: BookFontState,
    val margins: BookMarginsState,
    val lineHeight: BookLineHeightState,
    val spacing: BookSpacingState,
    val alignment: BookTextAlignmentState,
    val mode: BookReadingModeState,
    val reset: BookAppearanceResetState,
) {
    val failed: Boolean get() = global.loadFailed || theme.loadFailed || fontSize.loadFailed || font.loadFailed ||
        margins.loadFailed || lineHeight.loadFailed || spacing.loadFailed || alignment.loadFailed || mode.loadFailed
    val loaded: Boolean get() = !failed && !global.loading && !theme.loading && !fontSize.loading && !font.loading &&
        !margins.loading && !lineHeight.loading && !spacing.loading && !alignment.loading && !mode.loading
    val resetObserved: Boolean get() = appearanceResetObserved(reset, theme, fontSize, font, margins,
        lineHeight, spacing, alignment, mode)

    fun effective(): EpubSettings = global.saved.withBookTheme(theme.saved).withBookFontSize(fontSize.saved)
        .withBookFont(font.saved).withBookMargins(margins.saved).withBookLineHeight(lineHeight.saved)
        .withBookSpacing(spacing.saved).withBookTextAlignment(alignment.saved).withBookReadingMode(mode.saved)
}

internal enum class ReaderAppearanceResult { WAITING, UNCHANGED, APPLIED, RESET_APPLIED, RESET_FAILED }

/** Applies one coherent observed preference set; storage, edit/save and renderer work stay with their owners. */
internal class ReaderAppearanceCoordinator {
    private var appliedOwner: EpubAppearanceController? = null
    private var appliedSettings: EpubSettings? = null
    var resetPending by mutableStateOf(false)
        private set
    var resetRefreshFailed by mutableStateOf(false)
        private set

    fun beginReset() { resetPending = true }
    fun cancelReset() { resetPending = false; resetRefreshFailed = false }

    fun apply(
        owner: EpubAppearanceController?,
        inputs: ReaderAppearanceInputs,
        isCurrent: () -> Boolean,
        beforeReflow: () -> Unit,
    ): ReaderAppearanceResult {
        if (inputs.reset.saving || inputs.reset.completed) resetPending = true
        if (resetPending && inputs.reset.completed && inputs.failed) {
            resetRefreshFailed = true
            return ReaderAppearanceResult.RESET_FAILED
        }
        val resetFinished = resetPending && inputs.resetObserved
        if (resetPending && !resetFinished || owner == null || !inputs.loaded || !isCurrent())
            return ReaderAppearanceResult.WAITING
        val effective = inputs.effective()
        val changed = appliedOwner !== owner || appliedSettings != effective
        if (changed) {
            beforeReflow()
            if (!isCurrent()) return ReaderAppearanceResult.WAITING
            try { owner.apply(effective) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (!resetPending) throw failure
                resetRefreshFailed = true
                return ReaderAppearanceResult.RESET_FAILED
            }
            if (!isCurrent()) return ReaderAppearanceResult.WAITING
            appliedOwner = owner
            appliedSettings = effective
        }
        if (resetFinished) {
            resetPending = false
            resetRefreshFailed = false
            return ReaderAppearanceResult.RESET_APPLIED
        }
        return if (changed) ReaderAppearanceResult.APPLIED else ReaderAppearanceResult.UNCHANGED
    }
}

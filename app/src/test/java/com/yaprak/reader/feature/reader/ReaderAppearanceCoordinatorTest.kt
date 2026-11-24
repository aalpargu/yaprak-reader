package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.feature.settings.*
import com.yaprak.reader.reader.EpubAppearanceController
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ReaderAppearanceCoordinatorTest {
    private fun inputs() = ReaderAppearanceInputs(EpubSettingsState(loading = false), BookThemeState(loading = false),
        BookFontSizeState(loading = false), BookFontState(loading = false), BookMarginsState(loading = false),
        BookLineHeightState(loading = false), BookSpacingState(loading = false), BookTextAlignmentState(loading = false),
        BookReadingModeState(loading = false), BookAppearanceResetState())

    @Test fun savedBookOverridesUseExistingDomainPrecedenceAndEachNewSurfaceReceivesOneApplication() {
        val coordinator = ReaderAppearanceCoordinator()
        val applied = mutableListOf<EpubSettings>()
        val owner = EpubAppearanceController { applied += it }
        val input = inputs().let { it.copy(global = it.global.copy(saved = EpubSettings(fontSizePercent = 110, theme = ReadingTheme.SEPIA)),
            theme = it.theme.copy(saved = ReadingTheme.DARK), fontSize = it.fontSize.copy(saved = 150)) }
        assertEquals(ReaderAppearanceResult.APPLIED, coordinator.apply(owner, input, { true }, {}))
        assertEquals(150, applied.single().fontSizePercent)
        assertEquals(ReadingTheme.DARK, applied.single().theme)
        assertEquals(ReaderAppearanceResult.UNCHANGED, coordinator.apply(owner, input, { true }, {}))
        assertEquals(1, applied.size)
        val replacement = EpubAppearanceController { applied += it }
        assertEquals(ReaderAppearanceResult.APPLIED, coordinator.apply(replacement, input, { true }, {}))
        assertEquals(2, applied.size)
    }

    @Test fun committedResetWaitsForAllObservedDefaultsThenAcknowledgesOneReflow() {
        val coordinator = ReaderAppearanceCoordinator()
        var applications = 0
        val owner = EpubAppearanceController { applications++ }
        coordinator.beginReset()
        val ready = inputs().copy(reset = BookAppearanceResetState(completed = true))
        assertEquals(ReaderAppearanceResult.WAITING, coordinator.apply(owner,
            ready.copy(mode = ready.mode.copy(saved = BookReadingMode.PAGED)), { true }, {}))
        assertTrue(coordinator.resetPending)
        assertEquals(0, applications)
        assertEquals(ReaderAppearanceResult.WAITING, coordinator.apply(owner,
            ready.copy(font = ready.font.copy(loading = true)), { true }, {}))
        assertEquals(ReaderAppearanceResult.RESET_APPLIED, coordinator.apply(owner, ready, { true }, {}))
        assertFalse(coordinator.resetPending || coordinator.resetRefreshFailed)
        assertEquals(1, applications)
    }

    @Test fun failedResetRefreshRetainsRecoveryAndNeverAppliesMixedOrFailedSavedValues() {
        val coordinator = ReaderAppearanceCoordinator()
        var applications = 0
        val owner = EpubAppearanceController { applications++ }
        coordinator.beginReset()
        val ready = inputs().copy(reset = BookAppearanceResetState(completed = true))
        assertEquals(ReaderAppearanceResult.RESET_FAILED, coordinator.apply(owner,
            ready.copy(alignment = ready.alignment.copy(loadFailed = true)), { true }, {}))
        assertTrue(coordinator.resetPending && coordinator.resetRefreshFailed)
        assertEquals(0, applications)
        assertEquals(ReaderAppearanceResult.RESET_APPLIED, coordinator.apply(owner, ready, { true }, {}))
        assertEquals(1, applications)
        assertFalse(coordinator.resetRefreshFailed)
    }

    @Test fun reflowInvalidatingTheOwnerCannotApplyOrAcknowledgeACommittedReset() {
        val coordinator = ReaderAppearanceCoordinator()
        var current = true
        var applications = 0
        coordinator.beginReset()
        val input = inputs().copy(reset = BookAppearanceResetState(completed = true))
        assertEquals(ReaderAppearanceResult.WAITING, coordinator.apply(EpubAppearanceController { applications++ }, input,
            { current }, { current = false }))
        assertEquals(0, applications)
        assertTrue(coordinator.resetPending)
    }

    @Test fun cancellationIsNotConvertedIntoAResetFailure() {
        val coordinator = ReaderAppearanceCoordinator()
        coordinator.beginReset()
        try {
            coordinator.apply(EpubAppearanceController { throw CancellationException("paused") },
                inputs().copy(reset = BookAppearanceResetState(completed = true)), { true }, {})
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(coordinator.resetPending)
        assertFalse(coordinator.resetRefreshFailed)
    }
}

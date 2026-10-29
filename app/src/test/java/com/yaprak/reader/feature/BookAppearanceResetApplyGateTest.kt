package com.yaprak.reader.feature

import com.yaprak.reader.domain.settings.BookLineHeightChoice
import com.yaprak.reader.domain.settings.BookSpacingChoice
import com.yaprak.reader.domain.settings.BookSpacingSettings
import com.yaprak.reader.feature.settings.BookSpacingState
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.feature.settings.BookAppearanceResetState
import com.yaprak.reader.feature.settings.BookFontSizeState
import com.yaprak.reader.feature.settings.BookFontState
import com.yaprak.reader.feature.settings.BookLineHeightState
import com.yaprak.reader.feature.settings.BookMarginsState
import com.yaprak.reader.feature.settings.BookThemeState
import com.yaprak.reader.feature.settings.appearanceResetObserved
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookAppearanceResetApplyGateTest {
    @Test fun resetWaitsForActualReadingModeSourceAndDoesNotTreatExplicitPagedAsInheritance() {
        fun ready(mode: com.yaprak.reader.feature.settings.BookReadingModeState) = appearanceResetObserved(
            BookAppearanceResetState(completed = true), BookThemeState(loading = false), BookFontSizeState(loading = false),
            BookFontState(loading = false), BookMarginsState(loading = false), BookLineHeightState(loading = false),
            BookSpacingState(loading = false), com.yaprak.reader.feature.settings.BookTextAlignmentState(loading = false), mode)
        val empty = com.yaprak.reader.feature.settings.BookReadingModeState(loading = false)
        assertFalse(ready(empty.copy(loading = true)))
        assertFalse(ready(empty.copy(loadFailed = true)))
        com.yaprak.reader.domain.settings.BookReadingMode.entries.forEach { assertFalse(ready(empty.copy(saved = it))) }
        assertTrue(ready(empty))
    }
    @Test fun resetWaitsForActualAlignmentFlowIncludingExplicitPublisherBeforeApplyingInheritedSettings() {
        fun ready(alignment: com.yaprak.reader.feature.settings.BookTextAlignmentState) = appearanceResetObserved(
            BookAppearanceResetState(completed = true), BookThemeState(loading = false), BookFontSizeState(loading = false),
            BookFontState(loading = false), BookMarginsState(loading = false), BookLineHeightState(loading = false),
            BookSpacingState(loading = false), alignment)
        val empty = com.yaprak.reader.feature.settings.BookTextAlignmentState(loading = false)
        assertFalse(ready(empty.copy(loading = true)))
        assertFalse(ready(empty.copy(loadFailed = true)))
        com.yaprak.reader.domain.settings.ReadingTextAlignment.entries.forEach { assertFalse(ready(empty.copy(saved = it))) }
        assertTrue(ready(empty))
    }
    @Test fun resetWaitsForBothSpacingChoicesToBeObservedAsInherited() {
        val done = BookAppearanceResetState(completed = true)
        fun ready(spacing: BookSpacingState) = appearanceResetObserved(done,
            BookThemeState(loading = false), BookFontSizeState(loading = false), BookFontState(loading = false),
            BookMarginsState(loading = false), BookLineHeightState(loading = false), spacing)
        val empty = BookSpacingState(loading = false)
        assertFalse(ready(empty.copy(loading = true)))
        assertFalse(ready(empty.copy(loadFailed = true)))
        assertFalse(ready(empty.copy(saved = BookSpacingSettings(paragraphs = BookSpacingChoice.Publisher))))
        assertFalse(ready(empty.copy(saved = BookSpacingSettings(words = BookSpacingChoice.Custom(0)))))
        assertTrue(ready(empty))
    }
    @Test fun `commit waits for all five observed defaults before one reflow`() {
        val completed = BookAppearanceResetState(completed = true)
        val theme = BookThemeState(loading = false)
        val size = BookFontSizeState(loading = false)
        val font = BookFontState(loading = false)
        val margins = BookMarginsState(loading = false)
        val spacing = BookLineHeightState(loading = false)
        assertFalse(appearanceResetObserved(BookAppearanceResetState(), theme, size, font, margins, spacing))
        assertFalse(appearanceResetObserved(completed, theme, size.copy(loading = true), font, margins, spacing))
        assertFalse(appearanceResetObserved(completed, theme, size, font.copy(loadFailed = true), margins, spacing))
        assertFalse(appearanceResetObserved(completed, theme, size, font.copy(saved = ReadingFont.SERIF), margins, spacing))
        assertFalse(appearanceResetObserved(completed, theme, size, font, margins,
            spacing.copy(saved = BookLineHeightChoice.Publisher)))
        assertTrue(appearanceResetObserved(completed, theme, size, font, margins, spacing))
    }
}

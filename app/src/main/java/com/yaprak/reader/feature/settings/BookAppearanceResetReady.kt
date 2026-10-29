package com.yaprak.reader.feature.settings

import com.yaprak.reader.domain.settings.BookLineHeightChoice
import com.yaprak.reader.domain.settings.BookSpacingSettings

/** Wait for every independent Room flow before applying one committed reset to Readium. */
internal fun appearanceResetObserved(
    reset: BookAppearanceResetState,
    theme: BookThemeState,
    fontSize: BookFontSizeState,
    font: BookFontState,
    margins: BookMarginsState,
    lineHeight: BookLineHeightState,
    spacing: BookSpacingState = BookSpacingState(loading = false),
    textAlignment: BookTextAlignmentState = BookTextAlignmentState(loading = false),
    readingMode: BookReadingModeState = BookReadingModeState(loading = false),
): Boolean = reset.completed &&
    !theme.loading && !theme.loadFailed && theme.saved == null &&
    !fontSize.loading && !fontSize.loadFailed && fontSize.saved == null &&
    !font.loading && !font.loadFailed && font.saved == null &&
    !margins.loading && !margins.loadFailed && margins.saved == null &&
    !lineHeight.loading && !lineHeight.loadFailed && lineHeight.saved == BookLineHeightChoice.Inherit &&
    !spacing.loading && !spacing.loadFailed && spacing.saved == BookSpacingSettings() &&
    !textAlignment.loading && !textAlignment.loadFailed && textAlignment.saved == null &&
    !readingMode.loading && !readingMode.loadFailed && readingMode.saved == null

@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.reader.EpubImageFilterMode
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.ImageFilter
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

/** Ask the pinned SDK's publication/layout rules before enabling its advanced alignment styles. */
internal fun EpubNavigatorFactory.supportsTextAlignment(): Boolean = createPreferencesEditor(
    EpubPreferences(publisherStyles = false, textAlign = TextAlign.START),
).textAlign.isEffective

internal data class EpubSpacingSupport(val paragraphs: Boolean = true, val words: Boolean = true)

/** Use the publication's pinned SDK rules; word spacing is unavailable for RTL/CJK stylesheets. */
internal fun EpubNavigatorFactory.spacingSupport(): EpubSpacingSupport {
    val editor = createPreferencesEditor(EpubPreferences(
        publisherStyles = false, paragraphSpacing = 1.0, wordSpacing = 0.2,
    ))
    return EpubSpacingSupport(editor.paragraphSpacing.isEffective, editor.wordSpacing.isEffective)
}

internal fun EpubSettings.toReadium(
    darkSystem: Boolean,
    imageFilterMode: EpubImageFilterMode = EpubImageFilterMode.ORIGINAL,
    textAlignmentSupported: Boolean = true,
    spacingSupport: EpubSpacingSupport = EpubSpacingSupport(),
): EpubPreferences {
    val effectiveTheme = when (theme) {
        ReadingTheme.SYSTEM -> if (darkSystem) Theme.DARK else Theme.LIGHT
        ReadingTheme.LIGHT -> Theme.LIGHT
        ReadingTheme.SEPIA -> Theme.SEPIA
        ReadingTheme.DARK -> Theme.DARK
        ReadingTheme.AMOLED -> Theme.DARK
    }
    val effectiveAlignment = if (!textAlignmentSupported) null else when (textAlignment) {
        ReadingTextAlignment.PUBLISHER -> null
        ReadingTextAlignment.START -> TextAlign.START
        ReadingTextAlignment.JUSTIFY -> TextAlign.JUSTIFY
    }
    val paragraphSpacing = paragraphSpacingPercent?.takeIf { spacingSupport.paragraphs }?.div(100.0)
    val wordSpacing = wordSpacingPercent?.takeIf { spacingSupport.words }?.div(100.0)
    return EpubPreferences(
        fontSize = fontSizePercent / 100.0,
        fontFamily = when (font) {
            ReadingFont.ORIGINAL -> null
            ReadingFont.SERIF -> FontFamily.SERIF
            ReadingFont.SANS_SERIF -> FontFamily.SANS_SERIF
        },
        lineHeight = lineHeightPercent?.div(100.0),
        pageMargins = marginsPercent / 100.0,
        publisherStyles = lineHeightPercent == null && effectiveAlignment == null &&
            paragraphSpacing == null && wordSpacing == null,
        textAlign = effectiveAlignment,
        paragraphSpacing = paragraphSpacing,
        wordSpacing = wordSpacing,
        scroll = scroll,
        theme = effectiveTheme,
        backgroundColor = if (theme == ReadingTheme.AMOLED) Color(0xFF000000.toInt()) else null,
        textColor = if (theme == ReadingTheme.AMOLED) Color(0xFFFFFFFF.toInt()) else null,
        imageFilter = if (effectiveTheme != Theme.DARK) null else when (imageFilterMode) {
            EpubImageFilterMode.ORIGINAL -> null
            EpubImageFilterMode.DARKEN -> ImageFilter.DARKEN
            EpubImageFilterMode.INVERT -> ImageFilter.INVERT
        },
    )
}

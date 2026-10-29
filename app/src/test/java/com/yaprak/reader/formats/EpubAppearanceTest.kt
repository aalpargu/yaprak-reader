@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats

import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.formats.epub.toReadium
import com.yaprak.reader.reader.EpubImageFilterMode
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ImageFilter
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.junit.Assert.*
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubAppearanceTest {
    @Test fun `alignment controls advanced styles independently of publisher or custom line height`() {
        for ((alignment, sdkAlignment) in listOf(
            ReadingTextAlignment.PUBLISHER to null,
            ReadingTextAlignment.START to TextAlign.START,
            ReadingTextAlignment.JUSTIFY to TextAlign.JUSTIFY,
        )) {
            for (lineHeight in listOf(null, 140)) {
                val settings = EpubSettings(145, ReadingFont.SERIF, lineHeight, 120, ReadingTheme.DARK,
                    scroll = true, textAlignment = alignment)
                val publisher = settings.copy(textAlignment = ReadingTextAlignment.PUBLISHER)
                    .toReadium(false, EpubImageFilterMode.INVERT)
                val preferences = settings.toReadium(false, EpubImageFilterMode.INVERT)
                assertEquals(publisher.copy(textAlign = sdkAlignment,
                    publisherStyles = lineHeight == null && alignment == ReadingTextAlignment.PUBLISHER), preferences)
                assertEquals(sdkAlignment, preferences.textAlign)
                assertEquals(lineHeight?.div(100.0), preferences.lineHeight)
                assertEquals(lineHeight == null && sdkAlignment == null, preferences.publisherStyles)
                assertNull(preferences.paragraphIndent)
                assertNull(preferences.paragraphSpacing)
                assertNull(preferences.typeScale)
                assertNull(preferences.hyphens)
                val restored = settings.copy(textAlignment = ReadingTextAlignment.PUBLISHER)
                    .toReadium(false, EpubImageFilterMode.INVERT)
                assertEquals(publisher, restored)
            }
        }
    }

    @Test fun `unsupported alignment preserves the existing publisher and custom line height behavior`() {
        for (alignment in ReadingTextAlignment.entries) {
            for (lineHeight in listOf(null, 160)) {
                val settings = EpubSettings(155, ReadingFont.SANS_SERIF, lineHeight, 80, ReadingTheme.DARK,
                    scroll = true, textAlignment = alignment)
                assertEquals(settings.copy(textAlignment = ReadingTextAlignment.PUBLISHER)
                    .toReadium(false, EpubImageFilterMode.DARKEN),
                    settings.toReadium(false, EpubImageFilterMode.DARKEN, textAlignmentSupported = false))
                assertEquals(alignment, settings.textAlignment)
            }
        }
    }

    @Test fun `book publisher line height leaves explicit global alignment active`() {
        val global = EpubSettings(lineHeightPercent = 180, textAlignment = ReadingTextAlignment.START)
        val preferences = global.withBookLineHeight(BookLineHeightChoice.Publisher).toReadium(false)
        assertNull(preferences.lineHeight)
        assertEquals(TextAlign.START, preferences.textAlign)
        assertEquals(false, preferences.publisherStyles)
        assertEquals(true, global.copy(textAlignment = ReadingTextAlignment.PUBLISHER)
            .withBookLineHeight(BookLineHeightChoice.Publisher).toReadium(false).publisherStyles)
    }

    @Test fun `default and original mode leave images unchanged in every theme`() {
        for (theme in ReadingTheme.entries) {
            for (darkSystem in listOf(false, true)) {
                val settings = EpubSettings(theme = theme)
                assertNull(settings.toReadium(darkSystem).imageFilter)
                assertEquals(settings.toReadium(darkSystem),
                    settings.toReadium(darkSystem, EpubImageFilterMode.ORIGINAL))
            }
        }
    }

    @Test fun `image filters follow the effective EPUB theme including system preference`() {
        for ((mode, sdkFilter) in listOf(
            EpubImageFilterMode.DARKEN to ImageFilter.DARKEN,
            EpubImageFilterMode.INVERT to ImageFilter.INVERT,
        )) {
            for (darkSystem in listOf(false, true)) {
                assertEquals(sdkFilter, EpubSettings(theme = ReadingTheme.DARK)
                    .toReadium(darkSystem, mode).imageFilter)
                assertNull(EpubSettings(theme = ReadingTheme.LIGHT)
                    .toReadium(darkSystem, mode).imageFilter)
                assertNull(EpubSettings(theme = ReadingTheme.SEPIA)
                    .toReadium(darkSystem, mode).imageFilter)
                assertEquals(if (darkSystem) sdkFilter else null,
                    EpubSettings(theme = ReadingTheme.SYSTEM).toReadium(darkSystem, mode).imageFilter)
            }
        }
    }

    @Test fun `image choice preserves complete effective book appearance and scroll`() {
        val settings = EpubSettings(155, ReadingFont.SERIF, 180, 150, ReadingTheme.LIGHT, scroll = true,
            textAlignment = ReadingTextAlignment.JUSTIFY)
            .withBookTheme(ReadingTheme.DARK).withBookFontSize(100)
            .withBookFont(ReadingFont.SANS_SERIF).withBookMargins(80)
            .withBookLineHeight(BookLineHeightChoice.Custom(140))
        val original = settings.toReadium(false)
        for ((mode, filter) in listOf(
            EpubImageFilterMode.DARKEN to ImageFilter.DARKEN,
            EpubImageFilterMode.INVERT to ImageFilter.INVERT,
        )) {
            val filtered = settings.toReadium(false, mode)
            assertEquals(original.copy(imageFilter = filter), filtered)
            assertEquals(1.0, filtered.fontSize!!, 0.001)
            assertEquals(FontFamily.SANS_SERIF, filtered.fontFamily)
            assertEquals(0.8, filtered.pageMargins!!, 0.001)
            assertEquals(1.4, filtered.lineHeight!!, 0.001)
            assertEquals(false, filtered.publisherStyles)
            assertEquals(true, filtered.scroll)
            assertEquals(TextAlign.JUSTIFY, filtered.textAlign)
        }
        assertNull(settings.withBookTheme(ReadingTheme.SEPIA)
            .toReadium(true, EpubImageFilterMode.INVERT).imageFilter)
    }

    @Test fun `settings map to complete SDK preferences including restoration of publisher styles`() {
        val custom = EpubSettings(150, ReadingFont.SANS_SERIF, 180, 150, ReadingTheme.SEPIA, scroll = true).toReadium(false)
        assertEquals(1.5, custom.fontSize!!, 0.001)
        assertEquals(FontFamily.SANS_SERIF, custom.fontFamily)
        assertEquals(1.8, custom.lineHeight!!, 0.001)
        assertEquals(1.5, custom.pageMargins!!, 0.001)
        assertEquals(Theme.SEPIA, custom.theme)
        assertEquals(false, custom.publisherStyles)
        assertEquals(true, custom.scroll)
        val reset = EpubSettings().toReadium(true)
        assertEquals(Theme.DARK, reset.theme)
        assertNull(reset.fontFamily)
        assertNull(reset.lineHeight)
        assertEquals(true, reset.publisherStyles)
        assertEquals(false, reset.scroll)
        assertEquals(Theme.LIGHT, EpubSettings().toReadium(false).theme)
        assertEquals(Theme.LIGHT, EpubSettings(theme = ReadingTheme.LIGHT).toReadium(true).theme)
        assertEquals(Theme.DARK, EpubSettings(theme = ReadingTheme.DARK).toReadium(false).theme)
    }

    @Test fun `changing scroll mode changes only Readium scroll preference and can restore pagination`() {
        val settings = EpubSettings(145, ReadingFont.SERIF, 160, 120, ReadingTheme.DARK)
        val paginated = settings.toReadium(false)
        val scrolling = settings.copy(scroll = true).toReadium(false)
        assertEquals(false, paginated.scroll)
        assertEquals(true, scrolling.scroll)
        assertEquals(paginated.copy(scroll = true), scrolling)
        assertEquals(paginated, settings.copy(scroll = false).toReadium(false))
    }

    @Test fun `book publisher choice restores Readium publisher styles over a global percentage`() {
        val global = EpubSettings(lineHeightPercent = 180)
        val inherited = global.withBookLineHeight(BookLineHeightChoice.Inherit).toReadium(false)
        assertEquals(1.8, inherited.lineHeight!!, 0.001)
        assertEquals(false, inherited.publisherStyles)

        val publisher = global.withBookLineHeight(BookLineHeightChoice.Publisher).toReadium(false)
        assertNull(publisher.lineHeight)
        assertEquals(true, publisher.publisherStyles)

        val custom = EpubSettings().withBookLineHeight(BookLineHeightChoice.Custom(100)).toReadium(false)
        assertEquals(1.0, custom.lineHeight!!, 0.001)
        assertEquals(false, custom.publisherStyles)
    }
}

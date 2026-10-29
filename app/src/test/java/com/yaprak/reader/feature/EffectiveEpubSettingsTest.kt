package com.yaprak.reader.feature

import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.BookLineHeightChoice
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.domain.settings.ReadingTheme
import com.yaprak.reader.domain.settings.ReadingTextAlignment
import com.yaprak.reader.domain.settings.withBookTheme
import com.yaprak.reader.domain.settings.withBookFontSize
import com.yaprak.reader.domain.settings.withBookFont
import com.yaprak.reader.domain.settings.withBookMargins
import com.yaprak.reader.domain.settings.withBookLineHeight
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveEpubSettingsTest {
    @Test fun `global alignment survives all book overrides including publisher line height`() {
        for (alignment in ReadingTextAlignment.entries) {
            val global = EpubSettings(155, ReadingFont.SERIF, 180, 150, ReadingTheme.SEPIA,
                scroll = true, textAlignment = alignment)
            val inherited = global.withBookTheme(null).withBookFontSize(null).withBookFont(null)
                .withBookMargins(null).withBookLineHeight(BookLineHeightChoice.Inherit)
            assertEquals(global, inherited)
            val custom = global.withBookTheme(ReadingTheme.DARK).withBookFontSize(100)
                .withBookFont(ReadingFont.ORIGINAL).withBookMargins(80)
                .withBookLineHeight(BookLineHeightChoice.Custom(140))
            assertEquals(global.copy(theme = ReadingTheme.DARK, fontSizePercent = 100,
                font = ReadingFont.ORIGINAL, marginsPercent = 80, lineHeightPercent = 140), custom)
            assertEquals(alignment, custom.withBookLineHeight(BookLineHeightChoice.Publisher).textAlignment)
            assertEquals(custom.copy(lineHeightPercent = null),
                custom.withBookLineHeight(BookLineHeightChoice.Publisher))
            assertEquals(custom.copy(textAlignment = ReadingTextAlignment.PUBLISHER),
                global.copy(textAlignment = ReadingTextAlignment.PUBLISHER).withBookTheme(ReadingTheme.DARK)
                    .withBookFontSize(100).withBookFont(ReadingFont.ORIGINAL).withBookMargins(80)
                    .withBookLineHeight(BookLineHeightChoice.Custom(140)))
        }
    }

    @Test fun `global scroll mode survives every book override and inheritance choice`() {
        for (scroll in listOf(false, true)) {
            val global = EpubSettings(155, ReadingFont.SERIF, 180, 150, ReadingTheme.SEPIA, scroll = scroll)
            val inherited = global.withBookTheme(null).withBookFontSize(null).withBookFont(null)
                .withBookMargins(null).withBookLineHeight(BookLineHeightChoice.Inherit)
            assertEquals(global, inherited)
            val overridden = global.withBookTheme(ReadingTheme.DARK).withBookFontSize(100)
                .withBookFont(ReadingFont.ORIGINAL).withBookMargins(80)
                .withBookLineHeight(BookLineHeightChoice.Custom(140))
            assertEquals(global.copy(theme = ReadingTheme.DARK, fontSizePercent = 100,
                font = ReadingFont.ORIGINAL, marginsPercent = 80, lineHeightPercent = 140), overridden)
            assertEquals(overridden.copy(lineHeightPercent = null),
                overridden.withBookLineHeight(BookLineHeightChoice.Publisher))
            // Changing only the global mode is effective even when all book choices remain explicit.
            assertEquals(overridden.copy(scroll = !scroll),
                global.copy(scroll = !scroll).withBookTheme(ReadingTheme.DARK).withBookFontSize(100)
                    .withBookFont(ReadingFont.ORIGINAL).withBookMargins(80)
                    .withBookLineHeight(BookLineHeightChoice.Custom(140)))
        }
    }

    @Test fun `book theme overrides only theme while absence inherits changing global settings`() {
        val global = EpubSettings(155, ReadingFont.SERIF, 180, 150, ReadingTheme.SEPIA)
        assertEquals(global, global.withBookTheme(null))
        assertEquals(global.copy(theme = ReadingTheme.SYSTEM), global.withBookTheme(ReadingTheme.SYSTEM))
        val changedGlobal = global.copy(fontSizePercent = 175, theme = ReadingTheme.DARK)
        assertEquals(changedGlobal, changedGlobal.withBookTheme(null))
        assertEquals(changedGlobal.copy(theme = ReadingTheme.LIGHT), changedGlobal.withBookTheme(ReadingTheme.LIGHT))
    }

    @Test fun `book font size is independent of theme and inherits current global size when absent`() {
        val global = EpubSettings(155, ReadingFont.SERIF, 180, 150, ReadingTheme.SEPIA)
        val explicit = global.withBookTheme(ReadingTheme.DARK).withBookFontSize(100)
        assertEquals(global.copy(fontSizePercent = 100, theme = ReadingTheme.DARK), explicit)
        val changedGlobal = global.copy(fontSizePercent = 175, font = ReadingFont.SANS_SERIF,
            lineHeightPercent = null, marginsPercent = 80, theme = ReadingTheme.LIGHT)
        assertEquals(changedGlobal.copy(theme = ReadingTheme.DARK),
            changedGlobal.withBookTheme(ReadingTheme.DARK).withBookFontSize(null))
        assertEquals(changedGlobal.copy(fontSizePercent = 100, theme = ReadingTheme.DARK),
            changedGlobal.withBookTheme(ReadingTheme.DARK).withBookFontSize(100))
        assertEquals(changedGlobal.copy(fontSizePercent = 100),
            changedGlobal.withBookTheme(null).withBookFontSize(100))
    }

    @Test fun `book font family is independent of theme and size and inherits current global family`() {
        val global = EpubSettings(155, ReadingFont.SERIF, 180, 150, ReadingTheme.SEPIA)
        assertEquals(global.copy(font = ReadingFont.ORIGINAL, theme = ReadingTheme.DARK, fontSizePercent = 100),
            global.withBookTheme(ReadingTheme.DARK).withBookFontSize(100).withBookFont(ReadingFont.ORIGINAL))
        val changed = global.copy(font = ReadingFont.SANS_SERIF, marginsPercent = 90)
        assertEquals(changed.copy(theme = ReadingTheme.DARK, fontSizePercent = 100),
            changed.withBookTheme(ReadingTheme.DARK).withBookFontSize(100).withBookFont(null))
        assertEquals(changed.copy(font = ReadingFont.ORIGINAL), changed.withBookFont(ReadingFont.ORIGINAL))
    }

    @Test fun `book margins override only margins and inherit changing global value when absent`() {
        val global = EpubSettings(155, ReadingFont.SERIF, 180, 150, ReadingTheme.SEPIA)
        val explicit = global.withBookTheme(ReadingTheme.DARK).withBookFontSize(100)
            .withBookFont(ReadingFont.ORIGINAL).withBookMargins(80)
        assertEquals(global.copy(theme = ReadingTheme.DARK, fontSizePercent = 100,
            font = ReadingFont.ORIGINAL, marginsPercent = 80), explicit)
        val changedGlobal = global.copy(marginsPercent = 120, lineHeightPercent = null)
        assertEquals(changedGlobal.copy(theme = ReadingTheme.DARK),
            changedGlobal.withBookTheme(ReadingTheme.DARK).withBookMargins(null))
    }

    @Test fun `book line height distinguishes current global publisher style and custom percent`() {
        val global = EpubSettings(155, ReadingFont.SERIF, 180, 150, ReadingTheme.SEPIA)
        assertEquals(global, global.withBookLineHeight(BookLineHeightChoice.Inherit))
        assertEquals(global.copy(lineHeightPercent = null),
            global.withBookLineHeight(BookLineHeightChoice.Publisher))
        assertEquals(global.copy(lineHeightPercent = 100),
            global.withBookLineHeight(BookLineHeightChoice.Custom(100)))

        val changedGlobal = global.copy(lineHeightPercent = 210, marginsPercent = 90)
        assertEquals(changedGlobal, changedGlobal.withBookLineHeight(BookLineHeightChoice.Inherit))
        assertEquals(changedGlobal.copy(lineHeightPercent = null),
            changedGlobal.withBookLineHeight(BookLineHeightChoice.Publisher))
        assertEquals(changedGlobal.copy(lineHeightPercent = 140, marginsPercent = 80),
            changedGlobal.withBookMargins(80).withBookLineHeight(BookLineHeightChoice.Custom(140)))

        val publisherGlobal = changedGlobal.copy(lineHeightPercent = null)
        assertEquals(publisherGlobal, publisherGlobal.withBookLineHeight(BookLineHeightChoice.Inherit))
        assertEquals(publisherGlobal.copy(lineHeightPercent = 140),
            publisherGlobal.withBookLineHeight(BookLineHeightChoice.Custom(140)))
    }
}

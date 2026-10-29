package com.yaprak.reader.feature

import com.yaprak.reader.domain.settings.*
import org.junit.Assert.*
import org.junit.Test

class BookSpacingSettingsTest {
    @Test fun modesApplyIndependentlyAndKeepOtherPreferences() {
        val global = EpubSettings(fontSizePercent = 155, theme = ReadingTheme.AMOLED, scroll = true,
            paragraphSpacingPercent = 150, wordSpacingPercent = 20)
        val choices = BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Custom(0))
        assertEquals(global.copy(paragraphSpacingPercent = null, wordSpacingPercent = 0), global.withBookSpacing(choices))
        assertEquals(global, global.withBookSpacing(BookSpacingSettings()))
        val mixed = BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Inherit)
        assertEquals(global.copy(paragraphSpacingPercent = 200), global.withBookSpacing(mixed))
        assertEquals(global.copy(wordSpacingPercent = 100, paragraphSpacingPercent = 200),
            global.copy(wordSpacingPercent = 100).withBookSpacing(mixed))
    }
    @Test fun ownedRangesStepsAndZeroRemainStrict() {
        assertEquals(BookSpacingChoice.Custom(0), BookSpacingChoice.Custom(0))
        for (invalid in listOf(-10, 1, 11, 199, 201, 210, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { BookSpacingChoice.Custom(invalid) }
        }
        assertEquals(BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Custom(100)),
            BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Custom(100)))
        assertThrows(IllegalArgumentException::class.java) {
            BookSpacingSettings(words = BookSpacingChoice.Custom(110))
        }
    }
}

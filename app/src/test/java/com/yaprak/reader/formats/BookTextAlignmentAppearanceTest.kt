@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.formats.epub.EpubAppearanceBinding
import com.yaprak.reader.formats.epub.toReadium
import com.yaprak.reader.reader.EpubImageFilterMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.TextAlign
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookTextAlignmentAppearanceTest {
    @Test fun inheritanceTracksLiveGlobalWhileExplicitPublisherAndStartStayBookSpecific() {
        val global = EpubSettings(155, ReadingFont.SERIF, 180, 80, ReadingTheme.AMOLED,
            scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY, paragraphSpacingPercent = 0, wordSpacingPercent = 100)
        assertSame(global, global.withBookTextAlignment(null))
        for (choice in ReadingTextAlignment.entries) {
            val expected = global.copy(textAlignment = choice)
            assertEquals(expected, global.withBookTextAlignment(choice))
            val changed = global.copy(textAlignment = ReadingTextAlignment.START, marginsPercent = 120)
            assertEquals(changed, changed.withBookTextAlignment(null))
            assertEquals(changed.copy(textAlignment = choice), changed.withBookTextAlignment(choice))
        }
        assertEquals(ReadingTextAlignment.JUSTIFY, global.textAlignment)
    }

    @Test fun actualPinnedSdkReceivesBookAlignmentAndKeepsOtherTypographyThemeScrollAndImageFilter() {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false, true) { submitted += it }
        val global = EpubSettings(145, ReadingFont.SANS_SERIF, 160, 150, ReadingTheme.DARK,
            scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY, paragraphSpacingPercent = 80, wordSpacingPercent = 0)
        binding.applyImageFilter(EpubImageFilterMode.INVERT)
        for ((choice, align) in listOf(ReadingTextAlignment.PUBLISHER to null,
            ReadingTextAlignment.START to TextAlign.START, ReadingTextAlignment.JUSTIFY to TextAlign.JUSTIFY)) {
            binding.applySettings(global.withBookTextAlignment(choice))
            val expected = global.toReadium(false, EpubImageFilterMode.INVERT).copy(textAlign = align)
            assertEquals(expected, submitted.last())
            assertEquals(false, submitted.last().publisherStyles)
        }
        // With no other advanced overrides, explicit publisher alignment restores SDK publisher styles.
        binding.applySettings(EpubSettings(textAlignment = ReadingTextAlignment.JUSTIFY)
            .withBookTextAlignment(ReadingTextAlignment.PUBLISHER))
        assertNull(submitted.last().textAlign)
        assertEquals(true, submitted.last().publisherStyles)
        binding.applySettings(global.withBookTextAlignment(null))
        assertEquals(global.toReadium(false, EpubImageFilterMode.INVERT), submitted.last())
    }

    @Test fun unsupportedPublicationKeepsSavedChoiceButDoesNotForceAnUnsupportedSdkAlignment() {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false, false) { submitted += it }
        for (choice in ReadingTextAlignment.entries) {
            val global = EpubSettings(textAlignment = ReadingTextAlignment.JUSTIFY, scroll = true, marginsPercent = 80)
            val effective = global.withBookTextAlignment(choice)
            binding.applySettings(effective)
            assertEquals(choice, effective.textAlignment)
            assertNull(submitted.last().textAlign)
            assertEquals(true, submitted.last().publisherStyles)
            assertEquals(true, submitted.last().scroll)
            assertEquals(.8, submitted.last().pageMargins!!, .0001)
        }
    }
}

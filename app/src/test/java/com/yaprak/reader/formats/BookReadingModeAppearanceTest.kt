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
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookReadingModeAppearanceTest {
    @Test fun inheritanceTracksLiveGlobalWhileBothExplicitModesChangeOnlyScroll() {
        val global = EpubSettings(155, ReadingFont.SERIF, 180, 80, ReadingTheme.AMOLED,
            scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY, paragraphSpacingPercent = 0, wordSpacingPercent = 100)
        assertSame(global, global.withBookReadingMode(null))
        for ((mode, scroll) in listOf(BookReadingMode.PAGED to false, BookReadingMode.SCROLL to true)) {
            assertEquals(global.copy(scroll = scroll), global.withBookReadingMode(mode))
            val changed = global.copy(scroll = false, marginsPercent = 120)
            assertEquals(changed, changed.withBookReadingMode(null))
            assertEquals(changed.copy(scroll = scroll), changed.withBookReadingMode(mode))
        }
        assertTrue(global.scroll)
    }

    @Test fun actualPinnedSdkReceivesBookScrollAndPreservesEveryOtherPreferenceAndImageFilter() {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false, true) { submitted += it }
        val global = EpubSettings(145, ReadingFont.SANS_SERIF, 160, 150, ReadingTheme.DARK,
            scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY, paragraphSpacingPercent = 80, wordSpacingPercent = 0)
        binding.applyImageFilter(EpubImageFilterMode.INVERT)
        for ((mode, scroll) in listOf(BookReadingMode.PAGED to false, BookReadingMode.SCROLL to true)) {
            binding.applySettings(global.withBookReadingMode(mode))
            assertEquals(global.toReadium(false, EpubImageFilterMode.INVERT).copy(scroll = scroll), submitted.last())
        }
        binding.applySettings(global.withBookReadingMode(null))
        assertEquals(global.toReadium(false, EpubImageFilterMode.INVERT), submitted.last())
    }

    @Test fun realLtrAndRtlReflowablePublicationEditorsAcceptTheActualBookModeWithoutForcingDirection() {
        for (direction in listOf(ReadingProgression.LTR, ReadingProgression.RTL)) {
            val publication = Publication(Manifest(metadata = Metadata(localizedTitle = LocalizedString("Mode test"),
                languages = listOf(if (direction == ReadingProgression.LTR) "en" else "ar"), readingProgression = direction),
                readingOrder = listOf(Link(Url("EPUB/chapter.xhtml")!!, MediaType.XHTML))))
            try {
                val factory = EpubNavigatorFactory(publication)
                for ((mode, scroll) in listOf(BookReadingMode.PAGED to false, BookReadingMode.SCROLL to true)) {
                    val preferences = EpubSettings().withBookReadingMode(mode).toReadium(false)
                    val editor = factory.createPreferencesEditor(preferences)
                    assertEquals(scroll, editor.scroll.effectiveValue)
                    assertNull(preferences.readingProgression)
                    assertNull(preferences.language)
                    assertEquals(true, preferences.publisherStyles)
                }
            } finally { publication.close() }
        }
    }
}

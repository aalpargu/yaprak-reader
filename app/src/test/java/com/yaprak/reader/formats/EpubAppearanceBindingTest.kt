@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.domain.settings.BookLineHeightChoice
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.domain.settings.ReadingTheme
import com.yaprak.reader.domain.settings.ReadingTextAlignment
import com.yaprak.reader.domain.settings.withBookFont
import com.yaprak.reader.domain.settings.withBookFontSize
import com.yaprak.reader.domain.settings.withBookLineHeight
import com.yaprak.reader.domain.settings.withBookMargins
import com.yaprak.reader.domain.settings.withBookTheme
import com.yaprak.reader.formats.epub.EpubAppearanceBinding
import com.yaprak.reader.formats.epub.toReadium
import com.yaprak.reader.reader.EpubImageFilterMode
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ImageFilter
import org.readium.r2.navigator.preferences.TextAlign
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubAppearanceBindingTest {
    @Test fun `alignment changes retain image choice and publisher alignment can clear advanced styles`() {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false) { submitted += it }
        val start = EpubSettings(145, ReadingFont.SERIF, null, 80, ReadingTheme.DARK,
            scroll = true, textAlignment = ReadingTextAlignment.START)
        binding.applySettings(start)
        binding.applyImageFilter(EpubImageFilterMode.INVERT)
        val justified = start.copy(textAlignment = ReadingTextAlignment.JUSTIFY)
        binding.applySettings(justified)
        assertEquals(justified.toReadium(false, EpubImageFilterMode.INVERT), submitted.last())
        assertEquals(TextAlign.JUSTIFY, submitted.last().textAlign)
        assertNull(submitted.last().lineHeight)
        assertEquals(false, submitted.last().publisherStyles)
        val publisher = start.copy(textAlignment = ReadingTextAlignment.PUBLISHER)
        binding.applySettings(publisher)
        assertEquals(publisher.toReadium(false, EpubImageFilterMode.INVERT), submitted.last())
        assertNull(submitted.last().textAlign)
        assertEquals(true, submitted.last().publisherStyles)
        assertEquals(ImageFilter.INVERT, submitted.last().imageFilter)
        assertEquals(4, submitted.size)
        binding.applySettings(publisher)
        assertEquals(4, submitted.size)
    }

    @Test fun `unsupported alignment stays inactive across later complete appearance submissions`() {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false, textAlignmentSupported = false) { submitted += it }
        val settings = EpubSettings(145, ReadingFont.SERIF, null, 120, ReadingTheme.DARK,
            textAlignment = ReadingTextAlignment.JUSTIFY)
        binding.applySettings(settings)
        binding.applyImageFilter(EpubImageFilterMode.DARKEN)
        val changed = settings.copy(textAlignment = ReadingTextAlignment.START, scroll = true)
        binding.applySettings(changed)
        assertEquals(changed.toReadium(false, EpubImageFilterMode.DARKEN, textAlignmentSupported = false),
            submitted.last())
        assertNull(submitted.last().textAlign)
        assertEquals(true, submitted.last().publisherStyles)
        assertNull(submitted.last().lineHeight)
        assertEquals(ImageFilter.DARKEN, submitted.last().imageFilter)
        assertEquals(true, submitted.last().scroll)
        assertEquals(ReadingTextAlignment.START, changed.textAlignment)
        assertEquals(3, submitted.size)
    }

    @Test fun `mode chosen before appearance waits for complete settings and last choice wins`() {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false) { submitted += it }
        binding.applyImageFilter(EpubImageFilterMode.DARKEN)
        binding.applyImageFilter(EpubImageFilterMode.INVERT)
        assertTrue(submitted.isEmpty())

        val effective = EpubSettings(155, ReadingFont.SERIF, 180, 150, ReadingTheme.LIGHT, scroll = true)
            .withBookTheme(ReadingTheme.DARK).withBookFontSize(100)
            .withBookFont(ReadingFont.SANS_SERIF).withBookMargins(80)
            .withBookLineHeight(BookLineHeightChoice.Publisher)
        binding.applySettings(effective)
        assertEquals(listOf(effective.toReadium(false, EpubImageFilterMode.INVERT)), submitted)
        assertNull(submitted.single().lineHeight)
        assertEquals(true, submitted.single().publisherStyles)
        assertEquals(true, submitted.single().scroll)
    }

    @Test fun `filter changes retain all typography and returning to original clears SDK filter`() {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false) { submitted += it }
        val settings = EpubSettings(145, ReadingFont.SERIF, 160, 120, ReadingTheme.DARK, scroll = true)
        binding.applySettings(settings)
        binding.applyImageFilter(EpubImageFilterMode.DARKEN)
        binding.applyImageFilter(EpubImageFilterMode.INVERT)
        binding.applyImageFilter(EpubImageFilterMode.ORIGINAL)

        val original = settings.toReadium(false)
        assertEquals(listOf(original, original.copy(imageFilter = ImageFilter.DARKEN),
            original.copy(imageFilter = ImageFilter.INVERT), original), submitted)
    }

    @Test fun `later font margin line and scroll changes retain the selected filter`() {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false) { submitted += it }
        val settings = EpubSettings(145, ReadingFont.SERIF, 160, 120, ReadingTheme.DARK)
        binding.applySettings(settings)
        binding.applyImageFilter(EpubImageFilterMode.INVERT)
        val changed = settings.copy(fontSizePercent = 175, font = ReadingFont.SANS_SERIF,
            marginsPercent = 80, lineHeightPercent = null, scroll = true)
        binding.applySettings(changed)

        assertEquals(3, submitted.size)
        assertEquals(changed.toReadium(false, EpubImageFilterMode.INVERT), submitted.last())
        assertEquals(1.75, submitted.last().fontSize!!, 0.001)
        assertNull(submitted.last().lineHeight)
        assertEquals(true, submitted.last().publisherStyles)
        assertEquals(true, submitted.last().scroll)
    }

    @Test fun `non dark themes suppress filtering and the choice returns with dark theme`() {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false) { submitted += it }
        val dark = EpubSettings(theme = ReadingTheme.DARK)
        binding.applySettings(dark)
        binding.applyImageFilter(EpubImageFilterMode.DARKEN)
        binding.applySettings(dark.copy(theme = ReadingTheme.LIGHT))
        assertNull(submitted.last().imageFilter)
        binding.applySettings(dark.copy(theme = ReadingTheme.SEPIA))
        assertNull(submitted.last().imageFilter)
        binding.applySettings(dark)
        assertEquals(ImageFilter.DARKEN, submitted.last().imageFilter)
        assertEquals(5, submitted.size)
    }

    @Test fun `inactive and repeated choices deduplicate complete preferences while retaining intent`() {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false) { submitted += it }
        val light = EpubSettings(theme = ReadingTheme.LIGHT)
        binding.applySettings(light)
        binding.applySettings(light)
        binding.applyImageFilter(EpubImageFilterMode.DARKEN)
        binding.applyImageFilter(EpubImageFilterMode.INVERT)
        binding.applySettings(light)
        assertEquals(1, submitted.size)

        val dark = light.copy(theme = ReadingTheme.DARK)
        binding.applySettings(dark)
        binding.applyImageFilter(EpubImageFilterMode.INVERT)
        binding.applySettings(dark)
        assertEquals(2, submitted.size)
        assertEquals(dark.toReadium(false, EpubImageFilterMode.INVERT), submitted.last())
    }

    @Test fun `system dark theme applies pending choice and every new binding starts original`() {
        val submitted = mutableListOf<EpubPreferences>()
        val darkSystem = EpubAppearanceBinding(true) { submitted += it }
        darkSystem.applyImageFilter(EpubImageFilterMode.INVERT)
        darkSystem.applySettings(EpubSettings())
        assertEquals(ImageFilter.INVERT, submitted.single().imageFilter)

        val newSurface = EpubAppearanceBinding(true) { submitted += it }
        newSurface.applySettings(EpubSettings())
        assertNull(submitted.last().imageFilter)
        assertEquals(2, submitted.size)
    }

    @Test fun `synchronous first submission failure can retry unchanged complete inputs`() {
        val accepted = mutableListOf<EpubPreferences>()
        var attempts = 0
        val binding = EpubAppearanceBinding(false) { preferences ->
            attempts++
            if (attempts == 1) throw IllegalStateException("test submission failure")
            accepted += preferences
        }
        binding.applyImageFilter(EpubImageFilterMode.DARKEN)
        val dark = EpubSettings(theme = ReadingTheme.DARK, scroll = true)
        assertThrows(IllegalStateException::class.java) { binding.applySettings(dark) }
        assertTrue(accepted.isEmpty())
        binding.applySettings(dark)
        assertEquals(2, attempts)
        assertEquals(listOf(dark.toReadium(false, EpubImageFilterMode.DARKEN)), accepted)
        binding.applySettings(dark)
        assertEquals(2, attempts)
    }

    @Test fun `failed filter draft rolls back and only an explicit retry applies it`() {
        for (candidate in listOf(EpubImageFilterMode.INVERT, EpubImageFilterMode.ORIGINAL)) {
            val accepted = mutableListOf<EpubPreferences>()
            var reject = false
            var attempts = 0
            val binding = EpubAppearanceBinding(false) { preferences ->
                attempts++
                if (reject) throw IllegalStateException("test submission failure")
                accepted += preferences
            }
            val dark = EpubSettings(150, ReadingFont.SERIF, 180, 150, ReadingTheme.DARK,
                textAlignment = ReadingTextAlignment.START)
            binding.applySettings(dark)
            binding.applyImageFilter(EpubImageFilterMode.DARKEN)
            reject = true
            assertThrows(IllegalStateException::class.java) { binding.applyImageFilter(candidate) }
            assertEquals(2, accepted.size)
            assertEquals(ImageFilter.DARKEN, accepted.last().imageFilter)

            reject = false
            val changed = dark.copy(fontSizePercent = 175, scroll = true,
                textAlignment = ReadingTextAlignment.JUSTIFY)
            binding.applySettings(changed)
            assertEquals(changed.toReadium(false, EpubImageFilterMode.DARKEN), accepted.last())
            binding.applyImageFilter(candidate)
            assertEquals(changed.toReadium(false, candidate), accepted.last())
            assertEquals(5, attempts)
            binding.applyImageFilter(candidate)
            assertEquals(5, attempts)
        }
    }

    @Test fun `cancelled filter handoff restores the previous successful choice and rethrows`() {
        val accepted = mutableListOf<EpubPreferences>()
        val cancellation = CancellationException("test cancellation")
        var cancel = false
        val binding = EpubAppearanceBinding(false) { preferences ->
            if (cancel) throw cancellation
            accepted += preferences
        }
        val dark = EpubSettings(theme = ReadingTheme.DARK, textAlignment = ReadingTextAlignment.START)
        binding.applySettings(dark)
        binding.applyImageFilter(EpubImageFilterMode.DARKEN)
        cancel = true
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            binding.applyImageFilter(EpubImageFilterMode.INVERT)
        })
        cancel = false
        val changed = dark.copy(scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY)
        binding.applySettings(changed)
        assertEquals(changed.toReadium(false, EpubImageFilterMode.DARKEN), accepted.last())
    }
}

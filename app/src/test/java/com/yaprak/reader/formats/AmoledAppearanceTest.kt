@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats

import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.formats.epub.*
import com.yaprak.reader.reader.EpubImageFilterMode
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ImageFilter
import org.readium.r2.navigator.preferences.Theme
import org.junit.Assert.*
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AmoledAppearanceTest {
    @Test fun amoledUsesOpaqueBlackWhiteTextAndDarkImagesInBothSystemModes() {
        for (system in listOf(false, true)) for (mode in EpubImageFilterMode.entries) {
            val preferences = EpubSettings(theme = ReadingTheme.AMOLED).toReadium(system, mode)
            assertEquals(Theme.DARK, preferences.theme)
            assertEquals(0xFF000000.toInt(), preferences.backgroundColor!!.int)
            assertEquals(0xFFFFFFFF.toInt(), preferences.textColor!!.int)
            assertEquals(when (mode) {
                EpubImageFilterMode.ORIGINAL -> null
                EpubImageFilterMode.DARKEN -> ImageFilter.DARKEN
                EpubImageFilterMode.INVERT -> ImageFilter.INVERT
            }, preferences.imageFilter)
        }
    }
    @Test fun otherThemesHaveNoAmoledColorOverridesAndTheDefaultRemainsSystem() {
        assertEquals(ReadingTheme.SYSTEM, EpubSettings().theme)
        for (theme in ReadingTheme.entries.filter { it != ReadingTheme.AMOLED }) for (system in listOf(false, true)) {
            val preferences = EpubSettings(theme = theme).toReadium(system)
            assertNull(preferences.backgroundColor); assertNull(preferences.textColor)
        }
    }
    @Test fun completeBindingClearsBlackOverridesOnThemeChangeWithoutLosingAppearanceOrImageChoice() {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false, submit = submitted::add)
        val initial = EpubSettings(150, ReadingFont.SERIF, 160, 80, ReadingTheme.AMOLED, scroll = true,
            textAlignment = ReadingTextAlignment.JUSTIFY)
        binding.applySettings(initial); binding.applyImageFilter(EpubImageFilterMode.INVERT)
        binding.applySettings(initial.copy(theme = ReadingTheme.SEPIA))
        assertEquals(3, submitted.size); assertEquals(Theme.SEPIA, submitted.last().theme)
        assertNull(submitted.last().backgroundColor); assertNull(submitted.last().textColor)
        assertEquals(1.5, submitted.last().fontSize); assertEquals(1.6, submitted.last().lineHeight)
        assertEquals(true, submitted.last().scroll)
        binding.applySettings(initial); assertEquals(ImageFilter.INVERT, submitted.last().imageFilter)
        assertEquals(0xFF000000.toInt(), submitted.last().backgroundColor!!.int)
    }
    @Test fun bookOverrideInheritAndResetPreserveAllOtherGlobalPreferences() {
        val global = EpubSettings(160, ReadingFont.SANS_SERIF, 180, 140, ReadingTheme.SEPIA, scroll = true,
            textAlignment = ReadingTextAlignment.START)
        assertEquals(global.copy(theme = ReadingTheme.AMOLED), global.withBookTheme(ReadingTheme.AMOLED))
        assertEquals(global, global.withBookTheme(null))
        val amoledGlobal = global.copy(theme = ReadingTheme.AMOLED)
        assertEquals(ReadingTheme.AMOLED, amoledGlobal.withBookTheme(null).theme)
        assertNull(amoledGlobal.withBookTheme(ReadingTheme.DARK).toReadium(false).backgroundColor)
    }
}

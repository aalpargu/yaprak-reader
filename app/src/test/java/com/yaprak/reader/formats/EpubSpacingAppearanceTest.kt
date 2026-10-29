@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.formats.epub.*
import com.yaprak.reader.reader.EpubImageFilterMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ImageFilter
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubSpacingAppearanceTest {
    @Test fun spacingBoundsRejectInvalidValuesAndPublisherDiffersFromExplicitZero() {
        assertNotEquals(EpubSettings(), EpubSettings(paragraphSpacingPercent = 0, wordSpacingPercent = 0))
        for (paragraph in listOf(null, 0, 100, 200)) for (word in listOf(null, 0, 20, 100))
            EpubSettings(paragraphSpacingPercent = paragraph, wordSpacingPercent = word)
        for (value in listOf(-1, 201, Int.MAX_VALUE, Int.MIN_VALUE)) {
            try { EpubSettings(paragraphSpacingPercent = value); fail("Invalid paragraph") }
            catch (_: IllegalArgumentException) { }
        }
        for (value in listOf(-1, 101, Int.MAX_VALUE, Int.MIN_VALUE)) {
            try { EpubSettings(wordSpacingPercent = value); fail("Invalid word spacing") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun customAndZeroSpacingUseSdkRootFontUnitsWithoutLosingOtherAppearance() {
        val base = EpubSettings(145, ReadingFont.SERIF, null, 80, ReadingTheme.AMOLED, scroll = true)
        for (paragraph in listOf(null, 0, 100, 200)) for (word in listOf(null, 0, 30, 100)) {
            val preferences = base.copy(paragraphSpacingPercent = paragraph, wordSpacingPercent = word)
                .toReadium(false, EpubImageFilterMode.INVERT)
            assertEquals(paragraph?.div(100.0), preferences.paragraphSpacing)
            assertEquals(word?.div(100.0), preferences.wordSpacing)
            assertEquals(paragraph == null && word == null, preferences.publisherStyles)
            assertEquals(base.toReadium(false, EpubImageFilterMode.INVERT).copy(
                paragraphSpacing = paragraph?.div(100.0), wordSpacing = word?.div(100.0),
                publisherStyles = paragraph == null && word == null), preferences)
            assertNull(preferences.paragraphIndent); assertNull(preferences.letterSpacing)
        }
    }

    @Test fun unsupportedSpacingDoesNotEnableAdvancedStylesButEffectiveLineAndAlignmentRemain() {
        val settings = EpubSettings(paragraphSpacingPercent = 150, wordSpacingPercent = 40)
        val unsupported = EpubSpacingSupport(false, false)
        assertEquals(EpubSettings().toReadium(false), settings.toReadium(false, spacingSupport = unsupported))
        assertEquals(true, settings.toReadium(false, spacingSupport = unsupported).publisherStyles)
        val paragraphOnly = settings.toReadium(false, spacingSupport = EpubSpacingSupport(true, false))
        assertEquals(1.5, paragraphOnly.paragraphSpacing!!, 0.0001); assertNull(paragraphOnly.wordSpacing)
        assertEquals(false, paragraphOnly.publisherStyles)
        val lines = settings.copy(lineHeightPercent = 180).toReadium(false, spacingSupport = unsupported)
        assertEquals(false, lines.publisherStyles); assertEquals(1.8, lines.lineHeight!!, 0.0001)
        val aligned = settings.copy(textAlignment = ReadingTextAlignment.START).toReadium(false, spacingSupport = unsupported)
        assertEquals(false, aligned.publisherStyles); assertNotNull(aligned.textAlign)
    }

    @Test fun completeBindingClearsSpacingRestoresPublisherStylesAndRetriesFailedSubmission() {
        val submitted = mutableListOf<EpubPreferences>(); var failNext = false
        val binding = EpubAppearanceBinding(false) {
            if (failNext) { failNext = false; throw IllegalStateException("controlled submission failure") }
            submitted += it
        }
        val saved = EpubSettings(theme = ReadingTheme.AMOLED, paragraphSpacingPercent = 150, wordSpacingPercent = 30)
        binding.applySettings(saved); binding.applyImageFilter(EpubImageFilterMode.INVERT)
        assertEquals(ImageFilter.INVERT, submitted.last().imageFilter)
        failNext = true
        try { binding.applySettings(saved.copy(paragraphSpacingPercent = null, wordSpacingPercent = null)); fail("Failed submit") }
        catch (_: IllegalStateException) { }
        assertEquals(1.5, submitted.last().paragraphSpacing!!, 0.0001)
        binding.applySettings(saved.copy(paragraphSpacingPercent = null, wordSpacingPercent = null))
        val reset = submitted.last(); assertNull(reset.paragraphSpacing); assertNull(reset.wordSpacing)
        assertEquals(true, reset.publisherStyles); assertEquals(ImageFilter.INVERT, reset.imageFilter)
        assertEquals(0xFF000000.toInt(), reset.backgroundColor!!.int)
        val count = submitted.size; binding.applySettings(saved.copy(paragraphSpacingPercent = null, wordSpacingPercent = null))
        assertEquals(count, submitted.size)
    }

    @Test fun realPublicationEditorCapabilitiesRespectLtrRtlCjkAndFixedLayout() {
        data class Case(val language: String, val progression: ReadingProgression, val fixed: Boolean, val words: Boolean)
        for (case in listOf(Case("en", ReadingProgression.LTR, false, true),
            Case("ar", ReadingProgression.RTL, false, false), Case("ja", ReadingProgression.LTR, false, false),
            Case("ja", ReadingProgression.RTL, false, false), Case("en", ReadingProgression.LTR, true, false))) {
            val publication = Publication(Manifest(metadata = Metadata(localizedTitle = LocalizedString("Local spacing test"),
                languages = listOf(case.language), readingProgression = case.progression,
                otherMetadata = if (case.fixed) mapOf("presentation" to mapOf("layout" to "fixed")) else emptyMap()),
                readingOrder = listOf(Link(Url("EPUB/chapter.xhtml")!!, MediaType.XHTML))))
            try {
                val factory = EpubNavigatorFactory(publication); val support = factory.spacingSupport()
                assertEquals(!case.fixed, support.paragraphs); assertEquals(case.words, support.words)
                val prefs = EpubPreferences(publisherStyles = false, paragraphSpacing = 1.5, wordSpacing = 0.3)
                val editor = factory.createPreferencesEditor(prefs)
                assertEquals(support.paragraphs, editor.paragraphSpacing.isEffective)
                assertEquals(support.words, editor.wordSpacing.isEffective)
                assertFalse(factory.createPreferencesEditor(prefs.copy(publisherStyles = true)).paragraphSpacing.isEffective)
                assertFalse(factory.createPreferencesEditor(prefs.copy(publisherStyles = true)).wordSpacing.isEffective)
                val mapped = EpubSettings(paragraphSpacingPercent = 150, wordSpacingPercent = 30)
                    .toReadium(false, spacingSupport = support)
                assertEquals(if (support.paragraphs) 1.5 else null, mapped.paragraphSpacing)
                assertEquals(if (support.words) 0.3 else null, mapped.wordSpacing)
                assertNull(mapped.readingProgression); assertNull(mapped.language); assertNull(mapped.verticalText)
            } finally { publication.close() }
        }
    }

    @Test fun existingBookOverridesRetainGlobalSpacingThroughInheritanceAndExplicitPublisherLineHeight() {
        val global = EpubSettings(paragraphSpacingPercent = 120, wordSpacingPercent = 40)
        val effective = global.withBookTheme(ReadingTheme.AMOLED).withBookFontSize(155)
            .withBookFont(ReadingFont.SERIF).withBookMargins(150).withBookLineHeight(BookLineHeightChoice.Publisher)
        assertEquals(120, effective.paragraphSpacingPercent); assertEquals(40, effective.wordSpacingPercent)
        val prefs = effective.toReadium(false)
        assertEquals(1.2, prefs.paragraphSpacing!!, 0.0001); assertEquals(0.4, prefs.wordSpacing!!, 0.0001)
        assertNull(prefs.lineHeight); assertEquals(false, prefs.publisherStyles)
        assertEquals(global, global.withBookTheme(null).withBookFontSize(null).withBookFont(null)
            .withBookMargins(null).withBookLineHeight(BookLineHeightChoice.Inherit))
    }
}

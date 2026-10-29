@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.ReadingTextAlignment
import com.yaprak.reader.domain.settings.ReadingTheme
import com.yaprak.reader.formats.epub.EpubAppearanceBinding
import com.yaprak.reader.formats.epub.supportsTextAlignment
import com.yaprak.reader.reader.EpubImageFilterMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ImageFilter
import org.readium.r2.navigator.preferences.ReadingProgression as NavigatorReadingProgression
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression as PublicationReadingProgression
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubTextAlignmentCapabilityTest {
    @Test fun `real LTR publication editor requires explicit alignment with publisher styles disabled`() {
        withFactory("en", PublicationReadingProgression.LTR) { factory ->
            val preferences = EpubPreferences(publisherStyles = false, textAlign = TextAlign.START)
            val editor = factory.createPreferencesEditor(preferences)
            assertEquals(EpubLayout.REFLOWABLE, editor.layout)
            assertEquals(NavigatorReadingProgression.LTR, editor.readingProgression.effectiveValue)
            assertTrue(editor.textAlign.isEffective)
            assertEquals(TextAlign.START, editor.textAlign.effectiveValue)
            assertTrue(factory.supportsTextAlignment())
            assertFalse(factory.createPreferencesEditor(preferences.copy(publisherStyles = true)).textAlign.isEffective)
            assertFalse(factory.createPreferencesEditor(preferences.copy(textAlign = null)).textAlign.isEffective)
            assertTrue(factory.createPreferencesEditor(preferences.copy(textAlign = TextAlign.JUSTIFY)).textAlign.isEffective)
            assertTrue(factory.createPreferencesEditor(preferences.copy(scroll = true)).textAlign.isEffective)
            val submitted = mutableListOf<EpubPreferences>()
            val binding = EpubAppearanceBinding(false, factory.supportsTextAlignment()) { submitted += it }
            binding.applySettings(EpubSettings(textAlignment = ReadingTextAlignment.START))
            assertEquals(TextAlign.START, submitted.single().textAlign)
            assertEquals(false, submitted.single().publisherStyles)
            assertNull(submitted.single().lineHeight)
        }
    }

    @Test fun `real RTL publication editor preserves logical start and supports justification`() {
        for (progression in listOf(null, PublicationReadingProgression.RTL)) {
            withFactory("ar", progression) { factory ->
                val editor = factory.createPreferencesEditor(EpubPreferences(publisherStyles = false,
                    textAlign = TextAlign.START))
                assertEquals(NavigatorReadingProgression.RTL, editor.readingProgression.effectiveValue)
                assertTrue(editor.textAlign.isEffective)
                assertEquals(TextAlign.START, editor.textAlign.effectiveValue)
                assertTrue(factory.supportsTextAlignment())
                assertTrue(factory.createPreferencesEditor(EpubPreferences(publisherStyles = false,
                    textAlign = TextAlign.JUSTIFY)).textAlign.isEffective)
                val submitted = mutableListOf<EpubPreferences>()
                val binding = EpubAppearanceBinding(false, factory.supportsTextAlignment()) { submitted += it }
                binding.applySettings(EpubSettings(textAlignment = ReadingTextAlignment.START))
                assertEquals(TextAlign.START, submitted.single().textAlign)
                // The book's direction is resolved by Readium; the app does not force LTR or physical left.
                assertNull(submitted.single().readingProgression)
                assertNull(submitted.single().language)
            }
        }
    }

    @Test fun `real horizontal CJK publication disables alignment without enabling unrelated advanced styles`() {
        withFactory("ja", PublicationReadingProgression.LTR) { factory ->
            val editor = factory.createPreferencesEditor(EpubPreferences(publisherStyles = false,
                textAlign = TextAlign.START))
            assertEquals(EpubLayout.REFLOWABLE, editor.layout)
            assertFalse(editor.verticalText.effectiveValue)
            assertFalse(editor.textAlign.isEffective)
            assertFalse(factory.supportsTextAlignment())
            assertUnsupportedBindingPreservesOtherSettings(factory)
        }
    }

    @Test fun `real vertical CJK publication disables alignment and leaves its layout to Readium`() {
        withFactory("ja", PublicationReadingProgression.RTL) { factory ->
            val editor = factory.createPreferencesEditor(EpubPreferences(publisherStyles = false,
                textAlign = TextAlign.JUSTIFY))
            assertTrue(editor.verticalText.effectiveValue)
            assertFalse(editor.textAlign.isEffective)
            assertFalse(factory.supportsTextAlignment())
            assertUnsupportedBindingPreservesOtherSettings(factory)
        }
    }

    @Test fun `real fixed layout publication never reports text alignment as effective`() {
        withFactory("en", PublicationReadingProgression.LTR, fixed = true) { factory ->
            val editor = factory.createPreferencesEditor(EpubPreferences(publisherStyles = false,
                textAlign = TextAlign.START))
            assertEquals(EpubLayout.FIXED, editor.layout)
            assertFalse(editor.textAlign.isEffective)
            assertFalse(factory.supportsTextAlignment())
        }
    }

    private fun assertUnsupportedBindingPreservesOtherSettings(factory: EpubNavigatorFactory) {
        val submitted = mutableListOf<EpubPreferences>()
        val binding = EpubAppearanceBinding(false, factory.supportsTextAlignment()) { submitted += it }
        val saved = EpubSettings(fontSizePercent = 145, marginsPercent = 80, theme = ReadingTheme.DARK,
            scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY)
        binding.applySettings(saved)
        binding.applyImageFilter(EpubImageFilterMode.INVERT)
        val preferences = submitted.last()
        assertNull(preferences.textAlign)
        assertEquals(true, preferences.publisherStyles)
        assertNull(preferences.lineHeight)
        assertEquals(1.45, preferences.fontSize!!, 0.0001)
        assertEquals(0.8, preferences.pageMargins!!, 0.0001)
        assertEquals(true, preferences.scroll)
        assertEquals(ImageFilter.INVERT, preferences.imageFilter)
        assertNull(preferences.verticalText)
        assertNull(preferences.readingProgression)
        assertEquals(ReadingTextAlignment.JUSTIFY, saved.textAlignment)
        binding.applySettings(saved.copy(lineHeightPercent = 160, textAlignment = ReadingTextAlignment.START))
        assertNull(submitted.last().textAlign)
        assertEquals(false, submitted.last().publisherStyles)
        assertEquals(1.6, submitted.last().lineHeight!!, 0.0001)
        assertEquals(ImageFilter.INVERT, submitted.last().imageFilter)
    }

    private fun withFactory(
        language: String,
        readingProgression: PublicationReadingProgression?,
        fixed: Boolean = false,
        block: (EpubNavigatorFactory) -> Unit,
    ) {
        val publication = Publication(Manifest(
            metadata = Metadata(localizedTitle = LocalizedString("Local alignment test"),
                languages = listOf(language), readingProgression = readingProgression,
                otherMetadata = if (fixed) mapOf("presentation" to mapOf("layout" to "fixed")) else emptyMap()),
            readingOrder = listOf(Link(Url("EPUB/chapter.xhtml")!!, MediaType.XHTML)),
        ))
        try { block(EpubNavigatorFactory(publication)) } finally { publication.close() }
    }
}

package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

enum class ReadingTheme { SYSTEM, LIGHT, SEPIA, DARK, AMOLED }
enum class ReadingFont { ORIGINAL, SERIF, SANS_SERIF }
enum class ReadingTextAlignment { PUBLISHER, START, JUSTIFY }

/** Effective EPUB preferences, independent of storage, book identity and rendering SDK. */
data class EpubSettings(
    val fontSizePercent: Int = 100,
    val font: ReadingFont = ReadingFont.ORIGINAL,
    val lineHeightPercent: Int? = null,
    val marginsPercent: Int = 100,
    val theme: ReadingTheme = ReadingTheme.SYSTEM,
    val scroll: Boolean = false,
    val textAlignment: ReadingTextAlignment = ReadingTextAlignment.PUBLISHER,
    /** Percent of root font size; null preserves publisher spacing, zero explicitly overrides it. */
    val paragraphSpacingPercent: Int? = null,
    /** Additional space between words, as percent of root font size. */
    val wordSpacingPercent: Int? = null,
) {
    init {
        require(fontSizePercent in 75..200)
        require(lineHeightPercent == null || lineHeightPercent in 100..220)
        require(marginsPercent in 50..200)
        require(paragraphSpacingPercent == null || paragraphSpacingPercent in 0..200)
        require(wordSpacingPercent == null || wordSpacingPercent in 0..100)
    }
}

/** A missing book override inherits only the global theme; typography stays global. */
fun EpubSettings.withBookTheme(theme: ReadingTheme?): EpubSettings =
    if (theme == null) this else copy(theme = theme)

/** A missing book override inherits the current global font size. */
fun EpubSettings.withBookFontSize(fontSizePercent: Int?): EpubSettings =
    if (fontSizePercent == null) this else copy(fontSizePercent = fontSizePercent)

/** A missing book override inherits the current global font family. */
fun EpubSettings.withBookFont(font: ReadingFont?): EpubSettings =
    if (font == null) this else copy(font = font)

/** A missing book override inherits the current global margin setting. */
fun EpubSettings.withBookMargins(marginsPercent: Int?): EpubSettings =
    if (marginsPercent == null) this else copy(marginsPercent = marginsPercent)

/** Absence inherits the current global line height; publisher style is an explicit override. */
fun EpubSettings.withBookLineHeight(choice: BookLineHeightChoice): EpubSettings = when (choice) {
    BookLineHeightChoice.Inherit -> this
    BookLineHeightChoice.Publisher -> copy(lineHeightPercent = null)
    is BookLineHeightChoice.Custom -> copy(lineHeightPercent = choice.percent)
}

/** Absence follows the live global alignment; publisher alignment is an explicit book choice. */
fun EpubSettings.withBookTextAlignment(alignment: ReadingTextAlignment?): EpubSettings =
    if (alignment == null) this else copy(textAlignment = alignment)

/** A missing book mode follows live global scroll; explicit modes change only the navigation layout. */
fun EpubSettings.withBookReadingMode(mode: BookReadingMode?): EpubSettings =
    if (mode == null) this else copy(scroll = mode == BookReadingMode.SCROLL)

interface EpubSettingsRepository {
    val settings: Flow<EpubSettings>
    /** Completes only after the complete settings value has been durably written. */
    suspend fun save(settings: EpubSettings)
}

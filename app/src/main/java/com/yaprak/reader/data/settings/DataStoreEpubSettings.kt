package com.yaprak.reader.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yaprak.reader.domain.library.LibraryLayout
import com.yaprak.reader.domain.library.LibraryPreferences
import com.yaprak.reader.domain.library.LibraryPreferencesRepository
import com.yaprak.reader.domain.library.LibrarySort
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.domain.statistics.WeeklyReadingGoalRepository
import com.yaprak.reader.domain.statistics.WeeklyReadingGoalException
import com.yaprak.reader.domain.statistics.WeeklyReadingGoalFailure
import com.yaprak.reader.domain.statistics.validateWeeklyReadingGoal
import com.yaprak.reader.domain.statistics.DailyReadingGoalRepository
import com.yaprak.reader.domain.statistics.DailyReadingGoalException
import com.yaprak.reader.domain.statistics.DailyReadingGoalFailure
import com.yaprak.reader.domain.statistics.validateDailyReadingGoal
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.epubPreferences by preferencesDataStore(name = "epub_settings")

class DataStoreEpubSettings(private val store: DataStore<Preferences>) : EpubSettingsRepository,
    ScreenAwakeSettingsRepository, LibraryPreferencesRepository, PdfNightSettingsRepository,
    ReaderOrientationSettingsRepository, WeeklyReadingGoalRepository, DailyReadingGoalRepository, VolumeKeySettingsRepository, TapZoneSettingsRepository {
    constructor(context: Context) : this(context.applicationContext.epubPreferences)

    // Read failures propagate. Never silently overwrite an unreadable file with defaults.
    override val settings = store.data.map(::decode).distinctUntilChanged()
    override val keepScreenOn = store.data.map { it[screenAwake] ?: false }.distinctUntilChanged()
    override val volumePageTurning = store.data.map { values ->
        val raw = values.asMap()[volumeKeys]
        require(raw == null || raw is Boolean) { "Invalid volume key preference" }
        raw as Boolean? ?: false
    }.distinctUntilChanged()
    override val tapZones = store.data.map { values ->
        val raw = values.asMap()[tapZoneSettings] ?: return@map TapZoneSettings()
        require(raw is String) { "Invalid tap zone preference" }
        val parts = raw.split('|')
        require(parts.size == 5 && parts[0] == "1" && parts[1] in listOf("0", "1")) {
            "Invalid tap zone preference"
        }
        TapZoneSettings(parts[1] == "1", TapZoneAction.valueOf(parts[2]),
            TapZoneAction.valueOf(parts[3]), TapZoneAction.valueOf(parts[4])).also {
            require(it.valid) { "Tap zones require a menu" }
        }
    }.distinctUntilChanged()
    override val pdfNightMode = store.data.map { it[pdfNight] ?: false }.distinctUntilChanged()
    override val libraryPreferences = store.data.map(::decodeLibraryPreferences).distinctUntilChanged()
    override val weeklyMinutes = store.data.map { values ->
        // Typed Preferences.get<Int> can narrow other Number types; inspect the raw value.
        val raw = values.asMap()[weeklyReadingGoal]
        if (raw == null) null else {
            if (raw !is Int) throw WeeklyReadingGoalException(WeeklyReadingGoalFailure.INVALID_VALUE)
            validateWeeklyReadingGoal(raw)
            raw
        }
    }.distinctUntilChanged()
    override val dailyMinutes = store.data.map { values ->
        // Typed Preferences.get<Int> can narrow other Number types; inspect the raw value.
        val raw = values.asMap()[dailyReadingGoal]
        if (raw == null) null else {
            if (raw !is Int) throw DailyReadingGoalException(DailyReadingGoalFailure.INVALID_VALUE)
            validateDailyReadingGoal(raw)
            raw
        }
    }.distinctUntilChanged()
    override val orientation = store.data.map { values ->
        ReaderOrientation.entries.firstOrNull { it.name == values[readerOrientation] }
            ?: ReaderOrientation.AUTO
    }.distinctUntilChanged()

    override suspend fun saveOrientation(orientation: ReaderOrientation) {
        store.edit { it[readerOrientation] = orientation.name }
    }

    override suspend fun saveWeeklyMinutes(minutes: Int?) {
        validateWeeklyReadingGoal(minutes)
        store.edit { values ->
            if (minutes == null) values.remove(weeklyReadingGoal) else values[weeklyReadingGoal] = minutes
        }
    }

    override suspend fun saveDailyMinutes(minutes: Int?) {
        validateDailyReadingGoal(minutes)
        store.edit { values ->
            if (minutes == null) values.remove(dailyReadingGoal) else values[dailyReadingGoal] = minutes
        }
    }

    override suspend fun saveLibraryPreferences(preferences: LibraryPreferences) {
        store.edit { values ->
            values[libraryLayout] = preferences.layout.name
            values[librarySort] = preferences.sort.name
        }
    }

    override suspend fun saveKeepScreenOn(enabled: Boolean) {
        store.edit { it[screenAwake] = enabled }
    }

    override suspend fun saveVolumePageTurning(enabled: Boolean) {
        store.edit { it[volumeKeys] = enabled }
    }

    override suspend fun saveTapZones(settings: TapZoneSettings) {
        require(settings.valid) { "Tap zones require a menu" }
        // One owned key atomically saves the complete map; legacy/default reads never write.
        val encoded = listOf("1", if (settings.enabled) "1" else "0", settings.left.name,
            settings.middle.name, settings.right.name).joinToString("|")
        store.edit { it[tapZoneSettings] = encoded }
    }

    override suspend fun savePdfNightMode(enabled: Boolean) {
        store.edit { it[pdfNight] = enabled }
    }

    override suspend fun save(settings: EpubSettings) {
        store.edit { values ->
            values[fontSize] = settings.fontSizePercent
            values[font] = settings.font.name
            if (settings.lineHeightPercent == null) values.remove(lineHeight)
            else values[lineHeight] = settings.lineHeightPercent
            values[margins] = settings.marginsPercent
            values[theme] = settings.theme.name
            values[scroll] = settings.scroll
            values[textAlignment] = settings.textAlignment.name
            if (settings.paragraphSpacingPercent == null) values.remove(paragraphSpacing)
            else values[paragraphSpacing] = settings.paragraphSpacingPercent
            if (settings.wordSpacingPercent == null) values.remove(wordSpacing)
            else values[wordSpacing] = settings.wordSpacingPercent
        }
    }

    private fun decode(values: Preferences) = EpubSettings(
        fontSizePercent = values[fontSize]?.coerceIn(75, 200) ?: 100,
        font = ReadingFont.entries.firstOrNull { it.name == values[font] } ?: ReadingFont.ORIGINAL,
        lineHeightPercent = values[lineHeight]?.coerceIn(100, 220),
        marginsPercent = values[margins]?.coerceIn(50, 200) ?: 100,
        theme = ReadingTheme.entries.firstOrNull { it.name == values[theme] } ?: ReadingTheme.SYSTEM,
        scroll = values[scroll] ?: false,
        textAlignment = ReadingTextAlignment.entries.firstOrNull { it.name == values[textAlignment] }
            ?: ReadingTextAlignment.PUBLISHER,
        paragraphSpacingPercent = decodeSpacing(values, paragraphSpacing, 0..200),
        wordSpacingPercent = decodeSpacing(values, wordSpacing, 0..100),
    )

    private fun decodeSpacing(values: Preferences, key: Preferences.Key<Int>, range: IntRange): Int? {
        val raw = values.asMap()[key] ?: return null
        // Do not narrow wrong numeric types or silently replace an unreadable saved setting.
        require(raw is Int && raw in range) { "Invalid EPUB spacing preference" }
        return raw
    }

    private fun decodeLibraryPreferences(values: Preferences) = LibraryPreferences(
        layout = LibraryLayout.entries.firstOrNull { it.name == values[libraryLayout] } ?: LibraryLayout.GRID,
        sort = LibrarySort.entries.firstOrNull { it.name == values[librarySort] } ?: LibrarySort.RECENT,
    )

    private companion object {
        val fontSize = intPreferencesKey("epub_font_size_percent")
        val font = stringPreferencesKey("epub_font")
        val lineHeight = intPreferencesKey("epub_line_height_percent")
        val margins = intPreferencesKey("epub_margins_percent")
        val theme = stringPreferencesKey("epub_theme")
        val scroll = booleanPreferencesKey("epub_scroll")
        val textAlignment = stringPreferencesKey("epub_text_alignment")
        val paragraphSpacing = intPreferencesKey("epub_paragraph_spacing_percent")
        val wordSpacing = intPreferencesKey("epub_word_spacing_percent")
        val tapZoneSettings = stringPreferencesKey("reader_tap_zones_v1")
        val volumeKeys = booleanPreferencesKey("reader_volume_page_turning")
        val screenAwake = booleanPreferencesKey("reader_keep_screen_on")
        val pdfNight = booleanPreferencesKey("reader_pdf_night_mode")
        val readerOrientation = stringPreferencesKey("reader_orientation")
        val weeklyReadingGoal = intPreferencesKey("weekly_reading_goal_minutes")
        val dailyReadingGoal = intPreferencesKey("daily_reading_goal_minutes")
        val libraryLayout = stringPreferencesKey("library_layout")
        val librarySort = stringPreferencesKey("library_sort")
    }
}

package com.yaprak.reader.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yaprak.reader.data.settings.DataStoreEpubSettings
import com.yaprak.reader.domain.library.LibraryLayout
import com.yaprak.reader.domain.library.LibraryPreferences
import com.yaprak.reader.domain.library.LibrarySort
import com.yaprak.reader.domain.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubSettingsPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun spacingPersistsThroughRealFileReopenZeroAndPublisherResetWithoutChangingDevicePreferences() = runBlocking {
        val file = java.io.File(folder.root, "spacing.preferences_pb")
        val paragraph = intPreferencesKey("epub_paragraph_spacing_percent")
        val word = intPreferencesKey("epub_word_spacing_percent")
        val settings = EpubSettings(theme = ReadingTheme.AMOLED, paragraphSpacingPercent = 200, wordSpacingPercent = 100)
        withStore(file) { repository, _ -> repository.saveKeepScreenOn(true); repository.save(settings) }
        withStore(file) { repository, store ->
            assertEquals(settings, repository.settings.first())
            assertEquals(200, store.data.first()[paragraph]); assertEquals(100, store.data.first()[word])
            repository.save(settings.copy(paragraphSpacingPercent = 0, wordSpacingPercent = 0))
        }
        withStore(file) { repository, _ ->
            assertEquals(0, repository.settings.first().paragraphSpacingPercent)
            assertEquals(0, repository.settings.first().wordSpacingPercent); repository.save(EpubSettings())
        }
        withStore(file) { repository, store ->
            assertEquals(EpubSettings(), repository.settings.first()); assertTrue(repository.keepScreenOn.first())
            assertNull(store.data.first()[paragraph]); assertNull(store.data.first()[word])
        }
    }

    @Test fun legacySpacingReadLeavesRealFileBytesUnchangedAndInheritsPublisher() = runBlocking {
        val file = java.io.File(folder.root, "old-spacing.preferences_pb")
        withStore(file) { _, store -> store.edit { it[stringPreferencesKey("epub_theme")] = "AMOLED" } }
        val bytes = file.readBytes()
        withStore(file) { repository, store ->
            assertEquals(EpubSettings(theme = ReadingTheme.AMOLED), repository.settings.first())
            assertNull(store.data.first()[intPreferencesKey("epub_paragraph_spacing_percent")])
            assertNull(store.data.first()[intPreferencesKey("epub_word_spacing_percent")])
        }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun invalidSpacingRawTypesAndRangesFailWithoutNarrowingOrRewritingSavedFile() = runBlocking {
        for ((name, overLimit) in listOf("epub_paragraph_spacing_percent" to 201, "epub_word_spacing_percent" to 101))
        for ((index, raw) in listOf<Any>(-1, overLimit, 100L, "100", true).withIndex()) {
            val file = java.io.File(folder.root, "bad-spacing-" + name + index + ".preferences_pb")
            withStore(file) { repository, store ->
                repository.saveKeepScreenOn(true)
                store.edit { values -> when (raw) {
                    is Int -> values[intPreferencesKey(name)] = raw
                    is Long -> values[androidx.datastore.preferences.core.longPreferencesKey(name)] = raw
                    is String -> values[stringPreferencesKey(name)] = raw
                    is Boolean -> values[booleanPreferencesKey(name)] = raw
                } }
            }
            val bytes = file.readBytes()
            withStore(file) { repository, _ ->
                try { repository.settings.first(); fail("Invalid stored spacing") } catch (_: IllegalArgumentException) { }
                assertTrue(repository.keepScreenOn.first())
            }
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun amoledNamePersistsAcrossRealDataStoreReopenAndResetPreservesDeviceSettings() = runBlocking {
        val file = java.io.File(folder.root, "amoled.preferences_pb")
        val settings = EpubSettings(145, ReadingFont.SERIF, 160, 120, ReadingTheme.AMOLED, scroll = true)
        withStore(file) { repository, _ -> repository.saveKeepScreenOn(true); repository.save(settings) }
        withStore(file) { repository, store ->
            assertEquals(settings, repository.settings.first())
            assertEquals("AMOLED", store.data.first()[stringPreferencesKey("epub_theme")])
            assertTrue(repository.keepScreenOn.first()); repository.save(EpubSettings())
        }
        withStore(file) { repository, _ ->
            assertEquals(EpubSettings(), repository.settings.first()); assertTrue(repository.keepScreenOn.first())
        }
    }

    private suspend fun withStore(
        file: java.io.File,
        block: suspend (DataStoreEpubSettings, DataStore<Preferences>) -> Unit,
    ) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try { block(DataStoreEpubSettings(store), store) } finally { job.cancelAndJoin() }
    }

    @Test fun `all preferences survive real file close and reopen with reset`() = runBlocking {
        val file = java.io.File(folder.root, "reader.preferences_pb")
        val settings = EpubSettings(155, ReadingFont.SERIF, 180, 150, ReadingTheme.SEPIA, scroll = true,
            textAlignment = ReadingTextAlignment.JUSTIFY)
        withStore(file) { repository, _ ->
            assertEquals(EpubSettings(), repository.settings.first())
            repository.save(settings)
        }
        withStore(file) { repository, _ ->
            assertEquals(settings, repository.settings.first())
            repository.save(EpubSettings())
        }
        withStore(file) { repository, _ -> assertEquals(EpubSettings(), repository.settings.first()) }
    }

    @Test fun `legacy file without scroll key remains paginated without a read writing defaults`() = runBlocking {
        val file = java.io.File(folder.root, "legacy.preferences_pb")
        val scrollKey = booleanPreferencesKey("epub_scroll")
        val alignmentKey = stringPreferencesKey("epub_text_alignment")
        withStore(file) { _, store ->
            store.edit {
                it[intPreferencesKey("epub_font_size_percent")] = 140
                it[stringPreferencesKey("epub_theme")] = ReadingTheme.SEPIA.name
                it[booleanPreferencesKey("reader_keep_screen_on")] = true
            }
        }
        val original = file.readBytes()
        withStore(file) { repository, store ->
            assertEquals(EpubSettings(fontSizePercent = 140, theme = ReadingTheme.SEPIA, scroll = false),
                repository.settings.first())
            assertTrue(repository.keepScreenOn.first())
            assertNull(store.data.first()[scrollKey])
            assertNull(store.data.first()[alignmentKey])
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun `scroll saves and independent device writes preserve each other across reopen`() = runBlocking {
        val file = java.io.File(folder.root, "shared-scroll.preferences_pb")
        val appearance = EpubSettings(150, ReadingFont.SERIF, 180, 130, ReadingTheme.SEPIA, scroll = true,
            textAlignment = ReadingTextAlignment.START)
        val library = LibraryPreferences(LibraryLayout.LIST, LibrarySort.AUTHOR)
        val unrelated = stringPreferencesKey("unrelated_preference")
        withStore(file) { repository, store ->
            store.edit { it[unrelated] = "preserved" }
            repository.saveKeepScreenOn(true)
            repository.savePdfNightMode(true)
            repository.saveLibraryPreferences(library)
            repository.save(appearance)
            assertTrue(repository.keepScreenOn.first())
            assertTrue(repository.pdfNightMode.first())
            assertEquals(library, repository.libraryPreferences.first())
            assertEquals("preserved", store.data.first()[unrelated])
        }
        withStore(file) { repository, store ->
            assertEquals(appearance, repository.settings.first())
            repository.saveKeepScreenOn(false)
            repository.savePdfNightMode(false)
            repository.saveLibraryPreferences(LibraryPreferences())
            assertEquals(appearance, repository.settings.first())
            assertEquals("preserved", store.data.first()[unrelated])
            repository.save(appearance.copy(scroll = false))
            assertFalse(repository.keepScreenOn.first())
            assertFalse(repository.pdfNightMode.first())
            assertEquals(LibraryPreferences(), repository.libraryPreferences.first())
        }
        withStore(file) { repository, store ->
            assertEquals(appearance.copy(scroll = false), repository.settings.first())
            assertFalse(repository.keepScreenOn.first())
            assertFalse(repository.pdfNightMode.first())
            assertEquals(LibraryPreferences(), repository.libraryPreferences.first())
            assertEquals("preserved", store.data.first()[unrelated])
        }
    }

    @Test fun `each alignment survives actual file reopen and resets to publisher`() = runBlocking {
        val key = stringPreferencesKey("epub_text_alignment")
        for (alignment in ReadingTextAlignment.entries) {
            val file = java.io.File(folder.root, "alignment-${alignment.name}.preferences_pb")
            val settings = EpubSettings(135, ReadingFont.SANS_SERIF, null, 80, ReadingTheme.DARK,
                scroll = true, textAlignment = alignment)
            withStore(file) { repository, store ->
                repository.save(settings)
                assertEquals(alignment.name, store.data.first()[key])
            }
            withStore(file) { repository, _ ->
                assertEquals(settings, repository.settings.first())
                repository.save(EpubSettings())
            }
            withStore(file) { repository, store ->
                assertEquals(EpubSettings(), repository.settings.first())
                assertEquals(ReadingTextAlignment.PUBLISHER.name, store.data.first()[key])
            }
        }
    }

    @Test fun `unknown alignment values fall back without overwriting the existing preferences file`() = runBlocking {
        val key = stringPreferencesKey("epub_text_alignment")
        for ((index, unknown) in listOf("FUTURE_ALIGNMENT", "start", " START ", "").withIndex()) {
            val file = java.io.File(folder.root, "unknown-alignment-$index.preferences_pb")
            withStore(file) { _, store ->
                store.edit {
                    it[key] = unknown
                    it[intPreferencesKey("epub_font_size_percent")] = 145
                    it[booleanPreferencesKey("epub_scroll")] = true
                    it[booleanPreferencesKey("reader_keep_screen_on")] = true
                }
            }
            val original = file.readBytes()
            withStore(file) { repository, store ->
                assertEquals(EpubSettings(fontSizePercent = 145, scroll = true), repository.settings.first())
                assertEquals(unknown, store.data.first()[key])
                assertTrue(repository.keepScreenOn.first())
            }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun `unknown enums and out of range persisted numbers have safe effective values`() = runBlocking {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { java.io.File(folder.root, "bounds.preferences_pb") }
        try {
            store.edit {
                it[intPreferencesKey("epub_font_size_percent")] = Int.MAX_VALUE
                it[intPreferencesKey("epub_line_height_percent")] = -9
                it[intPreferencesKey("epub_margins_percent")] = 0
                it[stringPreferencesKey("epub_theme")] = "FUTURE_THEME"
                it[stringPreferencesKey("epub_font")] = "UNKNOWN"
            }
            assertEquals(EpubSettings(200, ReadingFont.ORIGINAL, 100, 50, ReadingTheme.SYSTEM), DataStoreEpubSettings(store).settings.first())
        } finally { job.cancelAndJoin() }
    }

    @Test fun `corrupt file reports failure without silent data loss`() = runBlocking {
        val file = java.io.File(folder.root, "corrupt.preferences_pb").apply { writeBytes(byteArrayOf(-1, -1, -1)) }
        val original = file.readBytes()
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            assertNotNull(runCatching { DataStoreEpubSettings(store).settings.first() }.exceptionOrNull())
            assertArrayEquals(original, file.readBytes())
        } finally { job.cancelAndJoin() }
    }
}

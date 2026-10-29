package com.yaprak.reader.data

import android.app.Application
import androidx.datastore.preferences.core.*
import com.yaprak.reader.data.settings.DataStoreEpubSettings
import com.yaprak.reader.domain.settings.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TapZoneSettingsPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun legacyReadDefaultsOffWithoutRewritingAndOwnedWritesSurviveReopen() = runBlocking {
        val file = File(folder.root, "reader.preferences_pb")
        val appearance = EpubSettings(150, ReadingFont.SERIF, 180, 130, ReadingTheme.AMOLED,
            paragraphSpacingPercent = 120, wordSpacingPercent = 30)
        withStore(file) { repository, store ->
            repository.save(appearance); repository.saveKeepScreenOn(true)
            repository.savePdfNightMode(true); repository.saveOrientation(ReaderOrientation.LANDSCAPE)
            repository.saveDailyMinutes(25)
            store.edit { it[stringPreferencesKey("unrelated")] = "keep" }
        }
        val original = file.readBytes()
        withStore(file) { repository, store ->
            assertEquals(TapZoneSettings(), repository.tapZones.first()); assertArrayEquals(original, file.readBytes())
            val before = store.data.first().asMap().mapKeys { it.key.name }
            repository.saveTapZones(TapZoneSettings(true, TapZoneAction.NEXT, TapZoneAction.MENU, TapZoneAction.NONE))
            val after = store.data.first().asMap().mapKeys { it.key.name }
            assertEquals(before, after.filterKeys { it != "reader_tap_zones_v1" })
        }
        withStore(file) { repository, store ->
            assertEquals(TapZoneSettings(true, TapZoneAction.NEXT, TapZoneAction.MENU, TapZoneAction.NONE), repository.tapZones.first()); assertEquals(appearance, repository.settings.first())
            repository.save(EpubSettings()); assertEquals(TapZoneSettings(true, TapZoneAction.NEXT, TapZoneAction.MENU, TapZoneAction.NONE), repository.tapZones.first())
            repository.saveTapZones(TapZoneSettings())
            assertTrue(repository.keepScreenOn.first()); assertTrue(repository.pdfNightMode.first())
            assertEquals(ReaderOrientation.LANDSCAPE, repository.orientation.first())
            assertEquals(25, repository.dailyMinutes.first())
            assertEquals("keep", store.data.first()[stringPreferencesKey("unrelated")])
        }
        withStore(file) { repository, _ -> assertEquals(TapZoneSettings(), repository.tapZones.first()) }
    }

    @Test fun wrongRawTypesAndCorruptionFailWithoutReplacingBytes() = runBlocking {
        for (kind in listOf("text", "int", "float", "corrupt", "version", "boolean", "action", "missing", "extra", "menu")) {
            val file = File(folder.root, "$kind.preferences_pb")
            if (kind == "corrupt") file.writeBytes(byteArrayOf(-1, -1, -1)) else withStore(file) { _, store ->
                store.edit { values -> when (kind) {
                    "text" -> values[stringPreferencesKey("reader_tap_zones_v1")] = "true"
                    "int" -> values[intPreferencesKey("reader_tap_zones_v1")] = 1
                    "float" -> values[floatPreferencesKey("reader_tap_zones_v1")] = 1f
                    "version" -> values[stringPreferencesKey("reader_tap_zones_v1")] = "2|1|NEXT|MENU|NONE"
                    "boolean" -> values[stringPreferencesKey("reader_tap_zones_v1")] = "1|true|NEXT|MENU|NONE"
                    "action" -> values[stringPreferencesKey("reader_tap_zones_v1")] = "1|1|PREV|MENU|NONE"
                    "missing" -> values[stringPreferencesKey("reader_tap_zones_v1")] = "1|1|NEXT|MENU"
                    "extra" -> values[stringPreferencesKey("reader_tap_zones_v1")] = "1|1|NEXT|MENU|NONE|extra"
                    else -> values[stringPreferencesKey("reader_tap_zones_v1")] = "1|1|NEXT|NONE|NONE"
                } }
            }
            val original = file.readBytes()
            withStore(file) { repository, _ ->
                assertNotNull(runCatching { repository.tapZones.first() }.exceptionOrNull())
                assertArrayEquals(original, file.readBytes())
            }
        }
    }

    private suspend fun withStore(file: File,
        block: suspend (DataStoreEpubSettings, androidx.datastore.core.DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try { block(DataStoreEpubSettings(store), store) } finally { job.cancelAndJoin() }
    }
}

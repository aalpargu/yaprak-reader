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
class VolumeKeySettingsPersistenceTest {
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
            assertFalse(repository.volumePageTurning.first()); assertArrayEquals(original, file.readBytes())
            val before = store.data.first().asMap().mapKeys { it.key.name }
            repository.saveVolumePageTurning(true)
            val after = store.data.first().asMap().mapKeys { it.key.name }
            assertEquals(before, after.filterKeys { it != "reader_volume_page_turning" })
        }
        withStore(file) { repository, store ->
            assertTrue(repository.volumePageTurning.first()); assertEquals(appearance, repository.settings.first())
            repository.save(EpubSettings()); assertTrue(repository.volumePageTurning.first())
            repository.saveVolumePageTurning(false)
            assertTrue(repository.keepScreenOn.first()); assertTrue(repository.pdfNightMode.first())
            assertEquals(ReaderOrientation.LANDSCAPE, repository.orientation.first())
            assertEquals(25, repository.dailyMinutes.first())
            assertEquals("keep", store.data.first()[stringPreferencesKey("unrelated")])
        }
        withStore(file) { repository, _ -> assertFalse(repository.volumePageTurning.first()) }
    }

    @Test fun wrongRawTypesAndCorruptionFailWithoutReplacingBytes() = runBlocking {
        for (kind in listOf("text", "int", "float", "corrupt")) {
            val file = File(folder.root, "$kind.preferences_pb")
            if (kind == "corrupt") file.writeBytes(byteArrayOf(-1, -1, -1)) else withStore(file) { _, store ->
                store.edit { values -> when (kind) {
                    "text" -> values[stringPreferencesKey("reader_volume_page_turning")] = "true"
                    "int" -> values[intPreferencesKey("reader_volume_page_turning")] = 1
                    else -> values[floatPreferencesKey("reader_volume_page_turning")] = 1f
                } }
            }
            val original = file.readBytes()
            withStore(file) { repository, _ ->
                assertNotNull(runCatching { repository.volumePageTurning.first() }.exceptionOrNull())
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

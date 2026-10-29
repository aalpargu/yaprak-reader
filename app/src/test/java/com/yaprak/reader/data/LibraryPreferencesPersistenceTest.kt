package com.yaprak.reader.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yaprak.reader.data.settings.DataStoreEpubSettings
import com.yaprak.reader.domain.library.LibraryLayout
import com.yaprak.reader.domain.library.LibraryPreferences
import com.yaprak.reader.domain.library.LibrarySort
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.domain.settings.ReadingTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LibraryPreferencesPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `layout and sort survive real file reopen and resetting defaults`() = runBlocking {
        val file = File(folder.root, "reader.preferences_pb")
        val selected = LibraryPreferences(LibraryLayout.LIST, LibrarySort.LAST_READ)
        withStore(file) { settings, _ -> settings.saveLibraryPreferences(selected) }
        withStore(file) { settings, _ ->
            assertEquals(selected, settings.libraryPreferences.first())
            settings.saveLibraryPreferences(LibraryPreferences())
        }
        withStore(file) { settings, _ ->
            assertEquals(LibraryPreferences(), settings.libraryPreferences.first())
        }
    }

    @Test fun `missing and unknown names default independently without rewriting stored names`() = runBlocking {
        val file = File(folder.root, "unknown.preferences_pb")
        val layout = stringPreferencesKey("library_layout")
        val sort = stringPreferencesKey("library_sort")
        withStore(file) { settings, store ->
            assertEquals(LibraryPreferences(), settings.libraryPreferences.first())
            store.edit {
                it[layout] = "FUTURE_LAYOUT"
                it[sort] = LibrarySort.AUTHOR.name
            }
            assertEquals(LibraryPreferences(LibraryLayout.GRID, LibrarySort.AUTHOR), settings.libraryPreferences.first())
            assertEquals("FUTURE_LAYOUT", store.data.first()[layout])
            store.edit {
                it[layout] = LibraryLayout.LIST.name
                it[sort] = "FUTURE_SORT"
            }
            assertEquals(LibraryPreferences(LibraryLayout.LIST, LibrarySort.RECENT), settings.libraryPreferences.first())
            assertEquals("FUTURE_SORT", store.data.first()[sort])
        }
    }

    @Test fun `library appearance and awake writes preserve each others preferences`() = runBlocking {
        val file = File(folder.root, "shared.preferences_pb")
        val initial = LibraryPreferences(LibraryLayout.LIST, LibrarySort.TITLE)
        val updated = LibraryPreferences(LibraryLayout.GRID, LibrarySort.AUTHOR)
        val appearance = EpubSettings(150, ReadingFont.SERIF, 180, 130, ReadingTheme.SEPIA)
        val unrelated = stringPreferencesKey("unrelated_preference")
        withStore(file) { settings, store ->
            store.edit { it[unrelated] = "preserved" }
            settings.saveLibraryPreferences(initial)
            settings.save(appearance)
            settings.saveKeepScreenOn(true)
            assertEquals(initial, settings.libraryPreferences.first())
            settings.saveLibraryPreferences(updated)
            assertEquals(appearance, settings.settings.first())
            assertTrue(settings.keepScreenOn.first())
            assertEquals("preserved", store.data.first()[unrelated])
            settings.save(EpubSettings())
            assertEquals(updated, settings.libraryPreferences.first())
            assertTrue(settings.keepScreenOn.first())
            settings.saveKeepScreenOn(false)
            assertEquals(updated, settings.libraryPreferences.first())
            assertEquals(EpubSettings(), settings.settings.first())
        }
        withStore(file) { settings, store ->
            assertEquals(updated, settings.libraryPreferences.first())
            assertEquals(EpubSettings(), settings.settings.first())
            assertFalse(settings.keepScreenOn.first())
            assertEquals("preserved", store.data.first()[unrelated])
        }
    }

    @Test fun `corrupt shared file reports library read and write failures without replacing data`() = runBlocking {
        val file = File(folder.root, "corrupt.preferences_pb").apply { writeBytes(byteArrayOf(-1, -1, -1)) }
        val original = file.readBytes()
        withStore(file) { settings, _ ->
            assertNotNull(runCatching { settings.libraryPreferences.first() }.exceptionOrNull())
            assertArrayEquals(original, file.readBytes())
            assertNotNull(runCatching { settings.saveLibraryPreferences(LibraryPreferences(LibraryLayout.LIST)) }.exceptionOrNull())
            assertArrayEquals(original, file.readBytes())
        }
    }

    private suspend fun withStore(
        file: File,
        block: suspend (DataStoreEpubSettings, DataStore<Preferences>) -> Unit,
    ) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try { block(DataStoreEpubSettings(store), store) } finally { job.cancelAndJoin() }
    }
}

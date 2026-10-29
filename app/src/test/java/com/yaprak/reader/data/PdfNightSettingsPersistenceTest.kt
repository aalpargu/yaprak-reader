package com.yaprak.reader.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yaprak.reader.data.settings.DataStoreEpubSettings
import com.yaprak.reader.domain.library.LibraryLayout
import com.yaprak.reader.domain.library.LibraryPreferences
import com.yaprak.reader.domain.library.LibrarySort
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.domain.settings.ReadingTheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
class PdfNightSettingsPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `default is original colors without writing and manual preference survives reopen`() = runBlocking {
        val file = File(folder.root, "reader.preferences_pb")
        withStore(file) { settings, store ->
            assertFalse(settings.pdfNightMode.first())
            assertFalse(store.data.first().contains(pdfNightKey))
            settings.savePdfNightMode(true)
        }
        withStore(file) { settings, _ ->
            assertTrue(settings.pdfNightMode.first())
            settings.savePdfNightMode(false)
        }
        withStore(file) { settings, store ->
            assertFalse(settings.pdfNightMode.first())
            assertEquals(false, store.data.first()[pdfNightKey])
        }
    }

    @Test fun `pdf night writes and other settings writes preserve all independent keys`() = runBlocking {
        val file = File(folder.root, "shared.preferences_pb")
        val appearance = EpubSettings(150, ReadingFont.SERIF, 180, 130, ReadingTheme.SEPIA)
        val library = LibraryPreferences(LibraryLayout.LIST, LibrarySort.TITLE)
        val unrelated = stringPreferencesKey("unrelated_preference")
        withStore(file) { settings, store ->
            store.edit { it[unrelated] = "preserved" }
            settings.save(appearance)
            settings.saveKeepScreenOn(true)
            settings.saveLibraryPreferences(library)
            settings.savePdfNightMode(true)
            assertEquals(appearance, settings.settings.first())
            assertTrue(settings.keepScreenOn.first())
            assertEquals(library, settings.libraryPreferences.first())
            assertEquals("preserved", store.data.first()[unrelated])
            settings.save(EpubSettings())
            settings.saveKeepScreenOn(false)
            settings.saveLibraryPreferences(LibraryPreferences())
            assertTrue(settings.pdfNightMode.first())
            assertEquals("preserved", store.data.first()[unrelated])
            settings.savePdfNightMode(false)
        }
        withStore(file) { settings, store ->
            assertFalse(settings.pdfNightMode.first())
            assertEquals(EpubSettings(), settings.settings.first())
            assertFalse(settings.keepScreenOn.first())
            assertEquals(LibraryPreferences(), settings.libraryPreferences.first())
            assertEquals("preserved", store.data.first()[unrelated])
        }
    }

    @Test fun `corrupt shared file reports read and write failures without replacing bytes`() = runBlocking {
        val file = File(folder.root, "corrupt.preferences_pb").apply { writeBytes(byteArrayOf(-1, -1, -1)) }
        val original = file.readBytes()
        withStore(file) { settings, _ ->
            assertNotNull(runCatching { settings.pdfNightMode.first() }.exceptionOrNull())
            assertArrayEquals(original, file.readBytes())
            assertNotNull(runCatching { settings.savePdfNightMode(false) }.exceptionOrNull())
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun `provider io failures propagate without retries or overwriting the stored preference`() = runBlocking {
        val failure = IOException("Storage unavailable")
        val store = FailingStore(failure)
        val settings = DataStoreEpubSettings(store)
        assertSame(failure, runCatching { settings.pdfNightMode.first() }.exceptionOrNull())
        assertSame(failure, runCatching { settings.savePdfNightMode(false) }.exceptionOrNull())
        assertTrue(store.stored[pdfNightKey] == true)
        assertEquals(1, store.reads)
        assertEquals(1, store.writes)
    }

    @Test fun `read and write cancellation remain cancellation and leave the preference unchanged`() = runBlocking {
        val cancellation = CancellationException("Cancelled")
        val store = FailingStore(cancellation)
        val settings = DataStoreEpubSettings(store)
        assertSame(cancellation, runCatching { settings.pdfNightMode.first() }.exceptionOrNull())
        assertSame(cancellation, runCatching { settings.savePdfNightMode(false) }.exceptionOrNull())
        assertTrue(store.stored[pdfNightKey] == true)
        assertEquals(1, store.reads)
        assertEquals(1, store.writes)
    }

    private suspend fun withStore(
        file: File,
        block: suspend (DataStoreEpubSettings, DataStore<Preferences>) -> Unit,
    ) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try { block(DataStoreEpubSettings(store), store) } finally { job.cancelAndJoin() }
    }

    private class FailingStore(private val failure: Exception) : DataStore<Preferences> {
        val stored = preferencesOf(pdfNightKey to true)
        var reads = 0
        var writes = 0
        override val data: Flow<Preferences> = flow {
            reads++
            throw failure
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writes++
            transform(stored)
            throw failure
        }
    }

    private companion object {
        val pdfNightKey = booleanPreferencesKey("reader_pdf_night_mode")
    }
}

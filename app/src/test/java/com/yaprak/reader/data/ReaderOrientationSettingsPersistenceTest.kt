package com.yaprak.reader.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yaprak.reader.data.settings.DataStoreEpubSettings
import com.yaprak.reader.domain.library.LibraryLayout
import com.yaprak.reader.domain.library.LibraryPreferences
import com.yaprak.reader.domain.library.LibrarySort
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.ReaderOrientation
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.domain.settings.ReadingTextAlignment
import com.yaprak.reader.domain.settings.ReadingTheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class ReaderOrientationSettingsPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `all orientation choices survive real file close and reopen`() = runBlocking {
        val file = File(folder.root, "orientation.preferences_pb")
        for (choice in listOf(ReaderOrientation.PORTRAIT, ReaderOrientation.LANDSCAPE, ReaderOrientation.AUTO)) {
            withStore(file) { settings, _ -> settings.saveOrientation(choice) }
            withStore(file) { settings, store ->
                assertEquals(choice, settings.orientation.first())
                assertEquals(choice.name, store.data.first()[orientationKey])
            }
        }
    }

    @Test fun `legacy file without orientation reads automatic without writing defaults`() = runBlocking {
        val file = File(folder.root, "legacy.preferences_pb")
        withStore(file) { settings, store ->
            settings.save(appearance)
            settings.saveKeepScreenOn(true)
            settings.savePdfNightMode(true)
            settings.saveLibraryPreferences(library)
            assertFalse(store.data.first().contains(orientationKey))
        }
        val original = file.readBytes()
        withStore(file) { settings, store ->
            assertEquals(ReaderOrientation.AUTO, settings.orientation.first())
            assertEquals(appearance, settings.settings.first())
            assertTrue(settings.keepScreenOn.first())
            assertTrue(settings.pdfNightMode.first())
            assertEquals(library, settings.libraryPreferences.first())
            assertFalse(store.data.first().contains(orientationKey))
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun `unknown and noncanonical names read automatic without replacing stored bytes`() = runBlocking {
        val file = File(folder.root, "unknown.preferences_pb")
        for (raw in listOf("FUTURE_ORIENTATION", "portrait", " LANDSCAPE", "AUTO ", "")) {
            withStore(file) { _, store -> store.edit { it[orientationKey] = raw } }
            val original = file.readBytes()
            withStore(file) { settings, store ->
                assertEquals(ReaderOrientation.AUTO, settings.orientation.first())
                assertEquals(raw, store.data.first()[orientationKey])
            }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun `orientation save changes only its key preserving every independent preference`() = runBlocking {
        val file = File(folder.root, "isolated.preferences_pb")
        withStore(file) { settings, store ->
            settings.save(appearance)
            settings.saveKeepScreenOn(true)
            settings.savePdfNightMode(true)
            settings.saveLibraryPreferences(library)
            store.edit { it[unrelatedKey] = "İğne\nUnicode 🔖" }
            val before = store.data.first().asMap()
            settings.saveOrientation(ReaderOrientation.LANDSCAPE)
            assertEquals(before, store.data.first().asMap().filterKeys { it != orientationKey })
            settings.saveOrientation(ReaderOrientation.PORTRAIT)
            assertEquals(before, store.data.first().asMap().filterKeys { it != orientationKey })
        }
        withStore(file) { settings, store ->
            assertEquals(ReaderOrientation.PORTRAIT, settings.orientation.first())
            assertEquals(appearance, settings.settings.first())
            assertTrue(settings.keepScreenOn.first())
            assertTrue(settings.pdfNightMode.first())
            assertEquals(library, settings.libraryPreferences.first())
            assertEquals("İğne\nUnicode 🔖", store.data.first()[unrelatedKey])
        }
    }

    @Test fun `other preference saves and resets preserve reader orientation`() = runBlocking {
        val file = File(folder.root, "other-writes.preferences_pb")
        withStore(file) { settings, store ->
            settings.saveOrientation(ReaderOrientation.LANDSCAPE)
            store.edit { it[unrelatedKey] = "preserved" }
            settings.save(appearance)
            settings.saveKeepScreenOn(true)
            settings.savePdfNightMode(true)
            settings.saveLibraryPreferences(library)
            assertEquals(ReaderOrientation.LANDSCAPE, settings.orientation.first())
            settings.save(EpubSettings())
            settings.saveKeepScreenOn(false)
            settings.savePdfNightMode(false)
            settings.saveLibraryPreferences(LibraryPreferences())
            assertEquals(ReaderOrientation.LANDSCAPE, settings.orientation.first())
        }
        withStore(file) { settings, store ->
            assertEquals(ReaderOrientation.LANDSCAPE, settings.orientation.first())
            assertEquals(EpubSettings(), settings.settings.first())
            assertFalse(settings.keepScreenOn.first())
            assertFalse(settings.pdfNightMode.first())
            assertEquals(LibraryPreferences(), settings.libraryPreferences.first())
            assertEquals("preserved", store.data.first()[unrelatedKey])
        }
    }

    @Test fun `backup settings apply and rollback keep the device local orientation`() = runBlocking {
        val file = File(folder.root, "restore.preferences_pb")
        val restored = EpubSettings(170, ReadingFont.SANS_SERIF, null, 160, ReadingTheme.DARK,
            scroll = false, textAlignment = ReadingTextAlignment.START)
        withStore(file) { settings, _ ->
            settings.save(appearance)
            settings.saveOrientation(ReaderOrientation.PORTRAIT)
            val previousSettings = settings.settings.first()
            // Backup restore and recovery use this same EPUB repository save boundary.
            settings.save(restored)
            assertEquals(restored, settings.settings.first())
            assertEquals(ReaderOrientation.PORTRAIT, settings.orientation.first())
            settings.save(previousSettings)
            assertEquals(appearance, settings.settings.first())
            assertEquals(ReaderOrientation.PORTRAIT, settings.orientation.first())
        }
        withStore(file) { settings, _ ->
            assertEquals(appearance, settings.settings.first())
            assertEquals(ReaderOrientation.PORTRAIT, settings.orientation.first())
        }
    }

    @Test fun `concurrent independent writers preserve all fields in the shared store`() = runBlocking {
        val file = File(folder.root, "concurrent.preferences_pb")
        withStore(file) { settings, _ ->
            coroutineScope {
                listOf(
                    async(Dispatchers.IO) { settings.saveOrientation(ReaderOrientation.LANDSCAPE) },
                    async(Dispatchers.IO) { settings.save(appearance) },
                    async(Dispatchers.IO) { settings.saveKeepScreenOn(true) },
                    async(Dispatchers.IO) { settings.savePdfNightMode(true) },
                    async(Dispatchers.IO) { settings.saveLibraryPreferences(library) },
                ).awaitAll()
            }
        }
        withStore(file) { settings, _ ->
            assertEquals(ReaderOrientation.LANDSCAPE, settings.orientation.first())
            assertEquals(appearance, settings.settings.first())
            assertTrue(settings.keepScreenOn.first())
            assertTrue(settings.pdfNightMode.first())
            assertEquals(library, settings.libraryPreferences.first())
        }
    }

    @Test fun `corrupt shared file reports read and write failure without replacing bytes`() = runBlocking {
        val file = File(folder.root, "corrupt.preferences_pb").apply { writeBytes(byteArrayOf(-1, -1, -1)) }
        val original = file.readBytes()
        withStore(file) { settings, _ ->
            assertNotNull(runCatching { settings.orientation.first() }.exceptionOrNull())
            assertArrayEquals(original, file.readBytes())
            assertNotNull(runCatching { settings.saveOrientation(ReaderOrientation.AUTO) }.exceptionOrNull())
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun `io failures propagate without defaulting retrying or changing stored orientation`() = runBlocking {
        val failure = IOException("Storage unavailable")
        val store = FailingStore(failure)
        val settings = DataStoreEpubSettings(store)
        assertSame(failure, runCatching { settings.orientation.first() }.exceptionOrNull())
        assertSame(failure, runCatching { settings.saveOrientation(ReaderOrientation.AUTO) }.exceptionOrNull())
        assertEquals(ReaderOrientation.LANDSCAPE.name, store.stored[orientationKey])
        assertEquals(1, store.reads)
        assertEquals(1, store.writes)
    }

    @Test fun `read and write cancellation propagate and leave stored orientation unchanged`() = runBlocking {
        val store = FailingStore(CancellationException("Cancelled orientation request"))
        val settings = DataStoreEpubSettings(store)
        val readFailure = runCatching { settings.orientation.first() }.exceptionOrNull()
        val writeFailure = runCatching { settings.saveOrientation(ReaderOrientation.AUTO) }.exceptionOrNull()
        assertTrue(readFailure is CancellationException)
        assertTrue(writeFailure is CancellationException)
        assertEquals("Cancelled orientation request", readFailure?.message)
        assertEquals("Cancelled orientation request", writeFailure?.message)
        assertEquals(ReaderOrientation.LANDSCAPE.name, store.stored[orientationKey])
        assertEquals(1, store.reads)
        assertEquals(1, store.writes)
    }

    @Test fun `cancelling a real in flight edit preserves the committed choice and file`() = runBlocking {
        val file = File(folder.root, "cancelled-edit.preferences_pb")
        withStore(file) { settings, store ->
            settings.saveOrientation(ReaderOrientation.PORTRAIT)
            settings.save(appearance)
            val original = file.readBytes()
            val gated = GatedStore(store)
            val pending = launch(Dispatchers.IO) {
                DataStoreEpubSettings(gated).saveOrientation(ReaderOrientation.LANDSCAPE)
            }
            try {
                withTimeout(5_000) { gated.transformed.await() }
            } finally {
                pending.cancelAndJoin()
            }
            assertEquals(ReaderOrientation.LANDSCAPE.name, gated.proposed?.get(orientationKey))
            assertEquals(ReaderOrientation.PORTRAIT, settings.orientation.first())
            assertEquals(appearance, settings.settings.first())
            assertArrayEquals(original, file.readBytes())
        }
        withStore(file) { settings, _ ->
            assertEquals(ReaderOrientation.PORTRAIT, settings.orientation.first())
            assertEquals(appearance, settings.settings.first())
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

    private class FailingStore(private val failure: Exception) : DataStore<Preferences> {
        val stored = preferencesOf(orientationKey to ReaderOrientation.LANDSCAPE.name)
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

    private class GatedStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val transformed = CompletableDeferred<Unit>()
        var proposed: Preferences? = null
        override val data: Flow<Preferences> = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            delegate.updateData { current ->
                proposed = transform(current)
                transformed.complete(Unit)
                awaitCancellation()
            }
    }

    private companion object {
        val orientationKey = stringPreferencesKey("reader_orientation")
        val unrelatedKey = stringPreferencesKey("unrelated_preference")
        val appearance = EpubSettings(155, ReadingFont.SERIF, 180, 135, ReadingTheme.SEPIA,
            scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY)
        val library = LibraryPreferences(LibraryLayout.LIST, LibrarySort.AUTHOR)
    }
}

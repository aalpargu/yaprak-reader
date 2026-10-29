package com.yaprak.reader.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.yaprak.reader.data.settings.DataStoreEpubSettings
import com.yaprak.reader.domain.library.LibraryLayout
import com.yaprak.reader.domain.library.LibraryPreferences
import com.yaprak.reader.domain.library.LibrarySort
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.domain.statistics.WeeklyReadingGoalException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
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
class WeeklyReadingGoalSettingsPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `legacy preferences read goal off without writing a default`() = runBlocking {
        val file = File(folder.root, "legacy.preferences_pb")
        withStore(file) { settings, store ->
            initializeIndependentPreferences(settings, store)
            assertFalse(store.data.first().contains(goalKey))
        }
        val original = file.readBytes()
        withStore(file) { settings, store ->
            assertNull(settings.weeklyMinutes.first())
            assertFalse(store.data.first().contains(goalKey))
            assertIndependentPreferences(settings, store)
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun `minute boundaries and disabling survive actual file reopen`() = runBlocking {
        val file = File(folder.root, "roundtrip.preferences_pb")
        for (minutes in listOf(1, 15, 7000)) {
            withStore(file) { settings, _ -> settings.saveWeeklyMinutes(minutes) }
            withStore(file) { settings, store ->
                assertEquals(minutes, settings.weeklyMinutes.first())
                assertEquals(minutes, store.data.first()[goalKey])
            }
        }
        withStore(file) { settings, store ->
            settings.saveWeeklyMinutes(null)
            assertNull(settings.weeklyMinutes.first())
            assertFalse(store.data.first().contains(goalKey))
        }
        withStore(file) { settings, store ->
            assertNull(settings.weeklyMinutes.first())
            assertFalse(store.data.first().contains(goalKey))
        }
    }

    @Test fun `invalid save input fails before DataStore edit and preserves committed bytes`() = runBlocking {
        val file = File(folder.root, "input.preferences_pb")
        withStore(file) { settings, store ->
            settings.saveWeeklyMinutes(37)
            val original = file.readBytes()
            val counted = CountingStore(store)
            val candidate = DataStoreEpubSettings(counted)
            for (invalid in listOf(Int.MIN_VALUE, -1, 0, 7001, Int.MAX_VALUE)) {
                assertInvalid { candidate.saveWeeklyMinutes(invalid) }
                assertEquals(0, counted.writes)
                assertEquals(37, settings.weeklyMinutes.first())
                assertArrayEquals(original, file.readBytes())
            }
        }
        withStore(file) { settings, _ -> assertEquals(37, settings.weeklyMinutes.first()) }
    }

    @Test fun `stored wrong types and ranges fail without coercion or rewriting the file`() = runBlocking {
        val file = File(folder.root, "typed.preferences_pb")
        val mutations: List<(MutablePreferences) -> Unit> = listOf(
            { it[goalKey] = -1 }, { it[goalKey] = 0 }, { it[goalKey] = 7001 },
            { it[stringPreferencesKey(goalKey.name)] = "15" },
            { it[longPreferencesKey(goalKey.name)] = 15L },
            { it[floatPreferencesKey(goalKey.name)] = 15f },
            { it[doublePreferencesKey(goalKey.name)] = 15.0 },
            { it[booleanPreferencesKey(goalKey.name)] = true },
            { it[stringSetPreferencesKey(goalKey.name)] = setOf("15") },
        )
        withStore(file) { settings, store -> initializeIndependentPreferences(settings, store) }
        for (mutation in mutations) {
            withStore(file) { _, store -> store.edit { mutation(it) } }
            val original = file.readBytes()
            withStore(file) { settings, store ->
                assertInvalid { settings.weeklyMinutes.first() }
                assertIndependentPreferences(settings, store)
                assertArrayEquals(original, file.readBytes())
            }
            assertArrayEquals(original, file.readBytes())
        }
        // An explicit valid save can repair only the malformed goal preference.
        withStore(file) { settings, store ->
            settings.saveWeeklyMinutes(15)
            assertEquals(15, settings.weeklyMinutes.first())
            assertIndependentPreferences(settings, store)
        }
    }

    @Test fun `corrupt protobuf cannot become goal off or overwrite the file`() = runBlocking {
        val file = File(folder.root, "corrupt.preferences_pb").apply { writeBytes(byteArrayOf(-1, -1, -1)) }
        val original = file.readBytes()
        withStore(file) { settings, _ ->
            assertTrue(runCatching { settings.weeklyMinutes.first() }.exceptionOrNull() is IOException)
            assertArrayEquals(original, file.readBytes())
            assertTrue(runCatching { settings.saveWeeklyMinutes(null) }.exceptionOrNull() is IOException)
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun `IO and cancellation propagate without default retries or changed goal`() = runBlocking {
        for (failure in listOf(IOException("unavailable"), CancellationException("goal cancelled"))) {
            val store = FailingStore(failure)
            val settings = DataStoreEpubSettings(store)
            val readFailure = runCatching { settings.weeklyMinutes.first() }.exceptionOrNull()
            val writeFailure = runCatching { settings.saveWeeklyMinutes(15) }.exceptionOrNull()
            if (failure is CancellationException) {
                assertTrue(readFailure is CancellationException)
                assertTrue(writeFailure is CancellationException)
                assertEquals(failure.message, readFailure?.message)
                assertEquals(failure.message, writeFailure?.message)
            } else {
                assertSame(failure, readFailure)
                assertSame(failure, writeFailure)
            }
            assertEquals(37, store.stored[goalKey])
            assertEquals(1, store.reads)
            assertEquals(1, store.writes)
        }
    }

    @Test fun `enable and disable change only the goal key preserving all independent preferences`() = runBlocking {
        val file = File(folder.root, "isolated.preferences_pb")
        withStore(file) { settings, store ->
            initializeIndependentPreferences(settings, store)
            val before = store.data.first().asMap()
            for (minutes in listOf(15, 7000, null)) {
                settings.saveWeeklyMinutes(minutes)
                assertEquals(before, store.data.first().asMap().filterKeys { it.name != goalKey.name })
                assertIndependentPreferences(settings, store)
            }
        }
        withStore(file) { settings, store ->
            assertNull(settings.weeklyMinutes.first())
            assertFalse(store.data.first().contains(goalKey))
            assertIndependentPreferences(settings, store)
        }
    }

    @Test fun `other settings writes and resets preserve the committed goal across reopen`() = runBlocking {
        val file = File(folder.root, "other.preferences_pb")
        withStore(file) { settings, store ->
            settings.saveWeeklyMinutes(37)
            initializeIndependentPreferences(settings, store)
            assertEquals(37, settings.weeklyMinutes.first())
            settings.save(EpubSettings())
            settings.saveOrientation(ReaderOrientation.AUTO)
            settings.saveKeepScreenOn(false)
            settings.savePdfNightMode(false)
            settings.saveLibraryPreferences(LibraryPreferences())
            assertEquals(37, settings.weeklyMinutes.first())
        }
        withStore(file) { settings, store ->
            assertEquals(37, settings.weeklyMinutes.first())
            assertEquals(EpubSettings(), settings.settings.first())
            assertEquals(ReaderOrientation.AUTO, settings.orientation.first())
            assertFalse(settings.keepScreenOn.first())
            assertFalse(settings.pdfNightMode.first())
            assertEquals(LibraryPreferences(), settings.libraryPreferences.first())
            assertEquals(unrelatedValue, store.data.first()[unrelatedKey])
        }
    }

    @Test fun `concurrent independent writers retain every shared store preference`() = runBlocking {
        val file = File(folder.root, "concurrent.preferences_pb")
        withStore(file) { settings, store ->
            coroutineScope {
                listOf(
                    async(Dispatchers.IO) { settings.saveWeeklyMinutes(37) },
                    async(Dispatchers.IO) { settings.saveDailyMinutes(13) },
                    async(Dispatchers.IO) { settings.save(appearance) },
                    async(Dispatchers.IO) { settings.saveOrientation(ReaderOrientation.LANDSCAPE) },
                    async(Dispatchers.IO) { settings.saveKeepScreenOn(true) },
                    async(Dispatchers.IO) { settings.savePdfNightMode(true) },
                    async(Dispatchers.IO) { settings.saveLibraryPreferences(library) },
                    async(Dispatchers.IO) { store.edit { it[unrelatedKey] = unrelatedValue } },
                ).awaitAll()
            }
        }
        withStore(file) { settings, store ->
            assertEquals(37, settings.weeklyMinutes.first())
            assertIndependentPreferences(settings, store)
        }
    }

    @Test fun `cancel before edit commit retains file and goal and an explicit retry succeeds`() = runBlocking {
        val file = File(folder.root, "cancelled.preferences_pb")
        withStore(file) { settings, store ->
            settings.saveWeeklyMinutes(37)
            initializeIndependentPreferences(settings, store)
            val original = file.readBytes()
            val gated = GatedStore(store)
            val pending = launch(Dispatchers.IO) { DataStoreEpubSettings(gated).saveWeeklyMinutes(7000) }
            try { withTimeout(5000) { gated.transformed.await() } }
            finally { pending.cancelAndJoin() }
            assertEquals(7000, gated.proposed?.get(goalKey))
            assertEquals(37, settings.weeklyMinutes.first())
            assertIndependentPreferences(settings, store)
            assertArrayEquals(original, file.readBytes())
        }
        withStore(file) { settings, store ->
            assertEquals(37, settings.weeklyMinutes.first())
            settings.saveWeeklyMinutes(7000)
            assertIndependentPreferences(settings, store)
        }
        withStore(file) { settings, store ->
            assertEquals(7000, settings.weeklyMinutes.first())
            assertIndependentPreferences(settings, store)
        }
    }

    private suspend fun initializeIndependentPreferences(settings: DataStoreEpubSettings, store: DataStore<Preferences>) {
        settings.saveDailyMinutes(13)
        settings.save(appearance)
        settings.saveOrientation(ReaderOrientation.LANDSCAPE)
        settings.saveKeepScreenOn(true)
        settings.savePdfNightMode(true)
        settings.saveLibraryPreferences(library)
        store.edit { it[unrelatedKey] = unrelatedValue }
    }

    private suspend fun assertIndependentPreferences(settings: DataStoreEpubSettings, store: DataStore<Preferences>) {
        assertEquals(13, settings.dailyMinutes.first())
        assertEquals(appearance, settings.settings.first())
        assertEquals(ReaderOrientation.LANDSCAPE, settings.orientation.first())
        assertTrue(settings.keepScreenOn.first())
        assertTrue(settings.pdfNightMode.first())
        assertEquals(library, settings.libraryPreferences.first())
        assertEquals(unrelatedValue, store.data.first()[unrelatedKey])
    }

    private suspend fun assertInvalid(block: suspend () -> Unit) {
        try { block(); fail("Expected INVALID_VALUE") }
        catch (failure: WeeklyReadingGoalException) { assertEquals("INVALID_VALUE", failure.reason.name) }
    }

    private suspend fun withStore(file: File, block: suspend (DataStoreEpubSettings, DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try { block(DataStoreEpubSettings(store), store) } finally { job.cancelAndJoin() }
    }

    private class CountingStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        var writes = 0
        override val data = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writes++
            return delegate.updateData(transform)
        }
    }

    private class FailingStore(private val failure: Exception) : DataStore<Preferences> {
        val stored = preferencesOf(goalKey to 37)
        var reads = 0
        var writes = 0
        override val data: Flow<Preferences> = flow { reads++; throw failure }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writes++
            transform(stored)
            throw failure
        }
    }

    private class GatedStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val transformed = CompletableDeferred<Unit>()
        var proposed: Preferences? = null
        override val data = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData {
            proposed = transform(it)
            transformed.complete(Unit)
            awaitCancellation()
        }
    }

    private companion object {
        val goalKey = intPreferencesKey("weekly_reading_goal_minutes")
        val unrelatedKey = stringPreferencesKey("unrelated_device_preference")
        val unrelatedValue = "İğne\nUnicode 🌿"
        val appearance = EpubSettings(155, ReadingFont.SERIF, 180, 135, ReadingTheme.SEPIA,
            scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY)
        val library = LibraryPreferences(LibraryLayout.LIST, LibrarySort.AUTHOR)
    }
}

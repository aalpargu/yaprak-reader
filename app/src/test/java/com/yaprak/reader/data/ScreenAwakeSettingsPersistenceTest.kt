package com.yaprak.reader.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.yaprak.reader.data.settings.DataStoreEpubSettings
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
class ScreenAwakeSettingsPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `awake preference survives reopen and appearance writes preserve it`() = runBlocking {
        val file = File(folder.root, "reader.preferences_pb")
        val appearance = EpubSettings(150, ReadingFont.SERIF, 180, 130, ReadingTheme.SEPIA)
        withStore(file) { settings ->
            assertFalse(settings.keepScreenOn.first())
            settings.save(appearance)
            settings.saveKeepScreenOn(true)
            assertEquals(appearance, settings.settings.first())
        }
        withStore(file) { settings ->
            assertTrue(settings.keepScreenOn.first())
            assertEquals(appearance, settings.settings.first())
            settings.save(EpubSettings())
            assertTrue(settings.keepScreenOn.first())
            settings.saveKeepScreenOn(false)
            assertEquals(EpubSettings(), settings.settings.first())
        }
        withStore(file) { assertFalse(it.keepScreenOn.first()) }
    }

    @Test fun `corrupt shared file reports awake read failure without replacing data`() = runBlocking {
        val file = File(folder.root, "corrupt.preferences_pb").apply { writeBytes(byteArrayOf(-1, -1, -1)) }
        val original = file.readBytes()
        withStore(file) { settings ->
            assertNotNull(runCatching { settings.keepScreenOn.first() }.exceptionOrNull())
            assertArrayEquals(original, file.readBytes())
        }
    }

    private suspend fun withStore(file: File, block: suspend (DataStoreEpubSettings) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try { block(DataStoreEpubSettings(store)) } finally { job.cancelAndJoin() }
    }
}

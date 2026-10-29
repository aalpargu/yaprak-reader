package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.feature.settings.EpubSettingsViewModel
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpubSettingsViewModelTest {
    @Test fun spacingDraftCancelResetFailureAndRetryRequireAnExplicitSuccessfulSave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val owner = ViewModelStore()
        try {
            val repository = Repository(); val saved = EpubSettings(paragraphSpacingPercent = 100, wordSpacingPercent = 20)
            repository.values.value = saved
            val model = EpubSettingsViewModel(repository).also { owner.put("spacing", it) }
            runCurrent(); model.open(); model.edit(saved.copy(paragraphSpacingPercent = 0, wordSpacingPercent = 100))
            assertEquals(saved, repository.values.value); model.dismiss(); assertEquals(0, repository.writes)
            model.open(); model.reset(); assertNull(model.state.value.draft.paragraphSpacingPercent)
            assertNull(model.state.value.draft.wordSpacingPercent); assertEquals(saved, repository.values.value)
            repository.failWrite = true; model.save(); advanceUntilIdle()
            assertTrue(model.state.value.saveFailed); assertEquals(saved, model.state.value.saved)
            assertEquals(EpubSettings(), model.state.value.draft); assertEquals(saved, repository.values.value)
            repository.failWrite = false; model.save(); advanceUntilIdle()
            assertEquals(EpubSettings(), repository.values.value); assertFalse(model.state.value.open)
            model.open(); model.edit(saved.copy(paragraphSpacingPercent = 200, wordSpacingPercent = 0))
            model.save(); advanceUntilIdle(); assertEquals(200, model.state.value.saved.paragraphSpacingPercent)
            assertEquals(0, repository.values.value.wordSpacingPercent)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    @Test fun amoledDraftRequiresSaveAndFailedWriteKeepsPreviousThemeUntilRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val model = EpubSettingsViewModel(repository).also { owner.put("amoled", it) }
            runCurrent(); model.open(); model.edit(EpubSettings(theme = ReadingTheme.AMOLED))
            assertEquals(ReadingTheme.SYSTEM, repository.values.value.theme)
            model.dismiss(); assertEquals(0, repository.writes)
            model.open(); model.edit(EpubSettings(theme = ReadingTheme.AMOLED))
            repository.failWrite = true; model.save(); advanceUntilIdle()
            assertTrue(model.state.value.saveFailed); assertTrue(model.state.value.open)
            assertEquals(ReadingTheme.AMOLED, model.state.value.draft.theme)
            assertEquals(ReadingTheme.SYSTEM, model.state.value.saved.theme)
            repository.failWrite = false; model.save(); advanceUntilIdle()
            assertEquals(ReadingTheme.AMOLED, repository.values.value.theme); assertFalse(model.state.value.open)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
    private class Repository : EpubSettingsRepository {
        val values = MutableStateFlow(EpubSettings())
        var failRead = false
        var failWrite = false
        var writes = 0
        override val settings = flow { if (failRead) throw IOException(); emitAll(values) }
        override suspend fun save(settings: EpubSettings) {
            writes++
            delay(100)
            if (failWrite) throw IOException()
            values.value = settings
        }
    }

    @Test fun `draft cancel and reset never write until save completes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val vm = EpubSettingsViewModel(repository).also { owner.put("settings", it) }
            runCurrent()
            vm.open()
            vm.edit(EpubSettings(theme = ReadingTheme.SEPIA, fontSizePercent = 150, scroll = true,
                textAlignment = ReadingTextAlignment.JUSTIFY))
            assertEquals(ReadingTheme.SYSTEM, vm.state.value.saved.theme)
            assertFalse(vm.state.value.saved.scroll)
            assertFalse(repository.values.value.scroll)
            assertEquals(ReadingTextAlignment.PUBLISHER, repository.values.value.textAlignment)
            vm.dismiss()
            assertEquals(0, repository.writes)
            vm.open()
            assertEquals(EpubSettings(), vm.state.value.draft)
            vm.edit(EpubSettings(theme = ReadingTheme.DARK, scroll = true,
                textAlignment = ReadingTextAlignment.START))
            vm.reset()
            assertEquals(EpubSettings(), vm.state.value.draft)
            vm.edit(EpubSettings(theme = ReadingTheme.SEPIA, scroll = true,
                textAlignment = ReadingTextAlignment.JUSTIFY))
            vm.save(); vm.save()
            runCurrent()
            assertTrue(vm.state.value.open && vm.state.value.saving)
            vm.edit(EpubSettings(scroll = false))
            assertTrue(vm.state.value.draft.scroll)
            assertEquals(ReadingTextAlignment.JUSTIFY, vm.state.value.draft.textAlignment)
            assertFalse(repository.values.value.scroll)
            vm.dismiss()
            assertTrue(vm.state.value.open)
            assertEquals(1, repository.writes)
            advanceUntilIdle()
            assertFalse(vm.state.value.open)
            assertEquals(ReadingTheme.SEPIA, vm.state.value.saved.theme)
            assertTrue(vm.state.value.saved.scroll)
            assertTrue(repository.values.value.scroll)
            assertEquals(ReadingTextAlignment.JUSTIFY, repository.values.value.textAlignment)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `failed save preserves draft and persisted appearance until retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failWrite = true }
            val vm = EpubSettingsViewModel(repository).also { owner.put("settings", it) }
            runCurrent(); vm.open(); vm.edit(EpubSettings(fontSizePercent = 175, scroll = true,
                textAlignment = ReadingTextAlignment.START)); vm.save()
            advanceUntilIdle()
            assertTrue(vm.state.value.saveFailed && vm.state.value.open)
            assertEquals(100, vm.state.value.saved.fontSizePercent)
            assertEquals(175, vm.state.value.draft.fontSizePercent)
            assertFalse(vm.state.value.saved.scroll)
            assertFalse(repository.values.value.scroll)
            assertTrue(vm.state.value.draft.scroll)
            assertEquals(ReadingTextAlignment.PUBLISHER, repository.values.value.textAlignment)
            assertEquals(ReadingTextAlignment.START, vm.state.value.draft.textAlignment)
            repository.failWrite = false
            vm.save(); advanceUntilIdle()
            assertEquals(175, vm.state.value.saved.fontSizePercent)
            assertTrue(vm.state.value.saved.scroll)
            assertTrue(repository.values.value.scroll)
            assertEquals(ReadingTextAlignment.START, repository.values.value.textAlignment)
            assertFalse(vm.state.value.saveFailed)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `read failure blocks overwriting unknown preferences and supports retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failRead = true; values.value = EpubSettings(theme = ReadingTheme.DARK,
                scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY) }
            val vm = EpubSettingsViewModel(repository).also { owner.put("settings", it) }
            runCurrent(); vm.open(); vm.edit(EpubSettings(theme = ReadingTheme.SEPIA)); vm.save()
            assertTrue(vm.state.value.loadFailed)
            assertEquals(0, repository.writes)
            assertTrue(repository.values.value.scroll)
            assertEquals(ReadingTextAlignment.JUSTIFY, repository.values.value.textAlignment)
            assertFalse(vm.state.value.draft.scroll)
            repository.failRead = false
            vm.reload(); runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertEquals(ReadingTheme.DARK, vm.state.value.draft.theme)
            assertTrue(vm.state.value.draft.scroll)
            assertEquals(ReadingTextAlignment.JUSTIFY, vm.state.value.draft.textAlignment)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `resetting saved vertical mode and alignment stays draft until a successful explicit save`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { values.value = EpubSettings(scroll = true,
                textAlignment = ReadingTextAlignment.JUSTIFY) }
            val vm = EpubSettingsViewModel(repository).also { owner.put("settings", it) }
            runCurrent(); vm.open(); vm.reset()
            assertFalse(vm.state.value.draft.scroll)
            assertTrue(vm.state.value.saved.scroll)
            assertTrue(repository.values.value.scroll)
            assertEquals(ReadingTextAlignment.PUBLISHER, vm.state.value.draft.textAlignment)
            assertEquals(ReadingTextAlignment.JUSTIFY, repository.values.value.textAlignment)
            assertEquals(0, repository.writes)
            vm.dismiss(); vm.open()
            assertTrue(vm.state.value.draft.scroll)
            assertEquals(ReadingTextAlignment.JUSTIFY, vm.state.value.draft.textAlignment)
            vm.reset(); vm.save(); runCurrent()
            assertTrue(vm.state.value.saving)
            assertTrue(repository.values.value.scroll)
            advanceUntilIdle()
            assertFalse(vm.state.value.open)
            assertFalse(vm.state.value.saved.scroll)
            assertFalse(repository.values.value.scroll)
            assertEquals(ReadingTextAlignment.PUBLISHER, repository.values.value.textAlignment)
            assertEquals(1, repository.writes)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}

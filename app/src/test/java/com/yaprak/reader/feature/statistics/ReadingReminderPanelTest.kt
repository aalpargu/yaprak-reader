package com.yaprak.reader.feature.statistics

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.domain.statistics.ReadingReminderRepository
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingReminderPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Store : ReadingReminderRepository {
        override val reminderMinuteOfDay = MutableStateFlow<Int?>(null)
        override suspend fun saveReminderMinuteOfDay(minuteOfDay: Int?) { reminderMinuteOfDay.value = minuteOfDay }
    }

    @Test fun turningOnPicksATimeArmsTheAlarmAndTurningOffDisarmsIt() {
        val store = Store(); val armed = mutableListOf<Int?>()
        val model = ReadingReminderViewModel(store) { armed += it }
        compose.setContent { YaprakTheme { ReadingReminderPanel(model) { true } } }
        compose.onNodeWithText("Kapalı").assertExists()
        compose.onNodeWithTag("statistics-reminder-switch").performClick()
        compose.onNodeWithText("Hatırlatma saati").assertExists()
        compose.onNodeWithTag("statistics-reminder-save").performClick()
        compose.waitForIdle()
        assertEquals(20 * 60, store.reminderMinuteOfDay.value)
        assertEquals(listOf<Int?>(20 * 60), armed)
        compose.onNodeWithText("Her gün 20:00", substring = true).assertExists()
        compose.onNodeWithTag("statistics-reminder-switch").performClick()
        compose.waitForIdle()
        assertNull(store.reminderMinuteOfDay.value)
        assertEquals(listOf(20 * 60, null), armed)
    }

    @Test fun cancellingTheTimeDialogChangesNothingAndMissingPermissionIsExplained() {
        val store = Store().apply { reminderMinuteOfDay.value = 7 * 60 + 30 }; val armed = mutableListOf<Int?>()
        val model = ReadingReminderViewModel(store) { armed += it }
        compose.setContent { YaprakTheme { ReadingReminderPanel(model) { false } } }
        compose.onNodeWithText("Her gün 07:30", substring = true).assertExists()
        compose.onNodeWithText("Bildirim izni kapalı", substring = true).assertExists()
        compose.onNodeWithTag("statistics-reminder-time").performClick()
        compose.onNodeWithText("Vazgeç").performClick()
        compose.waitForIdle()
        assertEquals(7 * 60 + 30, store.reminderMinuteOfDay.value); assertTrue(armed.isEmpty())
    }
}

package com.yaprak.reader.nativeui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native focus contracts only; these controlled components do not render or mutate a book. */
@RunWith(AndroidJUnit4::class)
class ReaderReturnFocusDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dialogReturnFocusKeepsTouchModeAndUsesCloseWhenCallerIsDisabled() {
        var editor by mutableStateOf(false)
        var writable by mutableStateOf(true)
        var requested by mutableStateOf<String?>(null)
        var draft by mutableStateOf("")
        var callerFocused = false
        var closeFocused = false
        var parentInput: InputModeManager? = null
        val restoredWithFocus = mutableListOf<Boolean>()
        fun cancel() {
            editor = false
            requested = if (writable) "caller" else "close"
        }
        compose.setContent { YaprakTheme {
            if (editor) Dialog(onDismissRequest = ::cancel) {
                Surface { Column(Modifier.padding(24.dp)) {
                    OutlinedTextField(draft, { draft = it }, Modifier.testTag("focus-draft"))
                    TextButton(::cancel, Modifier.testTag("focus-cancel")) { Text("Cancel") }
                } }
            } else Dialog(onDismissRequest = {}) {
                val input = LocalInputModeManager.current
                SideEffect { parentInput = input }
                Surface { Column(Modifier.padding(24.dp)) {
                    TextButton({ editor = true }, enabled = writable,
                        modifier = Modifier.testTag("focus-caller")
                            .onFocusChanged { callerFocused = it.isFocused }
                            .readerDialogReturnFocusTarget(requested == "caller") {
                                restoredWithFocus += callerFocused
                                requested = null
                            }) { Text("Edit") }
                    TextButton({}, Modifier.testTag("focus-close")
                        .onFocusChanged { closeFocused = it.isFocused }
                        .readerDialogReturnFocusTarget(requested == "close") {
                            restoredWithFocus += closeFocused
                            requested = null
                        }) { Text("Close") }
                } }
            }
        } }
        compose.onNodeWithTag("focus-caller").performTouchInput { click() }
        compose.onNodeWithTag("focus-caller").assertDoesNotExist()
        compose.onNodeWithTag("focus-draft").performTouchInput { click() }
        compose.onNodeWithTag("focus-draft").performTextReplacement("Uncommitted draft")
        compose.onNodeWithTag("focus-cancel").performTouchInput { click() }
        compose.waitUntil(10_000) { restoredWithFocus.size == 1 }
        compose.onNodeWithTag("focus-caller").assertIsDisplayed().assertIsFocused()
        compose.runOnIdle {
            assertEquals(InputMode.Touch, requireNotNull(parentInput).inputMode)
            assertEquals(listOf(true), restoredWithFocus)
        }

        compose.onNodeWithTag("focus-caller").performTouchInput { click() }
        compose.runOnIdle { writable = false }
        compose.onNodeWithTag("focus-cancel").performTouchInput { click() }
        compose.waitUntil(10_000) { restoredWithFocus.size == 2 }
        compose.onNodeWithTag("focus-caller").assertIsNotEnabled()
        compose.onNode(hasTestTag("focus-caller") and isFocused()).assertDoesNotExist()
        compose.onNodeWithTag("focus-close").assertIsDisplayed().assertIsFocused()
        compose.runOnIdle {
            assertEquals(InputMode.Touch, requireNotNull(parentInput).inputMode)
            assertEquals(listOf(true, true), restoredWithFocus)
        }
    }

    @Test fun presentationReturnFocusKeepsTouchModeAndPauseRevokesAnUnconsumedReturn() {
        val controller = ReaderPresentationController().apply {
            attach("native-focus")
            setControls(true)
        }
        var input: InputModeManager? = null
        var focus: FocusManager? = null
        val state = ReaderState.Ready(object : ReaderSession {
            override val book = Book("focus", "Focus fixture", null, BookFormat.EPUB,
                "focus.epub", "focus.epub", 1, "a".repeat(64), 1)
            override val resume: ReadingLocation? = null
            override fun createSurface(initial: ReadingLocation?, dark: Boolean,
                onTap: () -> Unit, onError: () -> Unit): ReaderSurface =
                error("The focus fixture cannot render a book")
            override fun close() = Unit
        })
        compose.setContent { YaprakTheme {
            val currentInput = LocalInputModeManager.current
            val currentFocus = LocalFocusManager.current
            SideEffect { input = currentInput; focus = currentFocus }
            ReaderChrome(state, true, false, false, {}, {}, {}, {}, {}, {}, {},
                presentationController = controller)
        } }
        val caller = ReaderPanelKind.CONTENT.focusKey
        val cancel = ReaderPanelKind.CONTENT.menuFocusKey
        compose.onNodeWithTag(caller).performTouchInput { click() }
        compose.onNodeWithTag(cancel).performTouchInput { click() }
        compose.waitUntil(10_000) { controller.state.value.returnFocus == null }
        compose.onNodeWithTag(caller).assertIsFocused()
        compose.runOnIdle { assertEquals(InputMode.Touch, requireNotNull(input).inputMode) }

        compose.onNodeWithTag(caller).performTouchInput { click() }
        // The Activity target stays composed behind the menu; clear its earlier focus so this
        // second return would need a new, still-current request to focus it after resume.
        compose.runOnIdle { requireNotNull(focus).clearFocus(force = true) }
        compose.onNodeWithTag(caller).assertIsNotFocused()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag(cancel).performTouchInput { click() }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertNotNull("Pause must cancel an actual pending return", controller.state.value.returnFocus)
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag(caller).assertIsNotFocused()
        compose.runOnIdle {
            assertNull(controller.state.value.returnFocus)
            assertEquals(InputMode.Touch, requireNotNull(input).inputMode)
        }
    }
}

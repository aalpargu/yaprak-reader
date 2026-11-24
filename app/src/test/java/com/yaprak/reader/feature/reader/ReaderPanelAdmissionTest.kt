package com.yaprak.reader.feature.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPanelAdmissionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun queuedButtonsAreRevokedByPauseDisposalAndSourceReplacement() {
        val visible = mutableStateOf(true)
        val child = mutableStateOf<String?>(null)
        val allowed = AtomicBoolean(true)
        var command: (() -> Unit)? = null
        var mutations = 0
        compose.setContent {
            if (visible.value) {
                val fresh = rememberReaderPanelAdmission(child.value) { allowed.get() }
                val click = { if (fresh()) mutations++ }
                SideEffect { command = click }
                Button(onClick = click) { Text("Save user draft") }
            }
        }
        compose.waitForIdle()
        val beforePause = checkNotNull(command)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.runOnIdle { beforePause() }
        assertEquals(0, mutations)
        compose.onNodeWithText("Save user draft").performClick()
        assertEquals(1, mutations)
        val beforeChild = checkNotNull(command)
        compose.runOnIdle { child.value = "edit-row" }
        compose.waitForIdle()
        compose.runOnIdle { beforeChild() }
        assertEquals(1, mutations)
        val beforeDispose = checkNotNull(command)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { beforeDispose() }
        assertEquals(1, mutations)
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        allowed.set(false)
        compose.runOnIdle { checkNotNull(command)() }
        assertEquals(1, mutations)
    }
}

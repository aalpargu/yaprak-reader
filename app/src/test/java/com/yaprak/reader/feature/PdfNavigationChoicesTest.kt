package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.yaprak.reader.domain.settings.PdfNavigationMode
import com.yaprak.reader.feature.reader.PdfNavigationChoices
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native input/text acceptance; compiled but pending on hosts that block Robolectric native DLLs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfNavigationChoicesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun selectedAxisIsAccessibleAndDisposedOrDisabledActionsCannotChooseAgain() {
        val current = mutableStateOf(PdfNavigationMode.PAGED)
        val enabled = mutableStateOf(true)
        val choices = mutableListOf<PdfNavigationMode>()
        compose.setContent { YaprakTheme {
            PdfNavigationChoices(current.value, enabled.value) { choices += it; current.value = it }
        } }
        val horizontal = compose.onNodeWithTag("pdf-navigation-HORIZONTAL_SCROLL")
        val old = horizontal.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        horizontal.assertIsNotSelected().performClick().assertIsSelected()
        compose.runOnIdle { old() }
        assertEquals(listOf(PdfNavigationMode.HORIZONTAL_SCROLL), choices)
        val vertical = compose.onNodeWithTag("pdf-navigation-VERTICAL_SCROLL")
        val stale = vertical.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { enabled.value = false }
        vertical.assertIsNotEnabled()
        compose.runOnIdle { stale() }
        assertEquals(listOf(PdfNavigationMode.HORIZONTAL_SCROLL), choices)
    }

    @Test fun doubleFontShortWindowKeepsEveryChoiceScrollableAndSelectedWithoutTruncation() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        val current = mutableStateOf(PdfNavigationMode.PAGED)
        compose.setContent { YaprakTheme {
            Column(Modifier.height(160.dp).verticalScroll(rememberScrollState())) {
                PdfNavigationChoices(current.value, true) { current.value = it }
            }
        } }
        for (mode in PdfNavigationMode.entries) {
            compose.onNodeWithTag("pdf-navigation-" + mode.name).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        }
    }
}

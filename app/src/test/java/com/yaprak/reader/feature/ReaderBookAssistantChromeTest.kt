package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.ReaderSession
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

/** Native widget geometry/events only; original-source and actual Activity navigation are separate acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderBookAssistantChromeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @After fun resetFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }
    private fun ready(format: BookFormat)=ReaderState.Ready(object: ReaderSession {
        override val book=Book("owned", "Owned",null,format,"source.docx","owned.docx",1,"a".repeat(64),1)
        override val resume: ReadingLocation?=null
        override fun createSurface(initial: ReadingLocation?,dark: Boolean,onTap: ()->Unit,onError: ()->Unit)=error("No reader rendering in chrome test")
        override fun close()=Unit
    })
    @Test fun directAssistantWrapsAtDoubleFontAndOldWidgetCannotSurviveNewBookCallbackHiddenOrFailedReader() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) };compose.activityRule.scenario.recreate()
        var state by mutableStateOf(ready(BookFormat.DOCX));var visible by mutableStateOf(true)
        var loadFailed by mutableStateOf(false);var saveFailed by mutableStateOf(false)
        var callback by mutableStateOf<(() -> Unit)?>(null);val events=mutableListOf<String>()
        val label=compose.activity.getString(R.string.reader_book_assistant)
        compose.setContent { YaprakTheme { ReaderChrome(state,visible,saveFailed,loadFailed,{},{},{},{},{},{},{},
            onBookAssistant=callback) } }
        compose.onNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { callback={ events+="first" } }
        val node=compose.onNodeWithText(label,useUnmergedTree=true).performScrollTo().assertIsDisplayed()
        val layouts=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout=layouts.single();assertEquals(2f,layout.layoutInput.density.fontScale);assertFalse(layout.hasVisualOverflow)
        fun action()=compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val old=action();compose.runOnIdle { callback={ events+="next" } };compose.runOnIdle { old() };assertTrue(events.isEmpty())
        compose.onNodeWithText(label).performScrollTo().performClick();assertEquals(listOf("next"),events)
        val priorBook=action();compose.runOnIdle { state=ready(BookFormat.EPUB) };compose.runOnIdle { priorBook() }
        assertEquals(1,events.size)
        val shown=action();compose.runOnIdle { visible=false };compose.runOnIdle { shown() };assertEquals(1,events.size)
        compose.onNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { visible=true;state=ready(BookFormat.CBZ) };compose.onNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { state=ready(BookFormat.DOCX);loadFailed=true };compose.onNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { loadFailed=false;saveFailed=true };compose.onNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { saveFailed=false };compose.onNodeWithText(label).assertExists()
    }
}

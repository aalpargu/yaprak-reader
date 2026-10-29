package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.domain.search.LibraryTextBookState
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookAssistantBookIndexChromeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun bookIndexIsASeparateExplicitActionAndOldEventsCannotRebuildPrepareOrSend() {
        var state by mutableStateOf(BookAssistantState(active=true,book=F.book,revision=7,indexState=LibraryTextBookState.NOT_INDEXED))
        val builds=mutableListOf<Long>();var global=0;var proofs=0;var sends=0
        compose.setContent { YaprakTheme { BookAssistantScreen(state,{},{},{},{},onPrepare={ proofs++ },onSend={ sends++ },
            onCancel={},onOpen={ _,_-> },onBack={},onOpenIndex={ global++ },onPrepareIndex={ revision->
                builds+=revision;state=state.copy(revision=state.revision+1,busy=true)
            }) } }
        val button=compose.onNodeWithTag("assistant-prepare-index").performScrollTo().assertIsDisplayed()
        val old=button.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        assertTrue(builds.isEmpty());button.performClick();assertEquals(listOf(7L),builds)
        compose.onNodeWithTag("assistant-prepare-index").assertIsNotEnabled();compose.runOnIdle { old() }
        assertEquals(1,builds.size);assertEquals(0,global);assertEquals(0,proofs);assertEquals(0,sends)
        compose.runOnIdle { state=state.copy(revision=9,busy=false,indexState=LibraryTextBookState.READY) }
        compose.onNodeWithTag("assistant-prepare-index").assertDoesNotExist();compose.runOnIdle { old() }
        assertEquals(1,builds.size);assertNull(state.sources);assertFalse(state.canSend)
        compose.runOnIdle { state=state.copy(revision=10,active=false,indexState=LibraryTextBookState.NOT_INDEXED) }
        compose.onNodeWithTag("assistant-prepare-index").performScrollTo().assertIsNotEnabled()
        assertEquals(0,global);assertEquals(0,proofs);assertEquals(0,sends)
    }
}

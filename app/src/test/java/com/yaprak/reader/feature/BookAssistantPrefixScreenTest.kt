package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.PrefixAssistantFixtures as P
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
class BookAssistantPrefixScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun prefixPreviewShowsOnlyAdmittedSlicesAndOldActionsCannotChangeBoundaryOrSend() {
        var state by mutableStateOf(BookAssistantState(active=true,book=F.book,revision=7,sourceMode=BookAssistantSourceMode.PREFIX,
            chapters=listOf(P.chapter),chaptersLoaded=true,chapter=P.chapter,sources=P.sources(),question="Soru"))
        var rendered by mutableStateOf(true);var choices=0;var sends=0
        compose.setContent { YaprakTheme { if(rendered) BookAssistantScreen(state,{},{},{},{},{},{ sends++ },{}, { _,_-> },{},onChapter={ _,_->choices++ }) } }
        fun scroll(tag: String)=compose.onNodeWithTag("assistant-list").performScrollToNode(hasTestTag(tag))
        scroll("assistant-source-S1");compose.onNodeWithText(P.reference.text).assertIsDisplayed()
        compose.onNodeWithText(P.raw).assertDoesNotExist();compose.onNodeWithText("LATER_SECRET",substring=true).assertDoesNotExist()
        scroll("assistant-chapter-0");val old=compose.onNodeWithTag("assistant-chapter-0").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        scroll("assistant-send");val send=compose.onNodeWithTag("assistant-send").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        assertEquals(0,choices);assertEquals(0,sends)
        compose.runOnIdle { state=state.copy(revision=8,busy=true) };compose.runOnIdle { old();send() }
        assertEquals(0,choices);assertEquals(0,sends)
        compose.runOnIdle { rendered=false };compose.runOnIdle { old();send() }
        assertEquals(0,choices);assertEquals(0,sends)
    }
}

package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.BookSourceFailure
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
class BookAssistantIndexChromeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun missingIndexHasAnExplicitLocalActionAndReadyPausedBusyOrDisposedEventsCannotOpenIt() {
        var state by mutableStateOf(BookAssistantState(active=true,book=F.book,revision=7,indexState=LibraryTextBookState.NOT_INDEXED))
        var shown by mutableStateOf(true);val opens=mutableListOf<Long>();var sends=0;var preparations=0
        compose.setContent { YaprakTheme { if(shown) BookAssistantScreen(state,{},{},{},{},
            onPrepare={ preparations++ },onSend={ sends++ },onCancel={},onOpen={ _,_-> },onBack={},onOpenIndex={ opens+=it }) } }
        val node=compose.onNodeWithTag("assistant-open-index").performScrollTo().assertIsDisplayed()
        val old=node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        assertTrue(opens.isEmpty());assertEquals(0,sends);assertEquals(0,preparations)
        node.performClick();assertEquals(listOf(7L),opens)
        compose.runOnIdle { state=state.copy(revision=8,indexState=LibraryTextBookState.READY) }
        compose.runOnIdle { old() };assertEquals(1,opens.size);compose.onNodeWithTag("assistant-open-index").assertDoesNotExist()
        compose.runOnIdle { state=state.copy(revision=9,indexState=LibraryTextBookState.NOT_INDEXED,busy=true) }
        compose.onNodeWithTag("assistant-open-index").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state=state.copy(revision=10,busy=false,active=false) }
        compose.onNodeWithTag("assistant-open-index").assertIsNotEnabled()
        compose.runOnIdle { shown=false };compose.runOnIdle { old() };assertEquals(1,opens.size)
        compose.runOnIdle { shown=true;state=state.copy(revision=11,active=true,indexState=LibraryTextBookState.LIMIT,sourceFailure=BookSourceFailure.LIMIT) }
        compose.onNodeWithTag("assistant-open-index").assertDoesNotExist()
        compose.onNodeWithTag("assistant-list").performScrollToKey("source-error")
        compose.onNodeWithText(compose.activity.getString(R.string.assistant_source_preparation_limit)).assertIsDisplayed()
        compose.runOnIdle { state=state.copy(revision=12,indexState=LibraryTextBookState.NO_TEXT,sourceFailure=BookSourceFailure.UNSUPPORTED,
            book=F.book.copy(format=BookFormat.DOCX,originalName="source.docx",storageName="source.docx")) }
        compose.onNodeWithTag("assistant-open-index").assertDoesNotExist()
        compose.onNodeWithTag("assistant-list").performScrollToKey("source-error")
        compose.onNodeWithText(compose.activity.getString(R.string.assistant_index_no_text)).assertIsDisplayed()
        assertEquals(1,opens.size);assertEquals(0,sends);assertEquals(0,preparations)
    }
}

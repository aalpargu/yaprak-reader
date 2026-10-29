package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.R
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.search.*
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
class LibraryTextSelectedBuildChromeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun scopedLabelHonestOutcomesAndStaleScopeAbaButtonsCannotStartNewJobs() {
        var state by mutableStateOf(LibraryTextSearchState(active=true,loading=false,scope=F.scope,revision=7,
            result=LibraryTextSearchResult("",emptyList(),0,listOf(LibraryTextBookStatus(F.book,LibraryTextBookState.READY)),F.scope)))
        var rendered by mutableStateOf(true);var builds=0
        compose.setContent { YaprakTheme { if(rendered) LibraryTextSearchScreen(state,{},{ _,_-> },{},{ builds++ },{}, {},{}) } }
        compose.onNodeWithTag("library-text-index-tab").performClick()
        compose.onNodeWithTag("library-text-rebuild").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.library_text_selected_rebuild)).assertIsDisplayed()
        val old=compose.onNodeWithTag("library-text-rebuild").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state=state.copy(scope=null,revision=8) };compose.runOnIdle { old() }
        compose.runOnIdle { state=state.copy(scope=F.scope,revision=9) };compose.runOnIdle { old() }
        assertEquals(0,builds)
        for((outcome,message) in listOf(LibraryTextBookState.NO_TEXT to R.string.library_text_selected_no_text,
            LibraryTextBookState.UNSUPPORTED to R.string.library_text_selected_unsupported,LibraryTextBookState.LIMIT to R.string.library_text_selected_limit,
            LibraryTextBookState.FAILED to R.string.library_text_selected_failed)) {
            compose.runOnIdle { state=state.copy(build=LibraryTextBuildPhase.Finished(1,false,outcome),revision=state.revision+1) }
            compose.onNodeWithText(compose.activity.getString(message)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.library_text_selected_ready)).assertDoesNotExist()
        }
        compose.runOnIdle { rendered=false };compose.runOnIdle { old() };assertEquals(0,builds)
    }
}

package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.reader.BookPositionJumpController
import com.yaprak.reader.feature.reader.BookPositionJumpDialog
import com.yaprak.reader.reader.BookPositionNavigator
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookPositionJumpDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `unknown position and invalid input cannot jump while a valid IME action submits exactly once`() {
        val navigator = Navigator()
        show(navigator, null)
        compose.onNodeWithTag(INPUT).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Git").assertIsNotEnabled()
        listOf("101", "-1", "+2", "1.5", "٠", "100" + " ".repeat(40) + "abc").forEach { invalid ->
            compose.onNodeWithTag(INPUT).performTextReplacement(invalid)
            compose.onNodeWithText("Git").assertIsNotEnabled()
            compose.onNodeWithTag(INPUT).performImeAction()
            assertTrue(navigator.requests.isEmpty())
        }
        compose.onNodeWithTag(INPUT).assertTextContains("…", substring = true)
        compose.onNodeWithTag(INPUT).performTextReplacement(" 100 ")
        compose.onNodeWithText("Git").assertIsEnabled()
        compose.onNodeWithTag(INPUT).performImeAction()
        compose.waitUntil(5_000) { navigator.targets.isNotEmpty() }
        assertEquals(listOf(100), navigator.requests)
        assertEquals(listOf(location(100)), navigator.targets)
        compose.onNodeWithText("Kitap yüzdesine git").assertDoesNotExist()
    }

    @Test fun `resolving disables duplicate submits and cancel blocks a noncooperative late navigation`() {
        val result = CompletableDeferred<ReadingLocation?>()
        try {
            val navigator = Navigator { withContext(NonCancellable) { result.await() } }
            val controller = show(navigator, 42)
            compose.onNodeWithText("Git").performClick()
            compose.onNodeWithText("Kitap konumu bulunuyor…").assertIsDisplayed()
            compose.onNodeWithTag(INPUT).assertIsNotEnabled()
            compose.onNodeWithText("Git").assertIsNotEnabled().performClick()
            compose.onNodeWithText("Vazgeç").assertIsEnabled().performClick()
            compose.onNodeWithText("Kitap yüzdesine git").assertDoesNotExist()
            val jobs = compose.runOnIdle { controller.stopJobs() }
            assertEquals(1, jobs.size)
            compose.runOnIdle { result.complete(location(42)) }
            compose.waitUntil(5_000) { jobs.single().isCompleted }
            assertEquals(listOf(42), navigator.requests)
            assertTrue(navigator.targets.isEmpty())
        } finally { result.complete(null) }
    }

    @Test fun `rejected navigation preserves editable draft and an explicit retry can succeed`() {
        val navigator = Navigator().apply { accept = false }
        show(navigator, 30)
        compose.onNodeWithTag(INPUT).performTextReplacement("12")
        compose.onNodeWithText("Git").performClick()
        compose.onNodeWithText("Bu kitap konumuna gidilemedi. Yeniden deneyebilirsin.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(INPUT).assertTextContains("12").assertIsEnabled()
        compose.onNodeWithText("Git").assertIsEnabled()
        compose.runOnIdle { navigator.accept = true }
        compose.onNodeWithText("Git").performClick()
        compose.waitUntil(5_000) { navigator.targets.size == 2 }
        assertEquals(listOf(12, 12), navigator.requests)
        compose.onNodeWithText("Kitap yüzdesine git").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land-mdpi")
    fun `large font landscape keeps field submit and busy cancel reachable`() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        val result = CompletableDeferred<ReadingLocation?>()
        val navigator = Navigator { result.await() }
        show(navigator, 7)
        compose.onNodeWithText("Kitap yüzdesine git").assertFontScale(1.5f)
        compose.onNodeWithTag(INPUT).performScrollTo().assertIsDisplayed().performTextReplacement("99")
        compose.onNodeWithText("Git").assertIsDisplayed().performClick()
        compose.onNodeWithText("Kitap konumu bulunuyor…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Git").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(navigator.targets.isEmpty())
        assertEquals(listOf(99), navigator.requests)
    }

    private fun show(navigator: Navigator, current: Int?): BookPositionJumpController {
        var active: BookPositionJumpController? = null
        compose.setContent { YaprakTheme {
            val scope = rememberCoroutineScope()
            val controller = remember(scope) { BookPositionJumpController(scope) }
            active = controller
            val state by controller.state.collectAsState()
            LaunchedEffect(controller) { controller.open(navigator, current) }
            BookPositionJumpDialog(state, controller::edit,
                { controller.submit({ true }) { surface, target -> surface.goTo(target) } },
                controller::dismiss)
        } }
        compose.waitForIdle()
        return requireNotNull(active)
    }

    private class Navigator(var resolve: suspend (Int) -> ReadingLocation? = { location(it) }) : BookPositionNavigator {
        val requests = mutableListOf<Int>()
        val targets = mutableListOf<ReadingLocation>()
        var accept = true
        override suspend fun resolvePercent(percent: Int): ReadingLocation? {
            requests += percent
            return resolve(percent)
        }
        override fun goTo(location: ReadingLocation): Boolean { targets += location; return accept }
    }

    private companion object {
        const val INPUT = "book-position-input"
        fun location(percent: Int) = ReadingLocation("position-$percent", "chapter.xhtml")
    }
}

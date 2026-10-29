package com.yaprak.reader.feature

import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.reader.BookPositionJumpController
import com.yaprak.reader.reader.BookPositionNavigator
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookPositionJumpControllerTest {
    @Test fun `unknown position stays blank and invalid or overlong input never resolves a clamped target`() = runTest {
        withController { controller, _ ->
            val navigator = Navigator()
            controller.open(navigator, null)
            assertEquals("", controller.state.value.input)
            assertFalse(controller.state.value.canSubmit)
            listOf("", "101", "-1", "+1", "1.5", "١", "1e2", "NaN",
                "100" + " ".repeat(40) + "abc").forEach { input ->
                controller.edit(input)
                submit(controller)
                runCurrent()
                assertFalse(controller.state.value.canSubmit)
                assertTrue(navigator.requests.isEmpty())
                assertNotNull(controller.state.value.error)
            }
            assertTrue(controller.state.value.input.length <= 33)
            assertTrue(controller.state.value.input.endsWith("…"))
            controller.edit(" 0 ")
            submit(controller)
            runCurrent()
            assertEquals(listOf(0), navigator.requests)
            controller.open(navigator, 101)
            assertEquals("", controller.state.value.input)
            controller.edit("100")
            submit(controller)
            runCurrent()
            assertEquals(listOf(0, 100), navigator.requests)
        }
    }

    @Test fun `busy blocks edits and duplicate submits and accepted navigation closes only after resolution`() = runTest {
        val result = CompletableDeferred<ReadingLocation?>()
        withController { controller, _ ->
            val navigator = Navigator { result.await() }
            controller.open(navigator, 42)
            submit(controller)
            assertTrue(controller.state.value.resolving)
            controller.edit("99")
            submit(controller)
            runCurrent()
            assertEquals("42", controller.state.value.input)
            assertEquals(listOf(42), navigator.requests)
            assertTrue(navigator.targets.isEmpty())
            assertTrue(controller.state.value.open)
            result.complete(location(42))
            runCurrent()
            assertEquals(listOf(location(42)), navigator.targets)
            assertFalse(controller.state.value.open || controller.state.value.resolving)
            assertEquals("", controller.state.value.input)
        }
    }

    @Test fun `null resolution exceptions and rejected navigation retain draft with a truthful retry error`() = runTest {
        withController { controller, _ ->
            val navigator = Navigator { null }
            controller.open(navigator, 30)
            submit(controller)
            runCurrent()
            assertTrue(controller.state.value.open && controller.state.value.canSubmit)
            assertNotNull(controller.state.value.error)
            assertTrue(navigator.targets.isEmpty())
            navigator.resolve = { throw IOException("unavailable") }
            submit(controller)
            runCurrent()
            assertEquals("30", controller.state.value.input)
            assertNotNull(controller.state.value.error)
            navigator.resolve = { location(it) }
            navigator.accept = false
            submit(controller)
            runCurrent()
            assertTrue(controller.state.value.open)
            assertNotNull(controller.state.value.error)
            navigator.accept = true
            submit(controller)
            runCurrent()
            assertFalse(controller.state.value.open)
            assertEquals(listOf(30, 30, 30, 30), navigator.requests)
            assertEquals(listOf(location(30), location(30)), navigator.targets)
        }
    }

    @Test fun `cancel retains draft tracks unfinished cancelled work and rejects a noncooperative late result`() = runTest {
        val result = CompletableDeferred<ReadingLocation?>()
        try {
            withController { controller, _ ->
                val navigator = Navigator { withContext(NonCancellable) { result.await() } }
                controller.open(navigator, 42)
                submit(controller)
                runCurrent()
                controller.cancel()
                assertTrue(controller.state.value.open && controller.state.value.canSubmit)
                assertEquals("42", controller.state.value.input)
                assertNull(controller.state.value.error)
                val unfinished = controller.stopJobs()
                assertEquals(1, unfinished.size)
                assertTrue(unfinished.single().isCancelled)
                assertFalse(unfinished.single().isCompleted)
                result.complete(location(42))
                runCurrent()
                assertTrue(unfinished.single().isCompleted)
                assertTrue(navigator.targets.isEmpty())
                assertTrue(controller.state.value.open)
                assertNull(controller.state.value.error)
            }
        } finally { result.complete(null) }
    }

    @Test fun `dismiss and a newer navigator invalidate late resolution without closing the new dialog`() = runTest {
        val result = CompletableDeferred<ReadingLocation?>()
        try {
            withController { controller, _ ->
                val first = Navigator { withContext(NonCancellable) { result.await() } }
                val second = Navigator()
                controller.open(first, 80)
                submit(controller)
                runCurrent()
                controller.dismiss()
                assertFalse(controller.state.value.open)
                controller.open(second, 15)
                result.complete(location(80))
                runCurrent()
                assertTrue(first.targets.isEmpty())
                assertTrue(controller.state.value.open)
                assertEquals("15", controller.state.value.input)
                assertNull(controller.state.value.error)
                submit(controller)
                runCurrent()
                assertEquals(listOf(location(15)), second.targets)
                assertFalse(controller.state.value.open)
            }
        } finally { result.complete(null) }
    }

    @Test fun `reader eligibility is rechecked after resolution and a later explicit retry can navigate`() = runTest {
        val result = CompletableDeferred<ReadingLocation?>()
        withController { controller, _ ->
            val navigator = Navigator { result.await() }
            var eligible = true
            controller.open(navigator, 50)
            submit(controller) { eligible }
            runCurrent()
            eligible = false
            result.complete(location(50))
            runCurrent()
            assertTrue(navigator.targets.isEmpty())
            assertTrue(controller.state.value.open && controller.state.value.canSubmit)
            assertNotNull(controller.state.value.error)
            eligible = true
            submit(controller) { eligible }
            runCurrent()
            assertEquals(listOf(location(50)), navigator.targets)
            assertFalse(controller.state.value.open)
        }
    }

    @Test fun `resolver cancellation leaves no failure notice and a fresh attempt remains possible`() = runTest {
        withController { controller, _ ->
            val navigator = Navigator { throw CancellationException("reader paused") }
            controller.open(navigator, 20)
            submit(controller)
            runCurrent()
            assertTrue(controller.state.value.open && controller.state.value.canSubmit)
            assertNull(controller.state.value.error)
            assertTrue(navigator.targets.isEmpty())
            navigator.resolve = { location(it) }
            submit(controller)
            runCurrent()
            assertEquals(listOf(location(20)), navigator.targets)
        }
    }

    @Test fun `inactive owner cannot start resolution or leave the dialog stuck busy`() = runTest {
        withController { controller, owner ->
            val navigator = Navigator()
            controller.open(navigator, 10)
            owner.cancel()
            submit(controller)
            runCurrent()
            assertFalse(controller.state.value.resolving)
            assertTrue(navigator.requests.isEmpty())
        }
    }

    private suspend fun TestScope.withController(
        action: suspend (BookPositionJumpController, CoroutineScope) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val controller = BookPositionJumpController(owner)
        try { action(controller, owner) }
        finally {
            controller.stopJobs()
            owner.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun submit(controller: BookPositionJumpController,
        gate: (BookPositionNavigator) -> Boolean = { true }) {
        controller.submit(gate) { navigator, target -> navigator.goTo(target) }
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
        fun location(percent: Int) = ReadingLocation("position-$percent", "chapter.xhtml")
    }
}

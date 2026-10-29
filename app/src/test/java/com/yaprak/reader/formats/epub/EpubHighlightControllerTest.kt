package com.yaprak.reader.formats.epub

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.reader.EpubHighlightFailure
import com.yaprak.reader.reader.EpubHighlightStatus
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubHighlightControllerTest {
    private val hash = "a".repeat(64)
    private val text = "Unique selected source"
    private val first = EpubPassage("one", "Unique", SourceTextAnchor("chapter.xhtml", 0, 6, hash), 1,
        EpubHighlightColor.YELLOW)
    private val second = first.copy(id = "two", anchor = (first.anchor as SourceTextAnchor).copy(resourceHref = "other.xhtml"))
    private val range = EpubPassage("multi", "ab cd", SourceRangeAnchor("chapter.xhtml", listOf(
        SourceRangeSegment("0/0", 1, 3, hash, "ab"),
        SourceRangeSegment("0/1/0", 0, 2, hash, "cd"))), 1, EpubHighlightColor.BLUE, revision = 9)
    private val topology: List<List<Any>> = listOf(
        listOf(1, "http://www.w3.org/1999/xhtml", "body", 1),
        listOf(1, "http://www.w3.org/1999/xhtml", "p", 2), listOf(3, "xab"),
        listOf(1, "http://www.w3.org/1999/xhtml", "em", 1), listOf(3, "cdy"))
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Document {
        val requests = mutableListOf<JSONObject>()
        var beginState = "begun"
        var applyState = "ready"
        var omitToken = false
        var wrongCount = false
        var mutated = false
        var resolvedOverride: Int? = null
        var beforeReply: suspend (JSONObject) -> Unit = {}
        suspend fun evaluate(script: String): String {
            val request = JSONObject(script); requests += request; beforeReply(request)
            val op = request.getString("op")
            val reply = JSONObject().put("owner", request.getString("owner"))
                .put("sourceHash", request.getString("sourceHash"))
                .put("generation", request.getLong("generation")).put("path", request.getString("path"))
            if (!omitToken) reply.put("documentToken", "11111111-1111-1111-1111-111111111111")
            val state = when (op) {
                "begin" -> beginState
                "clear" -> "cleared"
                "apply" -> applyState
                else -> if (mutated) "mutated" else applyState
            }
            reply.put("state", state)
            val total = if (op == "apply") request.getInt("total") else requests.lastOrNull {
                it.getString("op") == "apply"
            }?.getInt("total") ?: 0
            reply.put("total", if (wrongCount) total + 1 else total).put("resolved", resolvedOverride ?: total)
            return JSONObject.quote(reply.toString())
        }
        fun count(op: String) = requests.count { it.getString("op") == op }
    }
    private suspend fun TestScope.fixture(
        records: Flow<List<EpubPassage>> = MutableStateFlow(listOf(first)),
        block: suspend TestScope.(Owner, MutableStateFlow<String>, Document, EpubHighlightController) -> Unit,
        prove: suspend (String, List<SourceAnchorRequest>) -> SourceAnchorBatch = { _, requests ->
            SourceAnchorBatch(requests.associate { it.id to text })
        },
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = Owner(); owner.registry.currentState = Lifecycle.State.CREATED
        val hrefs = MutableStateFlow("chapter.xhtml"); val document = Document()
        val controller = EpubHighlightController(owner.lifecycle, hrefs, records, "/*YAPRAK_REQUEST*/", hash,
            { true }, document::evaluate, prove)
        try { block(owner, hrefs, document, controller) }
        finally { controller.close(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun `foreground gates source and paint while pause clears and resume reads latest href`() = runTest {
        fixture(block = { owner, hrefs, document, controller ->
            runCurrent(); assertTrue(document.requests.isEmpty())
            owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(EpubHighlightStatus.Ready(1), controller.status.value)
            owner.registry.currentState = Lifecycle.State.CREATED; runCurrent()
            val count = document.requests.size; assertTrue(document.count("clear") > 0)
            hrefs.value = "other.xhtml"; advanceTimeBy(5_000); runCurrent()
            assertEquals(count, document.requests.size)
            owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(EpubHighlightStatus.Ready(0), controller.status.value)
            assertEquals("/publication/other.xhtml", document.requests.last { it.getString("op") == "apply" }.getString("path"))
            val emptyRequests = document.requests.size; advanceTimeBy(5_000); runCurrent()
            assertEquals(emptyRequests, document.requests.size)
        })
    }

    @Test fun `batch uses only colored current resource records and source proof remains transient`() = runTest {
        val source = MutableStateFlow(listOf(first, second, first.copy(id = "plain", highlightColor = null)))
        val proven = mutableListOf<List<SourceAnchorRequest>>()
        fixture(records = source, prove = { _, requests -> proven += requests; SourceAnchorBatch(requests.associate { it.id to text }) },
            block = { owner, _, document, controller ->
                owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
                assertEquals(listOf("one"), proven.single().map { it.id })
                val apply = document.requests.single { it.getString("op") == "apply" }
                assertEquals(1, apply.getInt("total")); assertEquals(text, apply.getJSONArray("nodes").getString(0))
                source.value = listOf(first.copy(highlightColor = EpubHighlightColor.BLUE)); runCurrent()
                assertEquals("BLUE", document.requests.last { it.getString("op") == "apply" }
                    .getJSONArray("ranges").getJSONObject(0).getString("color"))
                assertEquals(EpubHighlightStatus.Ready(1), controller.status.value)
            })
    }

    @Test fun `page replacement invalidates in-flight noncooperative proof before queued reload`() = runTest {
        val release = CompletableDeferred<Unit>(); var reads = 0
        fixture(prove = { _, requests ->
            if (++reads == 1) withContext(NonCancellable) { release.await() }
            SourceAnchorBatch(requests.associate { it.id to text })
        }, block = { owner, _, document, controller ->
            owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(1, reads); assertEquals(0, document.count("apply"))
            controller.documentLoaded(); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals(2, reads); assertEquals(1, document.count("apply"))
            val begin = document.requests.filter { it.getString("op") == "begin" }
            assertEquals(2, begin.size)
            assertEquals(begin.last().getLong("generation"), document.requests.single { it.getString("op") == "apply" }.getLong("generation"))
            assertEquals(EpubHighlightStatus.Ready(1), controller.status.value)
        })
    }

    @Test fun `source failure is visible and explicit retry resubscribes without automatic loop`() = runTest {
        var fail = true; var subscriptions = 0
        val source = flow<List<EpubPassage>> {
            subscriptions++; if (fail) throw IllegalStateException("fixture source unavailable")
            emit(listOf(first)); awaitCancellation()
        }
        fixture(records = source, block = { owner, _, document, controller ->
            owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(EpubHighlightStatus.Failed(EpubHighlightFailure.SOURCE_UNAVAILABLE), controller.status.value)
            advanceTimeBy(5_000); runCurrent(); assertEquals(1, subscriptions); assertEquals(0, document.count("apply"))
            fail = false; controller.retry(); runCurrent()
            assertEquals(2, subscriptions); assertEquals(EpubHighlightStatus.Ready(1), controller.status.value)
        })
    }

    @Test fun `unsupported missing document identity and dishonest totals never publish ready`() = runTest {
        fixture(block = { owner, _, document, controller ->
            document.beginState = "unsupported"; owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(EpubHighlightStatus.Unsupported, controller.status.value); assertEquals(0, document.count("apply"))
            document.beginState = "begun"; document.omitToken = true; controller.retry(); runCurrent()
            assertEquals(EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED), controller.status.value)
            document.omitToken = false; document.wrongCount = true; controller.retry(); runCurrent()
            assertEquals(EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED), controller.status.value)
        })
    }

    @Test fun `source and DOM limits fail before paint and duplicates never start source proof`() = runTest {
        val rows = MutableStateFlow(List(1001) { first.copy(id = "$it") }); var proofs = 0
        fixture(records = rows, prove = { _, _ -> proofs++; SourceAnchorBatch(emptyMap(), SourceProofFailure.LIMIT) },
            block = { owner, _, document, controller ->
                owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
                assertEquals(EpubHighlightStatus.Failed(EpubHighlightFailure.SOURCE_LIMIT), controller.status.value)
                assertEquals(0, proofs); assertEquals(0, document.count("apply"))
                rows.value = listOf(first, first); runCurrent(); assertEquals(0, proofs)
                rows.value = listOf(first); runCurrent(); assertEquals(1, proofs)
                assertEquals(EpubHighlightStatus.Failed(EpubHighlightFailure.SOURCE_LIMIT), controller.status.value)
            })
        fixture(block = { owner, _, document, controller ->
            document.applyState = "limit"; owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(EpubHighlightStatus.Failed(EpubHighlightFailure.DOM_LIMIT), controller.status.value)
        })
    }

    @Test fun `mutation monitor revalidates once then stops until explicit retry`() = runTest {
        fixture(block = { owner, _, document, controller ->
            owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent(); document.mutated = true
            advanceTimeBy(1_000); runCurrent(); assertEquals(2, document.count("apply"))
            advanceTimeBy(1_000); runCurrent()
            assertEquals(EpubHighlightStatus.Partial(1, 1), controller.status.value)
            advanceTimeBy(20_000); runCurrent(); assertEquals(2, document.count("apply"))
            document.mutated = false; controller.retry(); runCurrent()
            assertEquals(3, document.count("apply")); assertEquals(EpubHighlightStatus.Ready(1), controller.status.value)
        })
    }

    @Test fun `pause rejects late noncooperative apply and retry cannot reactivate background reader`() = runTest {
        fixture(block = { owner, _, document, controller ->
            val release = CompletableDeferred<Unit>()
            document.beforeReply = { if (it.getString("op") == "apply") withContext(NonCancellable) { release.await() } }
            owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(EpubHighlightStatus.Loading, controller.status.value)
            owner.registry.currentState = Lifecycle.State.CREATED; runCurrent(); controller.retry(); runCurrent()
            release.complete(Unit); runCurrent()
            assertFalse(controller.status.value is EpubHighlightStatus.Ready); assertEquals(1, document.count("begin"))
            assertTrue(document.count("clear") > 0)
        })
    }

    @Test fun `close rejects late proof and loaded or retry hooks cannot reopen a closed renderer`() = runTest {
        val release = CompletableDeferred<Unit>()
        fixture(prove = { _, requests -> withContext(NonCancellable) { release.await() }; SourceAnchorBatch(requests.associate { it.id to text }) },
            block = { owner, _, document, controller ->
                owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
                controller.close(); runCurrent(); release.complete(Unit); runCurrent()
                controller.documentLoaded(); controller.retry(); runCurrent()
                assertEquals(0, document.count("apply")); assertEquals(1, document.count("begin"))
                assertFalse(controller.status.value is EpubHighlightStatus.Ready)
            })
    }

    @Test fun `mixed legacy and multi node snapshots count complete annotation groups`() = runTest {
        val records = MutableStateFlow(listOf(first, range, range.copy(id = "plain", highlightColor = null), second))
        val proven = mutableListOf<List<SourceAnchorRequest>>()
        fixture(records = records, prove = { _, requests ->
            proven += requests
            SourceAnchorBatch(mapOf("one" to text), rangeNodeTexts = mapOf("multi" to listOf("xab", "cdy")),
                bodyTopology = topology)
        }, block = { owner, _, document, controller ->
            owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(listOf("one", "multi"), proven.single().map { it.id })
            val request = document.requests.single { it.getString("op") == "apply" }
            assertEquals(2, request.getInt("total"))
            assertEquals(1, request.getJSONArray("ranges").length())
            assertEquals(1, request.getJSONArray("groups").length())
            assertEquals(2, request.getJSONArray("groups").getJSONObject(0).getJSONArray("segments").length())
            assertEquals("9", request.getJSONArray("groups").getJSONObject(0).getString("revision"))
            assertEquals(EpubHighlightStatus.Ready(2), controller.status.value)
        })
    }

    @Test fun `source proof from an older group revision never applies after a newer snapshot`() = runTest {
        val records = MutableStateFlow(listOf(range))
        val release = CompletableDeferred<Unit>(); var proofs = 0
        fixture(records = records, prove = { _, _ ->
            if (++proofs == 1) withContext(NonCancellable) { release.await() }
            SourceAnchorBatch(emptyMap(), rangeNodeTexts = mapOf("multi" to listOf("xab", "cdy")), bodyTopology = topology)
        }, block = { owner, _, document, controller ->
            owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(0, document.count("apply"))
            records.value = listOf(range.copy(revision = 10, highlightColor = EpubHighlightColor.PINK)); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals(2, proofs); assertEquals(1, document.count("apply"))
            val group = document.requests.single { it.getString("op") == "apply" }.getJSONArray("groups").getJSONObject(0)
            assertEquals("10", group.getString("revision")); assertEquals("PINK", group.getString("color"))
            assertEquals(EpubHighlightStatus.Ready(1), controller.status.value)
        })
    }

    @Test fun `one unresolved multi node group is partial and segment totals cannot claim ready`() = runTest {
        fixture(records = MutableStateFlow(listOf(range)), prove = { _, _ ->
            SourceAnchorBatch(emptyMap(), rangeNodeTexts = mapOf("multi" to listOf("xab", "cdy")), bodyTopology = topology)
        }, block = { owner, _, document, controller ->
            document.applyState = "partial"; document.resolvedOverride = 0
            owner.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(EpubHighlightStatus.Partial(1, 1), controller.status.value)
            document.applyState = "ready"; document.resolvedOverride = 2
            controller.retry(); runCurrent()
            assertEquals(EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED), controller.status.value)
        })
    }
}

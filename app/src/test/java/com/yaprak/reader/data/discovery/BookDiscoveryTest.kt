package com.yaprak.reader.data.discovery

import com.yaprak.reader.domain.library.*
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookDiscoveryTest {
    private class Graph(val graph: Map<String, List<DiscoveryDocument>>) : DiscoveryDocumentProvider {
        val queried = mutableListOf<String>()
        var rows = 0
        var beforeQuery: suspend (String) -> Unit = {}
        var beforeRow: () -> Unit = {}
        var treeError: Exception? = null
        override fun resolveTree(treeUri: String): DiscoveryTree {
            treeError?.let { throw it }
            return DiscoveryTree("content://provider/tree/root", "provider", "root")
        }
        override suspend fun children(tree: DiscoveryTree, parentId: String, onChild: (DiscoveryDocument) -> Boolean) {
            queried += parentId; beforeQuery(parentId)
            for (row in graph[parentId].orEmpty()) {
                rows++; beforeRow()
                if (!onChild(row)) break
            }
        }
        override fun documentUri(tree: DiscoveryTree, documentId: String) = "${tree.uri}/document/" + URLEncoder.encode(documentId, "UTF-8")
    }
    private fun folder(id: String) = DiscoveryDocument(id, id, DIRECTORY_MIME)
    private fun epub(id: String) = DiscoveryDocument(id, "$id.epub", "application/octet-stream")
    private fun pdf(id: String) = DiscoveryDocument(id, "$id.pdf", "application/pdf")

    @Test fun cbzExtensionIsOnlyACandidateHintAndGenericZipIsNotAComicCandidate() = runTest {
        val provider = Graph(mapOf("root" to listOf(DiscoveryDocument("comic", "Pages.CBZ", "application/zip"),
            DiscoveryDocument("generic", "archive.zip", "application/zip"), epub("epub"),
            DiscoveryDocument("virtual", "virtual.cbz", "application/zip", virtual = true))))
        val result = DefaultBookDiscovery(provider, StandardTestDispatcher(testScheduler), elapsedMillis = { 0 }).scan("chosen") {} as DiscoveryResult.Complete
        assertEquals(listOf("comic", "epub"), result.candidates.map { it.id })
        assertEquals(BookFormat.CBZ, result.candidates.first().formatHint)
        assertEquals(4, result.visited)
    }

    @Test fun `nested hints cycles and virtual`() = runTest {
        val graph = Graph(mapOf(
            "root" to listOf(folder("chapters"), epub("one"), pdf("two"),
                DiscoveryDocument("virtual", "virtual.pdf", "application/pdf", virtual = true),
                DiscoveryDocument("unknown", "image.png", "image/png")),
            "chapters" to listOf(folder("root"), epub("one"), folder("more"),
                DiscoveryDocument("mime-epub", "no extension", "application/epub+zip", 44),
                DiscoveryDocument("uppercase", "FILE.PDF", null, -3)),
            "more" to listOf(folder("chapters"), DiscoveryDocument("zip", "candidate.zip", "application/zip")),
        ))
        val progress = mutableListOf<DiscoveryProgress>()
        val scanner = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), elapsedMillis = { 0 })
        val result = scanner.scan("selected", progress::add) as DiscoveryResult.Complete
        assertEquals(listOf("one", "two", "mime-epub", "uppercase"), result.candidates.map { it.id })
        assertEquals(12, result.visited)
        assertEquals(listOf("root", "chapters", "more"), graph.queried)
        assertEquals(BookFormat.PDF, result.candidates[3].formatHint)
        assertNull(result.candidates[3].sizeBytes)
        assertEquals(44L, result.candidates[2].sizeBytes)
        assertEquals(DiscoveryProgress(0, 0), progress.first())
        assertEquals(DiscoveryProgress(12, 4), progress.last())
        assertTrue(result.candidates.all { it.documentUri.startsWith("content://provider/tree/root/document/") })
    }

    @Test fun `malformed rows count and opaque ids`() = runTest {
        val opaque = "../literal/path:opaque#id"
        val graph = Graph(mapOf("root" to listOf(
            DiscoveryDocument(null, "bad.pdf", "application/pdf"),
            DiscoveryDocument("", "bad.pdf", "application/pdf"),
            DiscoveryDocument("control\n", "bad.pdf", "application/pdf"),
            DiscoveryDocument("a".repeat(4097), "bad.pdf", "application/pdf"),
            DiscoveryDocument("empty", "   ", "application/pdf"),
            DiscoveryDocument("noname", null, "application/pdf"),
            DiscoveryDocument("badname", "bad\n.pdf", "application/pdf"),
            DiscoveryDocument(opaque, "safe.EPUB", "unknown", Long.MAX_VALUE),
        )))
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), elapsedMillis = { 0 })
            .scan("selected") {} as DiscoveryResult.Complete
        assertEquals(8, result.visited)
        assertEquals(listOf(opaque), result.candidates.map { it.id })
        assertEquals(Long.MAX_VALUE, result.candidates.single().sizeBytes)
        assertFalse(result.candidates.single().documentUri.contains("../literal/path"))
    }

    @Test fun `depth skips deep folders but keeps siblings`() = runTest {
        val graph = Graph(mapOf("root" to listOf(folder("a"), folder("b"), epub("root-book")),
            "a" to listOf(folder("too-deep"), epub("a-book")), "b" to listOf(pdf("b-book")),
            "too-deep" to listOf(epub("hidden"))))
        val scanner = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), DiscoveryLimits(maxDepth = 1), { 0 })
        val result = scanner.scan("selected") {} as DiscoveryResult.Truncated
        assertEquals(DiscoveryLimit.DEPTH, result.reason)
        assertEquals(listOf("root-book", "a-book", "b-book"), result.candidates.map { it.id })
        assertEquals(listOf("root", "a", "b"), graph.queried)
        assertEquals(6, result.visited)
    }

    @Test fun `document limit and exact boundary`() = runTest {
        val graph = Graph(mapOf("root" to (0 until 10).map { epub("book$it") }))
        val scanner = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), DiscoveryLimits(maxDocuments = 4), { 0 })
        val result = scanner.scan("selected") {} as DiscoveryResult.Truncated
        assertEquals(DiscoveryLimit.DOCUMENTS, result.reason)
        assertEquals(4, result.visited); assertEquals(4, result.candidates.size)
        assertEquals(5, graph.rows) // One extra streamed row proves the bounded result is incomplete.
        val exact = Graph(mapOf("root" to (0 until 4).map { epub("book$it") }))
        assertTrue(DefaultBookDiscovery(exact, StandardTestDispatcher(testScheduler), DiscoveryLimits(maxDocuments = 4), { 0 })
            .scan("selected") {} is DiscoveryResult.Complete)
    }

    @Test fun `candidate limit ignores unsupported rows`() = runTest {
        val graph = Graph(mapOf("root" to listOf(epub("one"), pdf("two"),
            DiscoveryDocument("unsupported", "plain.md", "text/markdown"), epub("three"))))
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), DiscoveryLimits(maxCandidates = 2), { 0 })
            .scan("selected") {} as DiscoveryResult.Truncated
        assertEquals(DiscoveryLimit.CANDIDATES, result.reason)
        assertEquals(listOf("one", "two"), result.candidates.map { it.id })
        assertEquals(4, result.visited)
        val exact = Graph(mapOf("root" to listOf(epub("one"), pdf("two"))))
        assertTrue(DefaultBookDiscovery(exact, StandardTestDispatcher(testScheduler), DiscoveryLimits(maxCandidates = 2), { 0 })
            .scan("selected") {} is DiscoveryResult.Complete)
    }

    @Test fun `ordinary zip files do not consume candidates`() = runTest {
        val rows = (0 until 1200).map { DiscoveryDocument("archive$it", "archive$it.zip", "application/zip") } +
            DiscoveryDocument("book", "actual.EPUB", "application/zip")
        val graph = Graph(mapOf("root" to rows))
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), elapsedMillis = { 0 })
            .scan("selected") {} as DiscoveryResult.Complete
        assertEquals(1201, result.visited)
        assertEquals(listOf("book"), result.candidates.map { it.id })
    }

    @Test fun `elapsed budget preserves partial list`() = runTest {
        var elapsed = 0L
        val graph = Graph(mapOf("root" to (0 until 100).map { epub("book$it") })).apply { beforeRow = { elapsed += 3 } }
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), DiscoveryLimits(budgetMillis = 10), { elapsed })
            .scan("selected") {} as DiscoveryResult.Truncated
        assertEquals(DiscoveryLimit.TIME, result.reason)
        assertEquals(3, result.visited); assertEquals(3, result.candidates.size)
        assertEquals(4, graph.rows)
    }

    @Test fun `suspended query times out`() = runTest {
        val graph = Graph(mapOf("root" to listOf(epub("one")))).apply { beforeQuery = { delay(100) } }
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), DiscoveryLimits(budgetMillis = 10), { 0 })
            .scan("selected") {} as DiscoveryResult.Truncated
        assertEquals(DiscoveryLimit.TIME, result.reason)
        assertEquals(0, result.visited); assertTrue(result.candidates.isEmpty())
    }

    @Test fun `external cancellation stays cancellation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val graph = Graph(mapOf("root" to listOf(epub("one")))).apply { beforeQuery = { gate.await() } }
        val scanner = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), elapsedMillis = { 0 })
        val pending = async { scanner.scan("selected") {} }
        runCurrent(); assertEquals(listOf("root"), graph.queried)
        pending.cancelAndJoin()
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, graph.rows)
    }

    @Test fun `provider failures discard partial success`() = runTest {
        val graph = Graph(mapOf("root" to listOf(epub("one"), folder("broken")))).apply {
            beforeQuery = { if (it == "broken") throw IOException("Cannot read folder") }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(DiscoveryResult.Failed(DiscoveryFailure.IO), DefaultBookDiscovery(graph, dispatcher, elapsedMillis = { 0 }).scan("selected") {})
        listOf(InvalidDiscoveryTreeException() to DiscoveryFailure.INVALID_TREE,
            SecurityException("No grant") to DiscoveryFailure.ACCESS,
            IllegalStateException("Provider failure") to DiscoveryFailure.UNEXPECTED).forEach { (error, expected) ->
            graph.treeError = error
            assertEquals(DiscoveryResult.Failed(expected), DefaultBookDiscovery(graph, dispatcher, elapsedMillis = { 0 }).scan("selected") {})
        }
    }

    @Test fun `production caps bound large stream`() = runTest {
        val graph = Graph(mapOf("root" to (0 until 2000).map { epub("book$it") }))
        val progress = mutableListOf<DiscoveryProgress>()
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), elapsedMillis = { 0 })
            .scan("selected", progress::add) as DiscoveryResult.Truncated
        assertEquals(DiscoveryLimit.CANDIDATES, result.reason)
        assertEquals(1000, result.candidates.size); assertEquals(1001, result.visited)
        assertTrue(progress.size < 100)
        assertEquals(DiscoveryProgress(1001, 1000), progress.last())
        val invalid = listOf(runCatching { DiscoveryLimits(maxDepth = 17) }, runCatching { DiscoveryLimits(maxDocuments = 10001) },
            runCatching { DiscoveryLimits(maxCandidates = 1001) }, runCatching { DiscoveryLimits(budgetMillis = 60001) })
        assertTrue(invalid.all { it.exceptionOrNull() is IllegalArgumentException })
    }

    @Test fun `production document cap counts duplicates`() = runTest {
        val rows = listOf(epub("first")) + List(10_001) { DiscoveryDocument("same", "image.png", "image/png") }
        val graph = Graph(mapOf("root" to rows))
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), elapsedMillis = { 0 })
            .scan("selected") {} as DiscoveryResult.Truncated
        assertEquals(DiscoveryLimit.DOCUMENTS, result.reason)
        assertEquals(10_000, result.visited)
        assertEquals(listOf("first"), result.candidates.map { it.id })
        assertEquals(10_001, graph.rows)
    }

    @Test fun `production depth cap is sixteen`() = runTest {
        val graphRows = linkedMapOf("root" to listOf(folder("level1")))
        for (depth in 1..20) graphRows["level$depth"] = listOf(epub("book$depth"), folder("level${depth + 1}"))
        val graph = Graph(graphRows)
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), elapsedMillis = { 0 })
            .scan("selected") {} as DiscoveryResult.Truncated
        assertEquals(DiscoveryLimit.DEPTH, result.reason)
        assertEquals((1..16).map { "book$it" }, result.candidates.map { it.id })
        assertEquals(17, graph.queried.size)
        assertFalse(graph.queried.contains("level17"))
    }

    @Test fun `root only scan reports omitted folders without hiding root books`() = runTest {
        val graph = Graph(mapOf("root" to listOf(folder("child"), epub("visible")),
            "child" to listOf(pdf("hidden"))))
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), DiscoveryLimits(maxDepth = 0), { 0 })
            .scan("selected") {} as DiscoveryResult.Truncated
        assertEquals(DiscoveryLimit.DEPTH, result.reason)
        assertEquals(listOf("visible"), result.candidates.map { it.id })
        assertEquals(listOf("root"), graph.queried)
    }

    @Test fun `provider names and metadata are bounded display hints`() = runTest {
        val graph = Graph(mapOf("root" to listOf(
            DiscoveryDocument("long", "a".repeat(2000) + ".epub", "application/octet-stream", 0),
            DiscoveryDocument("mime", "  without extension  ", " APPLICATION/PDF ", 12),
            DiscoveryDocument("extension", "  mixed.PdF  ", "application/zip", null),
        )))
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), elapsedMillis = { 0 })
            .scan("selected") {} as DiscoveryResult.Complete
        assertEquals(512, result.candidates[0].name.length)
        assertEquals(0L, result.candidates[0].sizeBytes)
        assertEquals(BookFormat.EPUB, result.candidates[0].formatHint)
        assertEquals("without extension", result.candidates[1].name)
        assertEquals(BookFormat.PDF, result.candidates[1].formatHint)
        assertEquals("mixed.PdF", result.candidates[2].name)
        assertNull(result.candidates[2].sizeBytes)
    }

    @Test fun `time exhaustion after depth omission remains a time partial result`() = runTest {
        var elapsed = 0L
        val graph = Graph(mapOf("root" to listOf(folder("omitted"), epub("visible"), epub("late")))).apply {
            beforeRow = { elapsed += 4 }
        }
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler),
            DiscoveryLimits(maxDepth = 0, budgetMillis = 10), { elapsed }).scan("selected") {} as DiscoveryResult.Truncated
        assertEquals(DiscoveryLimit.TIME, result.reason)
        assertEquals(listOf("visible"), result.candidates.map { it.id })
        assertEquals(2, result.visited)
    }

    @Test fun `default time budget cancels suspended provider at sixty seconds`() = runTest {
        val graph = Graph(mapOf("root" to listOf(epub("one")))).apply { beforeQuery = { delay(60_001) } }
        val result = DefaultBookDiscovery(graph, StandardTestDispatcher(testScheduler), elapsedMillis = { 0 })
            .scan("selected") {} as DiscoveryResult.Truncated
        assertEquals(DiscoveryLimit.TIME, result.reason)
        assertEquals(60_000L, testScheduler.currentTime)
        assertTrue(result.candidates.isEmpty())
    }
}

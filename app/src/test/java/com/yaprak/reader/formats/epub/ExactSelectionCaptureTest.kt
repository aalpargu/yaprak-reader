package com.yaprak.reader.formats.epub

import android.app.Application
import com.yaprak.reader.reader.SelectedText
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceTextAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExactSelectionCaptureTest {
    private val href = "EPUB/chapter.xhtml"
    private val selected = SelectedText("Bir")
    private val oldToken = "11111111-1111-4111-8111-111111111111:0:1"
    private val newToken = "11111111-1111-4111-8111-111111111111:0:2"

    private inner class Capture {
        var current = true
        var nativeMatches = true
        var token = oldToken
        var fullRangeSupported = true
        var sourceRangeMatches = true
        var singleSourceMatches = false
        var multiNode = false
        var rangeReads = 0
        var identityReads = 0
        var nativeReads = 0
        var singleReads = 0
        var singleSourceReads = 0
        var rangeSourceReads = 0
        var onRangeProof: suspend () -> Unit = {}
        var onSingleProof: suspend () -> Unit = {}
        var onNativeRead: suspend () -> Unit = {}
        var onRecapture: suspend () -> Unit = {}
        suspend fun capture(): SelectedText = ExactSelectionCapture.capture(selected, href, { current }, { script ->
            when (script) {
                "identity" -> { identityReads++; if (identityReads > 1) onRecapture(); identity() }
                "range" -> { rangeReads++; if (fullRangeSupported) range() else "null" }
                "single" -> { singleReads++; single() }
                else -> error("Unexpected script")
            }
        }, "single", "range", "identity", verifySingle = { candidate, _ ->
            singleSourceReads++; onSingleProof()
            if (singleSourceMatches) SourceTextAnchor(candidate.resourceHref, candidate.start, candidate.end, candidate.nodeTextSha256) else null
        }, verifyRange = { candidate ->
            rangeSourceReads++; onRangeProof()
            if (sourceRangeMatches) SourceRangeAnchor(candidate.resourceHref, candidate.segments) else null
        }, sameNativeSelection = { nativeReads++; onNativeRead(); nativeMatches })

        private fun identity() = JSONObject.quote(JSONObject().put("version", 2).put("href", "/publication/$href")
            .put("preview", selected.text).put("selectionToken", token).toString())
        private fun single() = JSONObject.quote(JSONObject().put("href", "/publication/$href").put("path", "0/0")
            .put("start", 0).put("end", 3).put("nodeText", "Bir pasaj").toString())
        private fun range() = JSONObject.quote(JSONObject().put("version", 2).put("href", "/publication/$href")
            .put("preview", selected.text).put("selectionToken", token).put("segments", JSONArray(listOf(
                JSONObject().put("path", "0/0").put("start", 0).put("end", if (multiNode) 2 else 3)
                    .put("nodeText", if (multiNode) "Bi" else "Bir pasaj")) + if (multiNode) listOf(
                JSONObject().put("path", "0/1/0").put("start", 0).put("end", 1).put("nodeText", "r pasaj")) else emptyList()))
            .put("bodyTopology", JSONArray(if (multiNode) listOf(listOf(1, "http://www.w3.org/1999/xhtml", "body", 1),
                listOf(1, "http://www.w3.org/1999/xhtml", "p", 2), listOf(3, "Bi"),
                listOf(1, "http://www.w3.org/1999/xhtml", "em", 1), listOf(3, "r pasaj")) else listOf(
                listOf(1, "http://www.w3.org/1999/xhtml", "body", 1),
                listOf(1, "http://www.w3.org/1999/xhtml", "p", 1), listOf(3, "Bir pasaj")))).toString())
    }

    @Test fun `repeated original one node uses v2 only after legacy uniqueness proof fails`() = runTest {
        val capture = Capture()
        assertTrue(capture.capture().sourceAnchor is SourceRangeAnchor)
        assertEquals(2, capture.rangeReads)
        assertEquals(2, capture.identityReads)
        assertEquals(1, capture.nativeReads)
        assertEquals(1, capture.singleSourceReads)
    }

    @Test fun `genuine multi node source ranges use v2 without attempting a fabricated legacy match`() = runTest {
        val capture = Capture().apply { multiNode = true; singleSourceMatches = true }
        val result = capture.capture()
        assertEquals(2, (result.sourceAnchor as SourceRangeAnchor).segments.size)
        assertEquals(0, capture.singleSourceReads)
        assertEquals(1, capture.rangeSourceReads)
    }

    @Test fun `unique one node keeps its exact legacy anchor and never creates a duplicate v2 identity`() = runTest {
        val capture = Capture().apply { singleSourceMatches = true }
        val result = capture.capture()
        assertTrue(result.sourceAnchor is SourceTextAnchor)
        assertTrue(result.rangeCandidate != null)
        assertEquals(0, capture.rangeSourceReads)
        assertEquals(1, capture.singleSourceReads)
        assertEquals(2, capture.identityReads)
        assertEquals(SourceTextAnchor(href, 0, 3, sourceNodeSha256("Bir pasaj")), result.sourceAnchor)
    }

    @Test fun `unsupported full topology still requires a same document identity for legacy fallback`() = runTest {
        val capture = Capture().apply { fullRangeSupported = false; singleSourceMatches = true }
        assertTrue(capture.capture().sourceAnchor is SourceTextAnchor)
        assertEquals(0, capture.rangeSourceReads)
        assertEquals(1, capture.singleSourceReads)
        assertEquals(2, capture.identityReads)
    }

    @Test fun `once proven legacy range that fails the current token check never falls through to v2`() = runTest {
        val capture = Capture().apply { singleSourceMatches = true; onSingleProof = { token = newToken } }
        assertNull(capture.capture().sourceAnchor)
        assertEquals(1, capture.singleSourceReads)
        assertEquals(0, capture.rangeSourceReads)
    }

    @Test fun `source IO cannot attach a replaced same valued native range`() = runTest {
        val capture = Capture()
        val entered = CompletableDeferred<Unit>()
        val continueProof = CompletableDeferred<Unit>()
        capture.onRangeProof = { entered.complete(Unit); continueProof.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { capture.capture() }
        entered.await()
        capture.token = newToken
        continueProof.complete(Unit)
        assertNull(result.await().sourceAnchor)
        assertEquals(1, capture.singleSourceReads)
    }

    @Test fun `legacy source IO cannot attach a replaced same valued selection even without topology`() = runTest {
        val capture = Capture().apply { fullRangeSupported = false; singleSourceMatches = true }
        val entered = CompletableDeferred<Unit>()
        val continueProof = CompletableDeferred<Unit>()
        capture.onSingleProof = { entered.complete(Unit); continueProof.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { capture.capture() }
        entered.await()
        capture.token = newToken
        continueProof.complete(Unit)
        val value = result.await()
        assertNull(value.sourceAnchor)
        assertNull(value.rangeCandidate)
        assertEquals(selected.text, value.text)
    }

    @Test fun `changed native preview or owner locator lifecycle invalidates source proof after IO`() = runTest {
        val native = Capture().apply { onRangeProof = { nativeMatches = false } }
        assertNull(native.capture().sourceAnchor)
        val owner = Capture().apply { onRangeProof = { current = false } }
        assertNull(owner.capture().sourceAnchor)
        assertEquals(0, owner.nativeReads)
        assertEquals(1, owner.identityReads)
    }

    @Test fun `late native or Javascript recapture invalidation cannot publish any anchor`() = runTest {
        val native = Capture().apply { onNativeRead = { current = false } }
        assertNull(native.capture().sourceAnchor)
        val recapture = Capture().apply { onRecapture = { current = false } }
        assertNull(recapture.capture().sourceAnchor)
        val body = Capture().apply { onRangeProof = { token = "22222222-2222-4222-8222-222222222222:0:1" } }
        assertNull(body.capture().sourceAnchor)
    }

    @Test fun `cancellation propagates and never turns a late source result into durable proof`() = runTest {
        val capture = Capture()
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        var returned = false
        capture.onRangeProof = { entered.complete(Unit); blocked.await() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { capture.capture(); returned = true }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(0, capture.nativeReads)
        val immediate = Capture().apply { onRangeProof = { throw CancellationException("Cancel source") } }
        var cancelled = false
        try { immediate.capture() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}

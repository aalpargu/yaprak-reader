package com.yaprak.reader.formats.epub

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.reader.EpubHighlightFailure
import com.yaprak.reader.reader.EpubHighlightStatus
import com.yaprak.reader.reader.EpubHighlights
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.Url

/** Owns source IO, lifecycle and the exact renderer protocol; UI never talks to the engine. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class EpubHighlightController(
    private val lifecycle: Lifecycle,
    hrefs: Flow<String>,
    records: Flow<List<EpubPassage>>,
    script: String,
    sourceHash: String,
    private val sessionOpen: () -> Boolean,
    private val evaluate: suspend (String) -> String?,
    private val prove: suspend (String, List<SourceAnchorRequest>) -> SourceAnchorBatch,
    private val onClosed: () -> Unit = {},
) : EpubHighlights, Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val protocol = ExactHighlightProtocol(script, "yaprak_" + UUID.randomUUID().toString().replace('-', '_'), sourceHash)
    private val refresh = MutableStateFlow(0L)
    private val mutableStatus = MutableStateFlow<EpubHighlightStatus>(EpubHighlightStatus.Loading)
    override val status = mutableStatus.asStateFlow()
    private var generation = 0L
    private var closed = false
    private var currentPath = ""
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) retire()
        if (event == Lifecycle.Event.ON_DESTROY) close()
    }

    init {
        lifecycle.addObserver(observer)
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val source = refresh.flatMapLatest {
                    records.map<List<EpubPassage>, List<EpubPassage>?> { it }.catch { error ->
                        currentCoroutineContext().ensureActive()
                        if (error is CancellationException) throw error
                        emit(null)
                    }
                }
                combine(hrefs, source) { href, passages -> href to passages }
                    .catch { error ->
                        currentCoroutineContext().ensureActive()
                        if (error is CancellationException) throw error
                        retire()
                        mutableStatus.value = EpubHighlightStatus.Failed(EpubHighlightFailure.SOURCE_UNAVAILABLE)
                    }.collectLatest { (href, passages) ->
                        if (passages == null) {
                            retire(); mutableStatus.value = EpubHighlightStatus.Failed(EpubHighlightFailure.SOURCE_UNAVAILABLE)
                            return@collectLatest
                        }
                        val colored = passages.filter { it.highlightColor != null && it.anchor.resourceHref == href }
                        renderAndMonitor(href, colored)
                    }
            }
        }
    }

    /** Pager replacement/appearance reload; an old document response cannot publish new state. */
    fun documentLoaded() {
        if (closed) return
        // Invalidate before a queued flow collector runs. A source read already in flight must
        // never apply its proof to a replaced pager document, even if the href did not change.
        retire()
        refresh.value++
    }

    override suspend fun retry() {
        currentCoroutineContext().ensureActive()
        if (active()) documentLoaded()
    }

    private fun active() = !closed && sessionOpen() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private suspend fun stillCurrent(epoch: Long): Boolean {
        currentCoroutineContext().ensureActive()
        return active() && generation == epoch
    }

    private suspend fun renderAndMonitor(href: String, records: List<EpubPassage>) {
        val url = Url(href)
        if (url !is RelativeUrl || href.startsWith("//") || url.removeFragment().toString() != href) {
            retire(); mutableStatus.value = EpubHighlightStatus.Failed(EpubHighlightFailure.SOURCE_UNAVAILABLE); return
        }
        currentPath = "/publication/" + url.path
        var revalidations = 0
        while (active()) {
            val epoch = ++generation
            val path = currentPath
            mutableStatus.value = EpubHighlightStatus.Loading
            try {
                val beginRaw = evaluate(protocol.request("begin", epoch, path, documentSeed = UUID.randomUUID().toString()))
                if (!stillCurrent(epoch)) return
                val begin = protocol.decode(beginRaw, epoch, path)
                when (begin?.state) {
                    "unsupported" -> { mutableStatus.value = EpubHighlightStatus.Unsupported; return }
                    "begun" -> Unit
                    else -> { retire(); mutableStatus.value = EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED); return }
                }
                val token = begin.documentToken ?: throw IllegalStateException("Missing document identity")
                if (records.size > 1000 || records.map { it.id }.toSet().size != records.size) {
                    mutableStatus.value = EpubHighlightStatus.Failed(EpubHighlightFailure.SOURCE_LIMIT); return
                }
                val proof = if (records.isEmpty()) SourceAnchorBatch(emptyMap()) else prove(href,
                    records.map { SourceAnchorRequest(it.id, it.anchor, it.text) })
                if (!stillCurrent(epoch)) return
                if (proof.failure != null) {
                    mutableStatus.value = EpubHighlightStatus.Failed(if (proof.failure == SourceProofFailure.LIMIT)
                        EpubHighlightFailure.SOURCE_LIMIT else EpubHighlightFailure.SOURCE_UNAVAILABLE)
                    return
                }
                val raw = evaluate(protocol.request("apply", epoch, path, token, records = records, proof = proof))
                if (!stillCurrent(epoch)) return
                val applied = protocol.decode(raw, epoch, path, token)
                mutableStatus.value = paintStatus(applied, records.size)
                if (applied?.state != "ready" && applied?.state != "partial") { retire(); return }
                // There is no paint to invalidate or report. Ordinary unannotated chapters
                // must not keep a polling timer alive; records/page-load still trigger updates.
                if (records.isEmpty()) return
                // MutationObserver clears immediately in the document. Poll only reports that fact;
                // at most one automatic exact revalidation per snapshot/page load avoids script loops.
                while (stillCurrent(epoch)) {
                    delay(1000)
                    if (!stillCurrent(epoch)) return
                    val checked = protocol.decode(evaluate(protocol.request("status", epoch, path, token)), epoch, path, token)
                    if (!stillCurrent(epoch)) return
                    if (checked?.state == "mutated") {
                        if (revalidations++ == 0) break
                        mutableStatus.value = EpubHighlightStatus.Partial(records.size, records.size); return
                    }
                    if (checked?.state != "ready" && checked?.state != "partial") {
                        retire()
                        mutableStatus.value = EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED); return
                    }
                    mutableStatus.value = paintStatus(checked, records.size)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: HighlightPayloadLimitException) {
                if (stillCurrent(epoch)) mutableStatus.value = EpubHighlightStatus.Failed(EpubHighlightFailure.SOURCE_LIMIT)
                return
            } catch (_: Exception) {
                if (stillCurrent(epoch)) {
                    retire()
                    mutableStatus.value = EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED)
                }
                return
            }
        }
    }

    private fun retire() {
        val epoch = ++generation
        val path = currentPath
        scope.launch { clear(epoch, path) }
    }

    private suspend fun clear(epoch: Long, path: String) {
        try { withTimeoutOrNull(1000) { evaluate(protocol.request("clear", epoch, path)) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* best effort: destroyed/replaced documents retain no live surface */ }
    }

    override fun close() {
        if (closed) return
        closed = true
        lifecycle.removeObserver(observer)
        onClosed()
        val epoch = ++generation
        val path = currentPath
        scope.cancel()
        // A bounded final request is separate from canceled source jobs. Generation ordering prevents
        // a delayed pause/close clear from deleting a newer request on the same live document.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try { clear(epoch, path) } finally { cancel() }
        }
    }
}

internal fun paintStatus(reply: HighlightReply?, expected: Int): EpubHighlightStatus {
    if (reply?.state == "limit") return EpubHighlightStatus.Failed(EpubHighlightFailure.DOM_LIMIT)
    val total = reply?.total
    val resolved = reply?.resolved
    if (total != expected || resolved == null || resolved !in 0..expected)
        return EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED)
    return when (reply.state) {
        "ready" -> if (resolved == total) EpubHighlightStatus.Ready(total)
            else EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED)
        "partial" -> if (resolved < total) EpubHighlightStatus.Partial(total - resolved, total)
            else EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED)
        else -> EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED)
    }
}

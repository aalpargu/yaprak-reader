package com.yaprak.reader.formats.epub

import com.yaprak.reader.reader.ReadingPaceObservation
import com.yaprak.reader.reader.ReadingPaceSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The caller must first prove this exact current locator's document is actually visible. */
internal class EpubReadingPace(private val clock: () -> Long) : ReadingPaceSource {
    private var active = true
    private var pendingPage = false
    private var revision = 0L
    private var href: String? = null
    private var viewport: Pair<Int, Int>? = null
    private val mutable = MutableStateFlow(ReadingPaceObservation(revision, null, clock()))
    override val observations = mutable.asStateFlow()

    /** Called synchronously before asynchronous SDK jump/layout work can complete. */
    fun invalidate(waitForPage: Boolean = pendingPage) {
        if (!active) return
        revision++; pendingPage = waitForPage
        clearPosition()
    }

    /** This is the actual pagination callback, never onPageLoaded (which also preloads resources). */
    fun pageChanged() {
        if (active && pendingPage) { pendingPage = false; invalidate() }
    }

    fun clearPosition() {
        if (active) mutable.value = ReadingPaceObservation(revision, null, clock())
    }

    fun visible(resourceHref: String, resource: Double?, range: EpubPaceRange?, width: Int, height: Int,
        chapterEntryId: String? = null) {
        if (!active) return
        val total = range?.position(resource)
        if (pendingPage || resourceHref.isEmpty() || width <= 0 || height <= 0 ||
            range == null || total == null) {
            clearPosition(); return
        }
        val nextViewport = width to height
        if (href?.let { it != resourceHref } == true || viewport?.let { it != nextViewport } == true) invalidate()
        href = resourceHref; viewport = nextViewport
        val chapter = chapterEntryId?.takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) }
        mutable.value = ReadingPaceObservation(revision, total, clock(), resourceHref, resource, range.start, range.end, chapter)
    }

    fun dispose() {
        if (!active) return
        invalidate(); active = false
    }
}

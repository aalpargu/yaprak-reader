package com.yaprak.reader.formats.pdf

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Current bitmap preparation and independently confirmed native primary draw have separate tickets. */
internal class PdfRenderStatus(private val pageCount: Int, initialPage: Int, initialAnchorY: Int? = null) {
    init {
        require(pageCount > 0 && initialPage in 0 until pageCount)
        require(initialAnchorY == null || initialAnchorY in 0..1_000_000)
    }

    private val requested = MutableStateFlow(initialPage)
    val requestedPage = requested.asStateFlow()
    private val rendered = MutableStateFlow<Int?>(null)
    val renderedPage = rendered.asStateFlow()
    private val drawn = MutableStateFlow<Int?>(null)
    val drawnPage = drawn.asStateFlow()
    private val position = MutableStateFlow<PdfResumePosition?>(null)
    val drawnPosition = position.asStateFlow()
    private var waitingInitialPreferences = initialAnchorY != null
    private var initialAnchor = initialAnchorY
    private var completedNavigation = -1L
    private var scrollViewport: PdfScrollViewport? = null
    private var lastPaintedPoint: PdfResumePosition? = null
    private var lastPointRequiresBaselineReset = false
    private val failed = MutableStateFlow(false)
    val renderFailed = failed.asStateFlow()
    private var renderGeneration = 0L
    private val spread = MutableStateFlow(false)
    val doublePage = spread.asStateFlow()
    private val tickets = mutableMapOf<Int, Long>()
    private val completed = mutableSetOf<Int>()
    private val failures = mutableSetOf<Int>()
    var scrolling: Boolean = false
        private set
    private val scrollItems = mutableSetOf<Int>()
    private val visible = MutableStateFlow<List<Int>>(emptyList())
    val visibleScrollPages = visible.asStateFlow()
    private val failedItems = MutableStateFlow<Set<Int>>(emptySet())
    val failedScrollItems = failedItems.asStateFlow()
    private val navigation = MutableStateFlow(PdfScrollNavigation(initialPage, 0))
    val scrollNavigation = navigation.asStateFlow()
    val scrollNavigationSettled: Boolean get() = !scrolling || completedNavigation == navigation.value.revision

    var horizontalScrolling: Boolean = false
        private set

    fun setScrolling(enabled: Boolean, horizontal: Boolean = false) {
        val sideways = enabled && horizontal
        if (scrolling == enabled && horizontalScrolling == sideways) return
        horizontalScrolling = sideways
        scrollViewport = null; lastPaintedPoint = null; lastPointRequiresBaselineReset = false
        invalidate()
        scrollItems.clear(); visible.value = emptyList()
        scrolling = enabled
        if (enabled) spread.value = false
        navigation.value = PdfScrollNavigation(requested.value, navigation.value.revision + 1)
        completedNavigation = -1L
    }

    /** Keeps the last actual painted original point across live crop/fit/window size changes. */
    fun updateScrollGeometry(viewport: PdfScrollViewport) {
        if (!scrolling || viewport == scrollViewport) return
        val previous = scrollViewport
        scrollViewport = viewport
        if (!viewport.isValid) { clearDraw(); return }
        // Pending commands use the new geometry in Compose, retaining their original target/revision.
        if (previous == null || waitingInitialPreferences || !scrollNavigationSettled) return
        val anchor = lastPaintedPoint?.takeIf { it.pageIndex == requested.value }?.anchorY
        nextScrollNavigation(requested.value, anchor, resetPointTransform = anchor != null && lastPointRequiresBaselineReset)
        clearDraw()
    }

    /** Called by the last acknowledged initial preference command, including a paged choice. */
    fun initialPreferencesApplied() {
        if (!waitingInitialPreferences) return
        waitingInitialPreferences = false
        val anchor = initialAnchor
        initialAnchor = null
        clearDraw()
        if (scrolling && anchor != null) {
            nextScrollNavigation(requested.value, anchor, resetPointTransform = true)
        }
    }

    /** Original-point navigation resets its page; a reflow resets only a transformed painted point. */
    private fun nextScrollNavigation(page: Int, anchorY: Int?, resetPointTransform: Boolean = false) {
        val previous = navigation.value
        val revision = previous.revision + 1
        val pointRevisions = if (resetPointTransform && anchorY != null)
            previous.pointNavigationRevisions + (page to revision) else previous.pointNavigationRevisions
        navigation.value = PdfScrollNavigation(page, revision, anchorY, pointRevisions)
        completedNavigation = -1L
    }

    fun registerScrollItem(page: Int) {
        if (scrolling && page in 0 until pageCount) scrollItems.add(page)
    }

    fun unregisterScrollItem(page: Int) {
        if (!scrolling) { scrollItems.remove(page); return }
        scrollItems.remove(page); tickets.remove(page); completed.remove(page); failures.remove(page)
        publishFailedItems()
        if (page == requested.value) { rendered.value = null; clearDraw(); failed.value = false }
    }

    fun updateScrollViewport(pages: List<Int>, primary: Int?, completedRevision: Long = navigation.value.revision) {
        if (!scrolling) return
        completedNavigation = completedRevision.takeIf { it == navigation.value.revision } ?: -1L
        visible.value = pages.filter { it in scrollItems }.distinct().sorted()
        if (primary != null && primary in visible.value) adoptScrollPrimary(primary)
        else clearDraw()
    }

    /** A visible selection can focus its actual page without issuing an external scroll command. */
    fun adoptScrollPrimary(page: Int): Boolean {
        if (!scrolling || page !in scrollItems || page !in visible.value) return false
        if (requested.value != page) { requested.value = page; clearDraw() }
        refreshScrollPrepared()
        return true
    }

    private fun refreshScrollPrepared() {
        rendered.value = requested.value.takeIf { it in completed && it !in failures }
        failed.value = requested.value in failures
    }

    fun preparedTicket(page: Int, ticket: Long): Boolean = tickets[page] == ticket && page in completed && page !in failures
    private fun acceptsRender(page: Int): Boolean = if (scrolling) page in scrollItems else page in requestedPages()
    private fun publishFailedItems() { failedItems.value = if (scrolling) failures.toSet() else emptySet() }

    fun requestedPages(): List<Int> = if (spread.value && requested.value < pageCount - 1)
        listOf(requested.value, requested.value + 1) else listOf(requested.value)

    /** Effective viewport mode, not the user's preference. The selected anchor never gets rounded. */
    fun setDoublePage(enabled: Boolean) {
        val effective = enabled && pageCount > 1 && !scrolling
        if (spread.value == effective) return
        invalidate()
        spread.value = effective
    }

    fun invalidate() {
        renderGeneration++
        tickets.clear(); completed.clear(); failures.clear()
        failedItems.value = emptySet()
        rendered.value = null
        clearDraw()
        failed.value = false
    }

    fun goTo(page: Int, anchorY: Int? = null): Boolean {
        if (page !in 0 until pageCount || (anchorY != null && anchorY !in 0..1_000_000)) return false
        lastPaintedPoint = null; lastPointRequiresBaselineReset = false
        // An explicit navigation supersedes the pending saved resume, even before preferences settle.
        waitingInitialPreferences = false; initialAnchor = null
        if (scrolling) {
            requested.value = page
            clearDraw(); refreshScrollPrepared()
            nextScrollNavigation(page, anchorY, resetPointTransform = anchorY != null)
            return true
        }
        if (page != requested.value) {
            invalidate()
            requested.value = page
        }
        return true
    }

    fun move(delta: Int): Boolean {
        val next = (requested.value.toLong() + delta).coerceIn(0, pageCount.toLong() - 1).toInt()
        if (next == requested.value) return false
        return goTo(next)
    }

    fun turn(direction: Int): Boolean {
        if (direction != -1 && direction != 1) return false
        if (direction == 1 && requestedPages().last() == pageCount - 1) return false
        return move(direction * if (spread.value) 2 else 1)
    }

    /** Returns a ticket so a late render cannot revive an obsolete page. */
    fun startRender(page: Int): Long? {
        if (!acceptsRender(page)) return null
        renderGeneration++
        tickets[page] = renderGeneration
        completed.remove(page)
        failures.remove(page)
        publishFailedItems()
        if (!scrolling || page == requested.value) { rendered.value = null; clearDraw() }
        failed.value = if (scrolling) requested.value in failures else failures.isNotEmpty()
        return renderGeneration
    }

    fun finishRender(page: Int, ticket: Long) {
        if (acceptsRender(page) && tickets[page] == ticket) {
            failures.remove(page)
            publishFailedItems()
            completed.add(page)
            if (scrolling) refreshScrollPrepared() else {
                failed.value = failures.isNotEmpty()
                if (completed.containsAll(requestedPages()) && failures.isEmpty()) rendered.value = requested.value
            }
        }
    }

    /** Called only after the real native host draws its current prepared primary bitmap. */
    fun finishDraw(page: Int, ticket: Long, anchorY: Int? = null, positionAvailable: Boolean = true,
        pointRequiresBaselineReset: Boolean = false) {
        if (page == requested.value && tickets[page] == ticket && rendered.value == page &&
            (!scrolling || page in visible.value) && scrollNavigationSettled &&
            completed.containsAll(requestedPages()) && (if (scrolling) page !in failures else failures.isEmpty())) {
            drawn.value = page
            position.value = if (!waitingInitialPreferences && positionAvailable) PdfResumePosition(page,
                anchorY?.takeIf { scrolling && it in 0..1_000_000 }) else null
            if (scrolling && !waitingInitialPreferences && positionAvailable) {
                // A page-only draw cannot resurrect a previously painted original point.
                lastPaintedPoint = position.value?.takeIf { it.anchorY != null }
                lastPointRequiresBaselineReset = lastPaintedPoint != null && pointRequiresBaselineReset
            }
        }
    }

    /** Visibility/geometry/lifecycle changes preserve preparation but require another native draw. */
    fun clearDraw() { drawn.value = null; position.value = null }

    /** A stale or already completed render cannot report a new failure. */
    fun failRender(page: Int, ticket: Long) {
        if (acceptsRender(page) && tickets[page] == ticket && page !in completed) {
            failures.add(page)
            publishFailedItems()
            if (!scrolling || page == requested.value) {
                rendered.value = null; clearDraw(); failed.value = true
            }
        }
    }
}

internal data class PdfScrollNavigation(val pageIndex: Int, val revision: Long, val anchorY: Int? = null,
    // Retain other pages' ownership when navigating to a neighbor; a null target must not reset them.
    val pointNavigationRevisions: Map<Int, Long> = emptyMap(),
) {
    fun pointRevision(page: Int): Long = pointNavigationRevisions[page] ?: 0L
}

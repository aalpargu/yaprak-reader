package com.yaprak.reader.formats.pdf

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.yaprak.reader.ui.theme.YaprakTheme
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.reader.PdfTextPoint
import com.yaprak.reader.reader.TextSelectionSource
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.pdfannotations.PdfAnnotationRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/** Session-injected fragment; ReaderActivity explicitly rebuilds it after recreation. */
internal class PdfReaderFragment(
    private val document: PdfDocument,
    initialPage: Int,
    private val onTap: () -> Unit,
    book: Book? = null,
    annotations: PdfAnnotationRepository? = null,
    private val current: () -> Boolean = { true },
    private val scrollingPreferred: MutableStateFlow<Boolean> = MutableStateFlow(false),
    initialAnchorY: Int? = null,
    private val modePreferred: MutableStateFlow<com.yaprak.reader.domain.settings.PdfNavigationMode> =
        MutableStateFlow(if (scrollingPreferred.value) com.yaprak.reader.domain.settings.PdfNavigationMode.VERTICAL_SCROLL
            else com.yaprak.reader.domain.settings.PdfNavigationMode.PAGED),
) : Fragment() {
    private val status = PdfRenderStatus(document.pageCount, initialPage, initialAnchorY).apply {
        setScrolling(scrollingPreferred.value, modePreferred.value == com.yaprak.reader.domain.settings.PdfNavigationMode.HORIZONTAL_SCROLL)
    }
    private val nightMode = MutableStateFlow(false)
    private val fitRequest = MutableStateFlow(PdfFitRequest())
    private val cropRequest = MutableStateFlow(PdfCropRequest())
    private val doublePagePreferred = MutableStateFlow(false)
    val doublePage = status.doublePage
    private var tapHandler: ((com.yaprak.reader.reader.ReaderTap) -> Boolean)? = null
    fun setTapHandler(handler: ((com.yaprak.reader.reader.ReaderTap) -> Boolean)?) { tapHandler = handler }
    private var swipeAllowed: () -> Boolean = { false }
    val renderedPage = status.renderedPage
    val drawnPage = status.drawnPage
    val drawnPosition = status.drawnPosition
    val renderFailed = status.renderFailed
    private val annotationPainter = if (book != null && annotations != null)
        PdfAnnotationPaintController(book, annotations, document.textSupported) { isResumed } else null
    private val selection = PdfTextSelectionSource(document) { page ->
        bookmarkPage != null && drawnPage.value == status.requestedPage.value && page in status.requestedPages()
    }
    val textSelection: TextSelectionSource? get() = selection.takeIf { document.textSupported }
    private var selectionPreparation: Job? = null
    val scrolling = scrollingPreferred

    private fun clearSelection() { selectionPreparation?.cancel(); selectionPreparation = null; selection.clear() }

    val navigationMode = modePreferred

    fun applyScrolling(enabled: Boolean): Boolean = applyNavigation(
        if (enabled) com.yaprak.reader.domain.settings.PdfNavigationMode.VERTICAL_SCROLL
        else com.yaprak.reader.domain.settings.PdfNavigationMode.PAGED)

    fun applyNavigation(mode: com.yaprak.reader.domain.settings.PdfNavigationMode): Boolean {
        if (!isResumed || !current() || !document.isOpen) return false
        val enabled = mode != com.yaprak.reader.domain.settings.PdfNavigationMode.PAGED
        if (modePreferred.value != mode || scrollingPreferred.value != enabled) {
            clearSelection()
            status.setScrolling(enabled, mode == com.yaprak.reader.domain.settings.PdfNavigationMode.HORIZONTAL_SCROLL)
            modePreferred.value = mode; scrollingPreferred.value = enabled
        }
        // Crop, fit and spread have been acknowledged before the binding's final mode command.
        status.initialPreferencesApplied()
        view?.invalidate()
        return true
    }

    // During a pending/failed render the old bitmap is not the requested current page.
    val bookmarkPage: Int? get() = drawnPage.value?.takeIf {
        isResumed && current() && document.isOpen && (view as? PdfDrawProofView)?.primaryVisible() == true
    }

    private fun navigationAllowed() = isResumed && current() && document.isOpen

    fun goTo(page: Int) = navigate(page, null)

    fun goTo(position: PdfResumePosition) = navigate(position.pageIndex, position.anchorY)

    private fun navigate(page: Int, anchorY: Int?): Boolean {
        if (!navigationAllowed()) return false
        clearSelection(); return status.goTo(page, anchorY)
    }

    fun move(delta: Int): Boolean {
        if (!navigationAllowed()) return false
        clearSelection(); return status.move(delta)
    }
    fun turn(direction: Int): Boolean {
        if (!navigationAllowed()) return false
        clearSelection(); return status.turn(direction)
    }
    fun applyDoublePage(enabled: Boolean) {
        if (doublePagePreferred.value != enabled) { clearSelection(); status.clearDraw() }
        doublePagePreferred.value = enabled
    }

    fun applyNightMode(enabled: Boolean) { nightMode.value = enabled }

    fun allowSwipesWhile(allowed: () -> Boolean) { swipeAllowed = allowed }

    fun fitPage() { clearSelection(); status.clearDraw(); fitRequest.update { PdfFitRequest(PdfFitMode.PAGE, it.revision + 1) } }
    fun fitWidth() { clearSelection(); status.clearDraw(); fitRequest.update { PdfFitRequest(PdfFitMode.WIDTH, it.revision + 1) } }

    fun applyCrop(crop: PdfPageCrop) { clearSelection(); status.clearDraw(); cropRequest.update { PdfCropRequest(crop, it.revision + 1) } }

    override fun onPause() { status.clearDraw(); clearSelection(); annotationPainter?.stop(); super.onPause() }
    override fun onDestroyView() { status.clearDraw(); clearSelection(); annotationPainter?.stop(); super.onDestroyView() }

    override fun onResume() { super.onResume(); status.clearDraw(); view?.invalidate() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val painter = annotationPainter ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                painter.start(this, combine(status.requestedPage, status.doublePage, status.visibleScrollPages) { page, double, visible ->
                    if (status.scrolling) visible
                    else if (double && page < document.pageCount - 1) listOf(page, page + 1) else listOf(page)
                })
                try { awaitCancellation() } finally { painter.stop() }
            }
        }
    }

    private fun select(page: Int, start: PdfTextPoint, stop: PdfTextPoint) {
        if (!isResumed || !swipeAllowed()) return
        // Every gesture replaces both a captured selection and a neighbor waiting for its own draw.
        clearSelection()
        if (status.scrolling && bookmarkPage != page) {
            val host = view as? PdfDrawProofView ?: return
            if (!host.pageVisible(page) || !status.adoptScrollPrimary(page)) return
            host.invalidate()
            selectionPreparation = viewLifecycleOwner.lifecycleScope.launch {
                val drawn = withTimeoutOrNull(1_500) { status.drawnPage.first { it == page } }
                if (drawn == page && isResumed && current() && swipeAllowed() && bookmarkPage == page)
                    selection.accept(page, start, stop)
            }
            return
        }
        selection.accept(page, start, stop)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        PdfDrawProofView(requireContext(), status) { isResumed && current() && document.isOpen }.apply {
            val proofHost = this
            addView(ComposeView(requireContext()).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val page by status.requestedPage.collectAsState()
                    val night by nightMode.collectAsState()
                    val fit by fitRequest.collectAsState()
                    val crop by cropRequest.collectAsState()
                    val doublePreferred by doublePagePreferred.collectAsState()
                    val scrollPreferred by scrollingPreferred.collectAsState()
                    val mode by modePreferred.collectAsState()
                    val annotationPaint = annotationPainter?.state?.collectAsState()?.value
                    val renderPage = remember(document) { document::render }
                    YaprakTheme {
                        PdfPages(renderPage, page, document.pageCount, doublePreferred, status, onTap,
                            nightMode = night, onSwipePage = { delta ->
                                if (!isResumed || !swipeAllowed() || (delta != -1 && delta != 1) ||
                                    bookmarkPage != page || status.requestedPage.value != page) false
                                else turn(delta)
                            }, fitRequest = fit, cropRequest = crop,
                            onTextSelection = if (document.textSupported) ::select else null,
                            onReaderTap = { tap -> tapHandler?.invoke(tap) ?: false },
                            annotationPaint = annotationPaint, onRetryAnnotations = annotationPainter?.let { painter -> painter::retry },
                            drawProofHost = proofHost, scrolling = scrollPreferred, loadPageDimensions = document::pageDimensions,
                            scrollAllowed = { isResumed && current() && swipeAllowed() && !selection.active },
                            onScrolledPrimary = ::clearSelection,
                            horizontalScrolling = mode == com.yaprak.reader.domain.settings.PdfNavigationMode.HORIZONTAL_SCROLL)
                    }
                }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
}

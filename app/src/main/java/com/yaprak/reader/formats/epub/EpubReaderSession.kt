@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import com.yaprak.reader.reader.BookTextSearch
import com.yaprak.reader.reader.BookSearchException
import com.yaprak.reader.reader.BookSearchFailure
import com.yaprak.reader.reader.EpubAppearanceController
import com.yaprak.reader.reader.EpubImageFilterController
import com.yaprak.reader.reader.ContentsNavigator
import com.yaprak.reader.reader.BookmarkNavigator
import com.yaprak.reader.reader.BookPositionNavigator
import com.yaprak.reader.reader.PassageNavigator
import com.yaprak.reader.reader.IndexedChapterNavigator
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.reader.SelectedText
import com.yaprak.reader.reader.TextSelectionException
import com.yaprak.reader.reader.TextSelectionFailure
import com.yaprak.reader.reader.TextSelectionSource
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.domain.passages.EpubPassageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import java.security.MessageDigest

private data class IndexedChapterProof(val target: LibraryTextTarget, val owner: Job?, val foregroundRevision: Long)

internal class EpubReaderSession(
    override val book: Book,
    private val opened: OpenEpub,
    override val resume: ReadingLocation?,
    private val visibleScript: String,
    private val selectionRangeScript: String,
    private val exactHighlightsScript: String = "",
    private val passages: EpubPassageRepository? = null,
    private val selectionRangeV2Script: String = "",
) : ReaderSession {
    private val contents = EpubContents(opened.publication.tableOfContents, opened.publication.readingOrder)
    private val search = EpubBookSearch(opened.publication)
    private val bookPosition = EpubBookPosition(opened.publication)
    private var closed = false
    private var surfaceGeneration = 0L
    private val visibilityClosed = MutableStateFlow(false)
    private val highlightControllers = mutableSetOf<EpubHighlightController>()
    private val paceWitnesses = mutableSetOf<EpubReadingPace>()
    override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface {
        check(!closed)
        paceWitnesses.forEach { it.dispose() }; paceWitnesses.clear()
        val surfaceOwner = ++surfaceGeneration
        fun currentSurface() = !closed && surfaceGeneration == surfaceOwner
        val selectionScriptBase = selectionRangeV2Script.replace("/*YAPRAK_SELECTION_OWNER*/",
            org.json.JSONObject.quote(java.util.UUID.randomUUID().toString()))
        val selectionScriptV2 = selectionScriptBase.replace("/*YAPRAK_SELECTION_MODE*/", "\"capture\"")
        val selectionIdentityScript = selectionScriptBase.replace("/*YAPRAK_SELECTION_MODE*/", "\"identity\"")
        var selectionGeneration = 0L
        val pace = if (opened.publication.metadata.presentation.layout == EpubLayout.FIXED) null
            else EpubReadingPace(android.os.SystemClock::elapsedRealtime).also { paceWitnesses.add(it) }
        val positionMapping = EpubPaceMapCache(::currentSurface) { loadEpubPaceMap(opened.publication) }
        val locator = decodeEpubLocation(initial, opened.publication.readingOrder)
        val navigatorFactory = EpubNavigatorFactory(opened.publication)
        var highlights: EpubHighlightController? = null
        val visibilityError = MutableStateFlow(false)
        val visibilityRevision = MutableStateFlow(0L)
        val factory = navigatorFactory.createFragmentFactory(
            initialLocator = locator,
            initialPreferences = EpubPreferences(theme = if (dark) Theme.DARK else Theme.LIGHT),
            listener = object : EpubNavigatorFragment.Listener {
                override fun onExternalLinkActivated(url: AbsoluteUrl) { /* Local-only reader; no automatic browser launch. */ }
                override fun onJumpToLocator(locator: Locator) {
                    if (currentSurface()) pace?.invalidate(waitForPage = true)
                }
                override fun onResourceLoadFailed(href: Url, error: ReadError) {
                    if (!currentSurface()) return
                    pace?.invalidate()
                    visibilityError.value = true
                    onError()
                }
            },
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    if (currentSurface()) pace?.pageChanged()
                }
                override fun onPageLoaded() {
                    if (!currentSurface()) return
                    // This also fires for preloaded resources. It invalidates a probe, never proves visibility.
                    visibilityRevision.update { it + 1 }
                    highlights?.documentLoaded()
                }
            },
        )
        val navigator = factory.instantiate(EpubNavigatorFragment::class.java.classLoader!!, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        fun navigationAllowed() = currentSurface() && navigator.isResumed
        var foregroundRevision = 0L
        navigator.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) {
                foregroundRevision++; selectionGeneration++
                pace?.invalidate()
                if (event == Lifecycle.Event.ON_DESTROY) {
                    pace?.dispose(); pace?.let { paceWitnesses.remove(it) }
                }
            }
        })
        val taps = EpubTapInput { (navigator.view?.width ?: 0) to (navigator.view?.height ?: 0) }
        navigator.addInputListener(taps)
        navigator.addInputListener(DirectionalNavigationAdapter(navigator))
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (!navigationAllowed()) return false
                onTap(); return true
            }
        })
        if (opened.publication.metadata.presentation.layout != EpubLayout.FIXED && passages != null) {
            val controller = EpubHighlightController(navigator.lifecycle,
                navigator.currentLocator.map { it.href.removeFragment().toString() }.distinctUntilChanged(),
                passages.observe(book.id, book.sha256), exactHighlightsScript, book.sha256, ::currentSurface,
                navigator::evaluateJavascript, { href, requests -> SourceTextAnchorVerifier.verifyBatch(opened.publication, href, requests) },
                onClosed = { highlights?.let { highlightControllers.remove(it) } })
            highlights = controller
            highlightControllers.add(controller)
        }
        return object : ReaderSurface {
            override val fragment = navigator
            override val readingPace = pace
            // SDK3.1.2 fixed-layout pages do not emit the unhandled JS content tap.
            override val taps = taps.takeIf { opened.publication.metadata.presentation.layout != EpubLayout.FIXED }
            override val epubHighlights = highlights
            override val readingVisible = channelFlow {
                send(false)
                var paceMapping: EpubPaceMap? = null
                if (pace != null) launch {
                    val prepared = positionMapping.get()
                    currentCoroutineContext().ensureActive()
                    if (currentSurface()) paceMapping = prepared
                }
                navigator.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    try {
                        combine(navigator.currentLocator, visibilityError, visibilityRevision, visibilityClosed) {
                            current, failed, revision, sessionClosed ->
                            Triple(current, failed || sessionClosed, revision)
                        }.collectLatest { (current, failed, revision) ->
                            pace?.clearPosition()
                            send(false)
                            val href = current.href.removeFragment()
                            if (!currentSurface() || failed || href !is RelativeUrl || opened.publication.readingOrder.count {
                                    it.url().removeFragment() == href } != 1) return@collectLatest
                            val expected = readingResourceUrl(opened.publication.baseUrl?.toString(), href.toString())
                                ?: return@collectLatest
                            while (true) {
                                if (!currentSurface()) { send(false); break }
                                val root = navigator.view
                                val webView = currentVisibleWebView(root, expected)
                                val visible = webView != null && verifyVisibleDocument(expected, current = {
                                    navigationAllowed() && !visibilityError.value &&
                                        navigator.currentLocator.value == current && visibilityRevision.value == revision &&
                                        navigator.view === root && currentVisibleWebView(root, expected) === webView
                                }, evaluate = { renderedDocumentProbe(webView) })
                                if (visible && navigationAllowed()) {
                                    pace?.visible(href.toString(), current.locations.progression,
                                        paceMapping?.range(href.toString()), webView.width, webView.height,
                                        chapterEntryId = if (book.format == BookFormat.EPUB)
                                            contents.wholeResourceEntryId(href.toString()) else null)
                                } else pace?.clearPosition()
                                send(visible && navigationAllowed())
                                delay(1_000)
                            }
                        }
                    } finally { pace?.clearPosition(); trySend(false) }
                }
            }.distinctUntilChanged().flowOn(Dispatchers.Main.immediate)
            override val indexedChapterNavigator = object : IndexedChapterNavigator {
                private var verified: IndexedChapterProof? = null
                private var verificationGeneration = 0
                override suspend fun verifiedChapterHref(target: LibraryTextTarget): String? {
                    val generation = ++verificationGeneration
                    verified = null
                    if (!navigationAllowed() || !target.isValid || target.sourceFormat != BookFormat.EPUB || target.bookId != book.id ||
                        target.contentSha256 != book.sha256 || chapterLink(target.resourceHref) == null) return null
                    val foreground = foregroundRevision
                    val publication = opened.publication
                    val actual = try {
                        withContext(Dispatchers.IO) {
                            val bytes = readIndexXhtml(publication, target.resourceHref)
                            currentCoroutineContext().ensureActive()
                            MessageDigest.getInstance("SHA-256").digest(bytes)
                                .joinToString("") { "%02x".format(it) }
                        }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { return null }
                    currentCoroutineContext().ensureActive()
                    if (!navigationAllowed() || foreground != foregroundRevision || generation != verificationGeneration ||
                        !target.isValid || target.bookId != book.id || target.contentSha256 != book.sha256 ||
                        chapterLink(target.resourceHref) == null || opened.publication !== publication ||
                        actual != target.resourceSha256) return null
                    return target.resourceHref.also { verified = IndexedChapterProof(target, currentCoroutineContext()[Job], foreground) }
                }
                override fun goToChapter(href: String): Boolean {
                    val proof = verified
                    verified = null
                    verificationGeneration++
                    if (!navigationAllowed() || proof == null || proof.foregroundRevision != foregroundRevision || proof.target.resourceHref != href ||
                        proof.target.bookId != book.id || proof.target.contentSha256 != book.sha256 ||
                        proof.owner?.isActive == false) return false
                    val link = chapterLink(href) ?: return false
                    return navigator.go(link, animated = false)
                }
            }
            override val bookPositionPercent = channelFlow<Int?> {
                send(null)
                if (opened.publication.metadata.presentation.layout == EpubLayout.FIXED) return@channelFlow
                val mapping = positionMapping.get()
                currentCoroutineContext().ensureActive()
                combine(navigator.currentLocator, visibilityClosed) { current, sessionClosed ->
                    if (sessionClosed || !currentSurface()) null else epubBookPositionPercent(
                        mapping?.range(current.href.removeFragment().toString())?.position(current.locations.progression),
                        fixedLayout = false)
                }.collectLatest { send(it) }
            }.distinctUntilChanged().flowOn(Dispatchers.Main.immediate)
            override val bookPositionNavigator = if (!bookPosition.available) null else object : BookPositionNavigator {
                override suspend fun resolvePercent(percent: Int): ReadingLocation? {
                    if (!navigationAllowed()) return null
                    val foreground = foregroundRevision
                    val location = bookPosition.resolvePercent(percent)
                    currentCoroutineContext().ensureActive()
                    return location.takeIf { navigationAllowed() && foreground == foregroundRevision }
                }

                override fun goTo(location: ReadingLocation): Boolean {
                    if (!navigationAllowed()) return false
                    val target = decodeEpubBookPositionLocation(location, opened.publication.readingOrder) ?: return false
                    return navigator.go(target, animated = false)
                }
            }
            override val bookSearch = if (!search.available) null else object : BookTextSearch {
                override val supported get() = currentSurface()
                override suspend fun find(query: String): com.yaprak.reader.reader.BookSearchResults {
                    if (!currentSurface()) throw BookSearchException(BookSearchFailure.SOURCE)
                    val result = search.find(query)
                    currentCoroutineContext().ensureActive()
                    if (!currentSurface()) throw BookSearchException(BookSearchFailure.SOURCE)
                    return result
                }
                override fun goTo(location: ReadingLocation): Boolean {
                    if (!navigationAllowed()) return false
                    val target = decodeEpubLocation(location, opened.publication.readingOrder) ?: return false
                    return navigator.go(target, animated = false)
                }
            }
            override val bookmarkNavigator = object : BookmarkNavigator {
                override fun capture() = if (!navigationAllowed()) null else {
                    bookmarkTarget(navigator.currentLocator.value).takeIf {
                        decodeEpubLocation(it.location, opened.publication.readingOrder) != null
                    }
                }
                override fun goTo(location: ReadingLocation): Boolean {
                    if (!navigationAllowed()) return false
                    val target = decodeEpubLocation(location, opened.publication.readingOrder) ?: return false
                    return navigator.go(target, animated = false)
                }
            }
            override val passageNavigator = if (opened.publication.metadata.presentation.layout == EpubLayout.FIXED) null
                else object : PassageNavigator {
                    override suspend fun verifiedChapterHref(passage: EpubPassage): String? {
                        if (!navigationAllowed()) return null
                        val foreground = foregroundRevision
                        val href = passage.anchor.resourceHref
                        if (chapterLink(href) == null) return null
                        val verified = SourceTextAnchorVerifier.verifySaved(opened.publication, passage.anchor, passage.text)
                        currentCoroutineContext().ensureActive()
                        return href.takeIf { verified && navigationAllowed() && foreground == foregroundRevision }
                    }

                    override fun goToChapter(href: String): Boolean {
                        if (!navigationAllowed()) return false
                        val link = chapterLink(href) ?: return false
                        return navigator.go(link, animated = false)
                    }
                }
            override val contentsNavigator = object : ContentsNavigator {
                override val contents = this@EpubReaderSession.contents.contents
                override fun entryIdForResource(href: String): String? = if (currentSurface() && chapterLink(href) != null)
                    this@EpubReaderSession.contents.entryIdForResource(href) else null
                override fun goTo(entryId: String): Boolean {
                    if (!navigationAllowed()) return false
                    val link = this@EpubReaderSession.contents.destination(entryId) ?: return false
                    return navigator.go(link, animated = false)
                }
            }
            private val appearanceBinding = if (opened.publication.metadata.presentation.layout == EpubLayout.FIXED) null
                else EpubAppearanceBinding(dark, navigatorFactory.supportsTextAlignment(), navigatorFactory.spacingSupport()) { preferences ->
                    pace?.invalidate(waitForPage = true)
                    navigator.submitPreferences(preferences)
                    highlights?.documentLoaded()
                }
            override val appearance = appearanceBinding?.let { binding ->
                EpubAppearanceController { settings ->
                    check(currentSurface()) { "Reader surface is unavailable" }
                    binding.applySettings(settings)
                }
            }
            override val epubImageFilter = appearanceBinding?.let { binding ->
                EpubImageFilterController { mode ->
                    check(currentSurface()) { "Reader surface is unavailable" }
                    binding.applyImageFilter(mode)
                }
            }
            override val textSelection = if (opened.publication.metadata.presentation.layout == EpubLayout.FIXED) null
                else TextSelectionSource {
                    if (closed || surfaceGeneration != surfaceOwner || !navigator.isResumed) return@TextSelectionSource null
                    val generation = ++selectionGeneration
                    val root = navigator.view
                    val documentRevision = visibilityRevision.value
                    val before = navigator.currentLocator.value
                    val selection = navigator.currentSelection()
                    if (!navigator.isResumed) return@TextSelectionSource null
                    val atSelection = navigator.currentLocator.value
                    val selected = selectedTextOrNull(selection?.locator, before, atSelection,
                        opened.publication.readingOrder) ?: return@TextSelectionSource null
                    fun current(): Boolean = root != null && !closed && surfaceGeneration == surfaceOwner &&
                        generation == selectionGeneration && navigator.isResumed && navigator.view === root &&
                        !visibilityError.value && visibilityRevision.value == documentRevision &&
                        navigator.currentLocator.value == atSelection
                    val href = selection?.locator?.href?.removeFragment()?.toString() ?: return@TextSelectionSource selected
                    ExactSelectionCapture.capture(selected, href, ::current, navigator::evaluateJavascript,
                        singleNodeScript = selectionRangeScript, rangeScript = selectionScriptV2,
                        identityScript = selectionIdentityScript,
                        verifySingle = { candidate, text -> SourceTextAnchorVerifier.verify(opened.publication, candidate, text) },
                        verifyRange = { candidate -> SourceTextAnchorVerifier.verifyRange(opened.publication, candidate) },
                        sameNativeSelection = { navigator.currentSelection()?.locator == selection.locator })
                }
            override fun forward() = navigationAllowed() && navigator.goForward(animated = true)
            override fun backward() = navigationAllowed() && navigator.goBackward(animated = true)
            override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
                navigator.currentLocator.collectLatest { current ->
                    if (!currentSurface()) return@collectLatest
                    val href = current.href.removeFragment().toString()
                    if (opened.publication.readingOrder.none { it.url().removeFragment() == current.href.removeFragment() }) return@collectLatest
                    // Navigator is the source of truth. Do not treat locator.text (which can
                    // include before/after context) as observed content.
                    val location = ReadingLocation(current.toJSON().toString(), href)
                    onSnapshot(ReadingSnapshot(location))
                    delay(700)
                    if (!navigationAllowed() || !coverageAllowed()) return@collectLatest
                    val foreground = foregroundRevision
                    try {
                        val before = VisibleTextObservation.decode(navigator.evaluateJavascript(visibleScript), href)
                        delay(700)
                        if (!navigationAllowed() || foreground != foregroundRevision || navigator.currentLocator.value != current || !coverageAllowed()) return@collectLatest
                        val after = VisibleTextObservation.decode(navigator.evaluateJavascript(visibleScript), href)
                        currentCoroutineContext().ensureActive()
                        if (!navigationAllowed() || foreground != foregroundRevision || navigator.currentLocator.value != current || !coverageAllowed()) return@collectLatest
                        val stable = before.intersect(after.toSet()).toList()
                        if (stable.isNotEmpty()) onSnapshot(ReadingSnapshot(location, stable))
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { /* Unknown coverage stays empty; resume still works. */ }
                }
            }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        visibilityClosed.value = true
        paceWitnesses.forEach { it.dispose() }; paceWitnesses.clear()
        highlightControllers.toList().forEach { it.close() }
        highlightControllers.clear()
        opened.close()
    }

    private fun chapterLink(href: String): Link? = opened.publication.readingOrder.singleOrNull { link ->
        val url = link.url()
        link.mediaType == MediaType.XHTML && url is RelativeUrl &&
            !url.toString().startsWith("//") && url.toString() == href
    }
}

/** Readium's selection locator carries the current reading location, not a precise text range. */
internal fun selectedTextOrNull(
    selected: Locator?, before: Locator, after: Locator, readingOrder: List<Link>,
): SelectedText? {
    if (selected == null) return null
    val href = selected.href.removeFragment()
    if (href != before.href.removeFragment() || href != after.href.removeFragment() ||
        href !is RelativeUrl || href.toString().startsWith("//") ||
        readingOrder.none { it.url().removeFragment() == href }
    ) throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
    val highlight = selected.text.highlight ?: return null
    if (highlight.isBlank()) return null
    if (highlight.length > 5000) throw TextSelectionException(TextSelectionFailure.TOO_LONG)
    return SelectedText(highlight)
}

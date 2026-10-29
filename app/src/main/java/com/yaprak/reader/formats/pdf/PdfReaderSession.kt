package com.yaprak.reader.formats.pdf

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import com.yaprak.reader.reader.BookmarkNavigator
import com.yaprak.reader.reader.PageNavigator
import com.yaprak.reader.reader.PdfNightModeController
import com.yaprak.reader.reader.PdfSwipeController
import com.yaprak.reader.reader.PdfViewController
import com.yaprak.reader.reader.PdfCropController
import com.yaprak.reader.reader.PdfSpreadController
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow

internal class PdfReaderSession(
    override val book: Book,
    private val document: PdfDocument,
    override val resume: ReadingLocation?,
    private val annotations: com.yaprak.reader.domain.pdfannotations.PdfAnnotationRepository? = null,
    private val ocrFactory: ((() -> Int?) -> com.yaprak.reader.reader.PdfOcrSource)? = null,
    private val indexedFiles: com.yaprak.reader.data.storage.VerifiedBookFile? = null,
    private val fileMutex: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex(),
    private val validateIndexedBook: suspend (Book) -> Unit = {},
    private val outlineReader: PdfOutlineReader? = null,
) : ReaderSession {
    private val thumbnails = DocumentPdfThumbnailsSource(document)
    private val visibilityClosed = MutableStateFlow(false)
    private val generation = MutableStateFlow(0L)
    private val scrollPreferred = MutableStateFlow(false)
    private val navigationPreferred = MutableStateFlow(com.yaprak.reader.domain.settings.PdfNavigationMode.PAGED)

    override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface {
        check(!visibilityClosed.value)
        generation.value++; val owner = generation.value
        val current = { !visibilityClosed.value && owner == generation.value && document.isOpen }
        val resumePosition = PdfPosition.decodeResume(initial, document.pageCount)
        val pdf = PdfReaderFragment(document, resumePosition.pageIndex, onTap, book, annotations, current, scrollPreferred, resumePosition.anchorY, navigationPreferred)
        val ocr = ocrFactory?.invoke { pdf.bookmarkPage }
        val links = if (document.linksSupported) DocumentPdfPageLinksSource(document,
            activePage = { if (current()) pdf.bookmarkPage else null }, navigate = { current() && pdf.goTo(it) }).also { source ->
            pdf.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
                    source.invalidate()
            })
        } else null
        return object : ReaderSurface {
            override val fragment = pdf
            override val taps = com.yaprak.reader.reader.ReaderTapController(pdf::setTapHandler)
            override val contentsNavigator = outlineReader?.let { reader -> indexedFiles?.let { files ->
                DocumentPdfContentsNavigator(book, document.pageCount, files, fileMutex, validateIndexedBook, reader::read,
                    active = { current() && pdf.isResumed && !pdf.renderFailed.value }, navigate = pdf::goTo)
            } }
            override val textSelection = pdf.textSelection
            override val pdfOcr = ocr
            override val pdfPageLinks = links
            override val pdfAnnotationNavigator = if (document.textSupported)
                com.yaprak.reader.reader.PdfAnnotationNavigator { anchor ->
                    pdfAnnotationPosition(anchor, document.pageCount)?.let { current() && pdf.goTo(it) } ?: false
                } else null
            override val indexedPdfPageNavigator = indexedFiles?.let { files ->
                DocumentIndexedPdfPageNavigator(book, document, files, fileMutex, validateIndexedBook,
                    active = { pdf.isResumed && current() && !pdf.renderFailed.value },
                    navigate = pdf::goTo)
            }
            override val bookSearch = PdfBookSearch(document, navigate = { page -> current() && pdf.goTo(page) })
            override val pdfThumbnails = thumbnails
            override val pdfCrop = PdfCropController(pdf::applyCrop)
            override val pdfSpread = PdfSpreadController(pdf::applyDoublePage)
            override val pdfScroll = object : com.yaprak.reader.reader.PdfScrollController {
                override val enabled = pdf.scrolling
                override fun apply(enabled: Boolean) = current() && pdf.applyScrolling(enabled)
                override val navigationMode = pdf.navigationMode
                override fun apply(mode: com.yaprak.reader.domain.settings.PdfNavigationMode) = current() && pdf.applyNavigation(mode)
            }
            override val renderingFailed = pdf.renderFailed
            override val readingVisible = combine(pdf.drawnPage, pdf.renderFailed, visibilityClosed, generation) { page, failed, closed, _ ->
                page != null && page in 0 until document.pageCount && !failed && !closed && current() && pdf.bookmarkPage == page
            }.distinctUntilChanged()
            override val bookPositionPercent = combine(pdf.drawnPage, pdf.renderFailed, visibilityClosed, generation) { page, failed, closed, _ ->
                if (!failed && !closed && current() && pdf.bookmarkPage == page)
                    com.yaprak.reader.reader.pagePositionPercent(page, document.pageCount) else null
            }.distinctUntilChanged()
            override val pdfNightMode = PdfNightModeController(pdf::applyNightMode)
            override val pdfSwipe = PdfSwipeController(pdf::allowSwipesWhile)
            override val pdfView = object : PdfViewController {
                override fun fitPage() = pdf.fitPage()
                override fun fitWidth() = pdf.fitWidth()
            }
            override val pageNavigator = object : PageNavigator {
                override val pageCount get() = document.pageCount
                override val currentPage get() = pdf.bookmarkPage
                override fun captureStablePage() = pdf.bookmarkPage
                override fun goTo(pageIndex: Int) = current() && pdf.goTo(pageIndex)
            }
            override val bookmarkNavigator = object : BookmarkNavigator {
                override fun capture(): BookmarkTarget? {
                    val page = pdf.bookmarkPage ?: return null
                    val position = pdf.drawnPosition.value?.takeIf { it.pageIndex == page } ?: PdfResumePosition(page)
                    val label = if (position.anchorY != null)
                        pdf.getString(com.yaprak.reader.R.string.pdf_bookmark_point_label, page + 1)
                    else "Sayfa ${page + 1}"
                    return BookmarkTarget(label, PdfPosition.encode(position))
                }
                override fun goTo(location: ReadingLocation): Boolean {
                    val position = PdfPosition.decodeResumeOrNull(location, document.pageCount) ?: return false
                    return current() && pdf.goTo(position)
                }
            }
            override val pageLabel = combine(pdf.drawnPage, pdf.doublePage, visibilityClosed, generation) { page, double, _, _ ->
                page?.takeIf { current() && pdf.bookmarkPage == it }?.let {
                    if (double && it < document.pageCount - 1) "${it + 1}–${it + 2} / ${document.pageCount}"
                    else "${it + 1} / ${document.pageCount}"
                }
            }
            override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
                pdf.drawnPosition.filterNotNull().collect {
                    if (current() && pdf.bookmarkPage == it.pageIndex) onSnapshot(ReadingSnapshot(PdfPosition.encode(it)))
                }
            }
            override fun forward() = current() && pdf.turn(1)
            override fun backward() = current() && pdf.turn(-1)
        }
    }

    override fun close() {
        if (visibilityClosed.value) return
        visibilityClosed.value = true
        generation.value++
        thumbnails.close()
        document.close()
    }
}

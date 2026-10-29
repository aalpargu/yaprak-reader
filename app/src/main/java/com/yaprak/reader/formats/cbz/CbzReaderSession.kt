package com.yaprak.reader.formats.cbz

import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.reader.BookContents
import com.yaprak.reader.domain.reader.ContentsEntry
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.reader.BookmarkNavigator
import com.yaprak.reader.reader.ContentsNavigator
import com.yaprak.reader.reader.PageNavigator
import com.yaprak.reader.reader.PageThumbnailsSource
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import com.yaprak.reader.reader.ReaderTapController
import com.yaprak.reader.reader.ComicSpreadController
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

internal class CbzReaderSession(override val book: Book, private val document: CbzDocument,
    override val resume: ReadingLocation?,
) : ReaderSession {
    private val closed = MutableStateFlow(false)
    private val generation = MutableStateFlow(0L)
    private val archive = document.archive
    private val thumbnails = CbzThumbnailsSource(document)
    private val doublePagePreferred = MutableStateFlow(false)
    override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface {
        check(!closed.value)
        generation.value++; val owner = generation.value
        val current = { !closed.value && owner == generation.value }
        val reader = CbzReaderFragment(document, CbzPosition.decode(initial, archive, book.format) ?: 0, current, onTap, doublePagePreferred.value)
        return object : ReaderSurface {
            override val fragment = reader
            override val taps = ReaderTapController(reader::setTapHandler)
            override val renderingFailed = reader.renderFailed
            override val readingVisible = combine(reader.drawnPage, closed, generation) { page, stopped, _ ->
                page != null && !stopped && current() && reader.stablePage == page
            }
            override val bookPositionPercent = combine(reader.drawnPage, closed, generation) { page, stopped, _ ->
                if (!stopped && current() && reader.stablePage == page)
                    com.yaprak.reader.reader.pagePositionPercent(page, archive.pages.size) else null
            }.distinctUntilChanged()
            override val pageLabel = combine(reader.drawnPage, reader.drawnPages) { anchor, indices ->
                anchor?.let { if (indices.size == 2) "${indices.first() + 1}–${indices.last() + 1} / ${archive.pages.size}"
                    else "${it + 1} / ${archive.pages.size}" }
            }
            override val comicSpread = object : ComicSpreadController {
                override val preferred = doublePagePreferred.asStateFlow()
                override fun apply(enabled: Boolean): Boolean {
                    if (!current() || !reader.setDoublePage(enabled)) return false
                    doublePagePreferred.value = enabled
                    return true
                }
                override fun restore(enabled: Boolean): Boolean {
                    if (!current() || !reader.restoreDoublePage(enabled)) return false
                    doublePagePreferred.value = enabled
                    return true
                }
            }
            override val pageThumbnails = object : PageThumbnailsSource {
                override val pageCount get() = archive.pages.size
                override suspend fun render(pageIndex: Int): android.graphics.Bitmap {
                    check(current())
                    val image = thumbnails.render(pageIndex)
                    check(current())
                    return image
                }
            }
            override val pageNavigator = object : PageNavigator {
                override val pageCount get() = archive.pages.size
                override val currentPage get() = reader.stablePage
                override fun captureStablePage() = reader.stablePage
                override fun goTo(pageIndex: Int) = current() && reader.goTo(pageIndex)
            }
            override val bookmarkNavigator = object : BookmarkNavigator {
                override fun capture() = reader.stablePage?.let { BookmarkTarget("Sayfa ${it + 1}", CbzPosition.encode(it, archive, book.format)) }
                override fun goTo(location: ReadingLocation): Boolean = CbzPosition.decode(location, archive, book.format)?.let { current() && reader.goTo(it) } ?: false
            }
            override val contentsNavigator = object : ContentsNavigator {
                private val indices = archive.pages.indices.associateBy { "cbz-page:$it" }
                override val contents = BookContents(archive.pages.indices.map { ContentsEntry("cbz-page:$it", "Sayfa ${it + 1}", 0, true) })
                override fun goTo(entryId: String) = indices[entryId]?.let { current() && reader.goTo(it) } ?: false
            }
            override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
                reader.drawnPage.filterNotNull().collect { if (current() && reader.stablePage == it) onSnapshot(ReadingSnapshot(CbzPosition.encode(it, archive, book.format))) }
            }
            override fun forward() = current() && reader.turn(1)
            override fun backward() = current() && reader.turn(-1)
        }
    }
    override fun close() { if (closed.value) return; closed.value = true; generation.value++; thumbnails.close(); document.close() }
}

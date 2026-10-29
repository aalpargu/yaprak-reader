package com.yaprak.reader.formats.pdf

import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.reader.*
import java.util.Collections
import java.util.UUID
import kotlin.math.floor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val MAX_PDF_PAGE_LINKS = 256
internal data class PdfNativeGotoLink(val pageIndex: Int, val x: Double, val y: Double, val zoom: Double)

internal fun validatePdfGotoLinks(links: List<PdfNativeGotoLink>, pageCount: Int) {
    if (links.size > MAX_PDF_PAGE_LINKS) throw PdfPageLinksException(PdfPageLinksFailure.LIMIT)
    if (links.any { it.pageIndex !in 0 until pageCount || !it.x.isFinite() || it.x < 0 ||
            !it.y.isFinite() || it.y < 0 || !it.zoom.isFinite() || it.zoom < 0 })
        throw PdfPageLinksException(PdfPageLinksFailure.SOURCE)
}

/** Main-thread session adapter. Native extraction and target dimensions use PdfDocument's IO lease. */
internal class DocumentPdfPageLinksSource(
    private val document: PdfDocument,
    private val activePage: () -> Int?,
    private val navigate: (PdfResumePosition) -> Boolean,
) : PdfPageLinksSource {
    private data class Batch(val generation: Long, val page: Int, val links: Map<String, PdfNativeGotoLink>)
    private var generation = 0L
    private var resolving = 0L
    private var batch: Batch? = null
    private var resolved: ReadingLocation? = null
    override val supported get() = document.linksAvailable

    fun invalidate() { generation++; resolving++; batch = null; resolved = null }

    private fun requireCurrent(page: Int) {
        if (!document.isOpen) throw PdfPageLinksException(PdfPageLinksFailure.SOURCE)
        if (!document.linksSupported) throw PdfPageLinksException(PdfPageLinksFailure.UNSUPPORTED)
        if (page !in 0 until document.pageCount || activePage() != page)
            throw PdfPageLinksException(PdfPageLinksFailure.INTERRUPTED)
    }

    override suspend fun load(pageIndex: Int): List<PdfPageLink> {
        currentCoroutineContext().ensureActive()
        val owner = ++generation
        batch = null; resolved = null; resolving++
        requireCurrent(pageIndex)
        val raw = document.gotoLinks(pageIndex)
        currentCoroutineContext().ensureActive(); requireCurrent(pageIndex)
        if (owner != generation) throw PdfPageLinksException(PdfPageLinksFailure.INTERRUPTED)
        val mapping = LinkedHashMap<String, PdfNativeGotoLink>()
        val rows = raw.mapIndexed { index, destination ->
            val id = UUID.randomUUID().toString()
            mapping[id] = destination
            PdfPageLink(id, index + 1, destination.pageIndex)
        }
        batch = Batch(owner, pageIndex, mapping)
        return Collections.unmodifiableList(rows)
    }

    override suspend fun resolve(pageIndex: Int, linkId: String): ReadingLocation {
        currentCoroutineContext().ensureActive()
        val owner = ++resolving
        resolved = null
        requireCurrent(pageIndex)
        val issued = batch?.takeIf { it.page == pageIndex && it.generation == generation }
            ?: throw PdfPageLinksException(PdfPageLinksFailure.INTERRUPTED)
        val target = issued.links[linkId] ?: throw PdfPageLinksException(PdfPageLinksFailure.SOURCE)
        val dimensions = document.pageDimensions(target.pageIndex)
        currentCoroutineContext().ensureActive(); requireCurrent(pageIndex)
        if (batch !== issued || owner != resolving)
            throw PdfPageLinksException(PdfPageLinksFailure.INTERRUPTED)
        if (dimensions.width <= 0 || dimensions.height <= 0 || target.x > dimensions.width || target.y > dimensions.height)
            throw PdfPageLinksException(PdfPageLinksFailure.SOURCE)
        val anchor = floor(target.y / dimensions.height * 1_000_000).toInt().coerceIn(0, 1_000_000)
        return PdfPosition.encode(PdfResumePosition(target.pageIndex, anchor)).also { resolved = it }
    }

    override fun goTo(location: ReadingLocation): Boolean {
        val issued = batch ?: return false
        if (location !== resolved || !supported || activePage() != issued.page || issued.generation != generation) return false
        val position = PdfPosition.decodeResumeOrNull(location, document.pageCount) ?: return false
        // A destination is single-use even if native navigation reports a failure.
        resolved = null
        return navigate(position)
    }
}

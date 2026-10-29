@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.reader.ReadingLocation
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.locateProgression

/** A locator is a position hint, never a measure of read or observed text. */
internal fun epubBookPositionPercent(totalProgression: Double?, fixedLayout: Boolean): Int? {
    if (fixedLayout || totalProgression == null || !totalProgression.isFinite() ||
        totalProgression !in 0.0..1.0) return null
    return (totalProgression * 100).roundToInt()
}

/** Readium owns whole-book-to-resource mapping; no guessed chapter or synthetic locator. */
internal class EpubBookPosition(
    private val publication: Publication,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val available: Boolean get() = publication.metadata.presentation.layout != EpubLayout.FIXED

    suspend fun resolvePercent(percent: Int): ReadingLocation? {
        if (!available || percent !in 0..100) return null
        return withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            val locator = publication.locateProgression(percent / 100.0) ?: return@withContext null
            currentCoroutineContext().ensureActive()
            epubBookPositionLocation(locator, publication.readingOrder)
        }
    }
}

internal fun epubBookPositionLocation(locator: Locator, readingOrder: List<Link>): ReadingLocation? {
    val target = normalizedBookPositionLocator(locator) ?: return null
    val location = ReadingLocation(target.toJSON().toString(), target.href.removeFragment().toString())
    return location.takeIf { decodeEpubLocation(it, readingOrder) != null }
}

internal fun decodeEpubBookPositionLocation(location: ReadingLocation, readingOrder: List<Link>): Locator? =
    decodeEpubLocation(location, readingOrder)?.let(::normalizedBookPositionLocator)

private fun normalizedBookPositionLocator(locator: Locator): Locator? {
    val locations = locator.locations
    if (!validProgression(locations.progression) || !validProgression(locations.totalProgression) ||
        locations.position?.let { it < 1 } == true) return null
    // Text and fragment targets take precedence in Readium; retain only the resolved percentage.
    return locator.copy(href = locator.href.removeFragment(), title = null, text = Locator.Text(),
        locations = locations.copy(fragments = emptyList(), otherLocations = emptyMap()))
}

private fun validProgression(value: Double?): Boolean = value != null && value.isFinite() && value in 0.0..1.0

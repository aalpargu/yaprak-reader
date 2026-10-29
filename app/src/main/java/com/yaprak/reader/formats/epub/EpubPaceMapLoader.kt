@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats.epub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.RelativeUrl

/** Read-only publication metadata. Failure disables pace; it never blocks ordinary visibility or duration. */
internal suspend fun loadEpubPaceMap(publication: Publication): EpubPaceMap? = withContext(Dispatchers.IO) {
    try {
        currentCoroutineContext().ensureActive()
        val order = publication.readingOrder
        if (order.isEmpty() || order.size > EpubPaceMap.MAX_RESOURCES) return@withContext null
        val resources = order.map {
            val url = it.url().removeFragment()
            if (url !is RelativeUrl || url.toString().startsWith("//")) return@withContext null
            url.toString()
        }
        val positions = publication.positionsByReadingOrder()
        currentCoroutineContext().ensureActive()
        if (positions.size != order.size || positions.any { it.isEmpty() } ||
            positions.sumOf { it.size.toLong() } > EpubPaceMap.MAX_POSITIONS) return@withContext null
        val mapping = EpubPaceMap.create(resources, positions.map { it.first().locations.totalProgression })
            ?: return@withContext null
        positions.forEachIndexed { index, rows ->
            currentCoroutineContext().ensureActive()
            val range = checkNotNull(mapping.range(resources[index]))
            var previous = range.start
            rows.forEach { locator ->
                val total = locator.locations.totalProgression ?: return@withContext null
                if (locator.href.removeFragment().toString() != resources[index] || !total.isFinite() ||
                    total !in range.start..range.end || total < previous) return@withContext null
                previous = total
            }
        }
        mapping
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}

package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.reader.ReadingLocation
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.RelativeUrl

internal fun decodeEpubLocation(location: ReadingLocation?, readingOrder: List<Link>): Locator? {
    if (location == null || location.locatorVersion != 1 || location.locatorJson.length > 64_000) return null
    val locator = runCatching { Locator.fromJSON(JSONObject(location.locatorJson)) }.getOrNull() ?: return null
    val href = locator.href.removeFragment()
    return locator.takeIf { href is RelativeUrl && !href.toString().startsWith("//") &&
        href.toString() == location.resourceHref && readingOrder.any { it.url().removeFragment() == href } }
}

internal fun bookmarkTarget(locator: Locator): BookmarkTarget {
    // A bookmark needs a location, not before/after text or duplicate title in its identity.
    val anchor = locator.copy(title = null, text = Locator.Text())
    return BookmarkTarget(locator.title?.trim()?.take(200)?.takeIf { it.isNotBlank() } ?: "Yer imi",
        ReadingLocation(anchor.toJSON().toString(), anchor.href.removeFragment().toString()))
}

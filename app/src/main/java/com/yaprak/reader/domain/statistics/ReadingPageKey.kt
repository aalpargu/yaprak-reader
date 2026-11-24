package com.yaprak.reader.domain.statistics

import com.yaprak.reader.domain.reader.ReadingLocation
import org.json.JSONObject

/**
 * Which "page" a saved reading position is on, for the pages-read statistic.
 * - PDF / CBZ / CBR: the real page index ("pageIndex").
 * - Reflowable (Readium): the publication position ("locations.position"), Readium's standard
 *   page equivalent (about 1024 characters).
 * Anything else returns null: an unknown page is never guessed.
 */
fun readingPageKey(location: ReadingLocation): String? = try {
    val json = JSONObject(location.locatorJson)
    when {
        json.has("pageIndex") -> json.getInt("pageIndex").takeIf { it >= 0 }?.let { "page:$it" }
        else -> json.optJSONObject("locations")?.takeIf { it.has("position") }?.getInt("position")
            ?.takeIf { it > 0 }?.let { "position:$it" }
    }
} catch (_: Exception) { null }

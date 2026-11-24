package com.yaprak.reader.data.persistence

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Best-effort reading percent for library cards. Paged formats use the saved page index against the
 * imported page count; reflowable formats use the engine's own total progression when it recorded one.
 * Anything unexpected yields null rather than a guessed value.
 */
internal fun displayProgressPercent(format: String, pageCount: Int?, locatorJson: String): Int? = try {
    val json = JSONObject(locatorJson)
    val pageIndex = json.optInt("pageIndex", -1)
    val fraction = when {
        pageIndex >= 0 && pageCount != null && pageCount > 0 ->
            if (pageCount == 1) 1.0 else pageIndex.toDouble() / (pageCount - 1)
        else -> json.optJSONObject("locations")?.let { locations ->
            locations.optDouble("totalProgression", Double.NaN).takeIf { !it.isNaN() }
        }
    }
    fraction?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { (it * 100).roundToInt() }
        ?.takeIf { format.isNotBlank() }
} catch (_: Exception) { null }

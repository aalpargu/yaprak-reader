package com.yaprak.reader.formats.pdf

import com.yaprak.reader.domain.reader.ReadingLocation
import org.json.JSONObject
import kotlin.math.roundToInt

/** Fixed-point original-page Y, independent of a display's pixel dimensions or crop. */
internal data class PdfResumePosition(val pageIndex: Int, val anchorY: Int? = null) {
    init {
        require(pageIndex >= 0)
        require(anchorY == null || anchorY in 0..1_000_000)
    }
}

/** PDF positions are resume locations, never evidence of observed EPUB text. */
internal object PdfPosition {
    fun encode(pageIndex: Int): ReadingLocation {
        require(pageIndex >= 0)
        return ReadingLocation(JSONObject().put("format", "pdf").put("version", 1)
            .put("pageIndex", pageIndex).toString(), "pdf")
    }

    fun encode(position: PdfResumePosition): ReadingLocation {
        val anchor = position.anchorY ?: return encode(position.pageIndex)
        // locatorVersion is the app envelope; this version belongs only to the opaque PDF payload.
        return ReadingLocation(JSONObject().put("format", "pdf").put("version", 2)
            .put("pageIndex", position.pageIndex).put("anchorY", anchor).toString(), "pdf")
    }

    fun decode(location: ReadingLocation?, pageCount: Int): Int = decodeOrNull(location, pageCount) ?: 0

    fun decodeResume(location: ReadingLocation?, pageCount: Int): PdfResumePosition =
        decodeResumeOrNull(location, pageCount) ?: PdfResumePosition(0)

    /** Explicit navigation must reject invalid anchors instead of silently opening page one. */
    fun decodeOrNull(location: ReadingLocation?, pageCount: Int): Int? =
        decodeResumeOrNull(location, pageCount)?.pageIndex

    fun decodeResumeOrNull(location: ReadingLocation?, pageCount: Int): PdfResumePosition? {
        if (location == null || location.locatorVersion != 1 || location.resourceHref != "pdf" ||
            location.locatorJson.length > 4096) return null
        return runCatching {
            val json = JSONObject(location.locatorJson)
            val index = json.get("pageIndex")
            if (json.getString("format") != "pdf" || index !is Int || index !in 0 until pageCount)
                return@runCatching null
            when (json.get("version")) {
                // Preserve the shipped v1 decoder, including its treatment of additional keys.
                1 -> PdfResumePosition(index)
                2 -> {
                    if (json.keys().asSequence().toSet() != setOf("format", "version", "pageIndex", "anchorY"))
                        return@runCatching null
                    val anchor = json.get("anchorY")
                    if (anchor is Int && anchor in 0..1_000_000) PdfResumePosition(index, anchor) else null
                }
                else -> null
            }
        }.getOrNull()
    }
}

/** A page uses at most 2048 x 2048 ARGB pixels (16 MiB), regardless of document dimensions. */
internal fun pdfBitmapSize(width: Int, height: Int, viewportWidth: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && viewportWidth > 0)
    val scale = minOf(viewportWidth.toDouble() * 2 / width, 2048.0 / maxOf(width, height))
    return (width * scale).roundToInt().coerceIn(1, 2048) to
        (height * scale).roundToInt().coerceIn(1, 2048)
}

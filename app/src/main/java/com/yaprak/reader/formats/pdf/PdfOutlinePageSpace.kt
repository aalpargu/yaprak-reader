package com.yaprak.reader.formats.pdf

import kotlin.math.roundToInt

/** Default PDF user space -> rotated, cropped native paper Y. No bitmap, fit/zoom or display coordinates. */
internal class PdfOutlinePageSpace private constructor(
    private val left: Double, private val bottom: Double, private val right: Double,
    private val top: Double, private val rotation: Int,
) {
    fun anchorY(destinationLeft: Double?, destinationTop: Double?): Int? {
        val fraction = when (rotation) {
            0 -> destinationTop?.let { (top - it) / (top - bottom) }
            90 -> destinationLeft?.let { (it - left) / (right - left) }
            180 -> destinationTop?.let { (it - bottom) / (top - bottom) }
            270 -> destinationLeft?.let { (right - it) / (right - left) }
            else -> null
        } ?: return null
        if (!fraction.isFinite()) return null
        return (fraction.coerceIn(0.0, 1.0) * 1_000_000).roundToInt()
    }

    companion object {
        fun intersection(media: List<Double>, crop: List<Double>, rotation: Int): PdfOutlinePageSpace? {
            if (rotation !in setOf(0, 90, 180, 270) || media.size != 4 || crop.size != 4 ||
                (media + crop).any { !it.isFinite() }) return null
            val left = maxOf(minOf(media[0], media[2]), minOf(crop[0], crop[2]))
            val bottom = maxOf(minOf(media[1], media[3]), minOf(crop[1], crop[3]))
            val right = minOf(maxOf(media[0], media[2]), maxOf(crop[0], crop[2]))
            val top = minOf(maxOf(media[1], media[3]), maxOf(crop[1], crop[3]))
            if (right <= left || top <= bottom || !(right - left).isFinite() || !(top - bottom).isFinite()) return null
            return PdfOutlinePageSpace(left, bottom, right, top, rotation)
        }
    }
}

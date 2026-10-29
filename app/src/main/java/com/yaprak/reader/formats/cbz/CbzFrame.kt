package com.yaprak.reader.formats.cbz

import android.graphics.Bitmap
import java.io.Closeable

internal data class CbzRenderedPage(val index: Int, val bitmap: Bitmap)

/** Owns unshared originals; the anchor is the selected page, including a pair's right member. */
internal class CbzFrame(val anchor: Int, val pages: List<CbzRenderedPage>) : Closeable {
    init {
        require(pages.size in 1..2 && pages.any { it.index == anchor })
        require(pages.all { it.index >= 0 && !it.bitmap.isRecycled })
        require(pages.zipWithNext().all { (left, right) -> right.index == left.index + 1 && left.bitmap !== right.bitmap })
        require(pages.sumOf { it.bitmap.width.toLong() * it.bitmap.height } <= CbzImages.MAX_BITMAP_PIXELS)
    }
    val aspectRatio = pages.sumOf { it.bitmap.width.toDouble() / it.bitmap.height }.toFloat()
    override fun close() { pages.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() } }
}

/** Pure geometry: no selected or adjacent index is considered read by this calculation. */
internal object CbzSpreadLayout {
    fun pages(anchor: Int, count: Int, doublePage: Boolean): List<Int> {
        require(anchor in 0 until count)
        if (!doublePage) return listOf(anchor)
        val first = anchor - anchor % 2
        return (first until minOf(first + 2, count)).toList()
    }
    fun turn(anchor: Int, count: Int, doublePage: Boolean, delta: Int): Int? {
        if (anchor !in 0 until count || delta !in setOf(-1, 1)) return null
        val target = if (doublePage) anchor - anchor % 2 + delta * 2 else anchor + delta
        return target.takeIf { it in 0 until count }
    }
    fun wide(width: Int, height: Int, density: Float): Boolean =
        density.isFinite() && density > 0f && height > 0 && width >= height && width / density >= 600f
}

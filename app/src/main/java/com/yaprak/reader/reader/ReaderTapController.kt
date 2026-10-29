package com.yaprak.reader.reader

import com.yaprak.reader.domain.settings.TapZone

/** Coordinates of an unhandled single content tap in the whole rendered viewport. */
data class ReaderTap(val xFraction: Float, val yFraction: Float) {
    val zone: TapZone? get() {
        if (!xFraction.isFinite() || !yFraction.isFinite() ||
            xFraction < 0f || xFraction >= 1f || yFraction < 0f || yFraction >= 1f) return null
        return when {
            xFraction < 1f / 3f -> TapZone.LEFT
            xFraction < 2f / 3f -> TapZone.MIDDLE
            else -> TapZone.RIGHT
        }
    }
    companion object {
        fun fromPixels(x: Float, y: Float, width: Int, height: Int): ReaderTap =
            if (width > 0 && height > 0) ReaderTap(x / width, y / height)
            else ReaderTap(Float.NaN, Float.NaN)
    }
}

/** False retains the format's original tap behavior; true owns even a blocked/no-op tap. */
fun interface ReaderTapController {
    fun setHandler(handler: ((ReaderTap) -> Boolean)?)
}
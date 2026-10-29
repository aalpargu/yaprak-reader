package com.yaprak.reader.covers

internal const val COVER_WIDTH = 384
internal const val COVER_HEIGHT = 512

/** Fits both dimensions without upscaling or multiplying untrusted Int dimensions. */
internal fun thumbnailSize(width: Int, height: Int, maxWidth: Int = COVER_WIDTH, maxHeight: Int = COVER_HEIGHT): Pair<Int, Int>? {
    if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) return null
    val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
    return (width * scale).toInt().coerceIn(1, maxWidth) to
        (height * scale).toInt().coerceIn(1, maxHeight)
}

/** Bounds decode output before the full raster is allocated. */
internal fun coverSampleSize(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0 || width > 16_384 || height > 16_384 ||
        width.toLong() * height > 40_000_000L) return null
    var sample = 1
    while ((width.toLong() + sample - 1) / sample > COVER_WIDTH ||
        (height.toLong() + sample - 1) / sample > COVER_HEIGHT) sample *= 2
    return sample
}

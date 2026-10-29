package com.yaprak.reader.reader

import android.graphics.Bitmap

/** Original fixed-page previews, separate from reading visibility and progress.
 * Indices are zero-based. Delivered bitmaps are shared, read-only and source-owned;
 * callers must never mutate or recycle them, including after dismissal or eviction.
 */
interface PageThumbnailsSource {
    val pageCount: Int
    suspend fun render(pageIndex: Int): Bitmap
}

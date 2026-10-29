package com.yaprak.reader.reader

import android.graphics.Bitmap

/** Original-page previews at the Android rendering boundary; never a reading-position change.
 * All page indices are zero-based. Returned images are shared, read-only and owned by the
 * source: callers must never mutate or recycle them, including after dismissal or eviction.
 */
interface PdfThumbnailsSource : PageThumbnailsSource {
    override val pageCount: Int
    override suspend fun render(pageIndex: Int): Bitmap
}

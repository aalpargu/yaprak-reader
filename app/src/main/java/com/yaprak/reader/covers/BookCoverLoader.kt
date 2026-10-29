package com.yaprak.reader.covers

import android.graphics.Bitmap
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import java.io.File

/** Optional local artwork; null leaves the typographic jacket in place. */
fun interface BookCoverLoader {
    suspend fun load(book: Book): Bitmap?
}

/** Format implementations own publication/PDF resources; callers own returned bitmaps. */
internal fun interface CoverBitmapReader {
    suspend fun read(file: File, format: BookFormat): Bitmap?
}

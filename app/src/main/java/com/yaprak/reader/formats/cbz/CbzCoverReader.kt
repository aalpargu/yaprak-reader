package com.yaprak.reader.formats.cbz

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.yaprak.reader.covers.thumbnailSize
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Caller verified the original file hash. No XML metadata, remote image or generated cover. */
internal class CbzCoverReader {
    suspend fun read(file: File, admitted: CbzArchive? = null): Bitmap? {
        val trusted = admitted?.pages?.firstOrNull()
        val entry = trusted?.entry ?: CbzArchive.index(file).filter { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp") }
            .minWithOrNull { a, b -> cbzNaturalOrder(a.name, b.name) } ?: return null
        val bytes = ZipFile(file, Charsets.UTF_8).use { CbzArchive.readEntry(it, entry) }
        if (trusted != null && cbzHash(bytes) != trusted.sha256) cbzFail()
        val image = CbzImages.validate(bytes, entry.name.substringAfterLast('.').lowercase(Locale.ROOT))
        var owned: Bitmap? = CbzImages.decode(bytes, image)
        try {
            val original = owned!!; val size = thumbnailSize(original.width, original.height) ?: return null
            if (size.first != original.width || size.second != original.height) {
                val scaled = original.scale(size.first, size.second, true)
                owned = scaled; if (scaled !== original) original.recycle()
            }
            currentCoroutineContext().ensureActive()
            val result = owned; owned = null; return result
        } finally { owned?.takeUnless { it.isRecycled }?.recycle() }
    }
}

package com.yaprak.reader.formats.epub

import android.content.ContentResolver
import android.graphics.Bitmap
import com.yaprak.reader.formats.BoundedCoverBitmapDecoder
import com.yaprak.reader.formats.MAX_COVER_BYTES
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource

internal class EpubCoverReader(resolver: ContentResolver) {
    private val source = EpubPublicationSource(resolver)

    suspend fun read(file: File): Bitmap? = source.open(file).use { opened ->
        val publication = opened.publication
        val cover = localRasterCover(publication.resources + publication.readingOrder) ?: return@use null
        val resource = publication.get(cover) ?: return@use null
        val bytes = readBoundedCover(resource) ?: return@use null
        val bitmap = BoundedCoverBitmapDecoder.decode(bytes) ?: return@use null
        try {
            currentCoroutineContext().ensureActive()
            bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }
}

/** Only an explicit local raster cover relationship supplies library artwork. */
internal fun localRasterCover(links: List<Link>): Link? = links.firstOrNull { link ->
    if ("cover" !in link.rels || link.href.isTemplated ||
        link.mediaType !in setOf(MediaType.JPEG, MediaType.PNG, MediaType.WEBP)) return@firstOrNull false
    val url = link.url() as? RelativeUrl ?: return@firstOrNull false
    val path = url.path ?: return@firstOrNull false
    val encoded = url.toString()
    !path.startsWith('/') && !encoded.startsWith("//") && '?' !in encoded && '#' !in encoded
}

/** The extra byte detects oversized content even when the ZIP size hint is incorrect. */
internal suspend fun readBoundedCover(resource: Resource): ByteArray? = try {
    val length = resource.length().getOrNull()
    if (length != null && (length <= 0 || length > MAX_COVER_BYTES)) null
    else {
        val bytes = resource.read(0L..MAX_COVER_BYTES.toLong()).getOrElse {
            throw IOException("Cannot read local cover resource")
        }
        bytes.takeIf { it.isNotEmpty() && it.size <= MAX_COVER_BYTES }
    }
} finally {
    resource.close()
}

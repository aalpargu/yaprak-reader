package com.yaprak.reader.formats.epub

import android.content.ContentResolver
import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.ImportFailure
import java.io.Closeable
import java.io.File
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.encryption.encryption
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.file.FileResource
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.resource.borrow
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser

internal class OpenEpub(val publication: Publication, private val resource: FileResource) : Closeable {
    override fun close() { try { publication.close() } finally { resource.close() } }
}

/** Import and reader share validation and resource ownership. */
internal class EpubPublicationSource(resolver: ContentResolver) {
    private val assets = AssetRetriever(resolver, DefaultHttpClient())
    private val opener = PublicationOpener(EpubParser())

    suspend fun open(file: File): OpenEpub {
        val resource = FileResource(file)
        var asset: Asset? = null
        var publication: Publication? = null
        try {
            val retrieved = assets.retrieve(resource.borrow())
                .getOrElse { throw BookImportException(ImportFailure.INVALID) }
            asset = retrieved
            val opened = opener.open(retrieved, allowUserInteraction = false)
                .getOrElse { throw BookImportException(ImportFailure.INVALID) }
            publication = opened
            val fontObfuscation = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
            val encrypted = opened.readingOrder.any { it.properties.encryption != null } ||
                opened.resources.any { it.properties.encryption?.algorithm?.let { value -> value !in fontObfuscation } == true }
            if (opened.isRestricted || encrypted) throw BookImportException(ImportFailure.PROTECTED)
            if (!opened.conformsTo(Publication.Profile.EPUB) || opened.readingOrder.isEmpty()) {
                throw BookImportException(ImportFailure.INVALID)
            }
            return OpenEpub(opened, resource)
        } catch (error: Throwable) {
            try { if (publication != null) publication.close() else asset?.close() }
            finally { resource.close() }
            throw error
        }
    }
}

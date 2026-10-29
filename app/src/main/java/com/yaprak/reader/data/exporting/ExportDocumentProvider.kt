package com.yaprak.reader.data.exporting

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream

/** The URI is supplied by ACTION_CREATE_DOCUMENT; no long-lived grant is retained. */
interface ExportDocumentProvider {
    fun accepts(uri: String): Boolean
    fun open(uri: String): OutputStream
    fun delete(uri: String): Boolean
}

class AndroidExportDocumentProvider(private val resolver: ContentResolver) : ExportDocumentProvider {
    override fun accepts(uri: String): Boolean =
        Uri.parse(uri).scheme == ContentResolver.SCHEME_CONTENT

    override fun open(uri: String): OutputStream {
        require(accepts(uri))
        return resolver.openOutputStream(Uri.parse(uri), "wt")
            ?: throw SecurityException("Cannot open created document for writing")
    }

    override fun delete(uri: String): Boolean {
        require(accepts(uri))
        return DocumentsContract.deleteDocument(resolver, Uri.parse(uri))
    }
}

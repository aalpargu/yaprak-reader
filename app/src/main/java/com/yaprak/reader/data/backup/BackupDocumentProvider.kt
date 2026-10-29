package com.yaprak.reader.data.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream

/** SAF URI access remains at the data edge; grants are not retained. */
interface BackupDocumentProvider {
    fun accepts(uri: String): Boolean
    fun openInput(uri: String): InputStream
    fun openOutput(uri: String): OutputStream
    fun delete(uri: String): Boolean
}

class AndroidBackupDocumentProvider(private val resolver: ContentResolver) : BackupDocumentProvider {
    override fun accepts(uri: String): Boolean =
        Uri.parse(uri).scheme == ContentResolver.SCHEME_CONTENT

    override fun openInput(uri: String): InputStream {
        require(accepts(uri))
        return resolver.openInputStream(Uri.parse(uri))
            ?: throw SecurityException("Cannot open backup document")
    }

    override fun openOutput(uri: String): OutputStream {
        require(accepts(uri))
        return resolver.openOutputStream(Uri.parse(uri), "wt")
            ?: throw SecurityException("Cannot write backup document")
    }

    override fun delete(uri: String): Boolean {
        require(accepts(uri))
        return DocumentsContract.deleteDocument(resolver, Uri.parse(uri))
    }
}

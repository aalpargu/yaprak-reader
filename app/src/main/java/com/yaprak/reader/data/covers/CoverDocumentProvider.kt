package com.yaprak.reader.data.covers

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream

fun interface CoverDocumentProvider {
    fun open(uri: String): InputStream
}

class AndroidCoverDocumentProvider(private val resolver: ContentResolver) : CoverDocumentProvider {
    override fun open(uri: String): InputStream {
        val document = Uri.parse(uri)
        if (document.scheme != ContentResolver.SCHEME_CONTENT) throw SecurityException("Cover requires content grant")
        return resolver.openInputStream(document) ?: throw SecurityException("Cover is unavailable")
    }
}

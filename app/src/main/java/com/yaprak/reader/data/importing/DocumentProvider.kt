package com.yaprak.reader.data.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.ImportFailure
import java.io.InputStream

data class SourceDocument(val name: String, val open: () -> InputStream)

fun interface DocumentProvider {
    fun resolve(uri: String): SourceDocument
}

class AndroidDocumentProvider(private val resolver: ContentResolver) : DocumentProvider {
    override fun resolve(uri: String): SourceDocument {
        val document = Uri.parse(uri)
        if (document.scheme != ContentResolver.SCHEME_CONTENT) {
            throw BookImportException(ImportFailure.ACCESS)
        }
        val name = resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.takeIf { it.isNotBlank() } ?: "Kitap"
        // The picker grant is needed only during copying. No persistent URI grant is retained.
        return SourceDocument(name.take(512)) {
            resolver.openInputStream(document) ?: throw BookImportException(ImportFailure.ACCESS)
        }
    }
}

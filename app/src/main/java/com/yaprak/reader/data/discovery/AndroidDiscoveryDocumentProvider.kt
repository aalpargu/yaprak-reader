package com.yaprak.reader.data.discovery

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Only constructs descendants through the selected tree grant; no broad storage permission. */
class AndroidDiscoveryDocumentProvider internal constructor(
    private val query: (Uri, Array<String>, CancellationSignal) -> Cursor?,
) : DiscoveryDocumentProvider {
    constructor(resolver: ContentResolver) : this({ uri, projection, signal ->
        resolver.query(uri, projection, null, null, null, signal)
    })

    override fun resolveTree(treeUri: String): DiscoveryTree {
        if (treeUri.length > 20_000 || Regex("%(?![a-fA-F0-9]{2})").containsMatchIn(treeUri)) throw InvalidDiscoveryTreeException()
        val uri = Uri.parse(treeUri)
        val authority = uri.authority
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || authority.isNullOrBlank() ||
            authority.length > 255 || authority != uri.encodedAuthority ||
            authority.any { it.isWhitespace() || Character.isISOControl(it) || it == '/' || it == '\\' || it == '?' || it == '#' || it == '@' || it == ':' } ||
            uri.query != null || uri.fragment != null || uri.pathSegments.size != 2 ||
            uri.pathSegments.firstOrNull() != "tree" || !DocumentsContract.isTreeUri(uri)) throw InvalidDiscoveryTreeException()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (!validDiscoveryId(rootId)) throw InvalidDiscoveryTreeException()
        // Rebuild rather than retain malformed escaping from the selection string.
        return DiscoveryTree(DocumentsContract.buildTreeDocumentUri(authority, rootId!!).toString(), authority, rootId)
    }

    override fun documentUri(tree: DiscoveryTree, documentId: String): String {
        require(validDiscoveryId(documentId))
        return DocumentsContract.buildDocumentUriUsingTree(checkedTree(tree), documentId).toString()
    }

    override suspend fun children(tree: DiscoveryTree, parentId: String, onChild: (DiscoveryDocument) -> Boolean) {
        require(validDiscoveryId(parentId))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(checkedTree(tree), parentId)
        // Registration precedes synchronous query. Cancellation can unblock a compliant provider.
        suspendCancellableCoroutine<Unit> { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                val cursor = query(children, PROJECTION, signal) ?: throw IOException("Document provider returned no cursor")
                cursor.use {
                    val idIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val flagsIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                    if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0 || flagsIndex < 0) throw IOException("Document provider omitted required columns")
                    fun text(index: Int): String? = if (index < 0 || it.isNull(index)) null else it.getString(index)
                    while (continuation.isActive && it.moveToNext()) {
                        if (!continuation.isActive) throw CancellationException("Document scan cancelled")
                        val flags = if (flagsIndex < 0 || it.isNull(flagsIndex)) 0 else it.getInt(flagsIndex)
                        val row = DiscoveryDocument(text(idIndex), text(nameIndex), text(mimeIndex),
                            if (sizeIndex < 0 || it.isNull(sizeIndex)) null else it.getLong(sizeIndex),
                            flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0)
                        if (!onChild(row)) break
                    }
                }
                if (continuation.isActive) continuation.resume(Unit)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private fun checkedTree(tree: DiscoveryTree): Uri {
        val parsed = resolveTree(tree.uri)
        require(parsed.authority == tree.authority && parsed.rootId == tree.rootId)
        return Uri.parse(parsed.uri)
    }

    private companion object {
        val PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)
    }
}

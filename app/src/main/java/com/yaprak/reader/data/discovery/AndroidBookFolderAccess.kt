package com.yaprak.reader.data.discovery

import android.content.ContentResolver
import android.content.Intent
import android.os.CancellationSignal
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.yaprak.reader.domain.library.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** All methods run on repository IO. A saved URI alone never proves current permission. */
internal interface BookFolderAccess {
    suspend fun inspect(selectedTreeUri: String): SavedBookFolder
    fun hasRead(treeUri: String): Boolean
    fun takeRead(treeUri: String)
    fun releaseRead(treeUri: String)
}

internal class AndroidBookFolderAccess(private val resolver: ContentResolver) : BookFolderAccess {
    private val trees = AndroidDiscoveryDocumentProvider(resolver)

    override suspend fun inspect(selectedTreeUri: String): SavedBookFolder {
        val tree = trees.resolveTree(selectedTreeUri)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree.uri.toUri(), tree.rootId)
        return suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                val cursor = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null, cancellation)
                    ?: throw IOException("Folder provider returned no cursor")
                val folder = cursor.use {
                    if (!it.moveToFirst()) throw IOException("Folder is missing")
                    val mime = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (it.getString(mime) != DocumentsContract.Document.MIME_TYPE_DIR) throw InvalidDiscoveryTreeException()
                    val name = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val displayName = if (it.isNull(name)) null else it.getString(name)
                    SavedBookFolder(tree.uri, displayName?.takeIf { candidate ->
                        candidate.isNotBlank() && candidate.length <= 512 && validFolderUnicode(candidate) && candidate.none(Char::isISOControl)
                    }).also { folder -> require(folder.valid) }
                }
                if (continuation.isActive) continuation.resume(folder)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }

    private fun matchingRead(treeUri: String) = resolver.persistedUriPermissions.filter { permission ->
        permission.isReadPermission && runCatching { trees.resolveTree(permission.uri.toString()).uri == treeUri }.getOrDefault(false)
    }
    override fun hasRead(treeUri: String) = matchingRead(treeUri).isNotEmpty()
    override fun takeRead(treeUri: String) {
        resolver.takePersistableUriPermission(treeUri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!hasRead(treeUri)) throw SecurityException("Persistent folder read permission is missing")
    }
    override fun releaseRead(treeUri: String) {
        matchingRead(treeUri).forEach { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

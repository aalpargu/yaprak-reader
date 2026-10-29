package com.yaprak.reader.data.discovery

/** IDs belong to the provider. A slash or colon in an ID is not a filesystem path. */
data class DiscoveryTree(val uri: String, val authority: String, val rootId: String)

data class DiscoveryDocument(
    val id: String?,
    val name: String?,
    val mimeType: String?,
    val sizeBytes: Long? = null,
    val virtual: Boolean = false,
)

interface DiscoveryDocumentProvider {
    fun resolveTree(treeUri: String): DiscoveryTree

    /** Streams rows. False stops immediately; the provider always closes its cursor. */
    suspend fun children(tree: DiscoveryTree, parentId: String, onChild: (DiscoveryDocument) -> Boolean)
    fun documentUri(tree: DiscoveryTree, documentId: String): String
}

class InvalidDiscoveryTreeException : IllegalArgumentException("Invalid document tree")

internal fun validDiscoveryId(value: String?) = value != null && value.isNotBlank() &&
    value.length <= 4096 && value.none { Character.isISOControl(it) }

internal const val DIRECTORY_MIME = "vnd.android.document/directory"

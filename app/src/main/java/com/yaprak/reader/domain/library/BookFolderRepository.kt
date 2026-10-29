package com.yaprak.reader.domain.library

import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Device-selected folder, independent of book backup, file ownership and library metadata. */
data class SavedBookFolder(val treeUri: String, val displayName: String?) {
    val valid: Boolean get() = validBookFolderUri(treeUri) && (displayName == null ||
        (displayName.isNotBlank() && displayName.length <= 512 && displayName.none { it.isISOControl() } && validFolderUnicode(displayName)))
}

data class BookFolderSnapshot(val revision: String, val folder: SavedBookFolder?, val automaticScan: Boolean = false) {
    val valid: Boolean get() = runCatching { UUID.fromString(revision).toString() == revision }.getOrDefault(false) &&
        folder?.valid != false && (!automaticScan || folder != null)
}

/** A committed preference remains committed even if old grant cleanup must be retried. */
data class BookFolderWrite(val snapshot: BookFolderSnapshot, val cleanupPending: Boolean = false)

interface BookFolderRepository {
    fun observe(): Flow<BookFolderSnapshot>
    suspend fun save(expected: BookFolderSnapshot, selectedTreeUri: String): BookFolderWrite
    suspend fun forget(expected: BookFolderSnapshot): BookFolderWrite
    suspend fun setAutomaticScan(expected: BookFolderSnapshot, enabled: Boolean): BookFolderWrite =
        throw UnsupportedOperationException("Opening scan preference is not supported")
    suspend fun retryCleanup()
    /** Actual saved revision and current persisted read permission are rechecked for every scan. */
    suspend fun resolveForScan(expected: BookFolderSnapshot): String
}

enum class BookFolderFailure { INVALID, SOURCE, ACCESS, CHANGED, RECOVERY }
class BookFolderException(val reason: BookFolderFailure) : Exception(reason.name)

internal fun validBookFolderUri(value: String): Boolean {
    if (value.length > 20_000 || value.any { it.isISOControl() || it == '\\' } || !validFolderUnicode(value)) return false
    return try {
        val uri = URI(value)
        val authority = uri.rawAuthority ?: return false
        val rootId = uri.path.removePrefix("/tree/")
        uri.scheme == "content" && authority.isNotBlank() && authority.length <= 255 && authority == uri.authority &&
            authority.none { it.isWhitespace() || it in "\\/?#@:%" } && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath?.matches(Regex("/tree/[^/]+")) == true && rootId.isNotBlank() && rootId.length <= 4096 &&
            validFolderUnicode(rootId) && uri.path.none { it.isISOControl() || it == '\\' }
    } catch (_: Exception) { false }
}

internal fun validFolderUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
            index += 2
        } else { if (character.isLowSurrogate()) return false; index++ }
    }
    return true
}

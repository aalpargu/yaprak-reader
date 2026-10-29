package com.yaprak.reader.domain.library

/** Removes one exact version of an imported book and its app-owned data. */
fun interface BookRemover {
    suspend fun remove(bookId: String, contentSha256: String): BookRemovalResult
}

sealed interface BookRemovalResult {
    data class Removed(val privateFileMayRemain: Boolean = false) : BookRemovalResult
    data object Missing : BookRemovalResult
    data object InUse : BookRemovalResult
    data object Failed : BookRemovalResult
}

package com.yaprak.reader.domain.library

/** Read-only discovery inside the folder selected by the user. Import verifies the actual bytes. */
interface BookDiscovery {
    suspend fun scan(treeUri: String, onProgress: (DiscoveryProgress) -> Unit): DiscoveryResult
}

data class BookCandidate(
    val id: String,
    val name: String,
    val documentUri: String,
    val sizeBytes: Long?,
    val formatHint: BookFormat,
)

data class DiscoveryProgress(val visited: Int, val candidates: Int)

enum class DiscoveryLimit { DEPTH, DOCUMENTS, CANDIDATES, TIME }
enum class DiscoveryFailure { INVALID_TREE, ACCESS, IO, UNEXPECTED }

sealed interface DiscoveryResult {
    data class Complete(val candidates: List<BookCandidate>, val visited: Int) : DiscoveryResult
    data class Truncated(val candidates: List<BookCandidate>, val visited: Int, val reason: DiscoveryLimit) : DiscoveryResult
    data class Failed(val reason: DiscoveryFailure) : DiscoveryResult
}

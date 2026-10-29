package com.yaprak.reader.domain.library

import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.flow.Flow

const val MAX_LIBRARY_LABEL_ROWS = 10_000
const val MAX_LIBRARY_LABEL_NAMES = 10_000
enum class LibraryLabelKind { TAG, COLLECTION }
enum class LibraryLabelFailure { SOURCE, RECOVERY, CONFLICT, INVALID_INPUT, LIMIT }
class LibraryLabelException(val reason: LibraryLabelFailure, cause: Throwable? = null) : Exception(reason.name, cause)
data class LibraryLabelMember(val bookId: String, val contentSha256: String, val organization: BookOrganization)
data class LibraryLabelEntry(val name: String, val bookCount: Int)
fun libraryLabelKey(value: String) = value.replace('I', 'i').replace('İ', 'i').replace('ı', 'i').lowercase(Locale.ROOT)
fun LibraryLabelKind.names(organization: BookOrganization) = when (this) {
    LibraryLabelKind.TAG -> organization.tags
    LibraryLabelKind.COLLECTION -> organization.collections
}
fun canonicalLibraryLabel(value: String): String = try {
    BookOrganization(tags = listOf(value)).normalized().tags.single()
} catch (failure: Exception) { throw LibraryLabelException(LibraryLabelFailure.INVALID_INPUT, failure) }

/** Defensive current-library snapshot. Source operations must recheck complete matching membership. */
class LibraryLabelSnapshot(members: List<LibraryLabelMember>) {
    val members: List<LibraryLabelMember> = Collections.unmodifiableList(members.map { row ->
        row.copy(organization = row.organization.copy(
            tags = Collections.unmodifiableList(ArrayList(row.organization.tags)),
            collections = Collections.unmodifiableList(ArrayList(row.organization.collections))))
    })
    private val entries: Map<LibraryLabelKind, List<LibraryLabelEntry>>
    init {
        if (this.members.size > MAX_LIBRARY_LABEL_ROWS) throw LibraryLabelException(LibraryLabelFailure.LIMIT)
        require(this.members.map { it.bookId }.distinct().size == this.members.size)
        this.members.forEach(::validateLibraryLabelMember)
        entries = LibraryLabelKind.entries.associateWith { kind ->
            val counts = linkedMapOf<String, LibraryLabelEntry>()
            for (row in this.members) for (name in kind.names(row.organization)) {
                val key = libraryLabelKey(name)
                val previous = counts[key]
                counts[key] = if (previous == null) LibraryLabelEntry(name, 1)
                    else previous.copy(bookCount = previous.bookCount + 1)
                if (counts.size > MAX_LIBRARY_LABEL_NAMES) throw LibraryLabelException(LibraryLabelFailure.LIMIT)
            }
            Collections.unmodifiableList(counts.toSortedMap().values.toList())
        }
    }
    fun entries(kind: LibraryLabelKind): List<LibraryLabelEntry> = entries.getValue(kind)
    fun matching(kind: LibraryLabelKind, name: String): List<LibraryLabelMember> {
        val key = libraryLabelKey(name)
        return members.filter { row -> kind.names(row.organization).any { libraryLabelKey(it) == key } }
    }
}
fun validateLibraryLabelMember(row: LibraryLabelMember) {
    require(row.bookId.isNotBlank() && row.bookId.length <= 200 && row.bookId.none(Char::isISOControl))
    require(row.contentSha256.matches(Regex("[0-9a-f]{64}")))
    require(row.organization.normalized() == row.organization)
}
interface LibraryLabelsRepository {
    fun observe(): Flow<LibraryLabelSnapshot>
    /** Returns the actual affected-book count, or atomically refuses changed membership/metadata. */
    suspend fun change(kind: LibraryLabelKind, source: String, target: String?,
        expected: List<LibraryLabelMember>): Int
}

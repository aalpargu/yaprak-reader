package com.yaprak.reader.domain

import com.yaprak.reader.domain.library.*
import org.junit.Assert.*
import org.junit.Test

class LibraryLabelsTest {
    private fun member(id: String, tags: List<String>) = LibraryLabelMember(id, "a".repeat(64), BookOrganization(tags = tags))
    @Test fun snapshotOwnsNestedInputsAndCountsFoldedNamesOncePerBook() {
        val tags = mutableListOf("İş", "Başka")
        val rows = mutableListOf(member("first", tags), member("second", listOf("ış")))
        val snapshot = LibraryLabelSnapshot(rows)
        rows.clear(); tags.clear()
        assertEquals(2, snapshot.entries(LibraryLabelKind.TAG).single { it.name == "İş" }.bookCount)
        assertEquals(listOf("first", "second"), snapshot.matching(LibraryLabelKind.TAG, "IŞ").map { it.bookId })
        assertTrue(runCatching { (snapshot.members as MutableList).clear() }.isFailure)
        assertTrue(runCatching { (snapshot.members.first().organization.tags as MutableList).clear() }.isFailure)
        assertEquals("Ad", canonicalLibraryLabel(" Ad "))
        for (invalid in listOf("", "a,b", "a\nb", "x".repeat(101))) assertTrue(runCatching { canonicalLibraryLabel(invalid) }.isFailure)
    }
    @Test fun excessiveDistinctLabelsRefuseTruncatedSuccessAndInvalidOwnershipIsRejected() {
        val rows = (0..200).map { index -> member(index.toString(), (0 until 50).map { "Ad-$index-$it" }) }
        assertEquals(LibraryLabelFailure.LIMIT, (runCatching { LibraryLabelSnapshot(rows) }.exceptionOrNull() as LibraryLabelException).reason)
        assertTrue(runCatching { LibraryLabelSnapshot(listOf(member("", listOf("Ad")))) }.isFailure)
        assertTrue(runCatching { LibraryLabelSnapshot(listOf(member("same", listOf("Ad")), member("same", listOf("Ad")))) }.isFailure)
        assertTrue(runCatching { LibraryLabelSnapshot(listOf(member("x", listOf(" İş ")))) }.isFailure)
    }
}

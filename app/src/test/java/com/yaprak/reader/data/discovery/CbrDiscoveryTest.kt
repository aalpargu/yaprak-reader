package com.yaprak.reader.data.discovery

import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.library.LibraryFilter
import com.yaprak.reader.feature.library.filterBooks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CbrDiscoveryTest {
    @Test fun cbrLibraryFilterPreservesOrderAndNeverMixesOtherContainers() {
        val books = listOf(BookFormat.PDF, BookFormat.CBR, BookFormat.CBZ, BookFormat.CBR).mapIndexed { index, format ->
            Book("$index", "Book $index", null, format, "source.${format.extension}", "$index.${format.extension}",
                1, "a".repeat(64), 1, 2)
        }
        assertEquals(listOf(books[1], books[3]), filterBooks(books, LibraryFilter.CBR))
        assertEquals(listOf(books[2]), filterBooks(books, LibraryFilter.CBZ))
        assertEquals(books, filterBooks(books, LibraryFilter.ALL))
    }
    @Test fun comicMimeAndCbrSuffixAreOnlyDiscoveryHintsAndVirtualRowsStayExcluded() = runBlocking {
        val provider = object : DiscoveryDocumentProvider {
            override fun resolveTree(treeUri: String) = DiscoveryTree(treeUri, "owned", "root")
            override suspend fun children(tree: DiscoveryTree, parentId: String, onChild: (DiscoveryDocument) -> Boolean) {
                for (row in listOf(
                    DiscoveryDocument("suffix", "Comic.CBR", "application/octet-stream"),
                    DiscoveryDocument("mime", "Unnamed comic", "application/vnd.comicbook-rar"),
                    DiscoveryDocument("alias", "Other comic", "application/x-cbr"),
                    DiscoveryDocument("virtual", "virtual.cbr", "application/vnd.comicbook-rar", virtual = true),
                    DiscoveryDocument("generic", "ordinary.rar", "application/vnd.rar"))) {
                    if (!onChild(row)) break
                }
            }
            override fun documentUri(tree: DiscoveryTree, documentId: String) = "content://owned/$documentId"
        }
        val result = DefaultBookDiscovery(provider, elapsedMillis = { 0L }).scan("content://owned/tree") {} as DiscoveryResult.Complete
        assertEquals(listOf("suffix", "mime", "alias"), result.candidates.map { it.id })
        assertTrue(result.candidates.all { it.formatHint == BookFormat.CBR })
    }
}

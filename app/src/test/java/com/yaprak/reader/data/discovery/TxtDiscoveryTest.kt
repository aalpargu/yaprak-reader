package com.yaprak.reader.data.discovery

import android.app.Application
import com.yaprak.reader.domain.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TxtDiscoveryTest {
    @Test fun selectedTreeFindsExplicitTxtNamesAlongsideExistingFormatsAndNeverImportsCandidates() = runBlocking {
        val reads = mutableListOf<String>()
        val provider = object : DiscoveryDocumentProvider {
            override fun resolveTree(treeUri: String) = DiscoveryTree(treeUri, "local", "root")
            override suspend fun children(tree: DiscoveryTree, parentId: String, onChild: (DiscoveryDocument) -> Boolean) {
                reads += parentId
                if (parentId == "root") {
                    onChild(DiscoveryDocument("folder", "Nested", DIRECTORY_MIME))
                    onChild(DiscoveryDocument("pdf", "pdf.pdf", "application/pdf"))
                    onChild(DiscoveryDocument("plain", "Readme", "text/plain"))
                } else {
                    onChild(DiscoveryDocument("txt", "Ders.TXT", "application/octet-stream", 120))
                    onChild(DiscoveryDocument("epub", "book.epub", "application/epub+zip", 200))
                    onChild(DiscoveryDocument("virtual", "virtual.txt", "text/plain", 10, virtual = true))
                }
            }
            override fun documentUri(tree: DiscoveryTree, documentId: String) = "content://local/" + documentId
        }
        val result = DefaultBookDiscovery(provider).scan("content://tree") {} as DiscoveryResult.Complete
        assertEquals(listOf("root", "folder"), reads)
        assertEquals(listOf(BookFormat.PDF, BookFormat.TXT, BookFormat.EPUB), result.candidates.map { it.formatHint })
        assertEquals(120L, result.candidates.single { it.formatHint == BookFormat.TXT }.sizeBytes)
        assertEquals("content://local/txt", result.candidates.single { it.formatHint == BookFormat.TXT }.documentUri)
    }
}

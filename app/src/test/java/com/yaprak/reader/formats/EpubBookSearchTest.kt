package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.formats.epub.EpubBookSearch
import com.yaprak.reader.formats.epub.EpubPublicationSource
import com.yaprak.reader.formats.epub.decodeEpubLocation
import com.yaprak.reader.formats.epub.searchSnippet
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubBookSearchTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `search finds real EPUB text with a navigable result and honest no-match result`() = runBlocking {
        val opened = EpubPublicationSource(RuntimeEnvironment.getApplication().contentResolver)
            .open(TestBooks.epub(temp.newFile("search.epub")))
        opened.use {
            val search = EpubBookSearch(opened.publication)
            assertTrue(search.available)
            val results = search.find("Güneş")
            assertFalse(results.truncated)
            assertTrue(results.hits.isNotEmpty())
            assertTrue(results.hits.any { hit -> hit.snippet?.contains("Güneş") == true })
            assertTrue(results.hits.all { hit ->
                decodeEpubLocation(hit.location, opened.publication.readingOrder) != null
            })
            assertTrue(search.find("metinde olmayan ifade").hits.isEmpty())
        }
    }

    @Test fun `snippet uses only matched text context and stays bounded`() {
        assertNull(searchSnippet(Locator.Text(before = "öncesi", after = "sonrası")))
        val snippet = searchSnippet(Locator.Text(before = "b".repeat(100),
            highlight = " aranan\nifade ", after = "s".repeat(100)))!!
        assertTrue(snippet.contains("aranan ifade"))
        assertTrue(snippet.length < 200)
        assertFalse(snippet.contains("\n"))
    }

    @Test fun `cancelled and overlapping queries leave the publication searchable`() = runBlocking {
        val opened = EpubPublicationSource(RuntimeEnvironment.getApplication().contentResolver)
            .open(TestBooks.epub(temp.newFile("overlap.epub")))
        opened.use {
            val search = EpubBookSearch(opened.publication)
            coroutineScope {
                val cancelled = async { search.find("olmayan ifade") }
                val next = async { search.find("Güneş") }
                cancelled.cancelAndJoin()
                assertTrue(next.await().hits.isNotEmpty())
            }
            assertTrue(search.find("Güneş").hits.isNotEmpty())
        }
    }
}

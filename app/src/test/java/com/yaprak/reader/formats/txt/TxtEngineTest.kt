package com.yaprak.reader.formats.txt

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.formats.epub.EpubPublicationSource
import com.yaprak.reader.formats.epub.EpubBookSearch
import com.yaprak.reader.formats.epub.EpubContents
import com.yaprak.reader.formats.epub.decodeEpubLocation
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TxtEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    @Test fun actualZipHasStoredFirstMimetypeNoExtraFieldAndLiteralEscapedCompleteBody() = runBlocking {
        val text = "A <script>'&'</script> 🌿\nline\n\nparagraph"
        val file = temp.newFile("view.epub"); TxtEpub.write(file, hash(text), StrictTxt.decode(text.toByteArray()))
        ZipFile(file).use { zip ->
            val entry = zip.entries().nextElement()
            assertEquals("mimetype", entry.name); assertEquals(java.util.zip.ZipEntry.STORED, entry.method)
            val extra = entry.extra
            assertTrue(extra == null || extra.isEmpty()); assertEquals("application/epub+zip", zip.getInputStream(entry).reader().readText())
            val body = zip.getInputStream(zip.getEntry("Text/part0001.xhtml")).reader().readText()
            assertTrue(body.contains("&lt;script&gt;&apos;&amp;&apos;&lt;/script&gt; 🌿"))
            assertFalse(body.contains("<script>")); assertTrue(body.contains("line</p><p>paragraph"))
            assertFalse(zip.entries().asSequence().any { it.name.endsWith(".txt") })
        }
    }
    @Test fun pinnedReadiumOpensEntireRealPublicationAndSearchesTheFinalSegment() = runBlocking {
        val text = "first ".repeat(11_000) + "unique needle 🌿"
        val file = temp.newFile("view.epub"); val parts = StrictTxt.decode(text.toByteArray())
        TxtEpub.write(file, hash(text), parts)
        EpubPublicationSource(context.contentResolver).open(file).use { opened ->
            val publication = opened.publication
            assertEquals(parts.map { it.href }, publication.readingOrder.map { it.url().toString() })
            val contents = EpubContents(publication.tableOfContents, publication.readingOrder)
            assertEquals(parts.map { it.title }, contents.contents.entries.map { it.title })
            assertTrue(contents.contents.entries.all { it.navigable })
            val search = EpubBookSearch(publication); assertTrue(search.available)
            val result = search.find("unique needle")
            assertEquals(1, result.hits.size); assertFalse(result.truncated)
            assertEquals(parts.last().href, result.hits.single().location.resourceHref)
            assertNotNull(decodeEpubLocation(result.hits.single().location, publication.readingOrder))
            assertTrue(result.hits.single().snippet!!.contains("unique needle"))
        }
    }
    @Test fun repeatedViewsKeepResourceBodyAndSearchLocationsCompatibleWithoutInventedChapterMetadata() = runBlocking {
        val text = "note one\n\nsecond 🌿"; val parts = StrictTxt.decode(text.toByteArray())
        val first = temp.newFile("first.epub"); val second = temp.newFile("second.epub")
        TxtEpub.write(first, hash(text), parts); TxtEpub.write(second, hash(text), parts)
        val location = EpubPublicationSource(context.contentResolver).open(first).use { EpubBookSearch(it.publication).find("second").hits.single().location }
        EpubPublicationSource(context.contentResolver).open(second).use {
            assertNotNull(decodeEpubLocation(location, it.publication.readingOrder))
            assertEquals("und", it.publication.metadata.languages.single())
        }
        ZipFile(first).use { a -> ZipFile(second).use { b ->
            assertArrayEquals(a.getInputStream(a.getEntry(parts.single().href)).readBytes(), b.getInputStream(b.getEntry(parts.single().href)).readBytes())
        } }
    }
}

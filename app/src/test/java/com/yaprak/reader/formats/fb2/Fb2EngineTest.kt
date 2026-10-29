package com.yaprak.reader.formats.fb2

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.formats.epub.EpubBookSearch
import com.yaprak.reader.formats.epub.EpubContents
import com.yaprak.reader.formats.epub.EpubPublicationSource
import com.yaprak.reader.formats.epub.decodeEpubLocation
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fb2EngineTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun original(body: String, metadata: String = "", extra: String = "") =
        "<FictionBook xmlns=\"$FB2_NAMESPACE\" xmlns:l=\"$FB2_LINK_NAMESPACE\"><description><title-info><book-title>Türkçe 🌿 &amp; kitap</book-title><author><first-name>Ayşe</first-name><last-name>Yazar</last-name></author><lang>tr</lang>$metadata</title-info></description><body>$body</body>$extra</FictionBook>".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private suspend fun view(bytes: ByteArray, name: String = "view.epub"): File = temp.newFile(name).also { Fb2Epub.write(it, hash(bytes), StrictFb2.decode(bytes)) }
    private fun ZipFile.text(path: String) = getInputStream(getEntry(path)).bufferedReader().use { it.readText() }
    private fun image(): ByteArray {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() }
        finally { bitmap.recycle() }
    }

    @Test fun completeBodiesStylesTablePoemImagesAndUnicodeInternalNotesSurviveSafeConversion() = runBlocking {
        val png = image()
        val bytes = original("<title><p>Önsöz</p></title><section id=\"ilk\"><title><p>Birinci 🌿</p></title><p>A &lt; B &amp; C <strong>kalın</strong> <emphasis>eğik</emphasis><a l:href=\"#n%C3%B6t+1\" type=\"note\">1</a></p><section><title><p>İç bölüm</p></title><poem><stanza><v>Birinci dize</v><v>İkinci dize</v></stanza></poem><table><tr><th>Başlık</th><td colspan=\"2\">Hücre</td></tr></table><image l:href=\"#kapak\" alt=\"Çiçek &amp; yaprak\"/></section></section><section><p>Son metin</p></section>",
            "<coverpage><image l:href=\"#kapak\"/></coverpage>",
            "<body name=\"notes\"><section id=\"nöt+1\"><title><p>Dipnot</p></title><p>Açıklama <a l:href=\"#ilk\">geri</a></p></section></body><binary id=\"kapak\" content-type=\"image/png\">${Base64.getEncoder().encodeToString(png)}</binary>")
        val source = temp.newFile("original.fb2").also { it.writeBytes(bytes) }
        val file = view(bytes)
        assertArrayEquals(bytes, source.readBytes())
        ZipFile(file).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name); assertEquals(java.util.zip.ZipEntry.STORED, first.method)
            assertTrue(first.extra == null || first.extra.isEmpty())
            assertEquals("application/epub+zip", zip.text("mimetype"))
            val chapter = zip.text("Text/b1-p2.xhtml")
            assertTrue(chapter.contains("A &lt; B &amp; C")); assertTrue(chapter.contains(">kalın</strong>")); assertTrue(chapter.contains(">eğik</em>"))
            assertTrue(chapter.contains("epub:type=\"noteref\"")); assertTrue(chapter.contains("href=\"b2-p1.xhtml#"))
            assertTrue(chapter.contains("colspan=\"2\"")); assertTrue(chapter.contains("İkinci dize"))
            assertTrue(chapter.contains("src=\"../Images/image1.png\"")); assertTrue(chapter.contains("alt=\"Çiçek &amp; yaprak\""))
            assertTrue(zip.text("Text/b2-p1.xhtml").contains("epub:type=\"footnote\""))
            assertTrue(zip.text("Text/b2-p1.xhtml").contains("href=\"b1-p2.xhtml#"))
            assertArrayEquals(png, zip.getInputStream(zip.getEntry("Images/image1.png")).readBytes())
            assertTrue(zip.text("package.opf").contains("properties=\"cover-image\""))
            assertFalse(zip.text("package.opf").contains("linear=\"no\""))
            val nav = zip.text("nav.xhtml")
            assertTrue(nav.contains("Birinci 🌿")); assertTrue(nav.contains("İç bölüm")); assertTrue(nav.contains("</a><ol>"))
            zip.entries().asSequence().filter { it.name.endsWith(".xhtml") || it.name.endsWith(".opf") }.forEach {
                javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
                    .parse(zip.getInputStream(it)).also { document -> assertNotNull(document.documentElement) }
            }
        }
    }
    @Test fun actualPinnedReadiumOpensMainChaptersAndNestedTocAndSearchesLastChapter() = runBlocking {
        val bytes = original("<section id=\"first\"><title><p>İlk</p></title><p>Birinci metin</p><section><title><p>Alt başlık</p></title><p>İç metin</p></section></section><section><title><p>Son</p></title><p>unique needle 🌿</p></section>")
        val file = view(bytes)
        EpubPublicationSource(context.contentResolver).open(file).use { opened ->
            val publication = opened.publication
            assertEquals(listOf("Text/b1-p1.xhtml", "Text/b1-p2.xhtml"), publication.readingOrder.map { it.url().toString() })
            assertEquals(listOf("tr"), publication.metadata.languages)
            val contents = EpubContents(publication.tableOfContents, publication.readingOrder).contents.entries
            assertEquals(listOf("İlk", "Alt başlık", "Son"), contents.map { it.title })
            assertTrue(contents.all { it.navigable })
            val hits = EpubBookSearch(publication).find("unique needle")
            assertFalse(hits.truncated); assertEquals(1, hits.hits.size)
            assertEquals("Text/b1-p2.xhtml", hits.hits.single().location.resourceHref)
            assertNotNull(decodeEpubLocation(hits.hits.single().location, publication.readingOrder))
        }
    }
    @Test fun identicalOriginalBytesKeepWholeArtifactAndNativeSearchLocationsStable() = runBlocking {
        val bytes = original("<section><p>First</p></section><section><p>unique 🌿</p></section>")
        val first = view(bytes, "one.epub"); val second = view(bytes, "two.epub")
        assertArrayEquals(first.readBytes(), second.readBytes())
        val location = EpubPublicationSource(context.contentResolver).open(first).use { EpubBookSearch(it.publication).find("unique").hits.single().location }
        EpubPublicationSource(context.contentResolver).open(second).use { assertNotNull(decodeEpubLocation(location, it.publication.readingOrder)) }
    }
    @Test fun notesBodyIsSearchableNavigableAndResumeCompatibleInActualReadium() = runBlocking {
        val file = view(original("<section><p>Ana metin <a l:href=\"#note\" type=\"note\">1</a></p></section>",
            extra = "<body name=\"notes\"><section id=\"note\"><title><p>Dipnotlar</p></title><p>footnote needle</p></section></body>"))
        EpubPublicationSource(context.contentResolver).open(file).use { opened ->
            val publication = opened.publication
            assertEquals(listOf("Text/b1-p1.xhtml", "Text/b2-p1.xhtml"), publication.readingOrder.map { it.url().toString() })
            assertTrue(EpubContents(publication.tableOfContents, publication.readingOrder).contents.entries.all { it.navigable })
            val hit = EpubBookSearch(publication).find("footnote needle").hits.single()
            assertEquals("Text/b2-p1.xhtml", hit.location.resourceHref)
            assertNotNull(decodeEpubLocation(hit.location, publication.readingOrder))
        }
    }
    @Test fun actualOriginalCoverDecodesAndAbsentCoverRemainsAbsent() = runBlocking {
        val png = image()
        val bytes = original("<section><p>text</p></section>", "<coverpage><image l:href=\"#cover\"/></coverpage>",
            "<binary id=\"cover\" content-type=\"image/png\">${Base64.getEncoder().encodeToString(png)}</binary>")
        val source = temp.newFile("cover.fb2").apply { writeBytes(bytes) }
        val bitmap = Fb2CoverReader().read(source)
        assertNotNull(bitmap); bitmap!!.recycle(); assertArrayEquals(bytes, source.readBytes())
        source.writeBytes(original("<section><p>text</p></section>"))
        assertNull(Fb2CoverReader().read(source))
    }
    @Test fun equalSectionsHaveDistinctIdentityAndAllActualTargetsResolve() = runBlocking {
        val file = view(original("<section><title><p>Same</p></title><p>Same text</p></section>".repeat(2)))
        ZipFile(file).use { zip ->
            val nav = zip.text("nav.xhtml")
            assertTrue(nav.contains("Text/b1-p1.xhtml#n1")); assertTrue(nav.contains("Text/b1-p2.xhtml#n2"))
            assertTrue(zip.text("Text/b1-p1.xhtml").contains("id=\"n1\"")); assertTrue(zip.text("Text/b1-p2.xhtml").contains("id=\"n2\""))
        }
    }
    @Test fun unsupportedMetadataTargetsMalformedExternalLinksAndOversizedEscapingRefuseWholeView() = runBlocking {
        val cases = listOf(
            original("<section><p><a l:href=\"#meta\">bad target</a></p></section>", "<annotation id=\"meta\"><p>metadata</p></annotation>"),
            original("<section><p><a l:href=\"https://\">bad url</a></p></section>"),
            original("<section><p><![CDATA[${"<".repeat(2_200_000)}]]></p></section>")
        )
        cases.forEachIndexed { index, bytes ->
            val document = StrictFb2.decode(bytes)
            val failure = runCatching { Fb2Epub.write(temp.newFile("bad$index.epub"), hash(bytes), document) }.exceptionOrNull()
            assertTrue("Case $index must refuse the complete view", failure is Fb2Exception)
            if (index == 2) assertEquals(Fb2Failure.LIMIT, (failure as Fb2Exception).reason)
        }
    }
    @Test fun cancellationPropagatesAndCacheRecoveryOnlyRemovesOwnedAbandonedViews() = runBlocking {
        val document = StrictFb2.decode(original("<section><p>text</p></section>"))
        val failure = runCatching { withContext(Job().apply { cancel() }) { Fb2Epub.write(temp.newFile("cancel.epub"), "a".repeat(64), document) } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        val directory = temp.newFolder("viewing"); val cache = Fb2DerivedFiles(directory)
        val first = cache.create(); val second = cache.create(); val other = File(directory, "keep.txt").apply { writeText("other") }
        cache.release(first); assertFalse(first.exists()); assertTrue(second.exists())
        assertTrue(runCatching { cache.release(File(temp.root, "outside.epub")) }.isFailure)
        cache.recoverAbandoned(); assertFalse(second.exists()); assertTrue(other.exists())
    }
}

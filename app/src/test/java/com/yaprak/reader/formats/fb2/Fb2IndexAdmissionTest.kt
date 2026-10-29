package com.yaprak.reader.formats.fb2

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.search.LocalBookTextExtractor
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Fb2IndexAdmissionTest {
    @get:Rule val temp = TemporaryFolder()
    private val hash = "a".repeat(64)
    private fun target(href: String) = LibraryTextTarget("fb2", hash, href, "b".repeat(64), sourceFormat = BookFormat.FB2)
    @Test fun typedOriginalFb2PartsAreAdmittedButArbitraryPathsAndPdfPagesAreRefused() {
        assertTrue(target("Text/b1-p1.xhtml").isValid)
        assertTrue(target("Text/b2-p2048.xhtml").isValid)
        for (href in listOf("Text/b0-p1.xhtml", "Text/b01-p1.xhtml", "Text/b1-p01.xhtml", "Text/b1-p2049.xhtml",
            "Text/b1-p1.xhtml#n1", "../Text/b1-p1.xhtml", "Text/b1-p1.xhtml?query", "Text/b1-p1.XHTML",
            "https://remote/book", "Text/b1-p1.xhtml/", "Text/b999999999999999-p1.xhtml")) assertFalse(href, target(href).isValid)
        assertFalse(target("Text/b1-p1.xhtml").copy(pdfPageIndex = 0).isValid)
        assertTrue(supportsBookSourceRetrieval(BookFormat.FB2))
    }
    @Test fun actualLocalRoutingExtractsOriginalFb2TextWithoutGrantingEpubRangeProvenance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = fixture().toByteArray()
        val directory = File(context.filesDir, "books").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.fb2").apply { writeBytes(bytes) }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val book = Book("fb2", "Book", null, BookFormat.FB2, file.name, file.name, file.length(), digest, 1)
        try {
            val adapter = LocalBookTextExtractor(context)
            assertTrue(adapter.supports(book))
            val result = adapter.extract(book)
            assertEquals(BookFormat.FB2, result.sourceFormat)
            assertEquals(listOf("İlk\ndependency injection", "Son\nlast needle"), result.chunks.map { it.text })
            assertTrue(result.chunks.all { it.sourceFormat == BookFormat.FB2 && it.pdfPageIndex == null })
            assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }
    @Test fun generatedArtifactCompatibilityFixture() = runBlocking {
        val bytes = fixture().toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val artifact = temp.newFile("view.epub")
        Fb2Epub.write(artifact, digest, StrictFb2.decode(bytes))
        val actual = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("fa048cd75dcd10214a718e8e02cce058761573977992334129e961334970852b", actual)
    }
    private fun fixture() = "<FictionBook xmlns=\"$FB2_NAMESPACE\"><description><title-info><book-title>Sabit 🌿</book-title><lang>tr</lang></title-info></description><body><section><title><p>İlk</p></title><p>depen<strong>dency</strong> injection</p></section><section><title><p>Son</p></title><p>last needle</p></section></body></FictionBook>"
}

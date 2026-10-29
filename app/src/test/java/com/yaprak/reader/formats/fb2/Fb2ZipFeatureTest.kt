package com.yaprak.reader.formats.fb2

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.MemoryLibrary
import com.yaprak.reader.data.discovery.*
import com.yaprak.reader.data.exporting.DefaultBookExporter
import com.yaprak.reader.data.exporting.ExportDocumentProvider
import com.yaprak.reader.data.importing.DefaultBookImporter
import com.yaprak.reader.data.importing.DocumentProvider
import com.yaprak.reader.data.importing.SourceDocument
import com.yaprak.reader.data.storage.LocalBookFiles
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.library.suggestedExportFileName
import com.yaprak.reader.feature.library.LibraryViewModel
import com.yaprak.reader.feature.library.StudyExportController
import com.yaprak.reader.formats.BookMetadataReader
import com.yaprak.reader.formats.epub.EpubBookSearch
import com.yaprak.reader.formats.epub.EpubPublicationSource
import com.yaprak.reader.formats.epub.decodeEpubLocation
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Fb2ZipFeatureTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun storedAndDeflatedOriginalArchivesProduceCompleteMetadataTextAndViewingEpub() = runBlocking {
        for (stored in listOf(false, true)) {
            val bytes = fb2Zip(stored = stored)
            val file = temp.newFile().apply { writeBytes(bytes) }
            val document = StrictFb2.read(file)
            assertEquals("Özgün 🌿 Kitap", document.title)
            assertEquals("Ada Yazar", document.authors)
            val parts = fb2OriginalText(document)
            assertEquals(listOf("Text/b1-p1.xhtml", "Text/b2-p1.xhtml"), parts.map { it.href })
            assertTrue(parts[0].text.contains("unique main tail"))
            assertTrue(parts[1].text.contains("unique note tail"))
            val view = temp.newFile()
            Fb2Epub.write(view, fb2ZipHash(bytes), document)
            ZipFile(view).use { zip ->
                assertTrue(zip.getInputStream(zip.getEntry(parts[0].href)).bufferedReader().readText().contains("unique main tail"))
                assertTrue(zip.getInputStream(zip.getEntry(parts[1].href)).bufferedReader().readText().contains("unique note tail"))
            }
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun originalArchiveIdentityAndBothBodiesReachTheActualTextExtractor() = runBlocking {
        val bytes = fb2Zip()
        val id = UUID.randomUUID().toString()
        val directory = temp.newFolder()
        val file = File(directory, "$id.fb2").apply { writeBytes(bytes) }
        val book = Book(id, "Özgün 🌿 Kitap", null, BookFormat.FB2, "book.fb2.zip", file.name,
            bytes.size.toLong(), fb2ZipHash(bytes), 1)
        val result = Fb2BookTextExtractor(VerifiedBookFile(directory), Dispatchers.Unconfined).extract(book)
        assertEquals(book.sha256, result.contentSha256)
        assertEquals(BookFormat.FB2, result.sourceFormat)
        assertEquals(setOf("Text/b1-p1.xhtml", "Text/b2-p1.xhtml"), result.chunks.map { it.resourceHref }.toSet())
        val complete = result.chunks.joinToString("\n") { it.text }
        assertTrue(complete.contains("unique main tail")); assertTrue(complete.contains("unique note tail"))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun realImporterPreservesArchiveHashDuplicateDetectionAndExactOriginalExport() = runBlocking {
        val bytes = fb2Zip()
        val directory = temp.newFolder()
        val library = MemoryLibrary()
        val importer = DefaultBookImporter(DocumentProvider { SourceDocument("Kitap.FB2.ZIP") { bytes.inputStream() } },
            LocalBookFiles(directory), BookMetadataReader { file, format ->
                assertEquals(BookFormat.FB2, format); Fb2MetadataReader().read(file)
            }, library, Dispatchers.Unconfined)
        assertEquals(ImportResult.Added, importer.import("content://test/source"))
        assertEquals(ImportResult.Duplicate, importer.import("content://test/source"))
        val book = library.books.value.single()
        assertEquals(BookFormat.FB2, book.format); assertEquals("Kitap.FB2.ZIP", book.originalName)
        assertEquals(fb2ZipHash(bytes), book.sha256); assertEquals(bytes.size.toLong(), book.byteSize)
        assertEquals("Özgün 🌿 Kitap", book.title)
        assertArrayEquals(bytes, File(directory, book.storageName).readBytes())
        assertEquals(listOf(book.storageName), directory.listFiles()!!.map { it.name })
        val output = ByteArrayOutputStream()
        val documents = object : ExportDocumentProvider {
            override fun accepts(uri: String) = uri == "content://test/export"
            override fun open(uri: String) = output
            override fun delete(uri: String) = true
        }
        assertEquals(ExportResult.Exported,
            DefaultBookExporter(library, VerifiedBookFile(directory), documents, Dispatchers.Unconfined)
                .export(book.id, "content://test/export"))
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun selectedFolderFindsExplicitCompressedFb2ButDoesNotTreatGenericZipAsABook() = runBlocking {
        val provider = object : DiscoveryDocumentProvider {
            override fun resolveTree(treeUri: String) = DiscoveryTree(treeUri, "test", "root")
            override suspend fun children(tree: DiscoveryTree, parentId: String, onChild: (DiscoveryDocument) -> Boolean) {
                onChild(DiscoveryDocument("fb2zip", "Kitap.FB2.ZIP", "application/zip", 100))
                onChild(DiscoveryDocument("zip", "archive.zip", "application/zip", 100))
                onChild(DiscoveryDocument("plain", "book.fb2", "application/xml", 100))
            }
            override fun documentUri(tree: DiscoveryTree, documentId: String) = "content://test/$documentId"
        }
        val result = DefaultBookDiscovery(provider, Dispatchers.Unconfined).scan("content://test/tree") {} as DiscoveryResult.Complete
        assertEquals(listOf("fb2zip", "plain"), result.candidates.map { it.id })
        assertTrue(result.candidates.all { it.formatHint == BookFormat.FB2 })
    }

    @Test fun originalExportSuggestionKeepsTheCompressedExtensionExactlyOnce() {
        val book = Book("id", "Kitap", null, BookFormat.FB2, "Kitap.FB2.ZIP", "id.fb2", 1, "a".repeat(64), 1)
        assertEquals("Kitap.fb2.zip", suggestedExportFileName(book))
        assertEquals("Kitap.fb2", suggestedExportFileName(book.copy(originalName = "Kitap.FB2")))
    }

    @Test fun missingEmbeddedTitleUsesTheWholeContainerStemWithoutAnFb2Suffix() = runBlocking {
        val xml = fb2ZipXml().toString(Charsets.UTF_8).replace("<book-title>Özgün 🌿 Kitap</book-title>", "").toByteArray()
        val bytes = fb2Zip(xml)
        val library = MemoryLibrary()
        val importer = DefaultBookImporter(DocumentProvider { SourceDocument("Başlıksız.FB2.ZIP") { bytes.inputStream() } },
            LocalBookFiles(temp.newFolder()), BookMetadataReader { file, _ -> Fb2MetadataReader().read(file) },
            library, Dispatchers.Unconfined)
        assertEquals(ImportResult.Added, importer.import("content://test/title"))
        assertEquals("Başlıksız", library.books.value.single().title)
        assertEquals("Başlıksız.FB2.ZIP", library.books.value.single().originalName)
        assertEquals(fb2ZipHash(bytes), library.books.value.single().sha256)
    }

    @Test fun plainOriginalXmlStillUsesTheSamePartsAndSourceBytes() = runBlocking {
        val bytes = fb2ZipXml()
        val file = temp.newFile().apply { writeBytes(bytes) }
        val document = StrictFb2.read(file)
        assertEquals("Özgün 🌿 Kitap", document.title)
        assertEquals(2, fb2OriginalText(document).size)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun actualReadiumReadsBothBodiesAndResolvesTheOriginalDerivedSearchLocation() = runBlocking {
        val bytes = fb2Zip()
        val source = temp.newFile().apply { writeBytes(bytes) }
        val view = temp.newFile("zip-view.epub")
        Fb2Epub.write(view, fb2ZipHash(bytes), StrictFb2.read(source))
        val context = ApplicationProvider.getApplicationContext<Application>()
        EpubPublicationSource(context.contentResolver).open(view).use { opened ->
            assertEquals(listOf("Text/b1-p1.xhtml", "Text/b2-p1.xhtml"), opened.publication.readingOrder.map { it.url().toString() })
            val hit = EpubBookSearch(opened.publication).find("unique note tail").hits.single()
            assertEquals("Text/b2-p1.xhtml", hit.location.resourceHref)
            assertNotNull(decodeEpubLocation(hit.location, opened.publication.readingOrder))
        }
        assertArrayEquals(bytes, source.readBytes())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun zipOriginalPickerRequestAndStudyExportNamesStaySeparate() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        val book = Book("id", "Kitap", null, BookFormat.FB2, "Kitap.FB2.ZIP", "id.fb2", 1, "a".repeat(64), 1)
        val importer = object : BookImporter { override suspend fun import(documentUri: String): ImportResult = error("No import expected") }
        val exporter = object : BookExporter { override suspend fun export(bookId: String, destinationUri: String): ExportResult = error("No export expected") }
        val study = object : BookStudyExporter {
            override suspend fun export(bookId: String, contentSha256: String, destinationUri: String): StudyExportResult = error("No export expected")
        }
        try {
            val model = LibraryViewModel(MemoryLibrary(), importer, SavedStateHandle(), dispatcher, exporter)
                .also { models.put("zip", it) }
            val request = requireNotNull(model.prepareExport(book))
            assertTrue(request.fb2Zip); assertEquals(BookFormat.FB2, request.format)
            assertEquals("Kitap.fb2.zip", request.suggestedFileName)
            for (format in StudyExportFormat.entries) {
                val controller = StudyExportController(study, SavedStateHandle(), backgroundScope)
                val name = requireNotNull(controller.prepare(book, format)).suggestedFileName
                assertTrue(name.endsWith(".${format.extension}")); assertFalse(name.contains("fb2.zip")); assertFalse(name.contains(".fb2-"))
            }
        } finally { models.clear(); Dispatchers.resetMain() }
    }
}

internal fun fb2ZipXml() = ("<FictionBook xmlns='$FB2_NAMESPACE' xmlns:l='$FB2_LINK_NAMESPACE'>" +
    "<description><title-info><book-title>Özgün 🌿 Kitap</book-title><author><first-name>Ada</first-name>" +
    "<last-name>Yazar</last-name></author><lang>tr</lang></title-info></description>" +
    "<body><section id='main'><title><p>Bölüm</p></title><p>unique main tail</p></section></body>" +
    "<body name='notes'><section id='note'><title><p>Dipnot</p></title><p>unique note tail</p></section></body></FictionBook>").toByteArray()

internal fun fb2Zip(xml: ByteArray = fb2ZipXml(), name: String = "Türkçe/book.FB2", stored: Boolean = false): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        val entry = ZipEntry(name)
        if (stored) { entry.method = ZipEntry.STORED; entry.size = xml.size.toLong(); entry.crc = CRC32().apply { update(xml) }.value }
        zip.putNextEntry(entry); zip.write(xml); zip.closeEntry()
    }
    return output.toByteArray()
}

internal fun fb2ZipHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

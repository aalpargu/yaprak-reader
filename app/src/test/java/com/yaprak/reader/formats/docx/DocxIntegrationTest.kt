package com.yaprak.reader.formats.docx

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.MemoryLibrary
import com.yaprak.reader.data.discovery.*
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.data.importing.*
import com.yaprak.reader.data.storage.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.feature.library.*
import com.yaprak.reader.formats.BookMetadataReader
import com.yaprak.reader.formats.epub.*
import com.yaprak.reader.reader.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocxIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun original() = requireNotNull(javaClass.getResourceAsStream("/docx/python-docx-numbered.docx")).use { it.readBytes() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun importer(bytes: ByteArray, name: String, directory: File, library: MemoryLibrary) =
        DefaultBookImporter(DocumentProvider { SourceDocument(name) { bytes.inputStream() } }, LocalBookFiles(directory),
            BookMetadataReader { file, format -> assertEquals(BookFormat.DOCX, format); DocxMetadataReader().read(file) },
            library, Dispatchers.Unconfined)

    @Test fun realImportPreservesOriginalIdentityMetadataDuplicateDetectionAndExactOriginalExport() = runBlocking {
        val bytes = original(); val library = MemoryLibrary(); val directory = temp.newFolder()
        val importer = importer(bytes, "Kaynak.DOCX", directory, library)
        assertEquals(ImportResult.Added, importer.import("content://owned/docx"))
        assertEquals(ImportResult.Duplicate, importer.import("content://owned/duplicate"))
        val book = library.books.value.single()
        assertEquals(BookFormat.DOCX, book.format); assertTrue(book.format.reflowable)
        assertEquals("Bağımsız üretici 🌿", book.title); assertEquals("Yaprak Test", book.authors)
        assertEquals("Kaynak.DOCX", book.originalName); assertEquals("docx", book.originalExtension)
        assertEquals(hash(bytes), book.sha256); assertEquals(bytes.size.toLong(), book.byteSize); assertNull(book.pageCount)
        assertArrayEquals(bytes, File(directory, book.storageName).readBytes())
        assertEquals(listOf(book.storageName), directory.listFiles()!!.map { it.name })
        val output = ByteArrayOutputStream()
        val provider = object : ExportDocumentProvider {
            override fun accepts(uri: String) = uri == "content://owned/output"
            override fun open(uri: String) = output
            override fun delete(uri: String) = true
        }
        assertEquals(ExportResult.Exported, DefaultBookExporter(library, VerifiedBookFile(directory), provider, Dispatchers.Unconfined)
            .export(book.id, "content://owned/output"))
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(suggestedExportFileName(book).endsWith(".docx"))
    }

    @Test fun actualReaderOpenRestoresARealLocatorAndReleasesOnlyItsPrivateView() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(context.filesDir, "books").apply { mkdirs() }; val library = MemoryLibrary()
        val bytes = original()
        assertEquals(ImportResult.Added, importer(bytes, "source.docx", directory, library).import("content://owned/source"))
        val book = library.books.value.single()
        val generated = temp.newFile("resume.epub")
        DocxEpub.write(generated, book.sha256, StrictDocx.decode(bytes))
        val location = EpubPublicationSource(context.contentResolver).open(generated).use {
            EpubBookSearch(it.publication).find("Independent producer unique tail").hits.single().location
        }
        val reading = object : ReadingStateRepository {
            override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation {
                assertEquals(book.id, bookId); assertEquals(book.sha256, contentSha256); return location
            }
            override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) = error("No synthetic read record")
        }
        val cache = File(context.cacheDir, "docx-viewing").apply { mkdirs() }
        val marker = File(cache, "keep-"+UUID.randomUUID()).apply { writeText("owned marker") }
        val before = cache.listFiles()!!.toSet()
        val mutex = Mutex(); var validations = 0
        val loader = DocxReaderLoader(context, library, reading, mutex) { assertTrue(mutex.isLocked); validations++ }
        var session: ReaderSession? = null
        try {
            session = loader.open(book.id)
            assertEquals(book, session.book); assertEquals(location, session.resume); assertEquals(2, validations)
            val artifact = (cache.listFiles()!!.toSet() - before).single()
            assertTrue(artifact.name.startsWith("docx-view-")); assertTrue(artifact.length() > 0)
            session.close(); session = null
            assertFalse(artifact.exists()); assertTrue(marker.exists())
            assertArrayEquals(bytes, File(directory, book.storageName).readBytes())
        } finally { session?.close(); marker.delete(); File(directory, book.storageName).delete() }
    }

    @Test fun changedOriginalAndChangedBookDuringResumeCannotOpenOrLeakAView() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(context.filesDir, "books").apply { mkdirs() }
        for (mutation in 0..1) {
            val bytes = original(); val library = MemoryLibrary()
            assertEquals(ImportResult.Added, importer(bytes, "book.docx", directory, library).import("content://owned/source"))
            val book = library.books.value.single(); val file = File(directory, book.storageName)
            val reading = object : ReadingStateRepository {
                override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? {
                    if (mutation == 0) file.appendBytes(byteArrayOf(0))
                    else library.books.value = listOf(book.copy(byteSize = book.byteSize + 1))
                    return null
                }
                override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) = Unit
            }
            val cache = File(context.cacheDir, "docx-viewing").apply { mkdirs() }; val before = cache.listFiles()!!.toSet()
            try {
                val failure = runCatching { DocxReaderLoader(context, library, reading).open(book.id) }.exceptionOrNull()
                assertTrue(failure is ReaderOpenException)
                assertEquals(ReaderFailure.CHANGED, (failure as ReaderOpenException).reason)
                assertEquals(before, cache.listFiles()!!.toSet())
            } finally { file.delete() }
        }
    }

    @Test fun unsupportedContentInvalidPackagesAndMislabeledPdfFailBeforeAnyLibraryCommit() = runBlocking {
        val unsupported = DocxFixtures.zip(DocxFixtures.parts(body = DocxFixtures.word("<w:p><w:r><w:t>Before</w:t><w:drawing/></w:r></w:p>")))
        for ((bytes, expected) in listOf(unsupported to ImportFailure.UNSUPPORTED, byteArrayOf(0x50,0x4b,3,4,0) to ImportFailure.INVALID,
            "%PDF-owned".toByteArray() to ImportFailure.INVALID)) {
            val directory = temp.newFolder(); val library = MemoryLibrary()
            assertEquals(ImportResult.Failed(expected), importer(bytes, "book.docx", directory, library).import("content://owned/bad"))
            assertTrue(library.books.value.isEmpty()); assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun sourceBudgetCancellationAndFailedRepositoryCommitCleanOwnedTemporaryFiles() = runBlocking {
        val directory = temp.newFolder(); val library = MemoryLibrary()
        val large = ByteArray(MAX_DOCX_PACKAGE_BYTES + 1)
        assertEquals(ImportResult.Failed(ImportFailure.TOO_LARGE), importer(large, "large.docx", directory, library).import("content://owned/large"))
        assertTrue(directory.listFiles()!!.isEmpty())
        val failing = MemoryLibrary { error("Controlled DB write failure") }
        assertEquals(ImportResult.Failed(ImportFailure.UNEXPECTED), importer(original(), "book.docx", directory, failing).import("content://owned/failure"))
        assertTrue(directory.listFiles()!!.isEmpty()); assertTrue(failing.books.value.isEmpty())
        var cancelled = false
        val child = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            try { importer(original(), "book.docx", directory, library).import("content://owned/cancel") }
            catch (_: CancellationException) { cancelled = true }
        }
        child.join(); assertTrue(cancelled); assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun selectedFolderDiscoveryIncludesDocxButNotGenericZipAndFormatFilterKeepsOnlyDocx() = runBlocking {
        val provider = object : DiscoveryDocumentProvider {
            override fun resolveTree(treeUri: String) = DiscoveryTree(treeUri, "owned", "root")
            override suspend fun children(tree: DiscoveryTree, parentId: String, onChild: (DiscoveryDocument) -> Boolean) {
                onChild(DiscoveryDocument("docx", "Book.DOCX", DOCX_MIME_TYPE, 100))
                onChild(DiscoveryDocument("zip", "archive.zip", "application/zip", 100))
                onChild(DiscoveryDocument("epub", "book.epub", "application/epub+zip", 100))
            }
            override fun documentUri(tree: DiscoveryTree, documentId: String) = "content://owned/$documentId"
        }
        val result = DefaultBookDiscovery(provider, Dispatchers.Unconfined).scan("content://owned/folder") {} as DiscoveryResult.Complete
        assertEquals(listOf("docx","epub"), result.candidates.map { it.id }); assertEquals(BookFormat.DOCX, result.candidates.first().formatHint)
        val book = Book("id", "Book", null, BookFormat.DOCX, "book.docx", "id.docx", 1, "a".repeat(64), 1)
        assertEquals(listOf(book), filterBooks(listOf(book, book.copy(id="epub",format=BookFormat.EPUB)), LibraryFilter.DOCX))
    }

    @Test fun absentEmbeddedTitleUsesTheOriginalStemAndValidationArtifactsAreRemoved() = runBlocking {
        val bytes=DocxFixtures.zip(DocxFixtures.parts())
        val directory=temp.newFolder(); val library=MemoryLibrary()
        assertEquals(ImportResult.Added,importer(bytes,"Başlıksız.DOCX",directory,library).import("content://owned/no-title"))
        val book=library.books.value.single()
        assertEquals("Başlıksız",book.title); assertNull(book.authors)
        assertEquals(listOf(book.storageName),directory.listFiles()!!.map { it.name })
        assertArrayEquals(bytes,File(directory,book.storageName).readBytes())
    }

    @Test fun formatRouterUsesTheDocxLoaderAndMissingOptionalLoaderFailsExplicitly() = runBlocking {
        val library=MemoryLibrary()
        val book=Book("id","Book",null,BookFormat.DOCX,"book.docx","id.docx",1,"a".repeat(64),1)
        library.add(book); var calls=0
        val native=object : ReaderSession {
            override val book=book
            override val resume: ReadingLocation?=null
            override fun createSurface(initial: ReadingLocation?,dark: Boolean,onTap: ()->Unit,onError: ()->Unit): ReaderSurface=error("No surface")
            override fun close()=Unit
        }
        val other=ReaderLoader { error("Wrong format reader") }
        val route=FormatReaderLoader(library,other,other,docx=ReaderLoader { calls++; native })
        assertSame(native,route.open(book.id)); assertEquals(1,calls)
        assertEquals(ReaderFailure.UNSUPPORTED,(runCatching { FormatReaderLoader(library,other,other).open(book.id) }.exceptionOrNull() as ReaderOpenException).reason)
    }
    @Test fun privateCacheStartupRecoveryNeverDeletesUnrelatedFilesAndRejectsForeignRelease() {
        val directory = temp.newFolder(); val files = DocxDerivedFiles(directory)
        val interrupted = files.create(); val unrelated = File(directory, "keep.txt").apply { writeText("keep") }
        files.recoverAbandoned(); assertFalse(interrupted.exists()); assertTrue(unrelated.exists())
        val foreign = temp.newFile(); assertTrue(runCatching { files.release(foreign) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(foreign.exists())
    }
}

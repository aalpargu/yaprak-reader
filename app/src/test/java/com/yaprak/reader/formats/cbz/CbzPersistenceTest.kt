package com.yaprak.reader.formats.cbz

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.importing.*
import com.yaprak.reader.data.storage.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.formats.BookMetadataReader
import com.yaprak.reader.fixtures.CoverTestImages
import com.yaprak.reader.reader.*
import java.io.*
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
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
class CbzPersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val originals get() = File(context.filesDir, "books").apply { mkdirs() }
    private fun source(badLast: Boolean = false): File = File(temp.newFolder(), "Çizgi.cbz").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in listOf("10.png" to (if (badLast) byteArrayOf(1) else CoverTestImages.png(12, 8)), "2.png" to CoverTestImages.png())) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }
    private fun imports(file: File, library: LibraryRepository) = DefaultBookImporter(
        DocumentProvider { SourceDocument(file.name) { file.inputStream() } }, LocalBookFiles(originals),
        BookMetadataReader { staged, format -> assertEquals(BookFormat.CBZ, format); CbzMetadataReader().read(staged) }, library)
    @Test fun actualImportDuplicateEditsOriginalExportNotesProgressBookmarksAndReopenArePreserved() = runBlocking {
        val file = source(); val bytes = file.readBytes(); val dbName = "cbz-reopen.db"
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, dbName).build()
        var id: String? = null; var hash: String? = null
        try {
            val library = RoomLibraryRepository(db.books()); val importer = imports(file, library)
            assertEquals(ImportResult.Added, importer.import("content://book"))
            val book = library.observeBooks().first().single(); id = book.id; hash = book.sha256
            assertEquals(BookFormat.CBZ, book.format); assertEquals("Çizgi", book.title); assertNull(book.authors); assertEquals(2, book.pageCount)
            assertArrayEquals(bytes, File(originals, book.storageName).readBytes()); assertEquals(cbzHash(bytes), book.sha256)
            library.updateMetadata(book.id, book.sha256, "Düzenlendi", "Yazar")
            library.updateOrganization(book.id, book.sha256, BookOrganization(favorite = true, tags = listOf("Çizgi")))
            assertEquals(ImportResult.Duplicate, importer.import("content://same")); assertEquals("Düzenlendi", library.findById(book.id)!!.title)
            val archive = withContext(Dispatchers.IO) { CbzArchive.read(File(originals, book.storageName)) }
            val location = CbzPosition.encode(1, archive)
            RoomReadingStateRepository(db).record(book.id, book.sha256, ReadingSnapshot(location))
            RoomBookmarkRepository(db).add(book.id, book.sha256, BookmarkTarget("Sayfa 2", location))
            RoomBookNoteRepository(db).add(book.id, book.sha256, "Çizgi notu")
            val output = ByteArrayOutputStream()
            val docs = object : ExportDocumentProvider {
                override fun accepts(uri: String) = true
                override fun open(uri: String): OutputStream = output
                override fun delete(uri: String) = true
            }
            assertEquals(ExportResult.Exported, DefaultBookExporter(library, VerifiedBookFile(originals), docs).export(book.id, "content://export"))
            assertArrayEquals(bytes, output.toByteArray())
            val study = RoomBookStudySource(db).snapshot(book.id, book.sha256, StudyExportFormat.MARKDOWN)
            assertTrue(study.utf8.toString(Charsets.UTF_8).contains("Çizgi notu"))
            val records = RoomBookStudySource(db).snapshotRecords(book.id, book.sha256)
            assertEquals(BookFormat.CBZ, records.book.format); assertEquals(2, records.book.pageCount); assertEquals(1, records.notes.size)
            assertEquals(BookFormat.CBZ, RoomLibraryNotesRepository(db).observe("Çizgi").first().entries.single().bookFormat)
            assertTrue(db.reading().observed(book.id, book.sha256).isEmpty())
        } finally { db.close() }
        val reopened = Room.databaseBuilder(context, LibraryDatabase::class.java, dbName).build()
        try {
            val book = RoomLibraryRepository(reopened.books()).findById(id!!)!!
            assertEquals(hash, book.sha256); assertEquals("Düzenlendi", book.title); assertTrue(book.organization.favorite)
            assertArrayEquals(bytes, File(originals, book.storageName).readBytes())
            assertEquals("cbz/10.png", RoomReadingStateRepository(reopened).resume(book.id, book.sha256)!!.resourceHref)
            assertEquals(1, RoomBookmarkRepository(reopened).observe(book.id, book.sha256).first().size)
        } finally { reopened.close(); context.deleteDatabase(dbName); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun malformedLastPageAndActualRoomInsertFailureLeaveNoBookOrCommittedOriginalThenRetry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val library = RoomLibraryRepository(db.books())
            assertEquals(ImportResult.Failed(ImportFailure.INVALID), imports(source(true), library).import("content://invalid"))
            assertTrue(library.observeBooks().first().isEmpty()); assertTrue(originals.listFiles().isNullOrEmpty())
            val valid = source()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_cbz BEFORE INSERT ON books BEGIN SELECT RAISE(ABORT, 'controlled failure'); END")
            assertTrue(imports(valid, library).import("content://valid") is ImportResult.Failed)
            assertTrue(library.observeBooks().first().isEmpty()); assertTrue(originals.listFiles().isNullOrEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_cbz")
            assertEquals(ImportResult.Added, imports(valid, library).import("content://valid"))
            assertEquals(1, originals.listFiles()!!.size)
        } finally { db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun actualLoaderRoutesNativeCapabilitiesAndRejectsSourceOrRecoveryChangeBeforeHandoff() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val library = RoomLibraryRepository(db.books()); imports(source(), library).import("content://book")
            val book = library.observeBooks().first().single(); val reading = RoomReadingStateRepository(db)
            val loader = CbzReaderLoader(context, library, reading)
            val routed = FormatReaderLoader(library, ReaderLoader { error("EPUB") }, ReaderLoader { error("PDF") }, cbz = loader)
            routed.open(book.id).use { session ->
                val surface = session.createSurface(null, false, {}, {})
                assertNotNull(surface.pageNavigator); assertNotNull(surface.bookmarkNavigator); assertEquals(2, surface.contentsNavigator!!.contents.entries.size)
            assertFalse(surface.readingVisible.first()); assertNull(surface.bookmarkNavigator!!.capture())
                assertNull(surface.appearance); assertNull(surface.textSelection); assertNull(surface.epubHighlights); assertNull(surface.passageNavigator)
                assertNull(surface.indexedChapterNavigator); assertNull(surface.indexedPdfPageNavigator); assertNull(surface.pdfOcr); assertNull(surface.pdfCrop)
                assertNull(surface.bookSearch); assertFalse(surface.forward())
            }
            val extractor = com.yaprak.reader.data.search.LocalBookTextExtractor(context)
            assertFalse(extractor.supports(book))
            assertEquals(TextExtractionFailure.UNSUPPORTED, (runCatching { extractor.extract(book) }.exceptionOrNull() as TextExtractionException).reason)
            assertFalse(LibraryTextTarget(book.id, book.sha256, "cbz/2.png", book.sha256, sourceFormat = BookFormat.CBZ).isValid)
            var ready = true
            val interruptedReading = object : ReadingStateRepository by reading {
                override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? { ready = false; return null }
            }
            assertTrue(runCatching { CbzReaderLoader(context, library, interruptedReading, validateRead = { check(ready) }).open(book.id) }.isFailure)
            File(originals, book.storageName).appendBytes(byteArrayOf(1))
            assertEquals(ReaderFailure.CHANGED, (runCatching { loader.open(book.id) }.exceptionOrNull() as ReaderOpenException).reason)
        } finally { db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun rendererRechecksOriginalBookAndRecoveryEveryPageAndClosedSessionCannotRender() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val library = RoomLibraryRepository(db.books()); imports(source(), library).import("content://book")
            val book = library.observeBooks().first().single(); val file = File(originals, book.storageName)
            val archive = withContext(Dispatchers.IO) { CbzArchive.read(file) }; var ready = true
            val document = CbzDocument(archive, book, VerifiedBookFile(originals), library, Mutex()) { check(ready) }
            document.render(1).also { assertEquals(12, it.width); it.recycle() }
            ready = false; assertTrue(runCatching { document.render(0) }.isFailure); ready = true
            val bytes = file.readBytes(); file.writeBytes(bytes.clone().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
            assertEquals(ReaderFailure.CHANGED, (runCatching { document.render(0) }.exceptionOrNull() as ReaderOpenException).reason)
            file.writeBytes(bytes); document.close(); assertTrue(runCatching { document.render(0) }.isFailure)
        } finally { db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun noOriginalEpubCoverageCanBePersistedForComicImages() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val library = RoomLibraryRepository(db.books()); imports(source(), library).import("content://book")
            val book = library.observeBooks().first().single()
            val archive = withContext(Dispatchers.IO) { CbzArchive.read(File(originals, book.storageName)) }
            assertTrue(runCatching { RoomReadingStateRepository(db).record(book.id, book.sha256, ReadingSnapshot(CbzPosition.encode(0, archive),
                listOf(ObservedTextSpan("cbz/2.png", "0", 0, 1, "a".repeat(64))))) }.isFailure)
            assertNull(RoomReadingStateRepository(db).resume(book.id, book.sha256)); assertTrue(db.reading().observed(book.id, book.sha256).isEmpty())
        } finally { db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun firstNaturalOriginalCoverAndActualFtsLedgerDoNotInventComicText() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val name = File(temp.newFolder(), "i.db").absolutePath
        val cache = Room.databaseBuilder(context, com.yaprak.reader.data.search.TextIndexDatabase::class.java, name).build()
        try {
            val library = RoomLibraryRepository(db.books()); imports(source(), library).import("content://comic")
            val book = library.observeBooks().first().single()
            val cover = com.yaprak.reader.data.covers.LocalBookCoverLoader(context, VerifiedBookFile(originals)).load(book)!!
            assertTrue(cover.height > cover.width); assertEquals(android.graphics.Color.rgb(23, 98, 65), cover.getPixel(cover.width / 2, cover.height / 2))
            val index = com.yaprak.reader.data.search.LocalLibraryTextIndex(cache, library, com.yaprak.reader.data.search.LocalBookTextExtractor(context))
            index.rebuild {}
            val result = index.observe("needle").first()
            assertEquals(LibraryTextBookState.UNSUPPORTED, result.books.single().state); assertEquals(0, result.totalMatches); assertTrue(result.hits.isEmpty())
            assertNull(RoomReadingStateRepository(db).resume(book.id, book.sha256))
        } finally { cache.close(); db.close(); context.deleteDatabase(name); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun txtSavedRecordsNowUseTheSameActualTextMarkdownAndStructuredExportAdapters() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val file = temp.newFile("notes.txt").apply { writeText("Original text") }; val library = RoomLibraryRepository(db.books())
            val importer = DefaultBookImporter(DocumentProvider { SourceDocument(file.name) { file.inputStream() } }, LocalBookFiles(originals),
                BookMetadataReader { staged, format -> assertEquals(BookFormat.TXT, format); com.yaprak.reader.formats.txt.StrictTxt.read(staged); com.yaprak.reader.formats.BookMetadata() }, library)
            assertEquals(ImportResult.Added, importer.import("content://text")); val book = library.observeBooks().first().single()
            RoomBookNoteRepository(db).add(book.id, book.sha256, "TXT çalışma notu")
            val exports = RoomBookStudySource(db)
            for (format in listOf(StudyExportFormat.TEXT, StudyExportFormat.MARKDOWN)) {
                val snapshot = exports.snapshot(book.id, book.sha256, format)
                assertTrue(snapshot.utf8.toString(Charsets.UTF_8).contains("TXT çalışma notu"))
            }
            assertEquals(BookFormat.TXT, exports.snapshotRecords(book.id, book.sha256).book.format)
            assertEquals(1, exports.snapshotRecords(book.id, book.sha256).notes.size)
        } finally { db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
}

package com.yaprak.reader.formats.txt

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
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.formats.BookMetadata
import com.yaprak.reader.formats.BookMetadataReader
import com.yaprak.reader.reader.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TxtPersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val originals get() = File(context.filesDir, "books").apply { mkdirs() }
    private val cache get() = File(context.cacheDir, "txt-viewing")
    private val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "İstanbul 🌿\r\nunique needle\n\nlast".toByteArray(Charsets.UTF_16LE)
    private fun importer(source: File, library: LibraryRepository) = DefaultBookImporter(
        DocumentProvider { SourceDocument(source.name) { source.inputStream() } }, LocalBookFiles(originals),
        BookMetadataReader { file, format -> assertEquals(BookFormat.TXT, format); StrictTxt.read(file); BookMetadata() }, library)
    private fun database() = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
    private suspend fun cleanClosedViews() {
        for (file in cache.listFiles().orEmpty()) {
            val probe = File(file.parentFile, file.name + ".probe")
            withTimeout(5000) { while (!file.renameTo(probe)) delay(10) }
            check(probe.renameTo(file))
        }
        TxtDerivedFiles(cache).recoverAbandoned()
    }
    @Test fun originalUtf16ImportDuplicateUserEditsExportAndRoomReopenRemainByteExact() = runBlocking {
        val source = temp.newFile("Ders.TXT").apply { writeBytes(bytes) }
        val name = "txt-reopen-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        var book: Book? = null
        try {
            val library = RoomLibraryRepository(db.books()); val imports = importer(source, library)
            assertEquals(ImportResult.Added, imports.import("content://source"))
            book = library.observeBooks().first().single()
            val current = book!!
            assertEquals("Ders", current.title); assertNull(current.authors); assertNull(current.pageCount)
            assertEquals(BookFormat.TXT, current.format); assertTrue(current.storageName.endsWith(".txt"))
            assertArrayEquals(bytes, File(originals, current.storageName).readBytes())
            assertTrue(library.updateMetadata(current.id, current.sha256, "Edited", "Writer"))
            assertTrue(library.updateOrganization(current.id, current.sha256, BookOrganization(favorite = true, tags = listOf("Ders"))))
            assertEquals(ImportResult.Duplicate, imports.import("content://source"))
            assertEquals("Edited", library.findById(current.id)!!.title)
            assertTrue(library.findById(current.id)!!.organization.favorite)
            val output = ByteArrayOutputStream()
            val documents = object : ExportDocumentProvider {
                override fun accepts(uri: String) = true
                override fun open(uri: String): OutputStream = output
                override fun delete(uri: String) = true
            }
            assertEquals(ExportResult.Exported, DefaultBookExporter(library, VerifiedBookFile(originals), documents).export(current.id, "content://export"))
            assertArrayEquals(bytes, output.toByteArray()); assertTrue(source.delete())
        } finally { db.close() }
        val reopened = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            val current = RoomLibraryRepository(reopened.books()).observeBooks().first().single()
            assertEquals(book!!.sha256, current.sha256); assertEquals("Edited", current.title)
            assertArrayEquals(bytes, File(originals, current.storageName).readBytes())
            assertEquals("İstanbul 🌿\nunique needle\n\nlast", StrictTxt.read(File(originals, current.storageName)).single().text)
        } finally { reopened.close(); context.deleteDatabase(name); book?.let { File(originals, it.storageName).delete() } }
    }
    @Test fun invalidTxtFailedAtomicBookInsertAndExplicitRetryLeaveOnlyOneCompleteCopy() = runBlocking {
        val source = temp.newFile("text.txt").apply { writeBytes(byteArrayOf(1, 0, 2)) }
        val db = database()
        try {
            val library = RoomLibraryRepository(db.books()); val imports = importer(source, library)
            assertEquals(ImportResult.Failed(ImportFailure.INVALID), imports.import("content://source"))
            assertTrue(originals.listFiles().isNullOrEmpty()); assertTrue(library.observeBooks().first().isEmpty())
            source.writeBytes(bytes)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_txt_insert BEFORE INSERT ON books BEGIN SELECT RAISE(ABORT, 'controlled insert failure'); END")
            assertTrue(imports.import("content://source") is ImportResult.Failed)
            assertTrue(originals.listFiles().isNullOrEmpty()); assertTrue(library.observeBooks().first().isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_txt_insert")
            assertEquals(ImportResult.Added, imports.import("content://source"))
            assertEquals(1, originals.listFiles()!!.size); assertEquals(1, library.observeBooks().first().size)
        } finally { db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun loaderUsesRealReadiumSurfaceSearchResumeAndPlainCapabilitiesWithOriginalHashProof() = runBlocking {
        val source = temp.newFile("text.txt").apply { writeBytes(bytes) }; val db = database()
        try {
            val library = RoomLibraryRepository(db.books()); importer(source, library).import("content://source")
            val book = library.observeBooks().first().single(); val reading = RoomReadingStateRepository(db)
            val native = TxtReaderLoader(context, library, reading)
            val routed = FormatReaderLoader(library, ReaderLoader { error("EPUB misroute") }, ReaderLoader { error("PDF misroute") }, native)
            val location = routed.open(book.id).use { session ->
                assertEquals(book, session.book); assertNull(session.resume)
                val surface = session.createSurface(null, false, {}, { error("resource failure") })
                assertNotNull(surface.appearance); assertNotNull(surface.bookmarkNavigator); assertNotNull(surface.bookPositionNavigator)
                assertNull(surface.pageNavigator); assertNull(surface.epubImageFilter); assertNull(surface.epubHighlights)
                assertNull(surface.passageNavigator); assertNotNull(surface.indexedChapterNavigator)
                assertEquals(listOf("Metin 1"), surface.contentsNavigator!!.contents.entries.map { it.title })
                assertEquals(BookSearchKind.TXT, surface.bookSearch!!.kind)
                surface.bookSearch!!.find("unique needle").hits.single().location
            }
            reading.record(book.id, book.sha256, ReadingSnapshot(location))
            routed.open(book.id).use { assertEquals(location, it.resume) }
            assertTrue(db.reading().observed(book.id, book.sha256).isEmpty())
            assertNotNull(runCatching { reading.record(book.id, book.sha256,
                ReadingSnapshot(location.copy(locatorJson = "changed"), listOf(ObservedTextSpan(location.resourceHref, "0", 0, 3, "b".repeat(64))))) }.exceptionOrNull())
            assertEquals(location, reading.resume(book.id, book.sha256))
            assertTrue(db.reading().observed(book.id, book.sha256).isEmpty())
            File(originals, book.storageName).appendText("changed")
            assertEquals(ReaderFailure.CHANGED, (runCatching { native.open(book.id) }.exceptionOrNull() as ReaderOpenException).reason)
            assertArrayEquals(bytes, source.readBytes())
            cleanClosedViews(); assertTrue(cache.listFiles().isNullOrEmpty())
        } finally { db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun cancellationAfterPublicationOpenClosesArtifactWithoutWritingProgressOrChangingOriginal() = runBlocking {
        val source = temp.newFile("text.txt").apply { writeBytes(bytes) }; val db = database()
        try {
            val library = RoomLibraryRepository(db.books()); importer(source, library).import("content://source")
            val book = library.observeBooks().first().single(); val entered = CompletableDeferred<Unit>()
            val reading = object : ReadingStateRepository by RoomReadingStateRepository(db) {
                override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? {
                    entered.complete(Unit); awaitCancellation()
                }
            }
            val job = launch { TxtReaderLoader(context, library, reading).open(book.id).close() }
            withTimeout(10000) { entered.await() }; assertTrue(cache.listFiles()!!.isNotEmpty())
            job.cancelAndJoin(); assertTrue(job.isCancelled)
            cleanClosedViews(); assertTrue(cache.listFiles().isNullOrEmpty())
            assertNull(RoomReadingStateRepository(db).resume(book.id, book.sha256))
            assertArrayEquals(bytes, File(originals, book.storageName).readBytes())
        } finally { db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun loaderRejectsViewTamperOrRecoveryChangeAfterActualPublicationOpenBeforeSessionHandoff() = runBlocking {
        val source = temp.newFile("text.txt").apply { writeBytes(bytes) }; val db = database()
        try {
            val library = RoomLibraryRepository(db.books()); importer(source, library).import("content://source")
            val book = library.observeBooks().first().single()
            for (kind in listOf("view", "recovery")) {
                var admitted = true; var resumed = false
                val reading = object : ReadingStateRepository by RoomReadingStateRepository(db) {
                    override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? {
                        resumed = true
                        if (kind == "view") RandomAccessFile(cache.listFiles()!!.single(), "rw").use {
                            val offset = it.length() - 1; it.seek(offset); val value = it.read()
                            it.seek(offset); it.write(value xor 1)
                        } else admitted = false
                        return null
                    }
                }
                val failure = runCatching { TxtReaderLoader(context, library, reading, validateRead = { check(admitted) }).open(book.id).close() }.exceptionOrNull()
                assertTrue(resumed)
                if (kind == "view") assertEquals(ReaderFailure.CHANGED, (failure as ReaderOpenException).reason)
                else assertTrue(failure is IllegalStateException)
                cleanClosedViews(); assertTrue(cache.listFiles().isNullOrEmpty())
                assertNull(RoomReadingStateRepository(db).resume(book.id, book.sha256))
                assertArrayEquals(bytes, File(originals, book.storageName).readBytes())
            }
        } finally { db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun txtAppearanceAllSevenFieldsAreSavedObservedResetAtomicallyAndNotesRemainSearchable() = runBlocking {
        val source = temp.newFile("text.txt").apply { writeBytes(bytes) }; val db = database()
        try {
            val library = RoomLibraryRepository(db.books()); importer(source, library).import("content://source")
            val book = library.observeBooks().first().single(); val id = book.id; val hash = book.sha256
            RoomBookThemeRepository(db).save(id, hash, ReadingTheme.AMOLED)
            RoomBookFontRepository(db).save(id, hash, ReadingFont.SERIF)
            RoomBookFontSizeRepository(db).save(id, hash, 125)
            RoomBookMarginsRepository(db).save(id, hash, 150)
            RoomBookLineHeightRepository(db).save(id, hash, BookLineHeightChoice.Custom(170))
            val pair = BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Custom(100))
            RoomBookSpacingRepository(db).save(id, hash, pair)
            assertEquals(ReadingTheme.AMOLED, RoomBookThemeRepository(db).observe(id, hash).first())
            assertEquals(ReadingFont.SERIF, RoomBookFontRepository(db).observe(id, hash).first())
            assertEquals(125, RoomBookFontSizeRepository(db).observe(id, hash).first())
            assertEquals(150, RoomBookMarginsRepository(db).observe(id, hash).first())
            assertEquals(BookLineHeightChoice.Custom(170), RoomBookLineHeightRepository(db).observe(id, hash).first())
            assertEquals(pair, RoomBookSpacingRepository(db).observe(id, hash).first())
            RoomBookNoteRepository(db).add(id, hash, "unique note")
            val note = RoomLibraryNotesRepository(db).observe("unique").first().entries.single()
            assertEquals(BookFormat.TXT, note.bookFormat); assertNull(note.sourceAnchor)
            assertNotNull(runCatching { RoomBookAppearanceResetRepository(db).reset(id, "stale") }.exceptionOrNull())
            assertEquals(pair, RoomBookSpacingRepository(db).observe(id, hash).first())
            RoomBookAppearanceResetRepository(db).reset(id, hash)
            assertNull(RoomBookThemeRepository(db).observe(id, hash).first()); assertNull(RoomBookFontRepository(db).observe(id, hash).first())
            assertNull(RoomBookFontSizeRepository(db).observe(id, hash).first()); assertNull(RoomBookMarginsRepository(db).observe(id, hash).first())
            assertEquals(BookLineHeightChoice.Inherit, RoomBookLineHeightRepository(db).observe(id, hash).first())
            assertEquals(BookSpacingSettings(), RoomBookSpacingRepository(db).observe(id, hash).first())
            assertEquals(note, RoomLibraryNotesRepository(db).observe("unique").first().entries.single())
            assertArrayEquals(bytes, File(originals, book.storageName).readBytes())
        } finally { db.close(); originals.listFiles().orEmpty().forEach { it.delete() } }
    }
    @Test fun pdfAndZipSignaturesKeepPriorityOverTxtSuffixAndUnnamedTextIsUnsupported() = runBlocking {
        for ((name, source, expected) in listOf(Triple("wrong.txt", "%PDF-header".toByteArray(), BookFormat.PDF),
            Triple("wrong.txt", byteArrayOf(0x50, 0x4b, 3, 4, 9), BookFormat.EPUB))) {
            val directory = temp.newFolder(); val staged = LocalBookFiles(directory).stage(SourceDocument(name) { source.inputStream() }, "book")
            assertEquals(expected, staged.format); assertArrayEquals(source, staged.file.readBytes())
        }
        val directory = temp.newFolder()
        assertEquals(ImportFailure.UNSUPPORTED, (runCatching { LocalBookFiles(directory).stage(SourceDocument("unknown") { bytes.inputStream() }, "book") }.exceptionOrNull() as BookImportException).reason)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }
}

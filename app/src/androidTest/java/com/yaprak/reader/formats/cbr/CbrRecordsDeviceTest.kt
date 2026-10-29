package com.yaprak.reader.formats.cbr

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.data.backup.*
import com.yaprak.reader.data.exporting.DefaultBookExporter
import com.yaprak.reader.data.exporting.ExportDocumentProvider
import com.yaprak.reader.data.importing.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.storage.LocalBookFiles
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.formats.BookMetadataReader
import com.yaprak.reader.formats.cbz.CbzPosition
import com.yaprak.reader.formats.cbz.cbzHash
import java.io.*
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real disk Room, native admission and original streaming; no user library reset. */
@RunWith(AndroidJUnit4::class)
class CbrRecordsDeviceTest {
    @Test fun importDuplicatesOriginalExportVersion26RestoreAndDiskReopenKeepCbrRecords() = runBlocking {
        withContext(Dispatchers.IO) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val root = File(context.cacheDir, "cbr-records-device-${UUID.randomUUID()}").apply { check(mkdir()) }
            val opened = mutableListOf<LibraryDatabase>()
            fun database(name: String) = Room.databaseBuilder(context, LibraryDatabase::class.java,
                File(root, "$name.db").absolutePath).build().also { opened += it }
            val backupFile = File(root, "owned-backup.zip")
            val documents = object : BackupDocumentProvider {
                override fun accepts(uri: String) = uri == "content://owned-cbr/backup"
                override fun openInput(uri: String) = backupFile.inputStream()
                override fun openOutput(uri: String) = backupFile.outputStream()
                override fun delete(uri: String) = !backupFile.exists() || backupFile.delete()
            }
            class Settings : EpubSettingsRepository {
                override val settings = MutableStateFlow(EpubSettings())
                override suspend fun save(settings: EpubSettings) { this.settings.value = settings }
            }
            fun backup(db: LibraryDatabase, name: String) = DefaultLibraryBackup(db,
                File(root, "$name/books"), File(root, "$name/stage"), File(root, "$name/restore.pending"),
                Settings(), documents, Mutex(), cbrCache = CbrPublicationCache(File(root, "$name/cache")))
            try {
                val original = instrumentation.context.assets.open("cbr183/self-fixtures/stored-rar5.cbr").use { it.readBytes() }
                val source = database("source")
                val library = RoomLibraryRepository(source.books())
                val cache = CbrPublicationCache(File(root, "import-cache"))
                val importer = DefaultBookImporter(DocumentProvider { SourceDocument("source.cbr") { original.inputStream() } },
                    LocalBookFiles(File(root, "source/books")), BookMetadataReader { file, format ->
                        assertEquals(BookFormat.CBR, format); CbrMetadataReader(cache).read(file)
                    }, library)
                assertEquals(ImportResult.Added, importer.import("content://owned-cbr/source"))
                assertEquals(ImportResult.Duplicate, importer.import("content://owned-cbr/source"))
                val book = library.observeBooks().first().single()
                assertEquals(BookFormat.CBR, book.format); assertEquals(2, book.pageCount)
                val originalFile = File(root, "source/books/${book.storageName}")
                val cover = com.yaprak.reader.data.covers.LocalBookCoverLoader(context,
                    VerifiedBookFile(originalFile.parentFile!!), cbrCache = cache).load(book)
                assertNotNull(cover)
                try { assertEquals(0xff164682.toInt(), cover!!.getPixel(0, 0)) } finally { cover?.recycle() }
                val textExtractor = com.yaprak.reader.data.search.LocalBookTextExtractor(context)
                assertFalse(textExtractor.supports(book))
                try { textExtractor.extract(book); fail("An image-only format cannot become an AI/search source") }
                catch (failure: com.yaprak.reader.domain.search.TextExtractionException) {
                    assertEquals(com.yaprak.reader.domain.search.TextExtractionFailure.UNSUPPORTED, failure.reason)
                }
                val location = cache.acquire(originalFile).use { CbzPosition.encode(1, it.archive, BookFormat.CBR) }
                RoomReadingStateRepository(source).record(book.id, book.sha256, ReadingSnapshot(location))
                val bookmark = BookmarkTarget("Sayfa 2", location)
                RoomBookmarkRepository(source).add(book.id, book.sha256, bookmark)
                RoomBookComicViewPreferencesRepository(source).saveSpread(book.id, book.sha256, true)
                RoomBookNoteRepository(source).add(book.id, book.sha256, "Owned CBR note")
                assertEquals(BookFormat.CBR, RoomLibraryNotesRepository(source).observe("Owned CBR").first().entries.single().bookFormat)
                assertTrue(library.updateOrganization(book.id, book.sha256, BookOrganization(favorite = true, tags = listOf("Comics"))))
                val exported = File(root, "exported.cbr")
                val exportDocuments = object : ExportDocumentProvider {
                    override fun accepts(uri: String) = true
                    override fun open(uri: String) = exported.outputStream()
                    override fun delete(uri: String) = !exported.exists() || exported.delete()
                }
                assertEquals(ExportResult.Exported, DefaultBookExporter(library,
                    VerifiedBookFile(originalFile.parentFile!!), exportDocuments).export(book.id, "content://owned-cbr/export"))
                assertArrayEquals(original, exported.readBytes())
                assertEquals(BackupResult.Created(1), backup(source, "source").create("content://owned-cbr/backup"))
                val archiveBytes = backupFile.readBytes()
                ZipFile(backupFile).use { zip ->
                    val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
                    assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
                    assertArrayEquals(original, zip.getInputStream(zip.getEntry("books/${book.storageName}")).readBytes())
                }
                var target = database("target")
                assertEquals(BackupResult.Restored(1), backup(target, "target").restore("content://owned-cbr/backup"))
                target.close(); target = database("target")
                val restored = RoomLibraryRepository(target.books()).findById(book.id)!!
                assertEquals(BookFormat.CBR, restored.format); assertEquals(book.sha256, restored.sha256)
                assertEquals("source.cbr", restored.originalName); assertTrue(restored.organization.favorite)
                assertArrayEquals(original, File(root, "target/books/${book.storageName}").readBytes())
                assertEquals(location, RoomReadingStateRepository(target).resume(book.id, book.sha256))
                assertEquals(bookmark, RoomBookmarkRepository(target).observe(book.id, book.sha256).first().single().target)
                assertTrue(RoomBookComicViewPreferencesRepository(target).observe(book.id, book.sha256).first().doublePagePreferred)
                assertEquals("Owned CBR note", RoomBookNoteRepository(target).observe(book.id, book.sha256).first().single().text)
                // A new header cannot smuggle the newly introduced format into an old archive version.
                val changed = ByteArrayOutputStream()
                ZipInputStream(archiveBytes.inputStream()).use { input -> ZipOutputStream(changed).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val bytes = input.readBytes()
                        val value = if (entry.name == "manifest.json") JSONObject(bytes.toString(Charsets.UTF_8))
                            .put("formatVersion", 25).toString().toByteArray() else bytes
                        output.putNextEntry(ZipEntry(entry.name)); output.write(value); output.closeEntry(); input.closeEntry()
                    }
                } }
                backupFile.writeBytes(changed.toByteArray())
                val denied = database("denied")
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), backup(denied, "denied").restore("content://owned-cbr/backup"))
                assertTrue(RoomLibraryRepository(denied.books()).observeBooks().first().isEmpty())
                assertFalse(File(root, "denied/restore.pending").exists())
                assertArrayEquals(original, originalFile.readBytes()); assertEquals(cbzHash(original), book.sha256)
            } finally {
                opened.forEach { it.close() }
                check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(root.deleteRecursively())
            }
        }
    }
}

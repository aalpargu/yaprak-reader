package com.yaprak.reader.reader

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.data.backup.BackupDocumentProvider
import com.yaprak.reader.data.backup.DefaultLibraryBackup
import com.yaprak.reader.data.importing.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.storage.LocalBookFiles
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.formats.BookMetadataReader
import com.yaprak.reader.formats.epub.EpubBookSearch
import com.yaprak.reader.formats.epub.EpubPublicationSource
import com.yaprak.reader.formats.epub.decodeEpubLocation
import com.yaprak.reader.formats.fb2.Fb2Epub
import com.yaprak.reader.formats.fb2.Fb2MetadataReader
import com.yaprak.reader.formats.fb2.StrictFb2
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated owned disk DBs and original files. Does not clear or restore the user's application library. */
@RunWith(AndroidJUnit4::class)
class Fb2ZipDeviceTest {
    @Test fun originalZipAndReaderRecordsSurviveBackupRestoreAndDiskReopen() = runBlocking {
        withTimeout(90_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(context.cacheDir, "fb2-zip-device-${UUID.randomUUID()}").apply { check(mkdir()) }
            val opened = mutableListOf<LibraryDatabase>()
            fun database(name: String) = Room.databaseBuilder(context, LibraryDatabase::class.java,
                File(root, "$name.db").absolutePath).build().also { opened += it }
            val backupFile = File(root, "owned-backup.zip")
            val documents = object : BackupDocumentProvider {
                override fun accepts(uri: String) = uri == "content://owned-test/backup"
                override fun openInput(uri: String) = backupFile.inputStream()
                override fun openOutput(uri: String) = backupFile.outputStream()
                override fun delete(uri: String) = !backupFile.exists() || backupFile.delete()
            }
            class Settings : EpubSettingsRepository {
                override val settings = MutableStateFlow(EpubSettings())
                override suspend fun save(settings: EpubSettings) { this.settings.value = settings }
            }
            fun backup(db: LibraryDatabase, name: String) = DefaultLibraryBackup(db,
                File(root, "$name/books"), File(root, "$name/stage"), File(root, "$name/journal.pending"),
                Settings(), documents, Mutex())
            try {
                val xml = ("<FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0'>" +
                    "<description><title-info><book-title>Owned ZIP test</book-title><lang>en</lang></title-info></description>" +
                    "<body><section><title><p>Chapter</p></title><p>device original tail</p></section></body></FictionBook>").toByteArray()
                val original = ByteArrayOutputStream().also { out ->
                    ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("book.fb2")); zip.write(xml); zip.closeEntry() }
                }.toByteArray()
                val source = database("source")
                val library = RoomLibraryRepository(source.books())
                val importer = DefaultBookImporter(DocumentProvider { SourceDocument("source.fb2.zip") { original.inputStream() } },
                    LocalBookFiles(File(root, "source/books")), BookMetadataReader { file, format ->
                        assertEquals(BookFormat.FB2, format); Fb2MetadataReader().read(file)
                    }, library)
                assertEquals(ImportResult.Added, importer.import("content://owned-test/source"))
                assertEquals(ImportResult.Duplicate, importer.import("content://owned-test/source"))
                val book = library.observeBooks().first().single()
                val originalFile = File(root, "source/books/${book.storageName}")
                val view = File(root, "source-view.epub")
                Fb2Epub.write(view, book.sha256, StrictFb2.read(originalFile))
                val location = EpubPublicationSource(context.contentResolver).open(view).use {
                    EpubBookSearch(it.publication).find("device original tail").hits.single().location
                }
                RoomReadingStateRepository(source).record(book.id, book.sha256, ReadingSnapshot(location))
                val bookmark = BookmarkTarget("Owned point", location)
                RoomBookmarkRepository(source).add(book.id, book.sha256, bookmark)
                RoomBookThemeRepository(source).save(book.id, book.sha256, ReadingTheme.AMOLED)
                source.bookNotes().add(BookNoteEntity(UUID.randomUUID().toString(), book.id, book.sha256, "Owned note", 1))
                assertEquals(BackupResult.Created(1), backup(source, "source").create("content://owned-test/backup"))
                val archive = backupFile.readBytes()
                ZipFile(backupFile).use { zip ->
                    assertEquals(26, JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()).getInt("formatVersion"))
                }
                var target = database("target")
                assertEquals(BackupResult.Restored(1), backup(target, "target").restore("content://owned-test/backup"))
                target.close(); target = database("target")
                val restored = RoomLibraryRepository(target.books()).findById(book.id)!!
                assertEquals(book.sha256, restored.sha256); assertEquals("source.fb2.zip", restored.originalName)
                assertArrayEquals(original, File(root, "target/books/${book.storageName}").readBytes())
                assertEquals(location, RoomReadingStateRepository(target).resume(book.id, book.sha256))
                assertEquals(bookmark, RoomBookmarkRepository(target).observe(book.id, book.sha256).first().single().target)
                assertEquals(ReadingTheme.AMOLED, RoomBookThemeRepository(target).observe(book.id, book.sha256).first())
                assertEquals("Owned note", target.bookNotes().observe(book.id, book.sha256).first().single().text)
                assertTrue(target.reading().observed(book.id, book.sha256).isEmpty())
                val nextView = File(root, "target-view.epub")
                Fb2Epub.write(nextView, restored.sha256, StrictFb2.read(File(root, "target/books/${book.storageName}")))
                EpubPublicationSource(context.contentResolver).open(nextView).use {
                    assertNotNull(decodeEpubLocation(location, it.publication.readingOrder))
                    assertEquals(location, EpubBookSearch(it.publication).find("device original tail").hits.single().location)
                }
                // The same actual archive with an old header must fail before durable writes.
                val downgraded = ByteArrayOutputStream()
                ZipInputStream(archive.inputStream()).use { input -> ZipOutputStream(downgraded).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val bytes = input.readBytes()
                        val changed = if (entry.name == "manifest.json") JSONObject(bytes.toString(Charsets.UTF_8))
                            .put("formatVersion", 22).toString().toByteArray() else bytes
                        output.putNextEntry(ZipEntry(entry.name)); output.write(changed); output.closeEntry(); input.closeEntry()
                    }
                } }
                backupFile.writeBytes(downgraded.toByteArray())
                val denied = database("denied")
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), backup(denied, "denied").restore("content://owned-test/backup"))
                assertTrue(RoomLibraryRepository(denied.books()).observeBooks().first().isEmpty())
                assertFalse(File(root, "denied/journal.pending").exists())
                val changedEntries = linkedMapOf<String, ByteArray>()
                ZipInputStream(archive.inputStream()).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        changedEntries[entry.name] = input.readBytes(); input.closeEntry()
                    }
                }
                val changedRecords = JSONObject(changedEntries.getValue("records.json").toString(Charsets.UTF_8))
                changedRecords.getJSONObject("tables").getJSONArray("books").getJSONObject(0).put("originalName", "source.fb2")
                val changedBytes = changedRecords.toString().toByteArray()
                changedEntries["records.json"] = changedBytes
                val changedHash = MessageDigest.getInstance("SHA-256").digest(changedBytes).joinToString("") { "%02x".format(it) }
                changedEntries["manifest.json"] = JSONObject(changedEntries.getValue("manifest.json").toString(Charsets.UTF_8))
                    .put("recordsSize", changedBytes.size).put("recordsSha256", changedHash).toString().toByteArray()
                val nameMismatch = ByteArrayOutputStream()
                ZipOutputStream(nameMismatch).use { output -> changedEntries.forEach { (name, bytes) ->
                    output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
                } }
                backupFile.writeBytes(nameMismatch.toByteArray())
                val deniedName = database("denied-name")
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE),
                    backup(deniedName, "denied-name").restore("content://owned-test/backup"))
                assertTrue(RoomLibraryRepository(deniedName.books()).observeBooks().first().isEmpty())
                assertFalse(File(root, "denied-name/journal.pending").exists())
                assertArrayEquals(original, originalFile.readBytes())
                val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
                assertEquals(hash, restored.sha256)
            } finally {
                opened.forEach { it.close() }
                check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(root.deleteRecursively())
            }
        }
    }
}

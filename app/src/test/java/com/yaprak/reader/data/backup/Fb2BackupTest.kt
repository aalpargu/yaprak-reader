package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.domain.settings.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Fb2BackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val original = byteArrayOf(0xfe.toByte(), 0xff.toByte()) + """<?xml version="1.0" encoding="UTF-16"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Özgün 🌿</book-title><lang>tr</lang></title-info></description><body><section><title><p>Bölüm</p></title><p>unique needle</p></section></body></FictionBook>""".toByteArray(Charsets.UTF_16BE)
    private val global = EpubSettings(theme = ReadingTheme.AMOLED, scroll = true, paragraphSpacingPercent = 150)
    private val pair = BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Publisher)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Settings(value: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(value); var writes = 0; var failNext = false
        override suspend fun save(settings: EpubSettings) {
            writes++; if (failNext) { failNext = false; throw IOException("controlled settings failure") }
            this.settings.value = settings
        }
    }
    private class Documents : BackupDocumentProvider {
        var bytes: ByteArray? = null
        override fun accepts(uri: String) = true
        override fun openInput(uri: String): InputStream = ByteArrayInputStream(bytes!!)
        override fun openOutput(uri: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() { super.close(); bytes = toByteArray() }
        }
        override fun delete(uri: String): Boolean { bytes = null; return true }
    }
    private data class Fixture(val db: LibraryDatabase, val books: File, val stage: File, val journal: File, val settings: Settings) {
        fun backup(docs: Documents) = DefaultLibraryBackup(db, books, stage, journal, settings, docs, Mutex())
    }
    private fun fixture(value: EpubSettings = global): Fixture {
        val root = temp.newFolder(); val context = ApplicationProvider.getApplicationContext<Application>()
        return Fixture(Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build(),
            File(root, "books").apply { mkdir() }, File(root, "stage"), File(root, "journal.pending"), Settings(value))
    }
    private suspend fun seed(f: Fixture, bytes: ByteArray = original): Book {
        val book = Book(id, "FB2 title", "Author", BookFormat.FB2, "source.fb2", id + ".fb2", bytes.size.toLong(), hash(bytes), 1)
        val library = RoomLibraryRepository(f.db.books()); library.add(book); File(f.books, book.storageName).writeBytes(bytes)
        library.updateOrganization(id, book.sha256, BookOrganization(favorite = true, tags = listOf("tag")))
        RoomBookNoteRepository(f.db).add(id, book.sha256, "FB2 note")
        RoomBookThemeRepository(f.db).save(id, book.sha256, ReadingTheme.SEPIA)
        RoomBookFontSizeRepository(f.db).save(id, book.sha256, 125)
        RoomBookFontRepository(f.db).save(id, book.sha256, ReadingFont.SERIF)
        RoomBookMarginsRepository(f.db).save(id, book.sha256, 150)
        RoomBookLineHeightRepository(f.db).save(id, book.sha256, BookLineHeightChoice.Publisher)
        RoomBookSpacingRepository(f.db).save(id, book.sha256, pair)
        RoomBookReadingModeRepository(f.db).save(id, book.sha256, BookReadingMode.SCROLL)
        RoomBookTextAlignmentRepository(f.db).save(id, book.sha256, ReadingTextAlignment.JUSTIFY)
        RoomReadingStateRepository(f.db).record(id, book.sha256, ReadingSnapshot(ReadingLocation("{\"href\":\"Text/b1-p1.xhtml\",\"type\":\"application/xhtml+xml\"}", "Text/b1-p1.xhtml")))
        return book
    }
    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>(); ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val next = zip.nextEntry ?: break; entries[next.name] = zip.readBytes(); zip.closeEntry() }
        }; return entries
    }
    private fun rewrite(bytes: ByteArray, edit: (LinkedHashMap<String, ByteArray>) -> Unit): ByteArray {
        val entries = entries(bytes); edit(entries); val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
        return out.toByteArray()
    }
    private suspend fun assertEmpty(f: Fixture, writes: Int = 0) {
        assertTrue(f.db.books().observeAll().first().isEmpty()); assertTrue(f.books.listFiles().isNullOrEmpty())
        assertFalse(f.journal.exists()); assertEquals(writes, f.settings.writes)
        assertTrue(f.stage.listFiles().isNullOrEmpty())
    }
    @Test fun currentBackupTwentyTwoPreservesOriginalFb2AppearanceNotesAndProgress() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            val book = seed(source); assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val entries = entries(docs.bytes!!); val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            assertEquals(setOf("manifest.json", "records.json", "books/" + book.storageName), entries.keys)
            assertArrayEquals(original, entries.getValue("books/" + book.storageName))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            val restored = RoomLibraryRepository(target.db.books()).findById(id)!!
            assertEquals(BookFormat.FB2, restored.format); assertEquals(book.sha256, restored.sha256)
            assertTrue(restored.organization.favorite); assertArrayEquals(original, File(target.books, book.storageName).readBytes())
            assertEquals(global, target.settings.settings.first())
            assertEquals(ReadingTheme.SEPIA, RoomBookThemeRepository(target.db).observe(id, book.sha256).first())
            assertEquals(ReadingFont.SERIF, RoomBookFontRepository(target.db).observe(id, book.sha256).first())
            assertEquals(125, RoomBookFontSizeRepository(target.db).observe(id, book.sha256).first())
            assertEquals(150, RoomBookMarginsRepository(target.db).observe(id, book.sha256).first())
            assertEquals(BookLineHeightChoice.Publisher, RoomBookLineHeightRepository(target.db).observe(id, book.sha256).first())
            assertEquals(pair, RoomBookSpacingRepository(target.db).observe(id, book.sha256).first())
            assertEquals(BookReadingMode.SCROLL, RoomBookReadingModeRepository(target.db).observe(id, book.sha256).first())
            assertEquals(ReadingTextAlignment.JUSTIFY, RoomBookTextAlignmentRepository(target.db).observe(id, book.sha256).first())
            assertEquals("FB2 note", target.db.bookNotes().observe(id, book.sha256).first().single().text)
            assertEquals("Text/b1-p1.xhtml", RoomReadingStateRepository(target.db).resume(id, book.sha256)!!.resourceHref)
            assertTrue(target.db.reading().observed(id, book.sha256).isEmpty()); assertFalse(target.journal.exists())
        } finally { source.db.close(); target.db.close() }
    }
    @Test fun everyOldHeaderRefusesFb2BeforePersistentWritesAndVersionTwentyOneRecordsRefuseIndependently() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = docs.bytes!!
            val records = entries(original).getValue("records.json")
            assertEquals(BackupFailure.INVALID_ARCHIVE, (runCatching { BackupRecordsCodec.decode(records, 21) }.exceptionOrNull() as BackupProblem).reason)
            for (version in 1..21) {
                docs.bytes = rewrite(original) { entries ->
                    val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
                    manifest.put("formatVersion", version).put("databaseVersion", when (version) {
                        1, 2, 3 -> 12; 4 -> 13; 5 -> 14; 6 -> 15; 7 -> 16; 8 -> 17; 9 -> 18; 10 -> 19; 11, 12, 13 -> 20; 14 -> 21; 15, 16, 17 -> 22; 18 -> 23; 19 -> 24; 20 -> 25; else -> 26
                    })
                    if (version < 5) { manifest.remove("coverCount"); manifest.remove("covers") }
                    entries["manifest.json"] = manifest.toString().toByteArray()
                }
                assertEquals("version $version", BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target); assertEquals(EpubSettings(), target.settings.settings.first())
            }
        } finally { source.db.close(); target.db.close() }
    }
    @Test fun matchingOriginalHashCannotAuthorizeBinaryOrMalformedTextAndRestoreCleansItsStage() = runBlocking {
        for (invalid in listOf("binary\u0000payload".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xff.toByte(), 0xfe.toByte(), 65))) {
            val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
            try {
                val book = seed(source, invalid)
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), source.backup(docs).create("content://backup"))
                assertNull(docs.bytes)
                val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    BackupRecordsCodec.snapshot(source.db, global)
                }
                val records = BackupRecordsCodec.encode(snapshot)
                val manifest = BackupRecordsCodec.encodeManifest(snapshot, records, hash(records))
                val out = ByteArrayOutputStream()
                ZipOutputStream(out).use { zip ->
                    for ((name, data) in listOf("manifest.json" to manifest, "records.json" to records, "books/${book.storageName}" to invalid)) {
                        zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
                    }
                }
                docs.bytes = out.toByteArray()
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target)
            } finally { source.db.close(); target.db.close() }
        }
    }
    @Test fun settingsFailureAfterFb2CommitRollsBackOriginalRowsAppearanceNotesAndProgressThenRetries() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            val book = seed(source); source.backup(docs).create("content://backup"); target.settings.failNext = true
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs).restore("content://backup"))
            assertEmpty(target, 2); assertEquals(EpubSettings(), target.settings.settings.first())
            assertTrue(target.db.bookNotes().observe(id, book.sha256).first().isEmpty())
            assertNull(RoomReadingStateRepository(target.db).resume(id, book.sha256))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertArrayEquals(original, File(target.books, book.storageName).readBytes())
            assertEquals(pair, RoomBookSpacingRepository(target.db).observe(id, book.sha256).first())
            assertEquals(BookReadingMode.SCROLL, RoomBookReadingModeRepository(target.db).observe(id, book.sha256).first())
            assertEquals(ReadingTextAlignment.JUSTIFY, RoomBookTextAlignmentRepository(target.db).observe(id, book.sha256).first())
        } finally { source.db.close(); target.db.close() }
    }
    @Test fun actualReadiumLocationBookmarkAndPreferencesSurviveZipRestoreDatabaseReopenAndReconversion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val source = fixture(); val base = fixture(EpubSettings()); base.db.close()
        val dbName = "fb-back.db"
        val target = base.copy(db = Room.databaseBuilder(context, LibraryDatabase::class.java, dbName).build())
        val docs = Documents()
        try {
            val book = seed(source)
            val first = temp.newFile("first.epub")
            com.yaprak.reader.formats.fb2.Fb2Epub.write(first, book.sha256, com.yaprak.reader.formats.fb2.StrictFb2.decode(original))
            val location = com.yaprak.reader.formats.epub.EpubPublicationSource(context.contentResolver).open(first).use {
                com.yaprak.reader.formats.epub.EpubBookSearch(it.publication).find("unique needle").hits.single().location
            }
            RoomReadingStateRepository(source.db).record(id, book.sha256, ReadingSnapshot(location))
            RoomBookmarkRepository(source.db).add(id, book.sha256, com.yaprak.reader.domain.bookmarks.BookmarkTarget("Bölüm 🌿", location))
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            target.db.close()
            val reopened = Room.databaseBuilder(context, LibraryDatabase::class.java, dbName).build()
            try {
                val restored = RoomLibraryRepository(reopened.books()).findById(id)!!
                assertEquals(BookFormat.FB2, restored.format); assertArrayEquals(original, File(target.books, restored.storageName).readBytes())
                assertEquals(location, RoomReadingStateRepository(reopened).resume(id, book.sha256))
                assertEquals(location, RoomBookmarkRepository(reopened).observe(id, book.sha256).first().single().target.location)
                assertEquals(BookReadingMode.SCROLL, RoomBookReadingModeRepository(reopened).observe(id, book.sha256).first())
                assertEquals(ReadingTextAlignment.JUSTIFY, RoomBookTextAlignmentRepository(reopened).observe(id, book.sha256).first())
                val second = temp.newFile("second.epub")
                com.yaprak.reader.formats.fb2.Fb2Epub.write(second, book.sha256, com.yaprak.reader.formats.fb2.StrictFb2.read(File(target.books, restored.storageName)))
                com.yaprak.reader.formats.epub.EpubPublicationSource(context.contentResolver).open(second).use {
                    assertNotNull(com.yaprak.reader.formats.epub.decodeEpubLocation(location, it.publication.readingOrder))
                }
                assertArrayEquals(first.readBytes(), second.readBytes())
            } finally { reopened.close() }
        } finally { source.db.close(); target.db.close(); context.deleteDatabase(dbName) }
    }
    @Test fun frozenTwentyOneStillRestoresOriginalTxtWithCurrentVersionTwentyTwo() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            val bytes = "Eski TXT 🌿".toByteArray()
            val book = Book(id, "Eski", null, BookFormat.TXT, "old.txt", "$id.txt", bytes.size.toLong(), hash(bytes), 1)
            RoomLibraryRepository(source.db.books()).add(book); File(source.books, book.storageName).writeBytes(bytes)
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            docs.bytes = rewrite(docs.bytes!!) { entries ->
                val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8)).put("formatVersion", 21)
                entries["manifest.json"] = manifest.toString().toByteArray()
            }
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(BookFormat.TXT, RoomLibraryRepository(target.db.books()).findById(id)!!.format)
            assertArrayEquals(bytes, File(target.books, book.storageName).readBytes())
        } finally { source.db.close(); target.db.close() }
    }
}

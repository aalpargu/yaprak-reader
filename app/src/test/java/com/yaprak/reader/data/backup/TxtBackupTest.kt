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
class TxtBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val original = byteArrayOf(0xfe.toByte(), 0xff.toByte()) + "Original 🌿\r\nsecond".toByteArray(Charsets.UTF_16BE)
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
        val book = Book(id, "TXT title", "Author", BookFormat.TXT, "source.txt", id + ".txt", bytes.size.toLong(), hash(bytes), 1)
        val library = RoomLibraryRepository(f.db.books()); library.add(book); File(f.books, book.storageName).writeBytes(bytes)
        library.updateOrganization(id, book.sha256, BookOrganization(favorite = true, tags = listOf("tag")))
        RoomBookNoteRepository(f.db).add(id, book.sha256, "TXT note")
        RoomBookThemeRepository(f.db).save(id, book.sha256, ReadingTheme.SEPIA)
        RoomBookFontSizeRepository(f.db).save(id, book.sha256, 125)
        RoomBookFontRepository(f.db).save(id, book.sha256, ReadingFont.SERIF)
        RoomBookMarginsRepository(f.db).save(id, book.sha256, 150)
        RoomBookLineHeightRepository(f.db).save(id, book.sha256, BookLineHeightChoice.Publisher)
        RoomBookSpacingRepository(f.db).save(id, book.sha256, pair)
        RoomReadingStateRepository(f.db).record(id, book.sha256, ReadingSnapshot(ReadingLocation("{\"href\":\"Text/part0001.xhtml\",\"type\":\"application/xhtml+xml\"}", "Text/part0001.xhtml")))
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
    @Test fun currentBackupSixteenPreservesOriginalTextAllSevenAppearanceFieldsNotesAndProgress() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            val book = seed(source); assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val entries = entries(docs.bytes!!); val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            assertEquals(setOf("manifest.json", "records.json", "books/" + book.storageName), entries.keys)
            assertArrayEquals(original, entries.getValue("books/" + book.storageName))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            val restored = RoomLibraryRepository(target.db.books()).findById(id)!!
            assertEquals(BookFormat.TXT, restored.format); assertEquals(book.sha256, restored.sha256)
            assertTrue(restored.organization.favorite); assertArrayEquals(original, File(target.books, book.storageName).readBytes())
            assertEquals(global, target.settings.settings.first())
            assertEquals(ReadingTheme.SEPIA, RoomBookThemeRepository(target.db).observe(id, book.sha256).first())
            assertEquals(ReadingFont.SERIF, RoomBookFontRepository(target.db).observe(id, book.sha256).first())
            assertEquals(125, RoomBookFontSizeRepository(target.db).observe(id, book.sha256).first())
            assertEquals(150, RoomBookMarginsRepository(target.db).observe(id, book.sha256).first())
            assertEquals(BookLineHeightChoice.Publisher, RoomBookLineHeightRepository(target.db).observe(id, book.sha256).first())
            assertEquals(pair, RoomBookSpacingRepository(target.db).observe(id, book.sha256).first())
            assertEquals("TXT note", target.db.bookNotes().observe(id, book.sha256).first().single().text)
            assertEquals("Text/part0001.xhtml", RoomReadingStateRepository(target.db).resume(id, book.sha256)!!.resourceHref)
            assertTrue(target.db.reading().observed(id, book.sha256).isEmpty()); assertFalse(target.journal.exists())
        } finally { source.db.close(); target.db.close() }
    }
    @Test fun everyOldHeaderRefusesTxtBeforePersistentWritesAndVersionFifteenRecordsRefuseIndependently() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = docs.bytes!!
            val records = entries(original).getValue("records.json")
            assertEquals(BackupFailure.INVALID_ARCHIVE, (runCatching { BackupRecordsCodec.decode(records, 15) }.exceptionOrNull() as BackupProblem).reason)
            for (version in 1..15) {
                docs.bytes = rewrite(original) { entries ->
                    val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
                    manifest.put("formatVersion", version).put("databaseVersion", when (version) {
                        1, 2, 3 -> 12; 4 -> 13; 5 -> 14; 6 -> 15; 7 -> 16; 8 -> 17; 9 -> 18; 10 -> 19; 11, 12, 13 -> 20; 14 -> 21; else -> 22
                    })
                    if (version < 5) { manifest.remove("coverCount"); manifest.remove("covers") }
                    entries["manifest.json"] = manifest.toString().toByteArray()
                }
                assertEquals("version $version", BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target); assertEquals(EpubSettings(), target.settings.settings.first())
            }
        } finally { source.db.close(); target.db.close() }
    }
    @Test fun frozenSixteenStillRestoresTxtWhileCurrentHeaderIsNineteen() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            val book = seed(source); source.backup(docs).create("content://backup")
            docs.bytes = rewrite(docs.bytes!!) { entries ->
                val records = JSONObject(entries.getValue("records.json").toString(Charsets.UTF_8))
                records.getJSONObject("tables").remove("book_pdf_navigation_preferences")
                records.getJSONObject("tables").remove("book_cbz_view_preferences")
                records.getJSONObject("tables").remove("book_epub_reading_mode_overrides")
                records.getJSONObject("tables").remove("book_epub_text_alignment_overrides")
                val encoded = records.toString().toByteArray()
                entries["records.json"] = encoded
                val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
                    .put("formatVersion", 16).put("databaseVersion", 22)
                    .put("recordsSize", encoded.size).put("recordsSha256", hash(encoded))
                entries["manifest.json"] = manifest.toString().toByteArray()
            }
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertArrayEquals(original, File(target.books, book.storageName).readBytes())
            assertEquals(pair, RoomBookSpacingRepository(target.db).observe(id, book.sha256).first())
        } finally { source.db.close(); target.db.close() }
    }
    @Test fun matchingOriginalHashCannotAuthorizeBinaryOrMalformedTextAndRestoreCleansItsStage() = runBlocking {
        for (invalid in listOf("binary\u0000payload".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xff.toByte(), 0xfe.toByte(), 65))) {
            val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
            try {
                seed(source, invalid); assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target)
            } finally { source.db.close(); target.db.close() }
        }
    }
    @Test fun settingsFailureAfterTxtCommitRollsBackOriginalRowsAppearanceNotesAndProgressThenRetries() = runBlocking {
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
        } finally { source.db.close(); target.db.close() }
    }
}

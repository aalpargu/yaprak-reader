package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.settings.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class BookComicViewPreferencesBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val original = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
        repeat(2) { index -> zip.putNextEntry(ZipEntry("${index + 1}.png")); zip.write(com.yaprak.reader.formats.cbz.CbzSpreadFixture.png(80, 120, com.yaprak.reader.formats.cbz.CbzSpreadFixture.colors[index])); zip.closeEntry() }
    } }.toByteArray()
    private val hash = sha(original)
    private val book = Book(id, "Original", null, BookFormat.CBZ, "source.cbz", "$id.cbz", original.size.toLong(), hash, 1, 2)
    private val global = EpubSettings(theme = ReadingTheme.AMOLED, scroll = true,
        paragraphSpacingPercent = 100, wordSpacingPercent = 0)
    private val table = "book_cbz_view_preferences"
    private class Settings(value: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(value)
        var failNext = false; var writes = 0; var failures = 0
        override suspend fun save(settings: EpubSettings) {
            writes++
            if (failures > 0) { failures--; throw IOException("controlled recovery failure") }
            if (failNext) { failNext = false; throw IOException("controlled preference failure") }
            this.settings.value = settings
        }
    }
    private data class Fixture(val db: LibraryDatabase, val books: File, val stage: File, val journal: File, val settings: Settings) {
        fun backup(docs: Documents) = DefaultLibraryBackup(db, books, stage, journal, settings, docs, Mutex())
    }
    private fun fixture(settings: EpubSettings = global): Fixture {
        val root = temporary.newFolder()
        return Fixture(Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), LibraryDatabase::class.java).build(),
            File(root, "books").apply { mkdir() }, File(root, "stage"), File(root, "restore.pending"), Settings(settings))
    }
    private suspend fun seed(f: Fixture, enabled: Boolean = true) {
        RoomLibraryRepository(f.db.books()).add(book); File(f.books, book.storageName).writeBytes(original)
        f.db.bookNotes().add(BookNoteEntity("note", id, hash, "Original note", 1))
        RoomBookComicViewPreferencesRepository(f.db).saveSpread(id, hash, true)
        if (!enabled) RoomBookComicViewPreferencesRepository(f.db).saveSpread(id, hash, false)
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
        } }.toByteArray()
        val epub = Book("22222222-2222-4222-8222-222222222222", "Other", null, BookFormat.EPUB, "other.epub",
            "22222222-2222-4222-8222-222222222222.epub", bytes.size.toLong(), sha(bytes), 1)
        RoomLibraryRepository(f.db.books()).add(epub); File(f.books, epub.storageName).writeBytes(bytes)
        RoomBookThemeRepository(f.db).save(epub.id, epub.sha256, ReadingTheme.SEPIA)
        RoomBookTextAlignmentRepository(f.db).save(epub.id, epub.sha256, ReadingTextAlignment.JUSTIFY)
        RoomBookReadingModeRepository(f.db).save(epub.id, epub.sha256, BookReadingMode.SCROLL)
    }
    private class Documents : BackupDocumentProvider {
        var bytes: ByteArray? = null
        override fun accepts(uri: String) = uri.startsWith("content://")
        override fun openInput(uri: String): InputStream = ByteArrayInputStream(bytes!!)
        override fun openOutput(uri: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() { super.close(); bytes = toByteArray() }
        }
        override fun delete(uri: String): Boolean { bytes = null; return true }
    }
    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes(); zip.closeEntry() }
        }
        return result
    }
    private fun rewrite(bytes: ByteArray, version: Int = 22, edit: (JSONObject) -> Unit = {}): ByteArray {
        val entries = entries(bytes)
        val records = JSONObject(entries.getValue("records.json").toString(Charsets.UTF_8))
        if (version <= 20) records.getJSONObject("tables").remove("book_pdf_navigation_preferences")
        edit(records)
        val encoded = records.toString().toByteArray()
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("formatVersion", version).put("databaseVersion", if (version in 21..22) 26 else if (version == 20) 25 else if (version == 19) 24 else 23)
            .put("recordsSize", encoded.size).put("recordsSha256", sha(encoded))
        entries["records.json"] = encoded; entries["manifest.json"] = manifest.toString().toByteArray()
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            for ((name, content) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
        } }.toByteArray()
    }

    @Test fun currentTwentyRestoresSingleAndDoubleOriginalNotesAndUnrelatedEpubChoices() = runBlocking {
        for (enabled in listOf(true, false)) {
            val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
            try {
                seed(source, enabled); assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
                val manifest = JSONObject(entries(docs.bytes!!).getValue("manifest.json").toString(Charsets.UTF_8))
                assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
                val row = JSONObject(entries(docs.bytes!!).getValue("records.json").toString(Charsets.UTF_8)).getJSONObject("tables").getJSONArray(table).getJSONObject(0)
                assertEquals(setOf("bookId", "contentSha256", "doublePagePreferred"), row.keys().asSequence().toSet())
                assertTrue(row.get("doublePagePreferred") is Int); assertEquals(if (enabled) 1 else 0, row.getInt("doublePagePreferred"))
                assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
                assertEquals(enabled, RoomBookComicViewPreferencesRepository(target.db).observe(id, hash).first().doublePagePreferred)
                assertEquals("Original note", target.db.bookNotes().observe(id, hash).first().single().text)
                val epub = target.db.books().observeAll().first().map { it.book }.single { it.format == "EPUB" }
                assertEquals(BookReadingMode.SCROLL, RoomBookReadingModeRepository(target.db).observe(epub.id, epub.sha256).first())
                assertEquals(ReadingTextAlignment.JUSTIFY, RoomBookTextAlignmentRepository(target.db).observe(epub.id, epub.sha256).first())
                assertArrayEquals(original, File(target.books, book.storageName).readBytes()); assertEquals(global, target.settings.settings.first())
                assertFalse(target.journal.exists())
            } finally { source.db.close(); target.db.close() }
        }
    }
    @Test fun frozenNineteenRetainsOldModesAndCbzOriginalButCannotForgeNewTableEvenEmpty() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val rejected = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val archive = docs.bytes!!
            docs.bytes = rewrite(archive, 19); assertRejected(rejected, docs)
            docs.bytes = rewrite(archive, 19) { it.getJSONObject("tables").put(table, JSONArray()) }; assertRejected(rejected, docs)
            docs.bytes = rewrite(archive, 19) { it.getJSONObject("tables").remove(table) }
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            assertFalse(RoomBookComicViewPreferencesRepository(target.db).observe(id, hash).first().doublePagePreferred)
            assertEquals(0L, target.db.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM $table").simpleQueryForLong())
            val epub = target.db.books().observeAll().first().map { it.book }.single { it.format == "EPUB" }
            assertEquals(BookReadingMode.SCROLL, RoomBookReadingModeRepository(target.db).observe(epub.id, epub.sha256).first())
            assertEquals(ReadingTextAlignment.JUSTIFY, RoomBookTextAlignmentRepository(target.db).observe(epub.id, epub.sha256).first())
            assertArrayEquals(original, File(target.books, book.storageName).readBytes())
        } finally { source.db.close(); target.db.close(); rejected.db.close() }
    }
    @Test fun strictNativeBooleanOwnerHashFormatDuplicateAndShapeAreRejectedBeforeAnyWrites() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val archive = docs.bytes!!
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("doublePagePreferred", -1) }, { it.put("doublePagePreferred", 2) }, { it.put("doublePagePreferred", 2147483648L) },
                { it.put("doublePagePreferred", 0.5) }, { it.put("doublePagePreferred", "1") }, { it.put("doublePagePreferred", true) },
                { it.put("doublePagePreferred", JSONObject.NULL) }, { it.remove("doublePagePreferred") }, { it.put("extra", "field") },
                { it.put("bookId", "missing") }, { it.put("contentSha256", "b".repeat(64)) }, { it.put("contentSha256", hash.uppercase()) })
            for (edit in edits) { docs.bytes = rewrite(archive) { edit(it.getJSONObject("tables").getJSONArray(table).getJSONObject(0)) }; assertRejected(target, docs) }
            docs.bytes = rewrite(archive) { val rows = it.getJSONObject("tables").getJSONArray(table); rows.put(JSONObject(rows.getJSONObject(0).toString())) }; assertRejected(target, docs)
            docs.bytes = rewrite(archive) { it.getJSONObject("tables").remove(table) }; assertRejected(target, docs)
            val epub = source.db.books().observeAll().first().map { it.book }.single { it.format == "EPUB" }
            docs.bytes = rewrite(archive) { it.getJSONObject("tables").getJSONArray(table).getJSONObject(0).put("bookId", epub.id).put("contentSha256", epub.sha256) }; assertRejected(target, docs)
        } finally { source.db.close(); target.db.close() }
    }
    @Test fun corruptSqlBooleanAndMissingKeyOrCascadeCannotProduceAnArchive() = runBlocking {
        for (schema in listOf("corrupt", "missing-key", "missing-cascade")) {
            val source = fixture(); val docs = Documents()
            try {
                seed(source); val sql = source.db.openHelper.writableDatabase
                if (schema == "corrupt") sql.execSQL("UPDATE $table SET doublePagePreferred=2")
                else {
                    sql.execSQL("DROP TABLE $table")
                    val key = if (schema == "missing-key") "" else " PRIMARY KEY"
                    val foreign = if (schema == "missing-cascade") "" else ", FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE"
                    sql.execSQL("CREATE TABLE $table (bookId TEXT NOT NULL$key, contentSha256 TEXT NOT NULL, doublePagePreferred INTEGER NOT NULL$foreign)")
                    sql.execSQL("INSERT INTO $table VALUES(?, ?, 1)", arrayOf(id, hash))
                }
                val expected = if (schema == "corrupt") BackupFailure.INVALID_ARCHIVE else BackupFailure.UNSUPPORTED_VERSION
                assertEquals(BackupResult.Failed(expected), source.backup(docs).create("content://backup")); assertNull(docs.bytes)
                assertArrayEquals(original, File(source.books, book.storageName).readBytes())
            } finally { source.db.close() }
        }
    }
    @Test fun settingsFailureCompensatesBothBooksPreferencesAndFilesBeforeSuccessfulRetry() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); target.settings.failNext = true
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs).restore("content://backup"))
            assertEquals(2, target.settings.writes); assertEquals(EpubSettings(), target.settings.settings.first())
            assertTrue(target.db.books().observeAll().first().isEmpty()); assertTrue(target.books.listFiles().isNullOrEmpty())
            assertEquals(0L, target.db.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM $table").simpleQueryForLong()); assertFalse(target.journal.exists())
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            assertTrue(RoomBookComicViewPreferencesRepository(target.db).observe(id, hash).first().doublePagePreferred)
        } finally { source.db.close(); target.db.close() }
    }
    @Test fun pendingRollbackJournalRetryDoesNotLeaveOrReintroduceImportedChoice() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); target.settings.failures = 2
            assertEquals(BackupResult.Failed(BackupFailure.RECOVERY_REQUIRED), target.backup(docs).restore("content://backup"))
            assertTrue(target.journal.isFile); assertTrue(target.db.books().observeAll().first().isEmpty())
            assertEquals(0L, target.db.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM $table").simpleQueryForLong())
            target.backup(docs).recoverPendingRestore(); target.backup(docs).recoverPendingRestore()
            assertFalse(target.journal.exists()); assertEquals(EpubSettings(), target.settings.settings.first()); assertTrue(target.books.listFiles().isNullOrEmpty())
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            assertTrue(RoomBookComicViewPreferencesRepository(target.db).observe(id, hash).first().doublePagePreferred)
        } finally { source.db.close(); target.db.close() }
    }
    private suspend fun assertRejected(target: Fixture, docs: Documents) {
        assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
        assertTrue(target.db.books().observeAll().first().isEmpty()); assertTrue(target.books.listFiles().isNullOrEmpty())
        assertEquals(0, target.settings.writes); assertFalse(target.journal.exists())
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

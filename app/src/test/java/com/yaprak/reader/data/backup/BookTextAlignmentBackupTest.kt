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
class BookTextAlignmentBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val original = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
    } }.toByteArray()
    private val hash = sha(original)
    private val book = Book(id, "Original", null, BookFormat.EPUB, "source.epub", "$id.epub", original.size.toLong(), hash, 1)
    private val global = EpubSettings(theme = ReadingTheme.AMOLED, textAlignment = ReadingTextAlignment.JUSTIFY,
        paragraphSpacingPercent = 100, wordSpacingPercent = 0)
    private val table = "book_epub_text_alignment_overrides"
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
    private suspend fun seed(f: Fixture, alignment: ReadingTextAlignment = ReadingTextAlignment.PUBLISHER) {
        RoomLibraryRepository(f.db.books()).add(book); File(f.books, book.storageName).writeBytes(original)
        f.db.bookNotes().add(BookNoteEntity("note", id, hash, "Original note", 1))
        RoomBookThemeRepository(f.db).save(id, hash, ReadingTheme.SEPIA)
        RoomBookTextAlignmentRepository(f.db).save(id, hash, alignment)
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
        if (version <= 20) records.getJSONObject("tables").remove("book_pdf_navigation_preferences"); edit(records)
        if (version <= 19) records.getJSONObject("tables").remove("book_cbz_view_preferences")
        if (version <= 18) records.getJSONObject("tables").remove("book_epub_reading_mode_overrides")
        val encoded = records.toString().toByteArray()
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("formatVersion", version).put("databaseVersion", when (version) { 21, 22 -> 26; 20 -> 25; 19 -> 24; 18 -> 23; else -> 22 })
            .put("recordsSize", encoded.size).put("recordsSha256", sha(encoded))
        entries["records.json"] = encoded; entries["manifest.json"] = manifest.toString().toByteArray()
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            for ((name, content) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
        } }.toByteArray()
    }

    @Test fun currentZipRestoresExplicitPublisherOtherPreferencesGlobalAlignmentNotesAndOriginalFile() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val manifest = JSONObject(entries(docs.bytes!!).getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            val row = JSONObject(entries(docs.bytes!!).getValue("records.json").toString(Charsets.UTF_8)).getJSONObject("tables").getJSONArray(table).getJSONObject(0)
            assertEquals(setOf("bookId", "contentSha256", "alignment"), row.keys().asSequence().toSet())
            assertEquals("PUBLISHER", row.getString("alignment"))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(ReadingTextAlignment.PUBLISHER, RoomBookTextAlignmentRepository(target.db).observe(id, hash).first())
            assertEquals(ReadingTheme.SEPIA, RoomBookThemeRepository(target.db).observe(id, hash).first())
            assertEquals(global, target.settings.settings.first())
            assertEquals("Original note", target.db.bookNotes().observe(id, hash).first().single().text)
            assertArrayEquals(original, File(target.books, book.storageName).readBytes()); assertFalse(target.journal.exists())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun frozenSeventeenRestoresAllOldChoicesWithoutInventingAlignmentAndNewTableIsRejectedEvenEmpty() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val rejected = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val archive = docs.bytes!!
            for (rows in listOf(null, JSONArray())) {
                docs.bytes = rewrite(archive, 17) { if (rows != null) it.getJSONObject("tables").put(table, rows) }
                assertRejected(rejected, docs)
            }
            docs.bytes = rewrite(archive, 17) { it.getJSONObject("tables").remove(table) }
            val frozen = JSONObject(entries(docs.bytes!!).getValue("records.json").toString(Charsets.UTF_8))
            assertFalse(frozen.getJSONObject("tables").has(table))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertNull(RoomBookTextAlignmentRepository(target.db).observe(id, hash).first())
            assertEquals(global, target.settings.settings.first())
            assertEquals(ReadingTheme.SEPIA, RoomBookThemeRepository(target.db).observe(id, hash).first())
            assertArrayEquals(original, File(target.books, book.storageName).readBytes())
        } finally { source.db.close(); target.db.close(); rejected.db.close() }
    }

    @Test fun currentStrictShapeEnumTypesDuplicatesHashAndOwnerAreValidatedBeforeAnyWrites() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val archive = docs.bytes!!
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("alignment", "UNKNOWN") }, { it.put("alignment", "START ") }, { it.put("alignment", "start") },
                { it.put("alignment", 0) }, { it.put("alignment", true) }, { it.put("alignment", JSONObject.NULL) },
                { it.remove("alignment") }, { it.put("extra", "field") }, { it.put("bookId", "missing") },
                { it.put("contentSha256", "a".repeat(64)) })
            for (edit in edits) {
                docs.bytes = rewrite(archive) { edit(it.getJSONObject("tables").getJSONArray(table).getJSONObject(0)) }
                assertRejected(target, docs)
            }
            docs.bytes = rewrite(archive) { records ->
                val rows = records.getJSONObject("tables").getJSONArray(table); rows.put(JSONObject(rows.getJSONObject(0).toString()))
            }; assertRejected(target, docs)
            for (format in listOf("PDF", "CBZ")) {
                docs.bytes = rewrite(archive) { records -> records.getJSONObject("tables").getJSONArray("books").getJSONObject(0)
                    .put("format", format).put("originalName", "source." + format.lowercase()).put("storageName", id + "." + format.lowercase()).put("pageCount", 2) }
                assertRejected(target, docs)
            }
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun snapshotRefusesCorruptSavedEnumAndSettingsFailureRollsBackImportedChoiceThenRetrySucceeds() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source, ReadingTextAlignment.START)
            source.db.openHelper.writableDatabase.execSQL("UPDATE $table SET alignment = 'CORRUPT'")
            assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), source.backup(docs).create("content://backup"))
            assertNull(docs.bytes)
            RoomBookTextAlignmentRepository(source.db).save(id, hash, ReadingTextAlignment.START)
            source.backup(docs).create("content://backup"); target.settings.failNext = true
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs).restore("content://backup"))
            assertEquals(2, target.settings.writes)
            assertEquals(EpubSettings(), target.settings.settings.first()); assertTrue(target.db.books().observeAll().first().isEmpty())
            assertEquals(0L, target.db.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM $table").simpleQueryForLong())
            assertTrue(target.books.listFiles().isNullOrEmpty()); assertFalse(target.journal.exists())
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(ReadingTextAlignment.START, RoomBookTextAlignmentRepository(target.db).observe(id, hash).first())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun actualTxtOriginalAndEachExplicitAlignmentRoundTripWithoutGlobalMutation() = runBlocking {
        for (choice in ReadingTextAlignment.entries) {
            val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
            try {
                val bytes = "Türkçe metin 🌿\nikinci satır".toByteArray()
                val txt = book.copy(format = BookFormat.TXT, originalName = "source.txt", storageName = "$id.txt",
                    sha256 = sha(bytes), byteSize = bytes.size.toLong())
                RoomLibraryRepository(source.db.books()).add(txt); File(source.books, txt.storageName).writeBytes(bytes)
                RoomBookTextAlignmentRepository(source.db).save(id, txt.sha256, choice)
                assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
                assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
                assertEquals(choice, RoomBookTextAlignmentRepository(target.db).observe(id, txt.sha256).first())
                assertArrayEquals(bytes, File(target.books, txt.storageName).readBytes())
                assertEquals(global, target.settings.settings.first())
            } finally { source.db.close(); target.db.close() }
        }
    }

    @Test fun interruptedRollbackJournalCanBeRetriedAndCannotKeepOrReintroduceImportedAlignment() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); target.settings.failures = 2
            assertEquals(BackupResult.Failed(BackupFailure.RECOVERY_REQUIRED), target.backup(docs).restore("content://backup"))
            assertTrue(target.journal.isFile)
            assertTrue(target.db.books().observeAll().first().isEmpty())
            assertEquals(0L, target.db.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM $table").simpleQueryForLong())
            target.backup(docs).recoverPendingRestore()
            target.backup(docs).recoverPendingRestore()
            assertFalse(target.journal.exists()); assertEquals(EpubSettings(), target.settings.settings.first())
            assertTrue(target.books.listFiles().isNullOrEmpty())
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(ReadingTextAlignment.PUBLISHER, RoomBookTextAlignmentRepository(target.db).observe(id, hash).first())
        } finally { source.db.close(); target.db.close() }
    }

    private suspend fun assertRejected(target: Fixture, docs: Documents) {
        assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
        assertTrue(target.db.books().observeAll().first().isEmpty()); assertTrue(target.books.listFiles().isNullOrEmpty())
        assertEquals(0, target.settings.writes); assertFalse(target.journal.exists())
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

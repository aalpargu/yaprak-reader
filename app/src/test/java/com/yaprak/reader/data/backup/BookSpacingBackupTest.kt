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
class BookSpacingBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val bytes = byteArrayOf(0x50, 0x4b, 3, 4, 1, 2, 3)
    private val hash = sha(bytes)
    private val book = Book(id, "Original", null, BookFormat.EPUB, "source.epub", id + ".epub", bytes.size.toLong(), hash, 1)
    private val global = EpubSettings(theme = ReadingTheme.AMOLED, paragraphSpacingPercent = 150, wordSpacingPercent = 0)
    private val pair = BookSpacingSettings(BookSpacingChoice.Publisher, BookSpacingChoice.Custom(100))
    private class Settings(value: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(value)
        var failNext = false; var writes = 0
        override suspend fun save(settings: EpubSettings) {
            writes++
            if (failNext) { failNext = false; throw IOException("controlled settings failure") }
            this.settings.value = settings
        }
    }
    private data class Fixture(val db: LibraryDatabase, val books: File, val stage: File, val journal: File, val settings: Settings) {
        fun backup(docs: Documents) = DefaultLibraryBackup(db, books, stage, journal, settings, docs, Mutex())
    }
    private fun fixture(settings: EpubSettings = global): Fixture {
        val root = temporary.newFolder()
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Fixture(Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build(),
            File(root, "books").apply { mkdir() }, File(root, "stage"), File(root, "restore.pending"), Settings(settings))
    }
    private suspend fun seed(f: Fixture, choice: BookSpacingSettings = pair, title: String = "Original") {
        RoomLibraryRepository(f.db.books()).add(book.copy(title = title)); File(f.books, book.storageName).writeBytes(bytes)
        f.db.bookNotes().add(BookNoteEntity("note", id, hash, title + " note", 1))
        RoomBookThemeRepository(f.db).save(id, hash, ReadingTheme.AMOLED)
        RoomBookSpacingRepository(f.db).save(id, hash, choice)
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
    private fun rewrite(bytes: ByteArray, version: Int = 22, edit: (JSONObject) -> Unit): ByteArray {
        val entries = entries(bytes)
        val records = JSONObject(entries.getValue("records.json").toString(Charsets.UTF_8))
        if (version <= 20) records.getJSONObject("tables").remove("book_pdf_navigation_preferences")
        if (version <= 19) records.getJSONObject("tables").remove("book_cbz_view_preferences")
        if (version <= 18) records.getJSONObject("tables").remove("book_epub_reading_mode_overrides")
        if (version <= 17) records.getJSONObject("tables").remove("book_epub_text_alignment_overrides")
        edit(records)
        val rewritten = records.toString().toByteArray()
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("formatVersion", version).put("databaseVersion", if (version <= 13) 20 else if (version == 14) 21 else if (version <= 17) 22 else if (version == 18) 23 else if (version == 19) 24 else if (version == 20) 25 else 26)
            .put("recordsSize", rewritten.size).put("recordsSha256", sha(rewritten))
        entries["records.json"] = rewritten; entries["manifest.json"] = manifest.toString().toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
        } }
        return output.toByteArray()
    }

    @Test fun currentZipRestoresBothModesExactZeroGlobalSettingsFilesAndNotes() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val zeroTarget = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup")
            val zip = entries(docs.bytes!!)
            val manifest = JSONObject(zip.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            val row = JSONObject(zip.getValue("records.json").toString(Charsets.UTF_8)).getJSONObject("tables").getJSONArray("book_epub_spacing_overrides").getJSONObject(0)
            assertEquals(6, row.length()); assertEquals("PUBLISHER", row.getString("paragraphMode")); assertTrue(row.isNull("paragraphSpacingPercent"))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(pair, RoomBookSpacingRepository(target.db).observe(id, hash).first())
            assertEquals(global, target.settings.settings.first())
            assertEquals("Original note", target.db.bookNotes().observe(id, hash).first().single().text)
            assertEquals(ReadingTheme.AMOLED, RoomBookThemeRepository(target.db).observe(id, hash).first())
            assertArrayEquals(bytes, File(target.books, book.storageName).readBytes()); assertFalse(target.journal.exists())
            val zero = BookSpacingSettings(BookSpacingChoice.Custom(0), BookSpacingChoice.Inherit)
            RoomBookSpacingRepository(source.db).save(id, hash, zero)
            source.backup(docs).create("content://backup")
            assertEquals(BackupResult.Restored(1), zeroTarget.backup(docs).restore("content://backup"))
            assertEquals(zero, RoomBookSpacingRepository(zeroTarget.db).observe(id, hash).first())
        } finally { source.db.close(); target.db.close(); zeroTarget.db.close() }
    }

    @Test fun genuineVersionThirteenPreservesGlobalSpacingWithoutInventingBookOverride() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source, BookSpacingSettings())
            source.backup(docs).create("content://backup")
            docs.bytes = rewrite(docs.bytes!!, 13) { it.asPreBookSpacingTables() }
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(global, target.settings.settings.first())
            assertEquals(BookSpacingSettings(), RoomBookSpacingRepository(target.db).observe(id, hash).first())
            assertEquals("Original note", target.db.bookNotes().observe(id, hash).first().single().text)
            assertArrayEquals(bytes, File(target.books, book.storageName).readBytes())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun malformedModesTypesBoundsStepsOwnershipAndForgedOldTableRefuseBeforeWrites() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = docs.bytes!!
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("paragraphMode", "UNKNOWN") }, { it.put("paragraphSpacingPercent", 0) },
                { it.put("wordSpacingPercent", 110) }, { it.put("wordSpacingPercent", 99) },
                { it.put("wordSpacingPercent", "100") }, { it.put("wordSpacingPercent", 1.5) },
                { it.put("wordSpacingPercent", true) }, { it.put("wordSpacingPercent", Long.MAX_VALUE) },
                { it.put("wordSpacingPercent", JSONObject.NULL) },
                { it.put("paragraphMode", "CUSTOM").put("paragraphSpacingPercent", 201) },
                { it.put("bookId", "missing") }, { it.put("contentSha256", "a".repeat(64)) },
                { it.put("paragraphMode", "INHERIT").put("wordMode", "INHERIT").put("wordSpacingPercent", JSONObject.NULL) },
                { it.remove("wordMode") })
            for (edit in edits) {
                docs.bytes = rewrite(original) { records ->
                    edit(records.getJSONObject("tables").getJSONArray("book_epub_spacing_overrides").getJSONObject(0))
                }
                assertRejected(target, docs)
            }
            docs.bytes = rewrite(original, 13) { }
            assertRejected(target, docs)
            docs.bytes = rewrite(original) { it.getJSONObject("tables").getJSONArray("books").getJSONObject(0)
                .put("format", "PDF").put("originalName", "source.pdf").put("storageName", id + ".pdf").put("pageCount", 2) }
            assertRejected(target, docs)
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun settingsFailureRemovesImportedPairAndRestoresPreviousGlobalSettings() = runBlocking {
        val source = fixture(EpubSettings()); val target = fixture(); val docs = Documents()
        try {
            seed(source, pair, "Replacement")
            source.backup(docs).create("content://backup"); target.settings.failNext = true
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs).restore("content://backup"))
            assertEquals(2, target.settings.writes)
            assertEquals(0L, target.db.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM book_epub_spacing_overrides").simpleQueryForLong())
            assertEquals(global, target.settings.settings.first())
            assertTrue(target.db.books().observeAll().first().isEmpty())
            assertTrue(target.db.bookNotes().observe(id, hash).first().isEmpty())
            assertTrue(target.books.listFiles().isNullOrEmpty()); assertFalse(target.journal.exists())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun nonemptyLibraryRefusesRestoreAndKeepsItsBookPairWithoutAttemptingSettingsWrite() = runBlocking {
        val source = fixture(); val target = fixture(); val docs = Documents()
        val previous = BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Publisher)
        try {
            seed(source); seed(target, previous, "Previous"); source.backup(docs).create("content://backup")
            assertEquals(BackupResult.Failed(BackupFailure.NOT_EMPTY), target.backup(docs).restore("content://backup"))
            assertEquals(previous, RoomBookSpacingRepository(target.db).observe(id, hash).first())
            assertEquals("Previous", RoomLibraryRepository(target.db.books()).findById(id)!!.title)
            assertEquals(0, target.settings.writes); assertFalse(target.journal.exists())
        } finally { source.db.close(); target.db.close() }
    }

    private suspend fun assertRejected(target: Fixture, docs: Documents) {
        assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
        assertTrue(target.db.books().observeAll().first().isEmpty()); assertTrue(target.books.listFiles().isNullOrEmpty())
        assertEquals(0, target.settings.writes); assertFalse(target.journal.exists())
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

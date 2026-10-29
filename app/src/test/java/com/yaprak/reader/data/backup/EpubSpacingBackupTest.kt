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
import kotlinx.coroutines.*
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
class EpubSpacingBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val bytes = byteArrayOf(0x50, 0x4b, 3, 4, 1, 2, 3)
    private val hash = sha(bytes)
    private val book = Book(id, "Original", null, BookFormat.EPUB, "source.epub", id + ".epub", bytes.size.toLong(), hash, 1)
    private val custom = EpubSettings(155, ReadingFont.SERIF, 180, 80, ReadingTheme.AMOLED, scroll = true,
        textAlignment = ReadingTextAlignment.START, paragraphSpacingPercent = 200, wordSpacingPercent = 0)
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
    private fun fixture(settings: EpubSettings = custom): Fixture {
        val root = temporary.newFolder()
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Fixture(Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build(),
            File(root, "books").apply { mkdir() }, File(root, "stage"), File(root, "restore.pending"), Settings(settings))
    }
    private suspend fun seed(f: Fixture, title: String = "Original") {
        RoomLibraryRepository(f.db.books()).add(book.copy(title = title)); File(f.books, book.storageName).writeBytes(bytes)
        f.db.bookNotes().add(BookNoteEntity("note", id, hash, title + " note", 1))
        RoomBookThemeRepository(f.db).save(id, hash, ReadingTheme.AMOLED)
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
        if (version <= 20) records.getJSONObject("tables").remove("book_pdf_navigation_preferences"); edit(records.getJSONObject("settings"))
        if (version <= 19) records.getJSONObject("tables").remove("book_cbz_view_preferences")
        if (version <= 18) records.getJSONObject("tables").remove("book_epub_reading_mode_overrides")
        if (version <= 17) records.getJSONObject("tables").remove("book_epub_text_alignment_overrides")
        if (version <= 13) records.asPreBookSpacingTables()
        val rewritten = records.toString().toByteArray()
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("formatVersion", version).put("databaseVersion", if (version <= 13) 20 else if (version == 14) 21 else if (version <= 17) 22 else if (version == 18) 23 else if (version == 19) 24 else if (version == 20) 25 else 26).put("recordsSize", rewritten.size).put("recordsSha256", sha(rewritten))
        entries["records.json"] = rewritten; entries["manifest.json"] = manifest.toString().toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
        } }
        return output.toByteArray()
    }

    @Test fun currentZipRestoresExactSpacingIncludingZeroFilesNotesAndBookOverride() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val manifest = JSONObject(entries(docs.bytes!!).getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            val settings = JSONObject(entries(docs.bytes!!).getValue("records.json").toString(Charsets.UTF_8)).getJSONObject("settings")
            assertEquals(9, settings.length()); assertEquals(200, settings.getInt("paragraphSpacingPercent"))
            assertEquals(0, settings.getInt("wordSpacingPercent"))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(custom, target.settings.settings.first())
            assertEquals(ReadingTheme.AMOLED, RoomBookThemeRepository(target.db).observe(id, hash).first())
            assertEquals("Original note", target.db.bookNotes().observe(id, hash).first().single().text)
            assertArrayEquals(bytes, File(target.books, book.storageName).readBytes()); assertFalse(target.journal.exists())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun genuineVersionTwelveSevenFieldArchiveKeepsAmoledAndOriginalPublisherSpacing() = runBlocking {
        val source = fixture(custom.copy(paragraphSpacingPercent = null, wordSpacingPercent = null))
        val target = fixture(); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup")
            docs.bytes = rewrite(docs.bytes!!, 12) { it.asPreSpacingSettings() }
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(custom.copy(paragraphSpacingPercent = null, wordSpacingPercent = null), target.settings.settings.first())
            assertEquals(ReadingTheme.AMOLED, RoomBookThemeRepository(target.db).observe(id, hash).first())
            assertArrayEquals(bytes, File(target.books, book.storageName).readBytes())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun malformedSpacingAndForgedOldHeaderAreRejectedBeforeAnyRestoreWrites() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = docs.bytes!!
            val mutations: List<(JSONObject) -> Unit> = listOf(
                { it.put("paragraphSpacingPercent", -1) }, { it.put("paragraphSpacingPercent", 201) },
                { it.put("wordSpacingPercent", 101) }, { it.put("wordSpacingPercent", -1) },
                { it.put("wordSpacingPercent", "20") }, { it.put("wordSpacingPercent", true) },
                { it.put("wordSpacingPercent", 1.5) }, { it.put("paragraphSpacingPercent", Long.MAX_VALUE) },
                { it.remove("wordSpacingPercent") }, { it.remove("paragraphSpacingPercent") }, { it.put("futureSpacing", 20) })
            for (edit in mutations) {
                docs.bytes = rewrite(original, edit = edit)
                assertRejectedWithoutWrites(target, docs)
            }
            docs.bytes = rewrite(original, 12) { }
            assertRejectedWithoutWrites(target, docs)
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun failedSettingsRestoreRollsBackPreviousSpacingAndImportedLibraryContents() = runBlocking {
        val source = fixture(EpubSettings()); val target = fixture(); val docs = Documents()
        try {
            seed(source, "Replacement")
            source.backup(docs).create("content://backup"); target.settings.failNext = true
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs).restore("content://backup"))
            assertEquals(2, target.settings.writes)
            assertEquals(custom, target.settings.settings.first())
            assertTrue(target.db.books().observeAll().first().isEmpty())
            assertTrue(target.db.bookNotes().observe(id, hash).first().isEmpty())
            assertTrue(target.books.listFiles().isNullOrEmpty()); assertFalse(target.journal.exists())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun currentAndHistoricalJournalShapesAreCompleteStrictAndKeepNullSeparateFromZero() {
        val encoded = BackupRecordsCodec.encodeSettings(custom)
        assertEquals(custom, BackupRecordsCodec.decodeSettings(encoded))
        assertEquals(custom, BackupRecordsCodec.decodeSettings(encoded, 13))
        for (version in 1..12) {
            val old = BackupRecordsCodec.encodeSettings(custom.copy(theme = ReadingTheme.DARK)).asPreSpacingSettings()
            if (version < 3) old.remove("textAlignment"); if (version < 2) old.remove("scroll")
            val expected = custom.copy(theme = ReadingTheme.DARK, paragraphSpacingPercent = null, wordSpacingPercent = null,
                scroll = version >= 2, textAlignment = if (version >= 3) ReadingTextAlignment.START else ReadingTextAlignment.PUBLISHER)
            assertEquals(expected, BackupRecordsCodec.decodeSettings(old, version))
            assertEquals(expected, BackupRecordsCodec.decodeSettings(old))
        }
        val zero = custom.copy(paragraphSpacingPercent = 0, wordSpacingPercent = 0)
        assertEquals(zero, BackupRecordsCodec.decodeSettings(BackupRecordsCodec.encodeSettings(zero)))
        for (key in listOf("paragraphSpacingPercent", "wordSpacingPercent", "textAlignment", "scroll")) {
            val incomplete = JSONObject(encoded.toString()).apply { remove(key) }
            try { BackupRecordsCodec.decodeSettings(incomplete); fail("Incomplete journal") }
            catch (failure: BackupProblem) { assertEquals(BackupFailure.INVALID_ARCHIVE, failure.reason) }
        }
    }

    private suspend fun assertRejectedWithoutWrites(target: Fixture, docs: Documents) {
        assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
        assertTrue(target.db.books().observeAll().first().isEmpty()); assertTrue(target.books.listFiles().isNullOrEmpty())
        assertEquals(0, target.settings.writes); assertEquals(EpubSettings(), target.settings.settings.first())
        assertFalse(target.journal.exists())
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.domain.vocabulary.*
import java.io.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
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
class AmoledThemeBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val fileBytes = byteArrayOf(0x50, 0x4b, 3, 4, 1, 2, 3)
    private val hash = sha(fileBytes)
    private val book = Book(id, "Original", null, BookFormat.EPUB, "source.epub", id + ".epub", fileBytes.size.toLong(), hash, 1)
    private val amoled = EpubSettings(155, ReadingFont.SERIF, 180, 80, ReadingTheme.AMOLED, scroll = true,
        textAlignment = ReadingTextAlignment.START)
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
    private fun fixture(settings: EpubSettings = amoled): Fixture {
        val root = temporary.newFolder()
        return Fixture(Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build(),
            File(root, "books").apply { mkdir() }, File(root, "stage"), File(root, "restore.pending"), Settings(settings))
    }
    private fun reviews(db: LibraryDatabase) = RoomWordReviewRepository(db,
        clock = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC), zone = { ZoneOffset.UTC })
    private suspend fun seed(f: Fixture, theme: ReadingTheme = ReadingTheme.AMOLED, title: String = "Original") {
        RoomLibraryRepository(f.db.books()).add(book.copy(title = title))
        File(f.books, book.storageName).writeBytes(fileBytes)
        RoomBookThemeRepository(f.db).save(id, hash, theme)
        f.db.bookNotes().add(BookNoteEntity("note", id, hash, title + " note", 1))
        f.db.savedWords().add(SavedWordEntity("word", id, hash, "leaf", "yaprak", 1))
        val repository = reviews(f.db)
        repository.rate(WordReviewCommand(UUID.randomUUID().toString(), repository.load().cards.single(), WordRating.GOOD))
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
    private fun rewrite(bytes: ByteArray, version: Int? = null, edit: (JSONObject) -> Unit = {}): ByteArray {
        val entries = entries(bytes)
        val records = JSONObject(entries.getValue("records.json").toString(Charsets.UTF_8))
        if (version != null && version <= 12) records.asPreBookSpacingTables().getJSONObject("settings").asPreSpacingSettings()
        edit(records)
        val rewritten = records.toString().toByteArray()
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("recordsSize", rewritten.size).put("recordsSha256", sha(rewritten))
        if (version != null) manifest.put("formatVersion", version).put("databaseVersion", if (version <= 13) 20 else if (version == 14) 21 else 22)
        entries["records.json"] = rewritten; entries["manifest.json"] = manifest.toString().toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
        } }
        return output.toByteArray()
    }

    @Test fun currentZipPreservesGlobalAndBookAmoledFilesNotesAndWordReviewHistory() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); val expected = reviews(source.db).load()
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val manifest = JSONObject(entries(docs.bytes!!).getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(amoled, target.settings.settings.first())
            assertEquals(ReadingTheme.AMOLED, RoomBookThemeRepository(target.db).observe(id, hash).first())
            assertEquals(expected, reviews(target.db).load())
            assertEquals("Original note", target.db.bookNotes().observe(id, hash).first().single().text)
            assertArrayEquals(fileBytes, File(target.books, book.storageName).readBytes()); assertFalse(target.journal.exists())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun genuineVersionElevenShapeStillRestoresTheExactDarkThemeAndReviewHistory() = runBlocking {
        val source = fixture(amoled.copy(theme = ReadingTheme.DARK)); val target = fixture(); val docs = Documents()
        try {
            seed(source, ReadingTheme.DARK); val expected = reviews(source.db).load()
            source.backup(docs).create("content://backup"); docs.bytes = rewrite(docs.bytes!!, 11)
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(amoled.copy(theme = ReadingTheme.DARK), target.settings.settings.first())
            assertEquals(ReadingTheme.DARK, RoomBookThemeRepository(target.db).observe(id, hash).first())
            assertEquals(expected, reviews(target.db).load()); assertArrayEquals(fileBytes, File(target.books, book.storageName).readBytes())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun everyHistoricalSettingsShapeRejectsAmoledAndRetainsItsOriginalThemeNames() {
        for (version in 1..11) {
            fun settings(theme: ReadingTheme) = BackupRecordsCodec.encodeSettings(amoled.copy(theme = theme)).asPreSpacingSettings().apply {
                if (version <= 2) remove("textAlignment"); if (version == 1) remove("scroll")
            }
            try { BackupRecordsCodec.decodeSettings(settings(ReadingTheme.AMOLED), version); fail("Forged legacy AMOLED") }
            catch (failure: BackupProblem) { assertEquals(BackupFailure.INVALID_ARCHIVE, failure.reason) }
            for (theme in listOf(ReadingTheme.SYSTEM, ReadingTheme.LIGHT, ReadingTheme.SEPIA, ReadingTheme.DARK)) {
                assertEquals(theme, BackupRecordsCodec.decodeSettings(settings(theme), version).theme)
            }
        }
        assertEquals(amoled, BackupRecordsCodec.decodeSettings(BackupRecordsCodec.encodeSettings(amoled).asPreSpacingSettings(), 12))
    }

    @Test fun forgedVersionElevenGlobalOrBookAmoledFailsBeforeAnyRestoreWrite() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = docs.bytes!!
            for (global in listOf(false, true)) {
                docs.bytes = rewrite(original, 11) { records ->
                    if (!global) records.getJSONObject("settings").put("theme", "DARK")
                    if (global) records.getJSONObject("tables").getJSONArray("book_epub_theme_overrides").getJSONObject(0).put("theme", "DARK")
                }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertTrue(target.db.books().observeAll().first().isEmpty()); assertTrue(target.books.listFiles().isNullOrEmpty())
                assertEquals(0, target.settings.writes); assertEquals(EpubSettings(), target.settings.settings.first())
                assertFalse(target.journal.exists())
            }
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun settingsFailureRestoresPreviousAmoledAndRemovesImportedBookFilesAndHistory() = runBlocking {
        val source = fixture(EpubSettings(theme = ReadingTheme.SEPIA)); val target = fixture(); val docs = Documents()
        try {
            seed(source, ReadingTheme.SEPIA, "Replacement")
            val expected = reviews(target.db).load()
            source.backup(docs).create("content://backup"); target.settings.failNext = true
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs).restore("content://backup"))
            assertEquals(2, target.settings.writes)
            assertEquals(amoled, target.settings.settings.first())
            assertNull(RoomBookThemeRepository(target.db).observe(id, hash).first())
            assertTrue(target.db.books().observeAll().first().isEmpty())
            assertTrue(target.db.bookNotes().observe(id, hash).first().isEmpty())
            assertEquals(expected, reviews(target.db).load()); assertTrue(target.books.listFiles().isNullOrEmpty())
            assertFalse(target.journal.exists())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun unversionedCurrentJournalAcceptsAmoledAndOldShapesKeepDefaultsWhileUnknownNamesRefuse() {
        val current = BackupRecordsCodec.encodeSettings(amoled)
        assertEquals(amoled, BackupRecordsCodec.decodeSettings(current))
        for (shape in 1..2) {
            val old = BackupRecordsCodec.encodeSettings(amoled.copy(theme = ReadingTheme.DARK)).asPreSpacingSettings()
            old.remove("textAlignment"); if (shape == 1) old.remove("scroll")
            val expected = amoled.copy(theme = ReadingTheme.DARK, textAlignment = ReadingTextAlignment.PUBLISHER, scroll = shape >= 2)
            assertEquals(expected, BackupRecordsCodec.decodeSettings(old))
        }
        try { BackupRecordsCodec.decodeSettings(current.put("theme", "UNKNOWN")); fail("Unknown journal theme") }
        catch (failure: BackupProblem) { assertEquals(BackupFailure.INVALID_ARCHIVE, failure.reason) }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

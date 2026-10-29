package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.vocabulary.*
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.settings.*
import java.io.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
@Config(sdk = [28], application = Application::class)
class WordReviewBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val bytes = byteArrayOf(0x50, 0x4b, 3, 4, 1, 2, 3)
    private val id = "11111111-1111-4111-8111-111111111111"
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val book = Book(id, "Kitap", null, BookFormat.EPUB, "source.epub", "$id.epub", bytes.size.toLong(), sha, 1)
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private fun repo(db: LibraryDatabase) = RoomWordReviewRepository(db, clock = Clock.fixed(now, ZoneOffset.UTC), zone = { ZoneOffset.UTC })
    private data class Fixture(val db: LibraryDatabase, val name: String, val books: File, val stage: File, val journal: File, val settings: Settings) {
        fun backup(documents: Documents) = DefaultLibraryBackup(db, books, stage, journal, settings, documents, Mutex())
    }
    private fun open(name: String) = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
    private fun fixture(): Fixture {
        val directory = temporary.newFolder(); val name = "srb-${UUID.randomUUID().toString().take(8)}.db"
        return Fixture(open(name), name, File(directory, "books").apply { mkdir() }, File(directory, "stage"), File(directory, "restore.pending"), Settings())
    }
    private fun close(f: Fixture) { f.db.close(); context.deleteDatabase(f.name) }
    private suspend fun seed(f: Fixture): WordReviewEvent {
        RoomLibraryRepository(f.db.books()).add(book); File(f.books, book.storageName).writeBytes(bytes)
        f.db.savedWords().add(SavedWordEntity("word", id, sha, "leaf", "yaprak", 7))
        f.db.bookNotes().add(BookNoteEntity("note", id, sha, "Preserved note", 8))
        val first = repo(f.db).rate(WordReviewCommand(UUID.randomUUID().toString(), repo(f.db).load().cards.single(), WordRating.GOOD))
        RoomSavedWordRepository(f.db).update(id, sha, "word", "leaf", "sayfa")
        repo(f.db).rate(WordReviewCommand(UUID.randomUUID().toString(), repo(f.db).load().cards.single(), WordRating.EASY))
        return first
    }
    @Test fun realZipRestoresExactSchedulesPreviousMeaningEventsNotesAndWordsAfterReopen() = runBlocking {
        val source = fixture(); var target = fixture(); val documents = Documents()
        try {
            val first = seed(source); val expected = repo(source.db).load()
            assertEquals(BackupResult.Created(1), source.backup(documents).create("content://backup"))
            val zip = entries(documents.bytes!!); val manifest = JSONObject(zip.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            assertEquals(BackupResult.Restored(1), target.backup(documents).restore("content://backup"))
            target.db.close(); target = target.copy(db = open(target.name))
            assertEquals(expected, repo(target.db).load()); assertTrue(expected.history.any { it.event == first && it.previousContent })
            assertEquals("sayfa", RoomSavedWordRepository(target.db).observe(id, sha).first().single().turkish)
            assertEquals(1L, RoomSavedWordRepository(target.db).observe(id, sha).first().single().revision)
            assertEquals("Preserved note", target.db.bookNotes().observe(id, sha).first().single().text)
            assertArrayEquals(bytes, File(target.books, book.storageName).readBytes()); assertFalse(target.journal.exists())
            target.db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { close(source); close(target) }
    }
    @Test fun allTenLegacyShapesHydrateEmptyReviewHistoryAndRevisionZeroWithoutDroppingOldText() = runBlocking {
        val source = fixture()
        try {
            seed(source)
            val snapshot = withContext(Dispatchers.IO) { BackupRecordsCodec.snapshot(source.db, EpubSettings()) }
            for (version in 1..10) {
                val root = JSONObject(BackupRecordsCodec.encode(snapshot).toString(Charsets.UTF_8)); val tables = root.getJSONObject("tables")
                stripWordReviewTables(tables)
                if (version <= 9) tables.remove("pdf_annotations")
                if (version <= 8) tables.remove("epub_range_passages")
                if (version <= 7) tables.remove("book_pdf_view_preferences")
                if (version <= 6) tables.remove("reading_duration_receipts")
                if (version <= 5) { val rows = tables.getJSONArray("epub_passages"); for (i in 0 until rows.length()) rows.getJSONObject(i).apply { remove("highlightColor"); remove("attachedNote"); remove("revision") } }
                if (version <= 4) tables.remove("book_covers")
                if (version <= 3) tables.remove("book_organizations")
                root.asPreBookSpacingTables().getJSONObject("settings").asPreSpacingSettings().apply { if (version <= 2) remove("textAlignment"); if (version == 1) remove("scroll") }
                val decoded = BackupRecordsCodec.decode(root.toString().toByteArray(), version)
                assertTrue(decoded.rows.getValue("word_review_events").isEmpty()); assertTrue(decoded.rows.getValue("word_schedules").isEmpty())
                assertEquals(0L, decoded.rows.getValue("saved_words").single().getLong("revision"))
                assertEquals("sayfa", decoded.rows.getValue("saved_words").single().getString("turkish"))
                val manifest = JSONObject(BackupRecordsCodec.encodeManifest(snapshot, byteArrayOf(1), "a".repeat(64)).toString(Charsets.UTF_8))
                    .put("formatVersion", version).put("databaseVersion", when (version) {
                        1,2,3 -> 12; 4 -> 13; 5 -> 14; 6 -> 15; 7 -> 16; 8 -> 17; 9 -> 18; else -> 19
                    })
                if (version <= 4) { manifest.remove("coverCount"); manifest.remove("covers") }
                assertEquals(version, BackupRecordsCodec.decodeManifest(manifest.toString().toByteArray()).formatVersion)
            }
        } finally { close(source) }
    }
    @Test fun alteredEventDetachedScheduleOrForgedLegacyHeaderFailsBeforeRestoreWritesAnyFilesOrBooks() = runBlocking {
        val source = fixture(); val target = fixture(); val documents = Documents()
        try {
            seed(source); source.backup(documents).create("content://backup"); val original = documents.bytes!!
            val edits: List<(JSONObject, JSONObject) -> Unit> = listOf(
                { _, r -> r.getJSONObject("tables").getJSONArray("word_review_events").getJSONObject(0).put("english", "forged") },
                { _, r -> r.getJSONObject("tables").getJSONArray("word_review_events").getJSONObject(0).put("version", 3) },
                { _, r -> r.getJSONObject("tables").getJSONArray("word_schedules").getJSONObject(0).put("lastEventId", UUID.randomUUID().toString()) },
                { _, r -> r.getJSONObject("tables").getJSONArray("word_schedules").getJSONObject(0).put("wordId", "missing") },
                { _, r -> r.getJSONObject("tables").getJSONArray("word_review_events").getJSONObject(0).put("dueAt", now.toEpochMilli() + 1) },
                { m, _ -> m.put("formatVersion", 10).put("databaseVersion", 19) },
            )
            for (edit in edits) {
                val zip = entries(original); val root = JSONObject(zip.getValue("records.json").toString(Charsets.UTF_8)); val manifest = JSONObject(zip.getValue("manifest.json").toString(Charsets.UTF_8))
                edit(manifest, root)
                val records = root.toString().toByteArray(); manifest.put("recordsSize", records.size).put("recordsSha256", hash(records))
                zip["records.json"] = records; zip["manifest.json"] = manifest.toString().toByteArray(); documents.bytes = pack(zip)
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(documents).restore("content://backup"))
                assertTrue(target.db.books().observeAll().first().isEmpty()); assertTrue(target.books.listFiles().isNullOrEmpty()); assertFalse(target.journal.exists())
            }
        } finally { close(source); close(target) }
    }
    @Test fun weakenedForeignKeyOrUniqueReviewIndexIsRejectedBeforeBackupPublication() = runBlocking {
        val source = fixture(); val documents = Documents()
        try {
            seed(source); source.db.openHelper.writableDatabase.execSQL("DROP INDEX index_word_review_events_wordId_version")
            assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), source.backup(documents).create("content://backup"))
            assertNull(documents.bytes)
        } finally { close(source) }
    }
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun entries(value: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { output ->
        ZipInputStream(ByteArrayInputStream(value)).use { zip -> while (true) { val entry = zip.nextEntry ?: break; output[entry.name] = zip.readBytes(); zip.closeEntry() } }
    }
    private fun pack(values: Map<String, ByteArray>) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> values.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() } }
    }.toByteArray()
    private class Settings : EpubSettingsRepository {
        override val settings = MutableStateFlow(EpubSettings())
        override suspend fun save(settings: EpubSettings) { this.settings.value = settings }
    }
    private class Documents : BackupDocumentProvider {
        var bytes: ByteArray? = null
        override fun accepts(uri: String) = uri.startsWith("content://")
        override fun openInput(uri: String): InputStream = ByteArrayInputStream(bytes!!)
        override fun openOutput(uri: String): OutputStream = object : ByteArrayOutputStream() { override fun close() { super.close(); bytes = toByteArray() } }
        override fun delete(uri: String): Boolean { bytes = null; return true }
    }
}

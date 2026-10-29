package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.BookNoteEntity
import com.yaprak.reader.data.persistence.EpubPassageEntity
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.ReadingProgressEntity
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.backup.BackupResult
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookOrganization
import com.yaprak.reader.domain.library.BookReadingStatus
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.EpubSettingsRepository
import com.yaprak.reader.domain.settings.ReadingTextAlignment
import com.yaprak.reader.domain.settings.ReadingTheme
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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
@Config(sdk = [35], application = Application::class)
class BookOrganizationBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
    private val id = "11111111-1111-4111-8111-111111111111"
    private val book = Book(id, "Kitap", "Yazar", BookFormat.EPUB, "source.epub",
        "$id.epub", bytes.size.toLong(), hash(bytes), 11)
    private val organization = BookOrganization(BookReadingStatus.READING, true,
        "Türkçe seri", "Teknik", listOf("Öğrenme", "Alıntı\" ve işaret"), listOf("Üniversite", "Başvuru"))
    private val preferences = EpubSettings(theme = ReadingTheme.SEPIA, scroll = true,
        textAlignment = ReadingTextAlignment.JUSTIFY)

    @Test fun importedPublicationLabelsRoundtripWithoutInventingCompletionOrReadingProgress() = runBlocking {
        val source = fixture("imported-source", preferences)
        val target = fixture("imported-target")
        val docs = MemoryDocuments()
        val initial = BookOrganization(series = "Yaprak Serisi", tags = listOf("Yazılım"), collections = listOf("Teknik"))
        try {
            source.library.addImported(book, initial)
            File(source.books, book.storageName).writeBytes(bytes)
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(book.copy(organization = initial), target.library.findById(id))
            assertArrayEquals(bytes, File(target.books, book.storageName).readBytes())
            assertEquals(0L, count(target.db, "book_completion_events"))
            assertEquals(0L, count(target.db, "reading_progress"))
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun bulkLabelChangesRoundtripWithOriginalBytesProgressNotesAndCompletionHistory() = runBlocking {
        val source = fixture("labels-source", preferences)
        val target = fixture("labels-target")
        val docs = MemoryDocuments()
        try {
            seed(source)
            source.library.updateOrganization(id, book.sha256, organization.copy(readingStatus = BookReadingStatus.COMPLETED))
            val labels = com.yaprak.reader.data.persistence.RoomLibraryLabelsRepository(source.db)
            val tag = com.yaprak.reader.domain.library.LibraryLabelKind.TAG
            val collection = com.yaprak.reader.domain.library.LibraryLabelKind.COLLECTION
            labels.change(tag, "Öğrenme", "Ders", labels.observe().first().matching(tag, "Öğrenme"))
            labels.change(collection, "Üniversite", null, labels.observe().first().matching(collection, "Üniversite"))
            val expected = source.library.findById(id)!!
            assertEquals(1L, count(source.db, "book_completion_events"))
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(expected, target.library.findById(id))
            assertArrayEquals(bytes, File(target.books, book.storageName).readBytes())
            assertEquals("saved", target.db.reading().resume(id, book.sha256)?.locatorJson)
            listOf("book_notes", "epub_passages", "book_completion_events").forEach { assertEquals(it, 1L, count(target.db, it)) }
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `current ZIP roundtrip preserves organization and all linked records`() = runBlocking {
        val source = fixture("roundtrip-source", preferences)
        val target = fixture("roundtrip-target")
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val archive = entries(requireNotNull(docs.bytes))
            val manifest = JSONObject(archive.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(26, manifest.getInt("formatVersion"))
            assertEquals(26, manifest.getInt("databaseVersion"))
            val rows = JSONObject(archive.getValue("records.json").toString(Charsets.UTF_8))
                .getJSONObject("tables").getJSONArray("book_organizations")
            assertEquals(1, rows.length())
            assertEquals("READING", rows.getJSONObject(0).getString("readingStatus"))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(book.copy(organization = organization), target.library.findById(id))
            assertEquals(preferences, target.settings.settings.first())
            assertArrayEquals(bytes, File(target.books, book.storageName).readBytes())
            listOf("reading_progress", "book_notes", "epub_passages", "book_organizations")
                .forEach { assertEquals(it, 1L, count(target.db, it)) }
            assertEquals("saved", target.db.reading().resume(id, book.sha256)?.locatorJson)
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `real legacy v1 v2 v3 ZIP records restore with default organization`() = runBlocking {
        val source = fixture("legacy-source", preferences)
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val original = requireNotNull(docs.bytes)
            for (version in 1..3) {
                val target = fixture("legacy-target-$version")
                try {
                    docs.bytes = rewrite(original, version, 12) { records ->
                        records.getJSONObject("tables").remove("book_organizations")
                        records.getJSONObject("tables").remove("book_covers")
                        records.asPreBookSpacingTables().getJSONObject("settings").asPreSpacingSettings().apply {
                            if (version < 3) remove("textAlignment")
                            if (version < 2) remove("scroll")
                        }
                    }
                    assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
                    assertEquals(book, target.library.findById(id))
                    assertEquals(0L, count(target.db, "book_organizations"))
                    assertEquals(preferences.copy(scroll = version >= 2,
                        textAlignment = if (version >= 3) ReadingTextAlignment.JUSTIFY else ReadingTextAlignment.PUBLISHER),
                        target.settings.settings.first())
                    assertEquals(1L, count(target.db, "epub_passages"))
                    assertArrayEquals(bytes, File(target.books, book.storageName).readBytes())
                    assertTrue(target.library.updateOrganization(id, book.sha256, organization))
                    assertEquals(organization, target.library.findByHash(book.sha256)?.organization)
                } finally { target.db.close() }
            }
        } finally { source.db.close() }
    }

    @Test fun `old header cannot conceal new table and format database pairs remain strict before writes`() = runBlocking {
        val source = fixture("headers-source", preferences)
        val previous = EpubSettings(theme = ReadingTheme.DARK)
        val target = fixture("headers-target", previous)
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val original = requireNotNull(docs.bytes)
            listOf(
                rewrite(original, 3, 12) {} to BackupFailure.INVALID_ARCHIVE,
                rewrite(original, 4, 13) { it.getJSONObject("tables").apply {
                    remove("book_covers"); remove("book_organizations") } } to BackupFailure.INVALID_ARCHIVE,
                rewrite(original, 3, 13) {} to BackupFailure.UNSUPPORTED_VERSION,
                rewrite(original, 4, 12) {} to BackupFailure.UNSUPPORTED_VERSION,
            ).forEach { (archive, reason) ->
                docs.bytes = archive
                assertEquals(BackupResult.Failed(reason), target.backup(docs).restore("content://backup"))
                assertEmpty(target, previous)
                assertEquals(0, target.settings.saveAttempts)
            }
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `malformed or noncanonical organization is rejected before files database or settings commit`() = runBlocking {
        val source = fixture("invalid-source", preferences)
        val previous = EpubSettings(theme = ReadingTheme.DARK)
        val target = fixture("invalid-target", previous)
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val original = requireNotNull(docs.bytes)
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("favorite", true) }, { it.put("favorite", 2) },
                { it.put("readingStatus", "UNKNOWN") }, { it.put("series", "s".repeat(201)) },
                { it.put("genre", "a\nb") }, { it.put("tagsJson", "[1]") },
                { it.put("tagsJson", "[\"\"]") }, { it.put("tagsJson", "[\"Işık\",\"ışık\"]") },
                { it.put("tagsJson", "[\"one,two\"]") },
                { it.put("tagsJson", JSONArray(List(51) { index -> "Tag $index" }).toString()) },
                { it.put("collectionsJson", JSONArray(listOf("x".repeat(101))).toString()) },
                { it.put("contentSha256", "a".repeat(64)) }, { it.put("futureColumn", "x") },
                { it.remove("tagsJson") },
            )
            edits.forEach { edit ->
                docs.bytes = rewrite(original, 5, 14) { records ->
                    edit(records.getJSONObject("tables").getJSONArray("book_organizations").getJSONObject(0))
                }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE),
                    target.backup(docs).restore("content://backup"))
                assertEmpty(target, previous)
                assertEquals(0, target.settings.saveAttempts)
            }
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `settings failure rolls organization back through cascade and a later restore succeeds`() = runBlocking {
        val source = fixture("rollback-source", preferences)
        val previous = EpubSettings(theme = ReadingTheme.DARK, textAlignment = ReadingTextAlignment.START)
        val target = fixture("rollback-target", previous)
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            target.settings.failNextSave = true
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs).restore("content://backup"))
            assertEmpty(target, previous)
            assertEquals(2, target.settings.saveAttempts)
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(organization, target.library.findById(id)?.organization)
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `reopened journal recovery preserves unrelated records`() = runBlocking {
        val parent = temporary.newFolder("recovery")
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "orc-${System.nanoTime().toString().takeLast(8)}.db"
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        val previous = EpubSettings(theme = ReadingTheme.DARK, textAlignment = ReadingTextAlignment.START)
        val settings = MemorySettings(preferences)
        val books = File(parent, "books").apply { mkdir() }
        val journal = File(parent, "restore.pending")
        val other = book.copy(id = "other", storageName = "other.epub", sha256 = "other-hash")
        try {
            val first = Fixture(open(), books, File(parent, "stage"), journal, settings)
            try {
                seed(first)
                first.library.add(other)
                assertTrue(first.library.updateOrganization(other.id, other.sha256,
                    BookOrganization(BookReadingStatus.TO_READ)))
                File(books, other.storageName).writeBytes(bytes)
                journal.writeText(JSONObject().put("books", JSONArray().put(JSONObject()
                    .put("id", id).put("storageName", book.storageName).put("format", "EPUB")
                    .put("byteSize", bytes.size).put("sha256", book.sha256)))
                    .put("previousSettings", BackupRecordsCodec.encodeSettings(previous)).toString(), Charsets.UTF_8)
            } finally { first.db.close() }
            val reopened = Fixture(open(), books, File(parent, "stage"), journal, settings)
            try {
                reopened.backup(MemoryDocuments()).recoverPendingRestore()
                assertNull(reopened.library.findById(id))
                assertEquals(BookOrganization(BookReadingStatus.TO_READ), reopened.library.findById(other.id)?.organization)
                assertEquals(1L, count(reopened.db, "book_organizations"))
                assertEquals(0L, count(reopened.db, "book_notes"))
                assertFalse(File(books, book.storageName).exists())
                assertTrue(File(books, other.storageName).exists())
                assertFalse(journal.exists())
                assertEquals(previous, settings.settings.first())
            } finally { reopened.db.close() }
        } finally { context.deleteDatabase(name) }
    }

    private data class Fixture(val db: LibraryDatabase, val books: File, val stage: File,
        val journal: File, val settings: MemorySettings) {
        val library = RoomLibraryRepository(db.books())
        fun backup(docs: BackupDocumentProvider) = DefaultLibraryBackup(db, books, stage, journal, settings, docs, Mutex())
    }

    private fun fixture(name: String, settings: EpubSettings = EpubSettings()): Fixture {
        val parent = temporary.newFolder(name)
        return Fixture(Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(),
            LibraryDatabase::class.java).build(), File(parent, "books").apply { mkdir() },
            File(parent, "stage"), File(parent, "restore.pending"), MemorySettings(settings))
    }

    private suspend fun seed(fixture: Fixture) {
        File(fixture.books, book.storageName).writeBytes(bytes)
        fixture.library.add(book)
        assertTrue(fixture.library.updateOrganization(id, book.sha256, organization))
        fixture.db.reading().save(ReadingProgressEntity(id, book.sha256, 1, "saved", "chapter", 50))
        fixture.db.bookNotes().add(BookNoteEntity("note", id, book.sha256, "Ders notu", 60))
        fixture.db.epubPassages().add(EpubPassageEntity("passage", id, book.sha256, 1,
            "chapter", "b".repeat(64), 0, 4, "leaf", 70))
    }

    private suspend fun assertEmpty(fixture: Fixture, previous: EpubSettings) {
        listOf("books", "book_organizations", "reading_progress", "book_notes", "epub_passages")
            .forEach { assertEquals(it, 0L, count(fixture.db, it)) }
        assertTrue(fixture.books.listFiles().isNullOrEmpty())
        assertTrue(fixture.stage.listFiles().isNullOrEmpty())
        assertFalse(fixture.journal.exists())
        assertEquals(previous, fixture.settings.settings.first())
    }

    private suspend fun count(db: LibraryDatabase, table: String): Long = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.compileStatement("SELECT count(*) FROM $table").simpleQueryForLong()
    }

    private fun entries(archive: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun rewrite(archive: ByteArray, version: Int, databaseVersion: Int,
        edit: (JSONObject) -> Unit): ByteArray {
        val entries = entries(archive)
        val records = JSONObject(entries.getValue("records.json").toString(Charsets.UTF_8))
        if (version in 1..9) records.getJSONObject("tables").also(::stripWordReviewTables).remove("pdf_annotations")
        if (version in 1..8) records.getJSONObject("tables").remove("epub_range_passages")
        if (version in 1..7) records.getJSONObject("tables").remove("book_pdf_view_preferences")
        if (version in 1..6) records.getJSONObject("tables").remove("reading_duration_receipts")
        if (version in 1..5) {
            val passages = records.getJSONObject("tables").getJSONArray("epub_passages")
            for (index in 0 until passages.length()) passages.getJSONObject(index).apply {
                remove("highlightColor"); remove("attachedNote"); remove("revision")
            }
        }
        edit(records)
        val encoded = records.toString().toByteArray(Charsets.UTF_8)
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("formatVersion", version).put("databaseVersion", databaseVersion)
            .put("recordsSize", encoded.size).put("recordsSha256", hash(encoded))
        if (version <= 4) { manifest.remove("coverCount"); manifest.remove("covers") }
        entries["records.json"] = encoded
        entries["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class MemorySettings(initial: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(initial)
        var failNextSave = false
        var saveAttempts = 0
        override suspend fun save(settings: EpubSettings) {
            saveAttempts++
            if (failNextSave) { failNextSave = false; throw IOException("simulated settings failure") }
            this.settings.value = settings
        }
    }

    private class MemoryDocuments : BackupDocumentProvider {
        var bytes: ByteArray? = null
        override fun accepts(uri: String) = uri.startsWith("content://")
        override fun openInput(uri: String): InputStream = ByteArrayInputStream(requireNotNull(bytes))
        override fun openOutput(uri: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() { super.close(); bytes = toByteArray() }
        }
        override fun delete(uri: String): Boolean { bytes = null; return true }
    }
}

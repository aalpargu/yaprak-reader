package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.BookCoverEntity
import com.yaprak.reader.data.persistence.BookNoteEntity
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
import com.yaprak.reader.domain.settings.ReadingTheme
import com.yaprak.reader.fixtures.CoverTestImages
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookCoverBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bookBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
    private val image = CoverTestImages.png(8, 12)
    private val id = "11111111-1111-4111-8111-111111111111"
    private val coverId = "33333333-3333-4333-8333-333333333333"
    private val book = Book(id, "Ders", "Yazar", BookFormat.EPUB, "source.epub",
        "$id.epub", bookBytes.size.toLong(), hash(bookBytes), 12)
    private val cover = ArchiveCover(id, book.sha256, coverId, "$coverId.png", "image/png",
        image.size.toLong(), hash(image), 8, 12)
    private val organization = BookOrganization(BookReadingStatus.READING, true,
        tags = listOf("Ders"), collections = listOf("Okul"))
    private val restored = EpubSettings(theme = ReadingTheme.SEPIA, scroll = true)

    @Test fun `current cover roundtrip`() = runBlocking {
        val source = fixture("src", restored)
        val target = fixture("dst")
        val docs = Documents()
        try {
            seed(source)
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val archive = entries(requireNotNull(docs.bytes))
            val manifest = json(archive.getValue("manifest.json"))
            assertEquals(26, manifest.getInt("formatVersion"))
            assertEquals(26, manifest.getInt("databaseVersion"))
            assertEquals(1, manifest.getInt("coverCount"))
            assertEquals(cover, BackupRecordsCodec.decodeCover(manifest.getJSONArray("covers").getJSONObject(0)))
            assertArrayEquals(image, archive.getValue(cover.entryName))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            val result = requireNotNull(target.library.findById(id))
            assertEquals(coverId, result.customCover?.id)
            assertEquals(organization, result.organization)
            assertEquals(book.sha256, result.sha256)
            assertArrayEquals(bookBytes, File(target.books, book.storageName).readBytes())
            assertArrayEquals(image, File(target.covers, cover.storageName).readBytes())
            assertEquals("saved", target.db.reading().resume(id, book.sha256)?.locatorJson)
            assertEquals(1L, count(target, "book_notes"))
            assertEquals(restored, target.settings.settings.first())
            assertFalse(target.journal.exists())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `legacy one through five retains versioned organization and covers`() = runBlocking {
        val source = fixture("l-src", restored)
        val docs = Documents()
        try {
            seed(source)
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val original = requireNotNull(docs.bytes)
            for (version in 1..5) {
                val target = fixture("l-$version")
                try {
                    docs.bytes = rewrite(original) { archive, manifest, records ->
                        manifest.put("formatVersion", version).put("databaseVersion", when (version) {
                            1, 2, 3 -> 12; 4 -> 13; else -> 14
                        })
                        if (version < 5) {
                            manifest.remove("covers"); manifest.remove("coverCount")
                            archive.remove(cover.entryName)
                        }
                        records.getJSONObject("tables").apply {
                            stripWordReviewTables(this); remove("pdf_annotations"); remove("epub_range_passages")
                            remove("book_pdf_view_preferences")
                            remove("reading_duration_receipts")
                            if (version < 5) remove("book_covers")
                            if (version < 4) remove("book_organizations")
                        }
                        records.asPreBookSpacingTables().getJSONObject("settings").asPreSpacingSettings().apply {
                            if (version < 3) remove("textAlignment")
                            if (version < 2) remove("scroll")
                        }
                    }
                    assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
                    assertEquals(if (version == 5) coverId else null, target.library.findById(id)?.customCover?.id)
                    assertEquals(if (version >= 4) organization else BookOrganization(),
                        target.library.findById(id)?.organization)
                    assertEquals(if (version == 5) 1L else 0L, count(target, "book_covers"))
                    if (version == 5) assertArrayEquals(image, File(target.covers, cover.storageName).readBytes())
                    else assertTrue(target.covers.listFiles().isNullOrEmpty())
                } finally { target.db.close() }
            }
        } finally { source.db.close() }
    }

    @Test fun `corrupt images reject before commit`() = runBlocking {
        val source = fixture("c-src", restored)
        val target = fixture("c-dst", EpubSettings(theme = ReadingTheme.DARK))
        val docs = Documents()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            val original = requireNotNull(docs.bytes)
            val edits: List<(LinkedHashMap<String, ByteArray>, JSONObject, JSONObject) -> Unit> = listOf(
                { archive, _, _ -> archive[cover.entryName] = image.copyOf().apply { this[lastIndex] = 0 } },
                { archive, manifest, records ->
                    val invalid = "not a raster".toByteArray()
                    archive[cover.entryName] = invalid
                    listOf(manifest.getJSONArray("covers"), records.getJSONObject("tables").getJSONArray("book_covers"))
                        .forEach { it.getJSONObject(0).put("byteSize", invalid.size).put("sha256", hash(invalid)) }
                },
                { _, manifest, records ->
                    listOf(manifest.getJSONArray("covers"), records.getJSONObject("tables").getJSONArray("book_covers"))
                        .forEach { it.getJSONObject(0).put("width", 9) }
                },
            )
            edits.forEach { edit ->
                docs.bytes = rewrite(original, edit)
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target)
            }
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `strict cover fields and headers`() = runBlocking {
        val source = fixture("f-src", restored)
        val target = fixture("f-dst")
        val docs = Documents()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            val original = requireNotNull(docs.bytes)
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("contentSha256", "f".repeat(64)) },
                { it.put("storageName", "../cover.png") },
                { it.put("coverId", coverId.replaceFirst("3", "A")) },
                { it.put("mediaType", "image/svg+xml") },
                { it.put("width", 16_385) }, { it.put("width", 10_000).put("height", 10_000) },
                { it.put("height", 1.5) }, { it.put("sha256", "bad") },
                { it.put("future", "x") }, { it.remove("height") },
            )
            edits.forEach { edit ->
                docs.bytes = rewrite(original) { _, manifest, records ->
                    edit(manifest.getJSONArray("covers").getJSONObject(0))
                    edit(records.getJSONObject("tables").getJSONArray("book_covers").getJSONObject(0))
                }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target)
            }
            val archiveEdits: List<(LinkedHashMap<String, ByteArray>, JSONObject, JSONObject) -> Unit> = listOf(
                { _, manifest, _ -> manifest.put("coverCount", 2) },
                { _, manifest, _ -> manifest.getJSONArray("covers").getJSONObject(0).put("height", 11) },
                { _, _, records -> records.getJSONObject("tables").remove("book_covers") },
                { archive, _, _ -> archive.remove(cover.entryName) },
                { archive, _, _ -> archive["covers/extra.png"] = image },
                { _, manifest, _ -> manifest.put("formatVersion", 4).put("databaseVersion", 13)
                    .apply { remove("coverCount"); remove("covers") } },
            )
            archiveEdits.forEach { edit ->
                docs.bytes = rewrite(original, edit)
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target)
            }
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `changed cover source rejects export`() = runBlocking {
        val fixture = fixture("chg", restored)
        val docs = Documents()
        try {
            seed(fixture)
            val path = File(fixture.covers, cover.storageName)
            path.writeBytes(image.copyOf().apply { this[lastIndex] = 0 })
            assertEquals(BackupResult.Failed(BackupFailure.CHANGED_SOURCE), fixture.backup(docs).create("content://backup"))
            assertNull(docs.bytes)
            assertEquals(1L, count(fixture, "book_covers"))
            path.delete()
            assertEquals(BackupResult.Failed(BackupFailure.CHANGED_SOURCE), fixture.backup(docs).create("content://backup"))
            assertNull(docs.bytes)
        } finally { fixture.db.close() }
    }

    @Test fun `oversized covers reject before writes`() = runBlocking {
        val source = fixture("big-src", restored)
        val target = fixture("big-dst")
        val docs = Documents()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            val original = requireNotNull(docs.bytes)
            docs.bytes = rewrite(original) { _, manifest, records ->
                listOf(manifest.getJSONArray("covers"), records.getJSONObject("tables").getJSONArray("book_covers"))
                    .forEach { it.getJSONObject(0).put("byteSize", BackupRecordsCodec.MAX_COVER_BYTES + 1) }
            }
            assertEquals(BackupResult.Failed(BackupFailure.TOO_LARGE), target.backup(docs).restore("content://backup"))
            assertEmpty(target)
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `settings rollback removes covers`() = runBlocking {
        val source = fixture("rb-src", restored)
        val previous = EpubSettings(theme = ReadingTheme.DARK)
        val target = fixture("rb-dst", previous)
        val docs = Documents()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            target.settings.failNext = true
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs).restore("content://backup"))
            assertEmpty(target)
            assertEquals(previous, target.settings.settings.first())
            assertEquals(2, target.settings.attempts)
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertArrayEquals(image, File(target.covers, cover.storageName).readBytes())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `recovery preserves unrelated cover`() = runBlocking {
        val fixture = fixture("rec", restored)
        val otherId = "22222222-2222-4222-8222-222222222222"
        val otherCoverId = "44444444-4444-4444-8444-444444444444"
        val other = book.copy(id = otherId, storageName = "$otherId.epub", sha256 = "e".repeat(64))
        try {
            seed(fixture)
            fixture.library.add(other)
            File(fixture.books, other.storageName).writeBytes(bookBytes)
            fixture.db.bookCovers().upsert(BookCoverEntity(otherId, other.sha256, otherCoverId,
                "$otherCoverId.png", "image/png", image.size.toLong(), hash(image), 8, 12))
            File(fixture.covers, "$otherCoverId.png").writeBytes(image)
            fixture.journal.writeText(journal(), Charsets.UTF_8)
            fixture.backup(Documents()).recoverPendingRestore()
            assertNull(fixture.library.findById(id))
            assertNotNull(fixture.library.findById(otherId))
            assertEquals(1L, count(fixture, "book_covers"))
            assertFalse(File(fixture.covers, cover.storageName).exists())
            assertTrue(File(fixture.covers, "$otherCoverId.png").exists())
            assertFalse(fixture.journal.exists())
            fixture.backup(Documents()).recoverPendingRestore()
            assertTrue(File(fixture.covers, "$otherCoverId.png").exists())
        } finally { fixture.db.close() }
    }

    @Test fun `recovery handles files before rows`() = runBlocking {
        val fixture = fixture("gap", restored)
        try {
            File(fixture.books, book.storageName).writeBytes(bookBytes)
            File(fixture.covers, cover.storageName).writeBytes(image)
            fixture.journal.writeText(journal(), Charsets.UTF_8)
            fixture.backup(Documents()).recoverPendingRestore()
            assertTrue(fixture.books.listFiles().isNullOrEmpty())
            assertTrue(fixture.covers.listFiles().isNullOrEmpty())
            assertEquals(EpubSettings(), fixture.settings.settings.first())
            assertFalse(fixture.journal.exists())
        } finally { fixture.db.close() }
    }

    @Test fun `recovery handles partial file moves`() = runBlocking {
        val fixture = fixture("partial", restored)
        try {
            File(fixture.books, book.storageName).writeBytes(bookBytes)
            // Process stopped after a book rename but before the cover rename and Room commit.
            fixture.journal.writeText(journal(), Charsets.UTF_8)
            fixture.backup(Documents()).recoverPendingRestore()
            assertTrue(fixture.books.listFiles().isNullOrEmpty())
            assertTrue(fixture.covers.listFiles().isNullOrEmpty())
            assertEquals(EpubSettings(), fixture.settings.settings.first())
            assertFalse(fixture.journal.exists())
        } finally { fixture.db.close() }
    }

    @Test fun `recovery preserves referenced file`() = runBlocking {
        val fixture = fixture("ref", restored)
        try {
            seed(fixture)
            withContext(Dispatchers.IO) {
                fixture.db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ? WHERE id = ?",
                    arrayOf("f".repeat(64), id))
            }
            fixture.journal.writeText(journal(), Charsets.UTF_8)
            fixture.backup(Documents()).recoverPendingRestore()
            assertNotNull(fixture.library.findById(id))
            assertEquals(1L, count(fixture, "book_covers"))
            assertArrayEquals(image, File(fixture.covers, cover.storageName).readBytes())
            assertTrue(File(fixture.books, book.storageName).exists())
        } finally { fixture.db.close() }
    }

    @Test fun `failed cleanup keeps journal`() = runBlocking {
        val fixture = fixture("err", restored)
        try {
            seed(fixture)
            val path = File(fixture.covers, cover.storageName)
            assertTrue(path.delete()); assertTrue(path.mkdir())
            val pending = journal()
            fixture.journal.writeText(pending, Charsets.UTF_8)
            assertEquals(BackupResult.Failed(BackupFailure.RECOVERY_REQUIRED), fixture.backup(Documents()).create("content://backup"))
            assertEquals(pending, fixture.journal.readText(Charsets.UTF_8))
            assertEquals(0, fixture.settings.attempts)
            assertTrue(path.delete())
            fixture.backup(Documents()).recoverPendingRestore()
            assertFalse(fixture.journal.exists())
            assertEquals(EpubSettings(), fixture.settings.settings.first())
        } finally { fixture.db.close() }
    }

    @Test fun `bad journal cannot delete data`() = runBlocking {
        val fixture = fixture("jbad", restored)
        try {
            seed(fixture)
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("future", true) }, { it.put("covers", JSONObject.NULL) },
                { it.getJSONArray("covers").getJSONObject(0).put("storageName", "../outside.png") },
                { it.getJSONArray("covers").getJSONObject(0).put("contentSha256", "f".repeat(64)) },
            )
            edits.forEach { edit ->
                fixture.journal.writeText(JSONObject(journal()).also(edit).toString(), Charsets.UTF_8)
                assertEquals(BackupResult.Failed(BackupFailure.RECOVERY_REQUIRED), fixture.backup(Documents()).create("content://backup"))
                assertEquals(1L, count(fixture, "books"))
                assertEquals(1L, count(fixture, "book_covers"))
                assertArrayEquals(image, File(fixture.covers, cover.storageName).readBytes())
                assertEquals(restored, fixture.settings.settings.first())
                assertEquals(0, fixture.settings.attempts)
            }
        } finally { fixture.db.close() }
    }

    private data class Fixture(val db: LibraryDatabase, val books: File, val covers: File,
        val stage: File, val journal: File, val settings: Settings) {
        val library = RoomLibraryRepository(db.books())
        fun backup(docs: BackupDocumentProvider) = DefaultLibraryBackup(db, books, stage, journal,
            settings, docs, Mutex(), coversDirectory = covers)
    }

    private fun fixture(name: String, settings: EpubSettings = EpubSettings()): Fixture {
        val parent = temporary.newFolder(name)
        return Fixture(Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(),
            LibraryDatabase::class.java).build(), File(parent, "books").apply { mkdir() },
            File(parent, "covers").apply { mkdir() }, File(parent, "stage"), File(parent, "pending"), Settings(settings))
    }

    private suspend fun seed(fixture: Fixture) {
        fixture.library.add(book)
        File(fixture.books, book.storageName).writeBytes(bookBytes)
        fixture.library.updateOrganization(id, book.sha256, organization)
        fixture.db.bookCovers().upsert(BookCoverEntity(id, book.sha256, coverId, cover.storageName,
            cover.mediaType, cover.byteSize, cover.sha256, cover.width, cover.height))
        File(fixture.covers, cover.storageName).writeBytes(image)
        fixture.db.reading().save(ReadingProgressEntity(id, book.sha256, 1, "saved", "chapter", 30))
        fixture.db.bookNotes().add(BookNoteEntity("note", id, book.sha256, "Ders notu", 40))
    }

    private fun journal(): String = JSONObject().put("books", JSONArray().put(JSONObject()
        .put("id", id).put("storageName", book.storageName).put("format", "EPUB")
        .put("byteSize", book.byteSize).put("sha256", book.sha256)))
        .put("covers", JSONArray().put(BackupRecordsCodec.encodeCover(cover)))
        .put("previousSettings", BackupRecordsCodec.encodeSettings(EpubSettings())).toString()

    private suspend fun assertEmpty(fixture: Fixture) {
        listOf("books", "book_covers", "book_organizations", "book_notes", "reading_progress")
            .forEach { assertEquals(it, 0L, count(fixture, it)) }
        assertTrue(fixture.books.listFiles().isNullOrEmpty())
        assertTrue(fixture.covers.listFiles().isNullOrEmpty())
        assertTrue(fixture.stage.listFiles().isNullOrEmpty())
        assertFalse(fixture.journal.exists())
    }

    private suspend fun count(fixture: Fixture, table: String): Long = withContext(Dispatchers.IO) {
        fixture.db.openHelper.writableDatabase.compileStatement("SELECT count(*) FROM $table").simpleQueryForLong()
    }

    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { result ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes(); zip.closeEntry()
            }
        }
    }

    private fun rewrite(bytes: ByteArray,
        edit: (LinkedHashMap<String, ByteArray>, JSONObject, JSONObject) -> Unit): ByteArray {
        val archive = entries(bytes)
        val manifest = json(archive.getValue("manifest.json"))
        val records = json(archive.getValue("records.json"))
        edit(archive, manifest, records)
        val encoded = records.toString().toByteArray(Charsets.UTF_8)
        manifest.put("recordsSize", encoded.size).put("recordsSha256", hash(encoded))
        archive["records.json"] = encoded
        archive["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> archive.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            } }
        }.toByteArray()
    }

    private fun json(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class Settings(initial: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(initial)
        var failNext = false
        var attempts = 0
        override suspend fun save(settings: EpubSettings) {
            attempts++
            if (failNext) { failNext = false; throw IOException("simulated failure") }
            this.settings.value = settings
        }
    }

    private class Documents : BackupDocumentProvider {
        var bytes: ByteArray? = null
        override fun accepts(uri: String) = uri.startsWith("content://")
        override fun openInput(uri: String): InputStream = ByteArrayInputStream(requireNotNull(bytes))
        override fun openOutput(uri: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() { super.close(); bytes = toByteArray() }
        }
        override fun delete(uri: String): Boolean { bytes = null; return true }
    }
}

package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.domain.statistics.*
import java.io.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
class ReadingDurationBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
    private val id = "11111111-1111-4111-8111-111111111111"
    private val book = Book(id, "Ders", "Yazar", BookFormat.EPUB, "source.epub", "$id.epub", bytes.size.toLong(), hash(bytes), 11)
    private val start = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli()
    private fun receipt(book: Book = this.book, elapsed: Long = 5000) = ReadingDurationReceipt(
        UUID.randomUUID().toString(), book.id, book.sha256, "2026-10-01", "Europe/Istanbul", start, start + elapsed, elapsed)
    private val preferences = EpubSettings(theme = ReadingTheme.SEPIA, scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY)

    @Test fun `v8 real ZIP and reopened Room retain measured receipts exact labels and annotation history`() = runBlocking {
        val source = fixture("source", preferences); var target = fixture("target"); val docs = Documents()
        try {
            val expected = seed(source)
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val archive = entries(requireNotNull(docs.bytes)); val manifest = json(archive.getValue("manifest.json"))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            assertEquals(1, json(archive.getValue("records.json")).getJSONObject("tables").getJSONArray("reading_duration_receipts").length())
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            target.db.close(); target = target.copy(db = open(target.databaseName))
            assertEquals(expected, target.db.readingDurations().all().single().toReceipt())
            assertEquals(5000L, target.repo().observe().first().totalMillis)
            assertEquals("Exact note", target.db.epubPassages().observe(id, book.sha256).first().single().attachedNote)
            assertArrayEquals(bytes, File(target.books, book.storageName).readBytes())
            assertEquals(preferences, target.settings.settings.first()); assertFalse(target.journal.exists())
        } finally { source.close(); target.close() }
    }

    @Test fun `every exact legacy version one through six restores empty duration without changing old fields`() = runBlocking {
        val source = fixture("legacy-source", preferences); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            for (version in 1..6) {
                val target = fixture("legacy-$version")
                try {
                    docs.bytes = legacy(original, version)
                    assertFalse(json(entries(requireNotNull(docs.bytes)).getValue("records.json")).getJSONObject("tables").has("reading_duration_receipts"))
                    assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
                    assertTrue(target.db.readingDurations().all().isEmpty())
                    val snapshot = target.repo().observe().first()
                    assertEquals(0L, snapshot.totalMillis); assertEquals(0L, snapshot.books.single().totalMillis)
                    val passage = target.db.epubPassages().observe(id, book.sha256).first().single()
                    assertEquals("leaf", passage.text)
                    assertEquals(if (version == 6) "GREEN" else null, passage.highlightColor)
                    assertEquals(if (version == 6) "Exact note" else null, passage.attachedNote)
                    assertEquals(if (version == 6) 7L else 0L, passage.revision)
                    assertEquals(preferences.copy(scroll = version >= 2,
                        textAlignment = if (version >= 3) ReadingTextAlignment.JUSTIFY else ReadingTextAlignment.PUBLISHER), target.settings.settings.first())
                } finally { target.close() }
            }
        } finally { source.close() }
    }

    @Test fun `malformed receipt identity types dates zones endpoints and duplicate rows reject before durable writes`() = runBlocking {
        val source = fixture("invalid-source", preferences); val target = fixture("invalid-target"); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            val mutations: List<(JSONObject) -> Unit> = listOf(
                { it.put("id", "bad") }, { it.put("id", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA") },
                { it.put("bookId", "22222222-2222-4222-8222-222222222222") }, { it.put("contentSha256", "f".repeat(64)) },
                { it.put("localDate", "2026-02-30") }, { it.put("localDate", "2026-10-02") },
                { it.put("zoneId", "Unknown/Zone") }, { it.put("zoneId", "UTC\u0000") },
                { it.put("startedAt", -1) }, { it.put("startedAt", 1.5) }, { it.put("startedAt", "0") },
                { it.put("endedAt", start - 1) }, { it.put("endedAt", start + 86_400_000) },
                { it.put("endedAt", MAX_READING_DURATION_TIMESTAMP + 1) }, { it.put("elapsedMillis", 0) },
                { it.put("elapsedMillis", -1) }, { it.put("elapsedMillis", 1.5) }, { it.put("elapsedMillis", "1000") },
                { it.put("elapsedMillis", MAX_READING_DURATION_MILLIS + 1) }, { it.put("elapsedMillis", JSONObject.NULL) },
                { it.remove("elapsedMillis") }, { it.put("future", true) },
            )
            for (mutation in mutations) {
                docs.bytes = rewrite(original) { _, records -> mutation(records.getJSONObject("tables").getJSONArray("reading_duration_receipts").getJSONObject(0)) }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target, EpubSettings()); assertEquals(0, target.settings.attempts)
            }
            docs.bytes = rewrite(original) { _, records -> val rows = records.getJSONObject("tables").getJSONArray("reading_duration_receipts"); rows.put(JSONObject(rows.getJSONObject(0).toString())) }
            assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup")); assertEmpty(target, EpubSettings())
        } finally { source.close(); target.close() }
    }

    @Test fun `legacy headers cannot hide the new receipt table and format database versions remain paired`() = runBlocking {
        val source = fixture("header-source"); val target = fixture("header-target"); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            for (version in 1..6) {
                docs.bytes = legacy(original, version, removeReceipts = false)
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup")); assertEmpty(target, EpubSettings())
            }
            for ((format, database) in listOf(7 to 15, 6 to 16, 8 to 16)) {
                docs.bytes = rewrite(original) { manifest, _ -> manifest.put("formatVersion", format).put("databaseVersion", database) }
                assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), target.backup(docs).restore("content://backup")); assertEmpty(target, EpubSettings())
            }
        } finally { source.close(); target.close() }
    }

    @Test fun `shared mutex snapshots one cumulative checkpoint while a later duration writer waits`() = runBlocking {
        val source = fixture("mutex-source"); val mutex = Mutex(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val docs = Documents { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        var backup: Deferred<BackupResult>? = null; var writer: Deferred<Unit>? = null
        try {
            val first = seed(source)
            backup = async(Dispatchers.IO) { source.backup(docs, mutex).create("content://backup") }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            val attempted = CompletableDeferred<Unit>()
            writer = async(Dispatchers.IO) { attempted.complete(Unit); source.repo(mutex).record(first.copy(elapsedMillis = 9000, endedAt = start + 9000)) }
            attempted.await(); assertFalse(writer.isCompleted); release.countDown()
            assertEquals(BackupResult.Created(1), backup.await()); writer.await()
            val archived = json(entries(requireNotNull(docs.bytes)).getValue("records.json")).getJSONObject("tables").getJSONArray("reading_duration_receipts").getJSONObject(0)
            assertEquals(5000L, archived.getLong("elapsedMillis")); assertEquals(9000L, source.repo().observe().first().totalMillis)
        } finally { release.countDown(); backup?.cancelAndJoin(); writer?.cancelAndJoin(); source.close() }
    }

    @Test fun `cancelled output removes only its accepted target and preserves receipts for retry`() = runBlocking {
        val source = fixture("cancel-source"); val mutex = Mutex(); val docs = Documents { throw CancellationException("controlled output cancellation") }
        try {
            val first = seed(source)
            assertTrue(runCatching { source.backup(docs, mutex).create("content://backup") }.exceptionOrNull() is CancellationException)
            assertEquals(1, docs.deletes); assertNull(docs.bytes); assertEquals(first, source.db.readingDurations().all().single().toReceipt())
            assertFalse(source.journal.exists()); source.repo(mutex).record(first.copy(elapsedMillis = 9000, endedAt = start + 9000))
            assertEquals(9000L, source.repo().observe().first().totalMillis)
        } finally { source.close() }
    }

    @Test fun `settings failure rolls restored receipts back before a later successful restore`() = runBlocking {
        val source = fixture("rollback-source", preferences); val previous = EpubSettings(theme = ReadingTheme.DARK)
        val target = fixture("rollback-target", previous); val docs = Documents()
        try {
            val first = seed(source); source.backup(docs).create("content://backup"); target.settings.failNext = true
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs).restore("content://backup"))
            assertEmpty(target, previous); assertEquals(2, target.settings.attempts)
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(first, target.db.readingDurations().all().single().toReceipt())
        } finally { source.close(); target.close() }
    }

    @Test fun `pending restore recovery after reopen cascades only journal book receipts`() = runBlocking {
        var target = fixture("recovery", preferences); val previous = EpubSettings(theme = ReadingTheme.DARK)
        val otherId = "22222222-2222-4222-8222-222222222222"
        val other = book.copy(id = otherId, storageName = "$otherId.epub", sha256 = "c".repeat(64))
        try {
            seed(target); target.library.add(other); File(target.books, other.storageName).writeBytes(bytes)
            val keep = receipt(other, elapsed = 7000); target.repo().record(keep)
            target.journal.writeText(JSONObject().put("books", JSONArray().put(JSONObject()
                .put("id", id).put("storageName", book.storageName).put("format", "EPUB")
                .put("byteSize", book.byteSize).put("sha256", book.sha256)))
                .put("previousSettings", BackupRecordsCodec.encodeSettings(previous)).toString())
            target.db.close(); target = target.copy(db = open(target.databaseName)); target.backup(Documents()).recoverPendingRestore()
            assertNull(target.library.findById(id)); assertEquals(keep, target.db.readingDurations().all().single().toReceipt())
            assertEquals(7000L, target.repo().observe().first().totalMillis)
            assertFalse(File(target.books, book.storageName).exists()); assertTrue(File(target.books, other.storageName).exists())
            assertFalse(target.journal.exists()); assertEquals(previous, target.settings.settings.first())
        } finally { target.close() }
    }

    @Test fun `receipt count limit rejects an archive before staging books into durable storage`() = runBlocking {
        val source = fixture("limit-source"); val target = fixture("limit-target"); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup")
            docs.bytes = rewrite(requireNotNull(docs.bytes)) { _, records ->
                val first = records.getJSONObject("tables").getJSONArray("reading_duration_receipts").getJSONObject(0)
                val rows = JSONArray()
                repeat(MAX_READING_DURATION_RECEIPTS + 1) { index -> rows.put(JSONObject(first.toString()).put("id", "%08x-0000-4000-8000-000000000000".format(index))) }
                records.getJSONObject("tables").put("reading_duration_receipts", rows)
            }
            assertEquals(BackupResult.Failed(BackupFailure.TOO_LARGE), target.backup(docs).restore("content://backup")); assertEmpty(target, EpubSettings())
        } finally { source.close(); target.close() }
    }

    private data class Fixture(val db: LibraryDatabase, val databaseName: String, val books: File, val stage: File, val journal: File, val settings: Settings) {
        val library get() = RoomLibraryRepository(db.books())
        fun backup(docs: BackupDocumentProvider, mutex: Mutex = Mutex()) = DefaultLibraryBackup(db, books, stage, journal, settings, docs, mutex)
        fun repo(mutex: Mutex = Mutex()) = RoomReadingDurationRepository(db, mutex)
        fun close() { db.close(); ApplicationProvider.getApplicationContext<Application>().deleteDatabase(databaseName) }
    }
    private fun open(name: String) = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
    private fun fixture(name: String, preferences: EpubSettings = EpubSettings()): Fixture {
        val parent = temporary.newFolder(name); val databaseName = "duration-backup-${UUID.randomUUID().toString().take(8)}.db"
        return Fixture(open(databaseName), databaseName, File(parent, "books").apply { mkdir() }, File(parent, "stage"), File(parent, "restore.pending"), Settings(preferences))
    }
    private suspend fun seed(target: Fixture): ReadingDurationReceipt {
        target.library.add(book); File(target.books, book.storageName).writeBytes(bytes)
        target.db.epubPassages().add(EpubPassageEntity("passage", id, book.sha256, 1, "OPS/chapter.xhtml", "b".repeat(64), 0, 4, "leaf", 10, "GREEN", "Exact note", 7))
        return receipt().also { target.repo().record(it) }
    }
    private suspend fun assertEmpty(target: Fixture, preferences: EpubSettings) {
        assertTrue(target.db.books().observeAll().first().isEmpty()); assertTrue(target.db.readingDurations().all().isEmpty())
        assertTrue(target.books.listFiles().isNullOrEmpty()); assertTrue(target.stage.listFiles().isNullOrEmpty())
        assertFalse(target.journal.exists()); assertEquals(preferences, target.settings.settings.first())
    }
    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { output ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val entry = zip.nextEntry ?: break; output[entry.name] = zip.readBytes(); zip.closeEntry() } }
    }
    private fun legacy(bytes: ByteArray, version: Int, removeReceipts: Boolean = true): ByteArray = rewrite(bytes) { manifest, records ->
        manifest.put("formatVersion", version).put("databaseVersion", when (version) { 1, 2, 3 -> 12; 4 -> 13; 5 -> 14; else -> 15 })
        val tables = records.getJSONObject("tables")
        stripWordReviewTables(tables); tables.remove("pdf_annotations"); tables.remove("epub_range_passages")
        tables.remove("book_pdf_view_preferences")
        if (removeReceipts) tables.remove("reading_duration_receipts")
        if (version <= 4) { tables.remove("book_covers"); manifest.remove("coverCount"); manifest.remove("covers") }
        if (version <= 3) tables.remove("book_organizations")
        if (version <= 5) { val passages = tables.getJSONArray("epub_passages"); for (i in 0 until passages.length()) passages.getJSONObject(i).apply { remove("highlightColor"); remove("attachedNote"); remove("revision") } }
        records.asPreBookSpacingTables().getJSONObject("settings").asPreSpacingSettings().apply { if (version < 3) remove("textAlignment"); if (version < 2) remove("scroll") }
    }
    private fun rewrite(bytes: ByteArray, edit: (JSONObject, JSONObject) -> Unit): ByteArray {
        val archive = entries(bytes); val manifest = json(archive.getValue("manifest.json")); val records = json(archive.getValue("records.json"))
        edit(manifest, records); val encoded = records.toString().toByteArray(Charsets.UTF_8)
        manifest.put("recordsSize", encoded.size).put("recordsSha256", hash(encoded))
        archive["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8); archive["records.json"] = encoded
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> archive.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() } } }.toByteArray()
    }
    private fun json(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Settings(initial: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(initial); var failNext = false; var attempts = 0
        override suspend fun save(settings: EpubSettings) { attempts++; if (failNext) { failNext = false; throw IOException("controlled settings failure") }; this.settings.value = settings }
    }
    private class Documents(private val beforeOutput: (() -> Unit)? = null) : BackupDocumentProvider {
        var bytes: ByteArray? = null; var deletes = 0
        override fun accepts(uri: String) = uri.startsWith("content://")
        override fun openInput(uri: String): InputStream = ByteArrayInputStream(requireNotNull(bytes))
        override fun openOutput(uri: String): OutputStream { beforeOutput?.invoke(); return object : ByteArrayOutputStream() { override fun close() { super.close(); bytes = toByteArray() } } }
        override fun delete(uri: String): Boolean { deletes++; bytes = null; return true }
    }
}

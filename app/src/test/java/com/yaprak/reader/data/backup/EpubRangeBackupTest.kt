package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.BookEntity
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomEpubPassageRepository
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.backup.BackupResult
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.domain.passages.EpubRangeAnchorCodec
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.EpubSettingsRepository
import com.yaprak.reader.domain.settings.ReadingTextAlignment
import com.yaprak.reader.domain.settings.ReadingTheme
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceRangeSegment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
@Config(sdk = [34], application = Application::class)
class EpubRangeBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val epubId = "11111111-1111-4111-8111-111111111111"
    private val pdfId = "22222222-2222-4222-8222-222222222222"
    private val legacyId = "44444444-4444-4444-8444-444444444444"
    private val epubBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4)
    private val pdfBytes = "%PDF-1.4\nPDF sibling".toByteArray()
    private val otherBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 9, 8, 7)
    private val settings = EpubSettings(155, theme = ReadingTheme.SEPIA, scroll = true,
        textAlignment = ReadingTextAlignment.JUSTIFY)
    private val previous = EpubSettings(theme = ReadingTheme.DARK)
    private val table = "epub_range_passages"
    private val indexName = "index_epub_range_passages_bookId_contentSha256_anchorKey"
    private val preview = "İğne 🌿\nöteki paragraf"
    private val attached = "  Not\n```\n<img src=x> [link](https://example.invalid)\t漢字 🌿  "

    @Test fun `v10 ZIP and reopen keep complete groups`() = runBlocking {
        val source = fixture("src", settings); var target = fixture("dst", previous); val docs = Documents()
        try {
            seed(source); val expected = rangeRows(source).map(JSONObject::toString)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            val archive = entries(requireNotNull(docs.bytes)); val manifest = json(archive.getValue("manifest.json"))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            val records = json(archive.getValue("records.json")); val saved = records.getJSONObject("tables").getJSONArray(table)
            assertEquals(5, saved.length()); assertEquals(12, saved.getJSONObject(0).length())
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            assertEquals(expected, rangeRows(target).map(JSONObject::toString)); assertOldData(target)
            assertEquals(settings, target.settings.settings.first()); assertFalse(target.journal.exists())
            target.db.close(); target = target.copy(db = open(target.databaseName))
            assertEquals(expected, rangeRows(target).map(JSONObject::toString))
            val recordsAfterOpen = RoomEpubPassageRepository(target.db).observe(epubId, hash(epubBytes)).first()
            assertEquals(6, recordsAfterOpen.size)
            val ranges = recordsAfterOpen.filter { it.anchor is SourceRangeAnchor }
            assertEquals(setOf(null, *EpubHighlightColor.entries.toTypedArray()), ranges.map { it.highlightColor }.toSet())
            assertTrue(ranges.all { it.text == preview }); assertTrue(ranges.any { it.attachedNote == attached })
            assertEquals(2, (ranges.first().anchor as SourceRangeAnchor).segments.size)
            assertFalse((ranges.first().anchor as SourceRangeAnchor).segments.any { '\n' in it.selectedText })
        } finally { source.close(); target.close() }
    }

    @Test fun `legacy one to eight preserves old data`() = runBlocking {
        val source = fixture("src", settings); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            for (version in 1..8) {
                val target = fixture("dst$version", previous)
                try {
                    docs.bytes = legacy(original, version)
                    assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
                    assertEquals(0, count(target, table)); assertEquals(1, count(target, "epub_passages"))
                    assertEquals(1, count(target, "book_notes"))
                    assertEquals(if (version >= 7) 1 else 0, count(target, "reading_duration_receipts"))
                    assertEquals(if (version >= 8) 1 else 0, count(target, "book_pdf_view_preferences"))
                    val old = target.db.openHelper.readableDatabase.query("SELECT text,highlightColor,attachedNote,revision FROM epub_passages").use {
                        assertTrue(it.moveToFirst()); listOf<Any?>(it.getString(0), if (it.isNull(1)) null else it.getString(1),
                            if (it.isNull(2)) null else it.getString(2), it.getLong(3))
                    }
                    assertEquals(listOf<Any?>("old", if (version >= 6) "PINK" else null,
                        if (version >= 6) "old note" else null, if (version >= 6) 7L else 0L), old)
                    assertArrayEquals(epubBytes, File(target.books, "$epubId.epub").readBytes())
                    assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
                    assertEquals(settings.copy(scroll = version >= 2,
                        textAlignment = if (version >= 3) ReadingTextAlignment.JUSTIFY else ReadingTextAlignment.PUBLISHER),
                        target.settings.settings.first())
                } finally { target.close() }
            }
        } finally { source.close() }
    }

    @Test fun `v17 migration exports preserved old rows`() = runBlocking {
        var source = fixture("src", settings); val target = fixture("dst", previous); val docs = Documents()
        try {
            seed(source)
            source.db.openHelper.writableDatabase.apply {
                // Reconstruct the genuine v17 vocabulary shape, preserving all source rows.
                execSQL("DROP TABLE word_review_events")
                execSQL("DROP TABLE word_schedules")
                execSQL("ALTER TABLE saved_words RENAME TO old_words")
                execSQL("DROP INDEX index_saved_words_bookId_contentSha256")
                execSQL("CREATE TABLE saved_words (id TEXT NOT NULL, bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, english TEXT NOT NULL, turkish TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                execSQL("CREATE INDEX index_saved_words_bookId_contentSha256 ON saved_words (bookId,contentSha256)")
                execSQL("INSERT INTO saved_words SELECT id,bookId,contentSha256,english,turkish,createdAt FROM old_words")
                execSQL("DROP TABLE old_words")
                execSQL("DROP TABLE pdf_annotations")
                execSQL("DROP TABLE $table")
                execSQL("UPDATE room_master_table SET identity_hash = ? WHERE id = 42", arrayOf("bd3f4d496664f94c003646c9b8df7d5d"))
                execSQL("PRAGMA user_version = 17")
            }
            source.db.close()
            source = source.copy(db = Room.databaseBuilder(context, LibraryDatabase::class.java, source.databaseName)
                .addMigrations(LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build())
            assertEquals(0, count(source, table)); assertOldData(source)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            assertEquals(0, count(target, table)); assertOldData(target)
            assertEquals(settings, target.settings.settings.first())
        } finally { source.close(); target.close() }
    }

    @Test fun `old headers cannot hide the range table`() = runBlocking {
        val source = fixture("src"); val target = fixture("dst", previous); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            for (empty in listOf(false, true)) {
                docs.bytes = rewrite(original) { manifest, records ->
                    manifest.put("formatVersion", 8).put("databaseVersion", 17)
                    if (empty) records.getJSONObject("tables").put(table, JSONArray())
                }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup")); assertEmpty(target)
            }
            for ((format, database) in listOf(12 to 21, 9 to 17, 8 to 18, 9 to 19)) {
                docs.bytes = rewrite(original) { manifest, _ -> manifest.put("formatVersion", format).put("databaseVersion", database) }
                assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), target.backup(docs).restore("content://backup")); assertEmpty(target)
            }
            assertEquals(0, target.settings.attempts)
        } finally { source.close(); target.close() }
    }

    @Test fun `invalid range fields fail before commit`() = runBlocking {
        val source = fixture("src"); val target = fixture("dst", previous); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("id", "") }, { it.put("id", " \n") }, { it.put("id", "x".repeat(1001)) },
                { it.put("anchorVersion", 1) }, { it.put("anchorVersion", 2.5) }, { it.put("anchorVersion", "2") },
                { it.put("revision", -1) }, { it.put("revision", "0") }, { it.put("createdAt", "1") },
                { it.put("highlightColor", "FUTURE") }, { it.put("attachedNote", " \n") },
                { it.put("attachedNote", "n".repeat(2001)) }, { it.remove("anchorKey") },
                { it.put("future", true) }, { it.put("contentSha256", "a".repeat(64)) },
                { it.put("resourceHref", "../outside.xhtml") }, { it.put("text", "") },
                { it.put("bookId", pdfId).put("contentSha256", hash(pdfBytes)) },
            )
            for (edit in edits) {
                docs.bytes = rewrite(original) { _, records -> edit(rangeRow(records)) }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup")); assertEmpty(target)
            }
            assertEquals(0, target.settings.attempts)
        } finally { source.close(); target.close() }
    }

    @Test fun `tampered segments and keys fail atomically`() = runBlocking {
        val source = fixture("src"); val target = fixture("dst", previous); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("anchorKey", "a".repeat(64)) }, { it.put("anchorKey", "bad") },
                { row -> alterAnchor(row) { it.put("resourceHref", "other.xhtml") } },
                { row -> alterAnchor(row) { it.put("extra", true) } },
                { row -> alterAnchor(row) { firstSegment(it).put("path", "00/0") } },
                { row -> alterAnchor(row) { firstSegment(it).put("start", "1") } },
                { row -> alterAnchor(row) { firstSegment(it).put("start", 1.5) } },
                { row -> alterAnchor(row) { firstSegment(it).put("end", 4294967297L) } },
                { row -> alterAnchor(row) { firstSegment(it).put("selectedText", "changed") } },
                { row -> alterAnchor(row) { firstSegment(it).put("nodeTextSha256", "bad") } },
                { row -> alterAnchor(row) { firstSegment(it).put("nodeTextSha256", "a".repeat(64)) } },
                { row -> alterAnchor(row) { val slices = it.getJSONArray("segments"); it.put("segments", JSONArray().put(slices.get(1)).put(slices.get(0))) } },
                { row -> alterAnchor(row) { val slices = it.getJSONArray("segments"); slices.put(slices.getJSONObject(0)) } },
                { row -> alterAnchor(row) { it.put("segments", JSONArray()) } },
                { row -> row.put("anchorJson", row.getString("anchorJson") + " ") },
                { row -> row.put("anchorJson", row.getString("anchorJson").replaceFirst("\"start\":", "\"start\":1,\"start\":")) },
            )
            for (edit in edits) {
                docs.bytes = rewrite(original) { _, records -> edit(rangeRow(records)) }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup")); assertEmpty(target)
            }
            assertEquals(0, target.settings.attempts)
        } finally { source.close(); target.close() }
    }

    @Test fun `cross table IDs and duplicate anchors fail`() = runBlocking {
        val source = fixture("src"); val target = fixture("dst", previous); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            docs.bytes = rewrite(original) { _, records -> rangeRow(records).put("id", legacyId) }
            assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup")); assertEmpty(target)
            docs.bytes = rewrite(original) { _, records -> val rows = records.getJSONObject("tables").getJSONArray(table)
                rows.put(JSONObject(rows.getJSONObject(0).toString()).put("id", UUID.randomUUID().toString())) }
            assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup")); assertEmpty(target)
            assertEquals(0, target.settings.attempts)
        } finally { source.close(); target.close() }
    }

    @Test fun `active limit spans both passage tables`() = runBlocking {
        val source = fixture("src"); val target = fixture("dst", previous); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            docs.bytes = manyAnnotations(original, 500, 501)
            assertEquals(BackupResult.Failed(BackupFailure.TOO_LARGE), target.backup(docs).restore("content://backup")); assertEmpty(target)
            docs.bytes = manyAnnotations(original, 500, 500, plainExtras = 3)
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            assertEquals(500, count(target, "epub_passages")); assertEquals(503, count(target, table))
            assertFalse(target.journal.exists()); assertEquals(BackupResult.Created(2), target.backup(Documents()).create("content://backup"))
        } finally { source.close(); target.close() }
    }

    @Test fun `range FK and index drift block export`() = runBlocking {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.db.openHelper.writableDatabase.execSQL("DROP INDEX $indexName") },
            { target -> index(target, "CREATE INDEX $indexName ON $table (bookId,contentSha256,anchorKey)") },
            { target -> index(target, "CREATE UNIQUE INDEX $indexName ON $table (bookId,contentSha256,anchorKey) WHERE revision > 0") },
            { target -> index(target, "CREATE UNIQUE INDEX $indexName ON $table (bookId,contentSha256,anchorKey DESC)") },
            { target -> index(target, "CREATE UNIQUE INDEX $indexName ON $table (bookId,contentSha256,anchorKey COLLATE NOCASE)") },
            { target -> val sql = target.db.openHelper.writableDatabase
                val create = sql.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='$table'").use { it.moveToFirst(); it.getString(0) }
                sql.execSQL("DROP TABLE $table"); sql.execSQL(create.replace("ON DELETE CASCADE", "ON DELETE NO ACTION"))
                sql.execSQL("CREATE UNIQUE INDEX $indexName ON $table (bookId,contentSha256,anchorKey)") },
        )
        for ((ordinal, change) in changes.withIndex()) {
            val source = fixture("drift$ordinal"); val docs = Documents()
            try {
                source.db.openHelper.writableDatabase; change(source)
                assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), source.backup(docs).create("content://backup"))
                assertNull(docs.bytes)
            } finally { source.close() }
        }
    }

    @Test fun `corrupt SQLite range fields block export`() = runBlocking {
        val source = fixture("src"); val docs = Documents()
        try {
            seed(source)
            val original = rangeRows(source).first()
            for ((field, value) in listOf<Pair<String, Any>>("anchorVersion" to 4294967298L,
                "revision" to "bad", "revision" to 0.5, "anchorJson" to "{}", "anchorKey" to "bad")) {
                val sql = source.db.openHelper.writableDatabase
                sql.execSQL("UPDATE $table SET $field = ? WHERE id = ?", arrayOf<Any>(value, original.getString("id")))
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), source.backup(docs).create("content://backup")); assertNull(docs.bytes)
                sql.execSQL("UPDATE $table SET $field = ? WHERE id = ?", arrayOf<Any>(original.get(field), original.getString("id")))
            }
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
        } finally { source.close() }
    }

    @Test fun `durable settings failure cascades rollback`() = runBlocking {
        val source = fixture("src", settings); val target = fixture("dst", previous); val docs = Documents()
        val failing = object : EpubSettingsRepository {
            override val settings = target.settings.settings
            override suspend fun save(settings: EpubSettings) {
                target.settings.save(settings)
                if (target.settings.attempts == 1) {
                    assertEquals(5, count(target, table)); assertOldData(target)
                    assertTrue(target.journal.exists()); throw IOException("controlled failure after durable settings save")
                }
            }
        }
        try {
            seed(source); source.backup(docs).create("content://backup")
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs, settings = failing).restore("content://backup"))
            assertEmpty(target); assertEquals(2, target.settings.attempts)
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup")); assertEquals(5, count(target, table))
        } finally { source.close(); target.close() }
    }

    @Test fun `reopened recovery cascades range groups`() = runBlocking {
        var target = fixture("dst", settings)
        try {
            seed(target); target.journal.writeText(pendingJournal(), Charsets.UTF_8)
            target.db.close(); target = target.copy(db = open(target.databaseName))
            target.backup(Documents()).recoverPendingRestore()
            assertNull(target.db.books().findById(epubId)); assertEquals(0, count(target, table))
            assertEquals(0, count(target, "epub_passages")); assertEquals(0, count(target, "reading_duration_receipts"))
            assertEquals(1, count(target, "book_pdf_view_preferences")); assertEquals(1, count(target, "books"))
            assertFalse(File(target.books, "$epubId.epub").exists()); assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
            assertEquals(previous, target.settings.settings.first()); assertFalse(target.journal.exists())
            target.backup(Documents()).recoverPendingRestore(); assertEquals(1, count(target, "book_pdf_view_preferences"))
        } finally { target.close() }
    }

    @Test fun `changed recovery source retains new groups`() = runBlocking {
        val target = fixture("dst", settings)
        try {
            seed(target); target.journal.writeText(pendingJournal(), Charsets.UTF_8)
            target.db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ?, byteSize = ? WHERE id = ?",
                arrayOf<Any>(hash(otherBytes), otherBytes.size, epubId))
            target.db.openHelper.writableDatabase.execSQL("UPDATE $table SET contentSha256 = ? WHERE bookId = ?", arrayOf(hash(otherBytes), epubId))
            File(target.books, "$epubId.epub").writeBytes(otherBytes)
            target.backup(Documents()).recoverPendingRestore()
            assertEquals(5, count(target, table)); assertTrue(rangeRows(target).all { it.getString("contentSha256") == hash(otherBytes) })
            assertArrayEquals(otherBytes, File(target.books, "$epubId.epub").readBytes()); assertFalse(target.journal.exists())
        } finally { target.close() }
    }

    @Test fun `input and export cancellation keep groups`() = runBlocking {
        val source = fixture("src"); val target = fixture("dst", previous); val docs = Documents(); val mutex = Mutex()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            docs.beforeInput = { throw CancellationException("controlled input cancellation") }
            assertTrue(runCatching { target.backup(docs, mutex).restore("content://backup") }.exceptionOrNull() is CancellationException)
            assertEmpty(target); assertFalse(mutex.isLocked)
            val failing = Documents { throw CancellationException("controlled output cancellation") }
            assertTrue(runCatching { source.backup(failing, mutex).create("content://backup") }.exceptionOrNull() is CancellationException)
            assertEquals(1, failing.deletes); assertNull(failing.bytes); assertEquals(5, count(source, table)); assertFalse(mutex.isLocked)
            docs.beforeInput = null; docs.bytes = original
            assertEquals(BackupResult.Restored(2), target.backup(docs, mutex).restore("content://backup")); assertEquals(5, count(target, table))
        } finally { source.close(); target.close() }
    }

    @Test fun `commit cancellation finishes complete groups`() = runBlocking {
        val source = fixture("src", settings); val target = fixture("dst", previous); val docs = Documents(); val mutex = Mutex()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); var restore: Deferred<BackupResult>? = null
        val guarded = object : EpubSettingsRepository {
            override val settings = target.settings.settings
            override suspend fun save(settings: EpubSettings) {
                assertEquals(5, count(target, table)); assertTrue(target.journal.exists())
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); target.settings.save(settings)
            }
        }
        try {
            seed(source); source.backup(docs).create("content://backup")
            restore = async(Dispatchers.IO) { target.backup(docs, mutex, guarded).restore("content://backup") }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            restore.cancel(); release.countDown(); restore.join(); assertTrue(restore.isCancelled)
            assertEquals(5, count(target, table)); assertOldData(target); assertEquals(settings, target.settings.settings.first())
            assertFalse(target.journal.exists()); assertTrue(target.stage.listFiles().isNullOrEmpty()); assertFalse(mutex.isLocked)
            assertEquals(BackupResult.Created(2), target.backup(Documents(), mutex).create("content://backup"))
        } finally { release.countDown(); restore?.cancelAndJoin(); source.close(); target.close() }
    }

    @Test fun `shared mutex archives one group revision`() = runBlocking {
        val source = fixture("src"); val mutex = Mutex(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val docs = Documents { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        var backup: Deferred<BackupResult>? = null; var editor: Deferred<EpubPassage>? = null
        try {
            seed(source); val repo = RoomEpubPassageRepository(source.db, fileMutex = mutex)
            val old = repo.observe(epubId, hash(epubBytes)).first().first { it.anchor is SourceRangeAnchor && it.revision == 0L }
            backup = async(Dispatchers.IO) { source.backup(docs, mutex).create("content://backup") }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            val attempted = CompletableDeferred<Unit>()
            editor = async(Dispatchers.IO) { attempted.complete(Unit); repo.updateAnnotation(epubId, hash(epubBytes), old, EpubHighlightColor.PINK, "edited note") }
            attempted.await(); assertFalse(editor.isCompleted); release.countDown()
            assertEquals(BackupResult.Created(2), backup.await()); val changed = editor.await()
            assertEquals(1L, changed.revision); assertEquals("edited note", changed.attachedNote)
            val rows = json(entries(requireNotNull(docs.bytes)).getValue("records.json")).getJSONObject("tables").getJSONArray(table)
            val exported = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == old.id }
            assertEquals(0L, exported.getLong("revision")); assertEquals(old.attachedNote ?: JSONObject.NULL, exported.get("attachedNote"))
            assertEquals(EpubRangeAnchorCodec.encode(old.anchor as SourceRangeAnchor), exported.getString("anchorJson"))
            assertEquals(changed, repo.observe(epubId, hash(epubBytes)).first().single { it.id == old.id })
        } finally { release.countDown(); backup?.cancelAndJoin(); editor?.cancelAndJoin(); source.close() }
    }

    private data class Fixture(val db: LibraryDatabase, val databaseName: String, val books: File,
        val stage: File, val journal: File, val settings: Settings) {
        fun backup(docs: BackupDocumentProvider, mutex: Mutex = Mutex(), settings: EpubSettingsRepository = this.settings) =
            DefaultLibraryBackup(db, books, stage, journal, settings, docs, mutex)
        fun close() { db.close(); ApplicationProvider.getApplicationContext<Application>().deleteDatabase(databaseName) }
    }
    private fun open(name: String): LibraryDatabase {
        val parent = requireNotNull(context.getDatabasePath(name).parentFile)
        check(parent.isDirectory || parent.mkdirs()); return Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
    }
    private fun fixture(name: String, settings: EpubSettings = EpubSettings()): Fixture {
        val parent = temporary.newFolder(name); val databaseName = "er-${UUID.randomUUID().toString().take(8)}.db"
        return Fixture(open(databaseName), databaseName, File(parent, "books").apply { mkdir() }, File(parent, "stage"),
            File(parent, "restore.pending"), Settings(settings))
    }
    private suspend fun seed(target: Fixture) {
        addBook(target, epubId, epubBytes, "EPUB"); addBook(target, pdfId, pdfBytes, "PDF")
        val sql = target.db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO epub_passages (id,bookId,contentSha256,anchorVersion,resourceHref,nodeTextSha256,startOffset,endOffset,text,createdAt,highlightColor,attachedNote,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(legacyId, epubId, hash(epubBytes), 1, "legacy.xhtml", hash("old".toByteArray()), 0, 3, "old", 12L, "PINK", "old note", 7L))
        sql.execSQL("INSERT INTO book_notes (id,bookId,contentSha256,text,createdAt,pdfPageIndex) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>(UUID.randomUUID().toString(), epubId, hash(epubBytes), "book note", 13L, null))
        sql.execSQL("INSERT INTO reading_duration_receipts (id,bookId,contentSha256,localDate,zoneId,startedAt,endedAt,elapsedMillis) VALUES (?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(UUID.randomUUID().toString(), epubId, hash(epubBytes), "2026-10-06", "Europe/Istanbul", 1791248400000L, 1791248401500L, 1500L))
        sql.execSQL("INSERT INTO book_pdf_view_preferences (bookId,contentSha256,horizontalPercent,verticalPercent,fitMode,doublePagePreferred) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>(pdfId, hash(pdfBytes), 8, 12, "WIDTH", 1))
        val colors = EpubHighlightColor.entries.map { it.name } + listOf(null)
        for (ordinal in 0..4) insertRange(target, makeRange(ordinal, colors[ordinal]))
    }
    private suspend fun addBook(target: Fixture, id: String, bytes: ByteArray, format: String) {
        val storage = "$id.${format.lowercase()}"
        target.db.books().insert(BookEntity(id, "$format title", "Author", format, storage, storage,
            bytes.size.toLong(), hash(bytes), 10L, if (format == "PDF") 4 else null)); File(target.books, storage).writeBytes(bytes)
    }
    private fun makeRange(ordinal: Int, color: String? = "YELLOW", suffix: String = ""): JSONObject {
        val href = "chapter-$ordinal$suffix.xhtml"; val anchor = SourceRangeAnchor(href, listOf(
            SourceRangeSegment("0/0", 1, 8, hash("xİğne 🌿".toByteArray()), "İğne 🌿"),
            SourceRangeSegment("1/0", 0, 14, hash("öteki paragraf!".toByteArray()), "öteki paragraf")))
        return JSONObject().put("id", UUID.nameUUIDFromBytes("range-$ordinal$suffix".toByteArray()).toString())
            .put("bookId", epubId).put("contentSha256", hash(epubBytes)).put("anchorVersion", 2)
            .put("resourceHref", href).put("anchorJson", EpubRangeAnchorCodec.encode(anchor)).put("anchorKey", EpubRangeAnchorCodec.key(anchor))
            .put("text", preview).put("createdAt", if (ordinal == 4) -1L else 100L + ordinal).put("highlightColor", color ?: JSONObject.NULL)
            .put("attachedNote", if (ordinal == 0) attached else JSONObject.NULL).put("revision", if (ordinal == 3) Long.MAX_VALUE else ordinal.toLong())
    }
    private fun insertRange(target: Fixture, row: JSONObject) {
        val names = listOf("id", "bookId", "contentSha256", "anchorVersion", "resourceHref", "anchorJson", "anchorKey", "text", "createdAt", "highlightColor", "attachedNote", "revision")
        target.db.openHelper.writableDatabase.execSQL("INSERT INTO $table (${names.joinToString(",")}) VALUES (${names.joinToString(",") { "?" }})",
            names.map { row.get(it).takeUnless { value -> value === JSONObject.NULL } }.toTypedArray())
    }
    private fun rangeRows(target: Fixture): List<JSONObject> = target.db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(JSONObject().apply {
                cursor.columnNames.forEachIndexed { index, name ->
                    put(name, when (cursor.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                        else -> error("Unexpected fixture field type")
                    })
                }
            })
        }
    }
    private fun count(target: Fixture, table: String): Int = target.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
    private fun assertOldData(target: Fixture) {
        assertEquals(1, count(target, "epub_passages")); assertEquals(1, count(target, "book_notes"))
        assertEquals(1, count(target, "reading_duration_receipts")); assertEquals(1, count(target, "book_pdf_view_preferences"))
        assertArrayEquals(epubBytes, File(target.books, "$epubId.epub").readBytes()); assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
    }
    private suspend fun assertEmpty(target: Fixture) {
        assertTrue(target.db.books().observeAll().first().isEmpty())
        for (name in listOf(table, "epub_passages", "book_notes", "reading_duration_receipts", "book_pdf_view_preferences")) assertEquals(0, count(target, name))
        assertTrue(target.books.listFiles().isNullOrEmpty()); assertTrue(target.stage.listFiles().isNullOrEmpty())
        assertFalse(target.journal.exists()); assertEquals(previous, target.settings.settings.first())
    }
    private fun index(target: Fixture, create: String) { target.db.openHelper.writableDatabase.execSQL("DROP INDEX $indexName"); target.db.openHelper.writableDatabase.execSQL(create) }
    private fun rangeRow(records: JSONObject): JSONObject = records.getJSONObject("tables").getJSONArray(table).getJSONObject(0)
    private fun firstSegment(anchor: JSONObject): JSONObject = anchor.getJSONArray("segments").getJSONObject(0)
    private fun alterAnchor(row: JSONObject, edit: (JSONObject) -> Unit) { val anchor = JSONObject(row.getString("anchorJson")); edit(anchor); row.put("anchorJson", anchor.toString()) }
    private fun manyAnnotations(bytes: ByteArray, legacyCount: Int, rangeCount: Int, plainExtras: Int = 0): ByteArray = rewrite(bytes) { _, records ->
        val tables = records.getJSONObject("tables"); val original = tables.getJSONArray("epub_passages").getJSONObject(0)
        val old = JSONArray(); repeat(legacyCount) { ordinal -> old.put(JSONObject(original.toString()).put("id", UUID.randomUUID().toString()).put("resourceHref", "old-$ordinal.xhtml")) }
        val ranges = JSONArray(); repeat(rangeCount) { ranges.put(makeRange(it, suffix = "-many")) }
        repeat(plainExtras) { ranges.put(makeRange(it, color = null, suffix = "-plain").put("attachedNote", JSONObject.NULL)) }
        tables.put("epub_passages", old).put(table, ranges)
    }
    private fun pendingJournal(): String = JSONObject().put("books", JSONArray().put(JSONObject().put("id", epubId)
        .put("storageName", "$epubId.epub").put("format", "EPUB").put("byteSize", epubBytes.size).put("sha256", hash(epubBytes))))
        .put("previousSettings", BackupRecordsCodec.encodeSettings(previous)).toString()
    private fun legacy(bytes: ByteArray, version: Int): ByteArray = rewrite(bytes) { manifest, records ->
        manifest.put("formatVersion", version).put("databaseVersion", when (version) { 1, 2, 3 -> 12; 4 -> 13; 5 -> 14; 6 -> 15; 7 -> 16; 8 -> 17; else -> error("Not legacy") })
        val tables = records.getJSONObject("tables"); stripWordReviewTables(tables); tables.remove("pdf_annotations"); tables.remove(table)
        if (version <= 7) tables.remove("book_pdf_view_preferences")
        if (version <= 6) tables.remove("reading_duration_receipts")
        if (version <= 5) { val rows = tables.getJSONArray("epub_passages"); for (i in 0 until rows.length()) rows.getJSONObject(i).apply { remove("highlightColor"); remove("attachedNote"); remove("revision") } }
        if (version <= 4) { tables.remove("book_covers"); manifest.remove("coverCount"); manifest.remove("covers") }
        if (version <= 3) tables.remove("book_organizations")
        records.asPreBookSpacingTables().getJSONObject("settings").asPreSpacingSettings().apply { if (version < 3) remove("textAlignment"); if (version < 2) remove("scroll") }
    }
    private fun rewrite(bytes: ByteArray, edit: (JSONObject, JSONObject) -> Unit): ByteArray {
        val archive = entries(bytes); val manifest = json(archive.getValue("manifest.json")); val records = json(archive.getValue("records.json")); edit(manifest, records)
        val encoded = records.toString().toByteArray(Charsets.UTF_8); manifest.put("recordsSize", encoded.size).put("recordsSha256", hash(encoded))
        archive["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8); archive["records.json"] = encoded
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> archive.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() } } }.toByteArray()
    }
    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { result -> ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes(); zip.closeEntry() } } }
    private fun json(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Settings(initial: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(initial); var attempts = 0
        override suspend fun save(settings: EpubSettings) { attempts++; this.settings.value = settings }
    }
    private class Documents(private val beforeOutput: (() -> Unit)? = null) : BackupDocumentProvider {
        var bytes: ByteArray? = null; var deletes = 0; var beforeInput: (() -> Unit)? = null
        override fun accepts(uri: String) = uri.startsWith("content://")
        override fun openInput(uri: String): InputStream { beforeInput?.invoke(); return ByteArrayInputStream(requireNotNull(bytes)) }
        override fun openOutput(uri: String): OutputStream { beforeOutput?.invoke(); return object : ByteArrayOutputStream() { override fun close() { super.close(); bytes = toByteArray() } } }
        override fun delete(uri: String): Boolean { deletes++; bytes = null; return true }
    }
}

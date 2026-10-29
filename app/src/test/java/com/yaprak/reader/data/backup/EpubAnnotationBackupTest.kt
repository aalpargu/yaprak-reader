package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.BookNoteEntity
import com.yaprak.reader.data.persistence.EpubPassageEntity
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomEpubPassageRepository
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.backup.BackupResult
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.domain.passages.MAX_EPUB_ANNOTATIONS_PER_BOOK
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.EpubSettingsRepository
import com.yaprak.reader.domain.settings.ReadingTextAlignment
import com.yaprak.reader.domain.settings.ReadingTheme
import com.yaprak.reader.reader.SourceTextAnchor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
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
@Config(sdk = [35], application = Application::class)
class EpubAnnotationBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
    private val id = "11111111-1111-4111-8111-111111111111"
    private val book = Book(id, "Ders", "Yazar", BookFormat.EPUB, "source.epub",
        "$id.epub", bytes.size.toLong(), hash(bytes), 11)
    private val quote = "  İğne\n🌿  "
    private val note = "  Not\n```\n<img src=x> [link](https://example.invalid)\t漢字 🌿  "
    private val preferences = EpubSettings(theme = ReadingTheme.SEPIA, scroll = true,
        textAlignment = ReadingTextAlignment.JUSTIFY)

    @Test fun `v9 real zip and reopened database retain colors exact notes revisions and plain passages`() = runBlocking {
        val source = fixture("source", preferences)
        var target = fixture("target")
        val docs = Documents()
        try {
            seed(source)
            val expected = passages(source)
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val archive = entries(requireNotNull(docs.bytes))
            val manifest = json(archive.getValue("manifest.json"))
            assertEquals(26, manifest.getInt("formatVersion"))
            assertEquals(26, manifest.getInt("databaseVersion"))
            val rows = json(archive.getValue("records.json")).getJSONObject("tables").getJSONArray("epub_passages")
            assertEquals(5, rows.length())
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(expected, passages(target))
            assertArrayEquals(bytes, File(target.books, book.storageName).readBytes())
            assertEquals(preferences, target.settings.settings.first())
            assertFalse(target.journal.exists())
            target.db.close()
            target = target.copy(db = open(target.databaseName))
            assertEquals(expected, passages(target))
            assertEquals(1L, count(target, "book_notes"))
        } finally { source.close(); target.close() }
    }

    @Test fun `all legacy one through five exact archives hydrate plain annotations without losing old fields`() = runBlocking {
        val source = fixture("legacy-source", preferences)
        val docs = Documents()
        try {
            seed(source)
            val expected = passages(source).map { it.copy(highlightColor = null, attachedNote = null, revision = 0) }
            source.backup(docs).create("content://backup")
            val original = requireNotNull(docs.bytes)
            for (version in 1..5) {
                val target = fixture("legacy-$version")
                try {
                    docs.bytes = legacy(original, version)
                    val oldRecords = json(entries(requireNotNull(docs.bytes)).getValue("records.json"))
                    val oldPassages = oldRecords.getJSONObject("tables").getJSONArray("epub_passages")
                    assertFalse(oldPassages.getJSONObject(0).has("highlightColor"))
                    assertFalse(oldPassages.getJSONObject(0).has("attachedNote"))
                    assertFalse(oldPassages.getJSONObject(0).has("revision"))
                    assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
                    assertEquals(expected, passages(target))
                    assertArrayEquals(bytes, File(target.books, book.storageName).readBytes())
                    assertEquals(preferences.copy(scroll = version >= 2,
                        textAlignment = if (version >= 3) ReadingTextAlignment.JUSTIFY else ReadingTextAlignment.PUBLISHER),
                        target.settings.settings.first())
                } finally { target.close() }
            }
        } finally { source.close() }
    }

    @Test fun `unknown malformed or missing new fields reject before books settings or files change`() = runBlocking {
        val source = fixture("invalid-source", preferences)
        val previous = EpubSettings(theme = ReadingTheme.DARK)
        val target = fixture("invalid-target", previous)
        val docs = Documents()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            val original = requireNotNull(docs.bytes)
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("highlightColor", "FUTURE") }, { it.put("highlightColor", "yellow") },
                { it.put("highlightColor", 4) }, { it.put("attachedNote", "") },
                { it.put("attachedNote", " \n\t") }, { it.put("attachedNote", "n".repeat(2001)) },
                { it.put("attachedNote", false) }, { it.put("revision", -1) },
                { it.put("revision", 0.5) }, { it.put("revision", "0") },
                { it.put("revision", JSONObject.NULL) }, { it.remove("revision") },
                { it.remove("attachedNote") }, { it.remove("highlightColor") }, { it.put("future", true) },
                { it.put("resourceHref", "../outside.xhtml") }, { it.put("nodeTextSha256", "bad") },
                { it.put("contentSha256", "f".repeat(64)) }, { it.put("endOffset", 4097) },
            )
            for (edit in edits) {
                docs.bytes = rewrite(original) { _, records ->
                    edit(records.getJSONObject("tables").getJSONArray("epub_passages").getJSONObject(0))
                }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target, previous)
                assertEquals(0, target.settings.attempts)
            }
        } finally { source.close(); target.close() }
    }

    @Test fun `escaped malformed Unicode annotation fields reject without replacement or persistent writes`() = runBlocking {
        val source = fixture("unicode-source", preferences)
        val target = fixture("unicode-target")
        val docs = Documents()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            val original = requireNotNull(docs.bytes)
            for (field in listOf("attachedNote", "text")) {
                for (escaped in listOf("\\ud800", "\\udc00", "\\ud800\\ud800")) {
                    docs.bytes = rewrite(original, escapedUnicode = escaped) { _, records ->
                        records.getJSONObject("tables").getJSONArray("epub_passages").getJSONObject(1).apply {
                            put(field, "__BAD_UTF16__")
                            if (field == "text") {
                                put("startOffset", 0)
                                put("endOffset", if (escaped == "\\ud800\\ud800") 2 else 1)
                            }
                        }
                    }
                    assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                    assertEmpty(target, EpubSettings())
                    assertEquals(0, target.settings.attempts)
                }
            }
        } finally { source.close(); target.close() }
    }

    @Test fun `shared backup mutex captures one annotation revision while an editor waits`() = runBlocking {
        val source = fixture("mutex-source", preferences)
        val mutex = Mutex()
        val entered = CountDownLatch(1)
        val continueOutput = CountDownLatch(1)
        val docs = Documents {
            entered.countDown()
            check(continueOutput.await(5, TimeUnit.SECONDS)) { "Output was not released" }
        }
        var backup: Deferred<BackupResult>? = null
        var edit: Deferred<EpubPassage>? = null
        try {
            seed(source)
            val previous = passages(source).first { it.highlightColor != null }
            val repo = RoomEpubPassageRepository(source.db, fileMutex = mutex)
            backup = async(Dispatchers.IO) { source.backup(docs, mutex).create("content://backup") }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            val attempted = CompletableDeferred<Unit>()
            edit = async(Dispatchers.IO) {
                attempted.complete(Unit)
                repo.updateAnnotation(id, book.sha256, previous, EpubHighlightColor.BLUE, "Later revision")
            }
            attempted.await()
            assertFalse(edit.isCompleted)
            continueOutput.countDown()
            assertEquals(BackupResult.Created(1), backup.await())
            val updated = edit.await()
            val archived = json(entries(requireNotNull(docs.bytes)).getValue("records.json"))
                .getJSONObject("tables").getJSONArray("epub_passages")
            val old = (0 until archived.length()).map { archived.getJSONObject(it) }.single { it.getString("id") == previous.id }
            assertEquals(previous.revision, old.getLong("revision"))
            assertEquals(previous.attachedNote, old.getString("attachedNote"))
            assertEquals(previous.highlightColor!!.name, old.getString("highlightColor"))
            assertEquals(previous.revision + 1, updated.revision)
            assertEquals(updated, passages(source).first { it.id == previous.id })
        } finally {
            continueOutput.countDown()
            backup?.cancelAndJoin()
            edit?.cancelAndJoin()
            source.close()
        }
    }

    @Test fun `cancelled annotation backup cleans its target preserves all rows and releases the shared mutex`() = runBlocking {
        val source = fixture("cancel-source", preferences)
        val mutex = Mutex()
        val docs = Documents { throw CancellationException("Output cancelled") }
        try {
            seed(source)
            val expected = passages(source)
            assertTrue(runCatching { source.backup(docs, mutex).create("content://backup") }
                .exceptionOrNull() is CancellationException)
            assertEquals(expected, passages(source))
            assertEquals(1, docs.deletes)
            assertNull(docs.bytes)
            assertFalse(source.journal.exists())
            assertArrayEquals(bytes, File(source.books, book.storageName).readBytes())
            val current = expected.first { it.highlightColor != null }
            val updated = RoomEpubPassageRepository(source.db, fileMutex = mutex)
                .updateAnnotation(id, book.sha256, current, EpubHighlightColor.GREEN, "After cancellation")
            assertEquals(current.revision + 1, updated.revision)
        } finally { source.close() }
    }

    @Test fun `old header cannot hide annotation fields and format database pairs stay strict`() = runBlocking {
        val source = fixture("header-source", preferences)
        val target = fixture("header-target")
        val docs = Documents()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            val original = requireNotNull(docs.bytes)
            for (version in 1..5) {
                docs.bytes = legacy(original, version, stripFields = false)
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target, EpubSettings())
            }
            for ((format, database) in listOf(6 to 14, 5 to 15, 7 to 15)) {
                docs.bytes = rewrite(original) { manifest, _ ->
                    manifest.put("formatVersion", format).put("databaseVersion", database)
                }
                assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), target.backup(docs).restore("content://backup"))
                assertEmpty(target, EpubSettings())
            }
        } finally { source.close(); target.close() }
    }

    @Test fun `annotation archive limit rejects all records before persistent writes`() = runBlocking {
        val source = fixture("limit-source", preferences)
        val target = fixture("limit-target")
        val docs = Documents()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            docs.bytes = rewrite(requireNotNull(docs.bytes)) { _, records ->
                val passages = records.getJSONObject("tables").getJSONArray("epub_passages")
                val original = passages.getJSONObject(0)
                val many = JSONArray()
                repeat(MAX_EPUB_ANNOTATIONS_PER_BOOK + 1) { index ->
                    many.put(JSONObject(original.toString()).put("id", "p-$index")
                        .put("resourceHref", "OPS/$index.xhtml").put("highlightColor", "GREEN"))
                }
                records.getJSONObject("tables").put("epub_passages", many)
            }
            assertEquals(BackupResult.Failed(BackupFailure.TOO_LARGE), target.backup(docs).restore("content://backup"))
            assertEmpty(target, EpubSettings())
        } finally { source.close(); target.close() }
    }

    @Test fun `settings failure rolls all annotations back then a later restore succeeds`() = runBlocking {
        val source = fixture("rollback-source", preferences)
        val previous = EpubSettings(theme = ReadingTheme.DARK)
        val target = fixture("rollback-target", previous)
        val docs = Documents()
        try {
            seed(source)
            val expected = passages(source)
            source.backup(docs).create("content://backup")
            target.settings.failNext = true
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs).restore("content://backup"))
            assertEmpty(target, previous)
            assertEquals(2, target.settings.attempts)
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertEquals(expected, passages(target))
        } finally { source.close(); target.close() }
    }

    @Test fun `reopened pending restore journal removes only its books and annotations`() = runBlocking {
        var fixture = fixture("recovery", preferences)
        val previous = EpubSettings(theme = ReadingTheme.DARK)
        val otherId = "22222222-2222-4222-8222-222222222222"
        val other = book.copy(id = otherId, storageName = "$otherId.epub", sha256 = "c".repeat(64))
        try {
            seed(fixture)
            fixture.library.add(other)
            File(fixture.books, other.storageName).writeBytes(bytes)
            val keep = RoomEpubPassageRepository(fixture.db).createAnnotation(other.id, other.sha256,
                quote, SourceTextAnchor("OPS/other.xhtml", 0, quote.length, "d".repeat(64)), EpubHighlightColor.PINK, "Other note")
            fixture.journal.writeText(JSONObject().put("books", JSONArray().put(JSONObject()
                .put("id", id).put("storageName", book.storageName).put("format", "EPUB")
                .put("byteSize", book.byteSize).put("sha256", book.sha256)))
                .put("previousSettings", BackupRecordsCodec.encodeSettings(previous)).toString(), Charsets.UTF_8)
            fixture.db.close()
            fixture = fixture.copy(db = open(fixture.databaseName))
            fixture.backup(Documents()).recoverPendingRestore()
            assertNull(fixture.library.findById(id))
            assertEquals(listOf(keep), RoomEpubPassageRepository(fixture.db).observe(other.id, other.sha256).first())
            assertEquals(1L, count(fixture, "epub_passages"))
            assertFalse(File(fixture.books, book.storageName).exists())
            assertTrue(File(fixture.books, other.storageName).exists())
            assertFalse(fixture.journal.exists())
            assertEquals(previous, fixture.settings.settings.first())
        } finally { fixture.close() }
    }

    private data class Fixture(val db: LibraryDatabase, val databaseName: String, val books: File,
        val stage: File, val journal: File, val settings: Settings) {
        val library = RoomLibraryRepository(db.books())
        fun backup(docs: BackupDocumentProvider, mutex: Mutex = Mutex()) =
            DefaultLibraryBackup(db, books, stage, journal, settings, docs, mutex)
        fun close() {
            db.close()
            ApplicationProvider.getApplicationContext<Application>().deleteDatabase(databaseName)
        }
    }

    private fun open(name: String) = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()

    private fun fixture(name: String, preferences: EpubSettings = EpubSettings()): Fixture {
        val parent = temporary.newFolder(name)
        val databaseName = "ab-${UUID.randomUUID().toString().take(8)}.db"
        return Fixture(open(databaseName), databaseName, File(parent, "books").apply { mkdir() },
            File(parent, "stage"), File(parent, "restore.pending"), Settings(preferences))
    }

    private suspend fun seed(fixture: Fixture) {
        fixture.library.add(book)
        File(fixture.books, book.storageName).writeBytes(bytes)
        fixture.db.bookNotes().add(BookNoteEntity("ordinary", id, book.sha256, "Eski not", 60))
        fixture.db.epubPassages().add(EpubPassageEntity("plain", id, book.sha256, 1,
            "OPS/plain.xhtml", "b".repeat(64), 0, quote.length, quote, 70))
        EpubHighlightColor.entries.forEachIndexed { index, color ->
            fixture.db.epubPassages().add(EpubPassageEntity("annotation-$index", id, book.sha256, 1,
                "OPS/$index.xhtml", "b".repeat(64), 0, quote.length, quote, 80 + index.toLong(), color.name, note, 9 + index.toLong()))
        }
    }

    private suspend fun passages(fixture: Fixture) = RoomEpubPassageRepository(fixture.db).observe(id, book.sha256).first()

    private suspend fun assertEmpty(fixture: Fixture, previous: EpubSettings) {
        for (table in listOf("books", "epub_passages", "book_notes")) assertEquals(table, 0L, count(fixture, table))
        assertTrue(fixture.books.listFiles().isNullOrEmpty())
        assertTrue(fixture.stage.listFiles().isNullOrEmpty())
        assertFalse(fixture.journal.exists())
        assertEquals(previous, fixture.settings.settings.first())
    }

    private suspend fun count(fixture: Fixture, table: String): Long = withContext(Dispatchers.IO) {
        fixture.db.openHelper.writableDatabase.compileStatement("SELECT count(*) FROM $table").simpleQueryForLong()
    }

    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { output ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                output[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
    }

    private fun legacy(bytes: ByteArray, version: Int, stripFields: Boolean = true): ByteArray = rewrite(bytes) { manifest, records ->
        manifest.put("formatVersion", version).put("databaseVersion", when (version) { 1, 2, 3 -> 12; 4 -> 13; else -> 14 })
        val tables = records.getJSONObject("tables")
        stripWordReviewTables(tables); tables.remove("pdf_annotations"); tables.remove("epub_range_passages")
        tables.remove("book_pdf_view_preferences")
        tables.remove("reading_duration_receipts")
        if (version <= 4) { manifest.remove("coverCount"); manifest.remove("covers"); tables.remove("book_covers") }
        if (version <= 3) tables.remove("book_organizations")
        if (stripFields) {
            val passages = tables.getJSONArray("epub_passages")
            for (index in 0 until passages.length()) passages.getJSONObject(index).apply {
                remove("highlightColor"); remove("attachedNote"); remove("revision")
            }
        }
        records.asPreBookSpacingTables().getJSONObject("settings").asPreSpacingSettings().apply {
            if (version < 3) remove("textAlignment")
            if (version < 2) remove("scroll")
        }
    }

    private fun rewrite(bytes: ByteArray, escapedUnicode: String? = null, edit: (JSONObject, JSONObject) -> Unit): ByteArray {
        val archive = entries(bytes)
        val manifest = json(archive.getValue("manifest.json"))
        val records = json(archive.getValue("records.json"))
        edit(manifest, records)
        val encoded = records.toString().let { if (escapedUnicode == null) it else
            it.replace("__BAD_UTF16__", escapedUnicode) }.toByteArray(Charsets.UTF_8)
        manifest.put("recordsSize", encoded.size).put("recordsSha256", hash(encoded))
        archive["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        archive["records.json"] = encoded
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> archive.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            } }
        }.toByteArray()
    }

    private fun json(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Settings(initial: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(initial)
        var failNext = false
        var attempts = 0
        override suspend fun save(settings: EpubSettings) {
            attempts++
            if (failNext) { failNext = false; throw IOException("Simulated settings failure") }
            this.settings.value = settings
        }
    }

    private class Documents(private val beforeOutput: (() -> Unit)? = null) : BackupDocumentProvider {
        var bytes: ByteArray? = null
        var deletes = 0
        override fun accepts(uri: String) = uri.startsWith("content://")
        override fun openInput(uri: String): InputStream = ByteArrayInputStream(requireNotNull(bytes))
        override fun openOutput(uri: String): OutputStream {
            beforeOutput?.invoke()
            return object : ByteArrayOutputStream() {
                override fun close() { super.close(); bytes = toByteArray() }
            }
        }
        override fun delete(uri: String): Boolean { deletes++; bytes = null; return true }
    }
}

package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.pdfink.*
import com.yaprak.reader.domain.settings.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ZIP/Room/files/restore engine for highlighter strokes (backup format 28). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfInkBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val bookId = "33333333-3333-4333-8333-333333333333"
    private val sourceBytes = "%PDF-1.4\nink archive fixture".toByteArray()
    private val sha = hash(sourceBytes)
    private val previous = EpubSettings(theme = ReadingTheme.DARK)
    private val settings = EpubSettings(150, theme = ReadingTheme.SEPIA)
    private val table = "pdf_ink_strokes"
    private val strokes = listOf(
        PdfInkStroke("11111111-1111-4111-8111-111111111111", 3, PdfHighlightColor.YELLOW, PdfInkMode.ALIGNED,
            listOf(PdfInkSegment(3030, listOf(PdfInkPoint(10_000, 12_000), PdfInkPoint(70_000, 12_000))),
                PdfInkSegment(1136, listOf(PdfInkPoint(10_000, 31_000), PdfInkPoint(45_000, 31_000)))), 10),
        PdfInkStroke("22222222-1111-4111-8111-111111111111", 0, PdfHighlightColor.BLUE, PdfInkMode.FREEHAND,
            listOf(PdfInkSegment(DEFAULT_PDF_INK_WIDTH, (0 until 40).map { PdfInkPoint(it * 2000, 50_000 + (it % 5) * 300) })), 11),
    )

    @Test fun strokesRoundTripThroughZipAndReopen() = runBlocking {
        val source = fixture("source"); var target = fixture("target", previous); val documents = Documents()
        try {
            seed(source)
            assertEquals(BackupResult.Created(1), source.backup(documents).create("content://backup"))
            val zip = entries(requireNotNull(documents.bytes)); val manifest = json(zip.getValue("manifest.json"))
            assertEquals(28, manifest.getInt("formatVersion")); assertEquals(28, manifest.getInt("databaseVersion"))
            val archived = json(zip.getValue("records.json")).getJSONObject("tables").getJSONArray(table)
            assertEquals(2, archived.length())
            assertEquals(setOf("id", "bookId", "contentSha256", "pageIndex", "color", "mode", "geometry", "createdAt"),
                archived.getJSONObject(0).keys().asSequence().toSet())
            assertEquals(BackupResult.Restored(1), target.backup(documents).restore("content://backup"))
            target.db.close(); target = target.copy(db = open(target.name))
            assertEquals(strokes, RoomPdfInkRepository(target.db).observe(bookId, sha).first())
            assertArrayEquals(sourceBytes, File(target.books, "$bookId.pdf").readBytes())
        } finally { source.close(); target.close() }
    }

    @Test fun formatTwentySevenArchivesRestoreWithoutInkAndCannotSmuggleIt() = runBlocking {
        val source = fixture("source"); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            val target = fixture("old", previous)
            try {
                docs.bytes = rewrite(original) { manifest, records ->
                    manifest.put("formatVersion", 27).put("databaseVersion", 27)
                    records.getJSONObject("tables").remove(table)
                }
                assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
                assertTrue(RoomPdfInkRepository(target.db).observe(bookId, sha).first().isEmpty())
                assertArrayEquals(sourceBytes, File(target.books, "$bookId.pdf").readBytes())
            } finally { target.close() }
            val strict = fixture("strict", previous)
            try {
                for (empty in listOf(false, true)) {
                    docs.bytes = rewrite(original) { manifest, records ->
                        manifest.put("formatVersion", 27).put("databaseVersion", 27)
                        if (empty) records.getJSONObject("tables").put(table, JSONArray())
                    }
                    assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), strict.backup(docs).restore("content://backup"))
                    assertEmpty(strict)
                }
                docs.bytes = rewrite(original) { manifest, _ -> manifest.put("databaseVersion", 27) }
                assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), strict.backup(docs).restore("content://backup"))
                assertEmpty(strict)
            } finally { strict.close() }
        } finally { source.close() }
    }

    @Test fun malformedStrokesFailBeforeAnyRestoreCommit() = runBlocking {
        val source = fixture("source"); val target = fixture("target", previous); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("id", "not-a-uuid") }, { it.put("pageIndex", 12) }, { it.put("pageIndex", -1) },
                { it.put("color", "ORANGE") }, { it.put("mode", "LASER") }, { it.put("createdAt", -1) },
                { it.put("geometry", "") }, { it.put("geometry", "1;1200,0,0,100001,0") }, { it.put("geometry", "2;1200,0,0") },
                { it.put("geometry", "1;01200,0,0") }, { it.put("contentSha256", "b".repeat(64)) },
                { it.put("bookId", UUID.randomUUID().toString()) }, { it.put("extra", 1) }, { it.remove("mode") },
                { it.put("pageIndex", "3") },
            )
            for (edit in edits) {
                docs.bytes = rewrite(original) { _, records -> edit(records.getJSONObject("tables").getJSONArray(table).getJSONObject(0)) }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target)
            }
            docs.bytes = rewrite(original) { _, records ->
                val items = records.getJSONObject("tables").getJSONArray(table)
                items.put(JSONObject(items.getJSONObject(0).toString()))
            }
            assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
            assertEmpty(target)
        } finally { source.close(); target.close() }
    }

    @Test fun aWeakenedCascadeFailsBeforeOpeningAnExportDocument() = runBlocking {
        val source = fixture("source"); val docs = Documents()
        try {
            seed(source)
            val sql = source.db.openHelper.writableDatabase
            val create = sql.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='$table'").use { it.moveToFirst(); it.getString(0) }
            sql.execSQL("DROP TABLE $table"); sql.execSQL(create.replace("ON DELETE CASCADE", "ON DELETE NO ACTION"))
            assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), source.backup(docs).create("content://backup"))
            assertNull(docs.bytes)
        } finally { source.close() }
    }

    private data class Fixture(val db: LibraryDatabase, val name: String, val books: File, val stage: File, val journal: File, val settings: Settings) {
        fun backup(documents: BackupDocumentProvider, mutex: Mutex = Mutex()) =
            DefaultLibraryBackup(db, books, stage, journal, settings, documents, mutex)
        fun close() { db.close(); ApplicationProvider.getApplicationContext<Application>().deleteDatabase(name) }
    }
    private fun open(name: String): LibraryDatabase { context.getDatabasePath(name).parentFile!!.mkdirs(); return Room.databaseBuilder(context, LibraryDatabase::class.java, name).build() }
    private fun fixture(label: String, initial: EpubSettings = settings): Fixture {
        val parent = temporary.newFolder(label); val name = "ib-${UUID.randomUUID().toString().take(8)}.db"
        return Fixture(open(name), name, File(parent, "books").apply { mkdir() }, File(parent, "stage"), File(parent, "pending"), Settings(initial))
    }
    private suspend fun seed(target: Fixture) {
        target.db.books().insert(BookEntity(bookId, "PDF title", "Author", "PDF", "source.pdf", "$bookId.pdf", sourceBytes.size.toLong(), sha, 1, 12))
        File(target.books, "$bookId.pdf").writeBytes(sourceBytes)
        strokes.forEach { RoomPdfInkRepository(target.db).add(bookId, sha, it) }
    }
    private suspend fun assertEmpty(target: Fixture) {
        assertTrue(target.db.books().observeAll().first().isEmpty())
        assertEquals(0L, target.db.openHelper.readableDatabase.compileStatement("SELECT COUNT(*) FROM $table").simpleQueryForLong())
        assertTrue(target.books.listFiles().isNullOrEmpty()); assertFalse(target.journal.exists())
        assertEquals(previous, target.settings.settings.value)
    }
    private fun rewrite(bytes: ByteArray, edit: (JSONObject, JSONObject) -> Unit): ByteArray {
        val zip = entries(bytes); val manifest = json(zip.getValue("manifest.json")); val records = json(zip.getValue("records.json")); edit(manifest, records)
        val encoded = records.toString().toByteArray(); manifest.put("recordsSize", encoded.size).put("recordsSha256", hash(encoded))
        zip["records.json"] = encoded; zip["manifest.json"] = manifest.toString().toByteArray()
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { archive ->
            zip.forEach { (name, content) -> archive.putNextEntry(ZipEntry(name)); archive.write(content); archive.closeEntry() }
        } }.toByteArray()
    }
    private fun entries(bytes: ByteArray) = linkedMapOf<String, ByteArray>().also { result ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes(); zip.closeEntry() } }
    }
    private fun json(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Settings(initial: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun save(settings: EpubSettings) { this.settings.value = settings }
    }
    private class Documents : BackupDocumentProvider {
        var bytes: ByteArray? = null
        override fun accepts(uri: String) = uri.startsWith("content://")
        override fun openInput(uri: String): InputStream = ByteArrayInputStream(requireNotNull(bytes))
        override fun openOutput(uri: String): OutputStream = object : ByteArrayOutputStream() { override fun close() { super.close(); bytes = toByteArray() } }
        override fun delete(uri: String): Boolean { bytes = null; return true }
    }
}

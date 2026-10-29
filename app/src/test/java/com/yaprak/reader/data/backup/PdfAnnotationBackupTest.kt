package com.yaprak.reader.data.backup

import android.app.Application
import android.content.ContentValues
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.reader.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ZIP/Room/files/restore engine; these fixtures do not claim native PDF decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfAnnotationBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val bookId = "22222222-2222-4222-8222-222222222222"
    private val sourceBytes = "%PDF-1.4\ncontrolled archive fixture".toByteArray()
    private val sha = hash(sourceBytes)
    private val previous = EpubSettings(theme = ReadingTheme.DARK)
    private val settings = EpubSettings(150, theme = ReadingTheme.SEPIA)
    private val quote = " alpha 🌿\nİğne "
    private val note = "  Not\n```\n<img src=x> [link](https://example.invalid)\t漢字 🌿  "
    private val table = "pdf_annotations"
    private val index = "index_pdf_annotations_bookId_contentSha256_selectionKey"

    @Test fun completeNativeGeometryColorsNotesAndRevisionsRoundTripThroughZipAndReopen() = runBlocking {
        val source = fixture("source"); var target = fixture("target", previous); val documents = Documents()
        try {
            seed(source); val before = rows(source).map(JSONObject::toString)
            assertEquals(BackupResult.Created(1), source.backup(documents).create("content://backup"))
            val zip = entries(requireNotNull(documents.bytes)); val manifest = json(zip.getValue("manifest.json"))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            assertEquals(13, json(zip.getValue("records.json")).getJSONObject("tables").getJSONArray(table).getJSONObject(0).length())
            assertEquals(BackupResult.Restored(1), target.backup(documents).restore("content://backup"))
            assertEquals(before, rows(target).map(JSONObject::toString)); assertEquals(settings, target.settings.settings.value)
            assertArrayEquals(sourceBytes, File(target.books, "$bookId.pdf").readBytes())
            target.db.close(); target = target.copy(db = open(target.name))
            val saved = repository(target).observe(bookId, sha).first()
            assertEquals(5, saved.size); assertEquals(setOf(null, *PdfHighlightColor.entries.toTypedArray()), saved.map { it.highlightColor }.toSet())
            assertTrue(saved.all { it.text == quote && it.anchor.bounds.size == 2 })
            assertTrue(saved.any { it.attachedNote == note && it.revision == Long.MAX_VALUE })
            assertEquals(before, rows(target).map(JSONObject::toString)); assertFalse(target.journal.exists())
        } finally { source.close(); target.close() }
    }

    @Test fun allNineFrozenLegacyShapesPreservePageNotesWithoutInventingGeometry() = runBlocking {
        val source = fixture("source"); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            for (version in 1..9) {
                val target = fixture("legacy$version", previous)
                try {
                    docs.bytes = rewrite(original) { manifest, records ->
                        manifest.put("formatVersion", version).put("databaseVersion", when (version) {
                            1, 2, 3 -> 12; 4 -> 13; 5 -> 14; 6 -> 15; 7 -> 16; 8 -> 17; else -> 18
                        })
                        val tables = records.getJSONObject("tables"); stripWordReviewTables(tables); tables.remove(table)
                        if (version <= 8) tables.remove("epub_range_passages")
                        if (version <= 7) tables.remove("book_pdf_view_preferences")
                        if (version <= 6) tables.remove("reading_duration_receipts")
                        if (version <= 4) { tables.remove("book_covers"); manifest.remove("coverCount"); manifest.remove("covers") }
                        if (version <= 3) tables.remove("book_organizations")
                        records.asPreBookSpacingTables().getJSONObject("settings").asPreSpacingSettings().apply { if (version <= 2) remove("textAlignment"); if (version == 1) remove("scroll") }
                    }
                    assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
                    assertTrue(repository(target).observe(bookId, sha).first().isEmpty())
                    assertEquals(1, target.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM book_notes WHERE pdfPageIndex=3").use { it.moveToFirst(); it.getInt(0) })
                    assertArrayEquals(sourceBytes, File(target.books, "$bookId.pdf").readBytes())
                } finally { target.close() }
            }
        } finally { source.close() }
    }

    @Test fun aLegacyHeaderCannotHideEvenAnEmptyPdfTableAndVersionsStayPaired() = runBlocking {
        val source = fixture("source"); val target = fixture("target", previous); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            for (empty in listOf(false, true)) {
                docs.bytes = rewrite(original) { manifest, records ->
                    manifest.put("formatVersion", 9).put("databaseVersion", 18)
                    if (empty) records.getJSONObject("tables").put(table, JSONArray())
                }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup")); assertEmpty(target)
            }
            for ((format, database) in listOf(10 to 18, 9 to 19, 12 to 21)) {
                docs.bytes = rewrite(original) { manifest, _ -> manifest.put("formatVersion", format).put("databaseVersion", database) }
                assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), target.backup(docs).restore("content://backup")); assertEmpty(target)
            }
        } finally { source.close(); target.close() }
    }

    @Test fun malformedRowsAndDetachedNativeGeometryFailBeforeAnyRestoreCommit() = runBlocking {
        val source = fixture("source"); val target = fixture("target", previous); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("id", "not-a-uuid") }, { it.put("anchorVersion", 2) }, { it.put("pageIndex", 11) },
                { it.put("revision", -1) }, { it.put("createdAt", -1) }, { it.put("revision", "0") },
                { it.put("highlightColor", "FUTURE") }, { it.put("attachedNote", " \n") },
                { it.put("attachedNote", "x".repeat(2001)) }, { it.put("text", "x".repeat(5001)) },
                { it.put("text", "changed") }, { it.put("anchorJson", "{}") }, { it.put("anchorKey", "b".repeat(64)) },
                { it.put("selectionKey", "b".repeat(64)) }, { it.put("contentSha256", "b".repeat(64)) },
                { it.put("highlightColor", JSONObject.NULL).put("attachedNote", JSONObject.NULL) },
                { it.put("future", true) }, { it.remove("selectionKey") },
                { val a = JSONObject(it.getString("anchorJson")); a.put("bounds", JSONArray()); it.put("anchorJson", a.toString()) },
            )
            for (edit in edits) {
                docs.bytes = rewrite(original) { _, records -> edit(records.getJSONObject("tables").getJSONArray(table).getJSONObject(0)) }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup")); assertEmpty(target)
            }
        } finally { source.close(); target.close() }
    }

    @Test fun duplicatePhysicalSelectionAndTheThousandRecordLimitCannotBeBypassedInArchives() = runBlocking {
        val source = fixture("source"); val target = fixture("target", previous); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            docs.bytes = rewrite(original) { _, records ->
                val items = records.getJSONObject("tables").getJSONArray(table); val copy = JSONObject(items.getJSONObject(0).toString())
                val old = PdfAnchorCodec.decode(copy.getString("anchorJson"))
                val shiftedTouch = PdfSourceAnchor(old.pageIndex, old.pageWidth, old.pageHeight, PdfPagePoint(old.start.x+1, old.start.y), old.stop, old.bounds)
                copy.put("id", UUID.randomUUID().toString()).put("anchorJson", PdfAnchorCodec.encode(shiftedTouch)).put("anchorKey", PdfAnchorCodec.key(shiftedTouch))
                items.put(copy)
            }
            assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup")); assertEmpty(target)
            docs.bytes = rewrite(original) { _, records -> records.getJSONObject("tables").put(table, JSONArray((0..1000).map(::row))) }
            assertEquals(BackupResult.Failed(BackupFailure.TOO_LARGE), target.backup(docs).restore("content://backup")); assertEmpty(target)
        } finally { source.close(); target.close() }
    }

    @Test fun rollbackAfterSettingsFailureRemovesAllNewAnnotationsAndOriginalFiles() = runBlocking {
        val source = fixture("source"); val target = fixture("target", previous); val docs = Documents()
        val failing = object : EpubSettingsRepository {
            override val settings = target.settings.settings
            override suspend fun save(settings: EpubSettings) {
                if (settings != previous) { assertEquals(5, rows(target).size); throw IOException("controlled commit gap") }
                target.settings.save(settings)
            }
        }
        try {
            seed(source); source.backup(docs).create("content://backup")
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs, configuration = failing).restore("content://backup")); assertEmpty(target)
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup")); assertEquals(5, rows(target).size)
        } finally { source.close(); target.close() }
    }

    @Test fun backupAndAnnotationEditShareTheFileMutexAndArchiveOneCompleteRevision() = runBlocking {
        val source = fixture("source"); val mutex = Mutex(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val docs = Documents { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        var export: Deferred<BackupResult>? = null; var edit: Deferred<PdfAnnotation>? = null
        try {
            seed(source); val repo = repository(source, mutex); val old = repo.observe(bookId, sha).first().first { it.revision == 0L }
            export = async(Dispatchers.IO) { source.backup(docs, mutex).create("content://backup") }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            val attempted = CompletableDeferred<Unit>()
            edit = async(Dispatchers.IO) { attempted.complete(Unit); repo.update(bookId, sha, old, PdfHighlightColor.PINK, "changed") }
            attempted.await(); assertFalse(edit.isCompleted); release.countDown(); assertEquals(BackupResult.Created(1), export.await())
            val changed = edit.await(); assertEquals(1L, changed.revision)
            val items = json(entries(requireNotNull(docs.bytes)).getValue("records.json")).getJSONObject("tables").getJSONArray(table)
            val archived = (0 until items.length()).map(items::getJSONObject).single { it.getString("id") == old.id }
            assertEquals(0L, archived.getLong("revision")); assertEquals(old.highlightColor!!.name, archived.getString("highlightColor"))
            assertEquals(changed, repo.observe(bookId, sha).first().single { it.id == old.id })
        } finally { release.countDown(); export?.cancelAndJoin(); edit?.cancelAndJoin(); source.close() }
    }

    @Test fun weakenedUniqueIndexesAndForeignKeyCascadeFailBeforeOpeningAnExportDocument() = runBlocking {
        val source = fixture("source"); val docs = Documents()
        try {
            seed(source)
            for (statement in listOf(
                "CREATE INDEX $index ON $table(bookId,contentSha256,selectionKey)",
                "CREATE UNIQUE INDEX $index ON $table(bookId,selectionKey,contentSha256)",
                "CREATE UNIQUE INDEX $index ON $table(bookId,contentSha256,selectionKey COLLATE NOCASE)",
                "CREATE UNIQUE INDEX $index ON $table(bookId,contentSha256,selectionKey) WHERE revision=0",
            )) {
                val sql = source.db.openHelper.writableDatabase; sql.execSQL("DROP INDEX $index"); sql.execSQL(statement)
                assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), source.backup(docs).create("content://backup")); assertNull(docs.bytes)
            }
            val sql = source.db.openHelper.writableDatabase
            val create = sql.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='$table'").use { it.moveToFirst(); it.getString(0) }
            sql.execSQL("DROP TABLE $table"); sql.execSQL(create.replace("ON DELETE CASCADE", "ON DELETE NO ACTION"))
            sql.execSQL("CREATE UNIQUE INDEX $index ON $table(bookId,contentSha256,selectionKey)")
            assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), source.backup(docs).create("content://backup")); assertNull(docs.bytes)
        } finally { source.close() }
    }

    private data class Fixture(val db: LibraryDatabase, val name: String, val books: File, val stage: File, val journal: File, val settings: Settings) {
        fun backup(documents: BackupDocumentProvider, mutex: Mutex = Mutex(), configuration: EpubSettingsRepository = settings) =
            DefaultLibraryBackup(db, books, stage, journal, configuration, documents, mutex)
        fun close() { db.close(); ApplicationProvider.getApplicationContext<Application>().deleteDatabase(name) }
    }
    private fun open(name: String): LibraryDatabase { context.getDatabasePath(name).parentFile!!.mkdirs(); return Room.databaseBuilder(context, LibraryDatabase::class.java, name).build() }
    private fun fixture(label: String, initial: EpubSettings = settings): Fixture {
        val parent = temporary.newFolder(label); val name = "pb-${UUID.randomUUID().toString().take(8)}.db"
        return Fixture(open(name), name, File(parent,"books").apply { mkdir() }, File(parent,"stage"), File(parent,"pending"), Settings(initial))
    }
    private fun repository(target: Fixture, mutex: Mutex = Mutex()) = RoomPdfAnnotationRepository(target.db, PdfAnnotationOriginalSource { _, _, _ -> }, mutex)
    private suspend fun seed(target: Fixture) {
        target.db.books().insert(BookEntity(bookId,"PDF title","Author","PDF","source.pdf","$bookId.pdf",sourceBytes.size.toLong(),sha,1,12))
        File(target.books,"$bookId.pdf").writeBytes(sourceBytes)
        target.db.openHelper.writableDatabase.execSQL("INSERT INTO book_notes VALUES (?,?,?,?,?,?)", arrayOf<Any>(UUID.randomUUID().toString(), bookId, sha, "independent page note", 7, 3))
        repeat(5) { insert(target, row(it)) }
    }
    private fun row(ordinal: Int): JSONObject {
        val anchor = PdfSourceAnchor(3, 4000, 600, PdfPagePoint(ordinal+1,100), PdfPagePoint(ordinal+2,100),
            listOf(PdfTextBounds(ordinal+1.0,90.0,ordinal+2.0,110.0), PdfTextBounds(ordinal+1.0,120.0,ordinal+2.0,140.0)))
        return JSONObject().put("id",UUID.nameUUIDFromBytes("pdf-$ordinal".toByteArray()).toString()).put("bookId",bookId).put("contentSha256",sha)
            .put("anchorVersion",1).put("pageIndex",3).put("anchorJson",PdfAnchorCodec.encode(anchor)).put("anchorKey",PdfAnchorCodec.key(anchor))
            .put("selectionKey",PdfAnchorCodec.selectionKey(anchor,quote)).put("text",quote).put("createdAt",10L+ordinal)
            .put("highlightColor",if(ordinal==4) JSONObject.NULL else PdfHighlightColor.entries[ordinal % 4].name)
            .put("attachedNote",if(ordinal>=3) note else JSONObject.NULL).put("revision",if(ordinal==3) Long.MAX_VALUE else ordinal.toLong())
    }
    private fun insert(target: Fixture, row: JSONObject) {
        val values = ContentValues(); row.keys().forEach { key -> when(val value=row.get(key)) { JSONObject.NULL -> values.putNull(key); is String -> values.put(key,value); is Number -> values.put(key,value.toLong()); else -> error("fixture") } }
        target.db.openHelper.writableDatabase.insert(table,0,values)
    }
    private fun rows(target: Fixture): List<JSONObject> = target.db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { cursor -> buildList {
        while(cursor.moveToNext()) add(JSONObject().apply { cursor.columnNames.forEachIndexed { index, name -> put(name,when(cursor.getType(index)) {
            android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL; android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index); else -> cursor.getString(index)
        }) } })
    } }
    private suspend fun assertEmpty(target: Fixture) { assertTrue(target.db.books().observeAll().first().isEmpty()); assertTrue(rows(target).isEmpty()); assertTrue(target.books.listFiles().isNullOrEmpty()); assertTrue(target.stage.listFiles().isNullOrEmpty()); assertFalse(target.journal.exists()); assertEquals(previous,target.settings.settings.value) }
    private fun rewrite(bytes: ByteArray, edit: (JSONObject,JSONObject)->Unit): ByteArray {
        val zip=entries(bytes); val manifest=json(zip.getValue("manifest.json")); val records=json(zip.getValue("records.json")); edit(manifest,records)
        val encoded=records.toString().toByteArray(); manifest.put("recordsSize",encoded.size).put("recordsSha256",hash(encoded)); zip["records.json"]=encoded; zip["manifest.json"]=manifest.toString().toByteArray()
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { archive -> zip.forEach { (name,content) -> archive.putNextEntry(ZipEntry(name)); archive.write(content); archive.closeEntry() } } }.toByteArray()
    }
    private fun entries(bytes: ByteArray)=linkedMapOf<String,ByteArray>().also { result -> ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while(true) { val entry=zip.nextEntry?:break; result[entry.name]=zip.readBytes(); zip.closeEntry() } } }
    private fun json(bytes: ByteArray)=JSONObject(bytes.toString(Charsets.UTF_8))
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Settings(initial: EpubSettings): EpubSettingsRepository { override val settings=MutableStateFlow(initial); override suspend fun save(settings: EpubSettings) { this.settings.value=settings } }
    private class Documents(private val beforeOutput: (() -> Unit)?=null): BackupDocumentProvider {
        var bytes: ByteArray?=null
        override fun accepts(uri: String)=uri.startsWith("content://")
        override fun openInput(uri: String): InputStream=ByteArrayInputStream(requireNotNull(bytes))
        override fun openOutput(uri: String): OutputStream { beforeOutput?.invoke(); return object: ByteArrayOutputStream() { override fun close() { super.close(); bytes=toByteArray() } } }
        override fun delete(uri: String): Boolean { bytes=null; return true }
    }
}

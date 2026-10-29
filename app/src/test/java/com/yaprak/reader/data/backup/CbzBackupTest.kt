package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.backup.*
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.formats.cbz.*
import com.yaprak.reader.fixtures.CoverTestImages
import java.io.*
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CbzBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val global = EpubSettings(theme = ReadingTheme.SEPIA)
    private class Settings(value: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(value); var writes = 0; var failNext = false
        override suspend fun save(settings: EpubSettings) {
            writes++; if (failNext) { failNext = false; throw IOException("controlled failure") }; this.settings.value = settings
        }
    }
    private class Documents : BackupDocumentProvider {
        var bytes: ByteArray? = null
        override fun accepts(uri: String) = true
        override fun openInput(uri: String): InputStream = ByteArrayInputStream(bytes!!)
        override fun openOutput(uri: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() { super.close(); bytes = toByteArray() }
        }
        override fun delete(uri: String): Boolean { bytes = null; return true }
    }
    private data class Fixture(val db: LibraryDatabase, val books: File, val stage: File, val journal: File, val settings: Settings) {
        fun backup(docs: Documents) = DefaultLibraryBackup(db, books, stage, journal, settings, docs, Mutex())
    }
    private fun fixture(value: EpubSettings = global): Fixture {
        val root = temp.newFolder(); val context = ApplicationProvider.getApplicationContext<Application>()
        return Fixture(Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build(),
            File(root, "books").apply { mkdir() }, File(root, "stage"), File(root, "journal.pending"), Settings(value))
    }
    private fun original(badLast: Boolean = false): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> for ((name, bytes) in listOf("2.png" to CoverTestImages.png(),
            "10.png" to if (badLast) byteArrayOf(1) else CoverTestImages.png(12, 8))) {
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    private suspend fun seed(f: Fixture, bytes: ByteArray = original(), wrongLocation: Boolean = false, wrongCount: Boolean = false, skipLocation: Boolean = false): Book {
        val book = Book(id, "Çizgi", null, BookFormat.CBZ, "source.cbz", "$id.cbz", bytes.size.toLong(), cbzHash(bytes), 1, if (wrongCount) 3 else 2)
        val library = RoomLibraryRepository(f.db.books()); library.add(book); File(f.books, book.storageName).writeBytes(bytes)
        library.updateOrganization(id, book.sha256, BookOrganization(favorite = true, tags = listOf("Çizgi")))
        RoomBookNoteRepository(f.db).add(id, book.sha256, "CBZ notu")
        if (!skipLocation) {
            val archive = withContext(Dispatchers.IO) { CbzArchive.read(File(f.books, book.storageName)) }
            val location = CbzPosition.encode(1, archive).let { if (wrongLocation) it.copy(resourceHref = "pdf") else it }
            RoomReadingStateRepository(f.db).record(id, book.sha256, ReadingSnapshot(location))
            RoomBookmarkRepository(f.db).add(id, book.sha256, BookmarkTarget("Sayfa 2", location))
        }
        return book
    }
    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes(); zip.closeEntry()
        } }; return result
    }
    private fun rewrite(bytes: ByteArray, change: (LinkedHashMap<String, ByteArray>) -> Unit): ByteArray {
        val entries = entries(bytes); change(entries)
        return ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> entries.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } } }.toByteArray()
    }
    private suspend fun empty(f: Fixture, writes: Int = 0) {
        assertTrue(f.db.books().observeAll().first().isEmpty()); assertTrue(f.books.listFiles().isNullOrEmpty())
        assertFalse(f.journal.exists()); assertTrue(f.stage.listFiles().isNullOrEmpty()); assertEquals(writes, f.settings.writes)
    }
    @Test fun currentSeventeenRestoresExactOriginalNotesOrganizationProgressAndBookmark() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            val book = seed(source); val bytes = File(source.books, book.storageName).readBytes()
            assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
            val entries = entries(docs.bytes!!)
            val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            assertArrayEquals(bytes, entries.getValue("books/${book.storageName}"))
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            val restored = RoomLibraryRepository(target.db.books()).findById(id)!!
            assertEquals(BookFormat.CBZ, restored.format); assertEquals(2, restored.pageCount); assertTrue(restored.organization.favorite)
            assertArrayEquals(bytes, File(target.books, restored.storageName).readBytes())
            assertEquals("CBZ notu", target.db.bookNotes().observe(id, book.sha256).first().single().text)
            assertEquals("cbz/10.png", RoomReadingStateRepository(target.db).resume(id, book.sha256)!!.resourceHref)
            assertEquals(1, RoomBookmarkRepository(target.db).observe(id, book.sha256).first().size)
            assertTrue(target.db.reading().observed(id, book.sha256).isEmpty()); assertEquals(global, target.settings.settings.first())
        } finally { source.db.close(); target.db.close() }
    }
    @Test fun allFrozenHeadersIncludingSixteenAndIndependentSixteenRecordsRefuseCbzBeforeWrites() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            seed(source); source.backup(docs).create("content://backup"); val bytes = docs.bytes!!
            val records = entries(bytes).getValue("records.json")
            assertEquals(BackupFailure.INVALID_ARCHIVE, (runCatching { BackupRecordsCodec.decode(records, 16) }.exceptionOrNull() as BackupProblem).reason)
            for (version in 1..16) {
                docs.bytes = rewrite(bytes) { entries ->
                    val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
                    manifest.put("formatVersion", version).put("databaseVersion", when (version) {
                        1,2,3 -> 12; 4 -> 13; 5 -> 14; 6 -> 15; 7 -> 16; 8 -> 17; 9 -> 18; 10 -> 19; 11,12,13 -> 20; 14 -> 21; else -> 22
                    })
                    if (version < 5) { manifest.remove("covers"); manifest.remove("coverCount") }
                    entries["manifest.json"] = manifest.toString().toByteArray()
                }
                assertEquals("version $version", BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                empty(target); assertEquals(EpubSettings(), target.settings.settings.first())
            }
        } finally { source.db.close(); target.db.close() }
    }
    @Test fun matchingHashCannotAuthorizeBadFinalImageForeignLocatorOrInventedPageCount() = runBlocking {
        for (mode in 0..2) {
            val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
            try {
                seed(source, if (mode == 0) original(true) else original(), wrongLocation = mode == 1, wrongCount = mode == 2, skipLocation = mode == 0)
                assertEquals(BackupResult.Created(1), source.backup(docs).create("content://backup"))
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup")); empty(target)
            } finally { source.db.close(); target.db.close() }
        }
    }
    @Test fun admittedSettingsFailureRollsBackCbzFilesAllRowsAndPreferencesThenRetryRestores() = runBlocking {
        val source = fixture(); val target = fixture(EpubSettings()); val docs = Documents()
        try {
            val book = seed(source); source.backup(docs).create("content://backup"); target.settings.failNext = true
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs).restore("content://backup")); empty(target, 2)
            assertNull(RoomReadingStateRepository(target.db).resume(id, book.sha256))
            assertTrue(RoomBookmarkRepository(target.db).observe(id, book.sha256).first().isEmpty())
            assertEquals(EpubSettings(), target.settings.settings.first())
            assertEquals(BackupResult.Restored(1), target.backup(docs).restore("content://backup"))
            assertArrayEquals(File(source.books, book.storageName).readBytes(), File(target.books, book.storageName).readBytes())
        } finally { source.db.close(); target.db.close() }
    }
}

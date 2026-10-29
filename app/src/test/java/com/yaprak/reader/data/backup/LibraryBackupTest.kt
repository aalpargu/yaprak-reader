package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yaprak.reader.data.persistence.BookEntity
import com.yaprak.reader.data.persistence.BookEpubFontOverrideEntity
import com.yaprak.reader.data.persistence.BookEpubFontSizeOverrideEntity
import com.yaprak.reader.data.persistence.BookEpubLineSpacingOverrideEntity
import com.yaprak.reader.data.persistence.BookEpubMarginOverrideEntity
import com.yaprak.reader.data.persistence.BookEpubThemeOverrideEntity
import com.yaprak.reader.data.persistence.BookNoteEntity
import com.yaprak.reader.data.persistence.BookmarkEntity
import com.yaprak.reader.data.persistence.EpubPassageEntity
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.ObservedTextEntity
import com.yaprak.reader.data.persistence.ReadingProgressEntity
import com.yaprak.reader.data.persistence.SavedWordEntity
import com.yaprak.reader.data.settings.DataStoreEpubSettings
import com.yaprak.reader.domain.library.LibraryLayout
import com.yaprak.reader.domain.library.LibraryPreferences
import com.yaprak.reader.domain.library.LibrarySort
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.backup.BackupResult
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.EpubSettingsRepository
import com.yaprak.reader.domain.settings.ReaderOrientation
import com.yaprak.reader.domain.settings.ReadingFont
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    private val epubId = "11111111-1111-4111-8111-111111111111"
    private val pdfId = "22222222-2222-4222-8222-222222222222"
    private val epubBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4)
    private val pdfBytes = "%PDF-1.4\nexample".toByteArray()
    private val restoredSettings = EpubSettings(155, ReadingFont.SERIF, 180, 150, ReadingTheme.SEPIA,
        scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY)
    private val roomTables = listOf("books", "reading_progress", "observed_text", "bookmarks",
        "book_epub_theme_overrides", "book_epub_font_size_overrides", "book_epub_font_overrides",
        "book_epub_margin_overrides", "book_epub_line_spacing_overrides", "saved_words",
        "book_notes", "epub_passages", "book_organizations", "book_covers")

    @Test fun `roundtrip preserves both files all Room tables and global settings`() = runBlocking {
        val source = fixture("source", restoredSettings)
        val target = fixture("target", EpubSettings())
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            val archive = archiveEntries(requireNotNull(docs.bytes))
            assertEquals(22, JSONObject(archive.getValue("manifest.json").toString(Charsets.UTF_8))
                .getInt("formatVersion"))
            assertEquals(true, JSONObject(archive.getValue("records.json").toString(Charsets.UTF_8))
                .getJSONObject("settings").get("scroll"))
            assertEquals("JUSTIFY", JSONObject(archive.getValue("records.json").toString(Charsets.UTF_8))
                .getJSONObject("settings").get("textAlignment"))
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            assertArrayEquals(epubBytes, File(target.books, "$epubId.epub").readBytes())
            assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
            assertEquals(restoredSettings, target.settings.settings.first())
            listOf("books", "reading_progress", "observed_text", "bookmarks",
                "book_epub_theme_overrides", "book_epub_font_size_overrides", "book_epub_font_overrides",
                "book_epub_margin_overrides", "book_epub_line_spacing_overrides", "saved_words",
                "book_notes", "epub_passages", "book_organizations", "book_covers").forEach { table ->
                assertEquals(table, count(source.db, table), count(target.db, table))
            }
            // Querying the underlying row also covers the nullable PDF page column.
            assertEquals("PDF page note", target.db.openHelper.readableDatabase.query(
                "SELECT text FROM book_notes WHERE bookId = '$pdfId' AND pdfPageIndex = 2"
            ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) })
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `legacy version one archive restores paged settings and original files`() = runBlocking {
        val source = fixture("legacy-source", restoredSettings)
        val target = fixture("legacy-target", EpubSettings(scroll = true, textAlignment = ReadingTextAlignment.START))
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            docs.bytes = rewriteArchive(requireNotNull(docs.bytes), 1) {
                it.remove("scroll")
                it.remove("textAlignment")
            }
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            assertEquals(restoredSettings.copy(scroll = false, textAlignment = ReadingTextAlignment.PUBLISHER),
                target.settings.settings.first())
            assertEquals(2, count(target.db, "books"))
            assertArrayEquals(epubBytes, File(target.books, "$epubId.epub").readBytes())
            assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `legacy version two archive preserves scroll and defaults to publisher alignment`() = runBlocking {
        val source = fixture("version-two-source", restoredSettings)
        val target = fixture("version-two-target", EpubSettings(textAlignment = ReadingTextAlignment.START))
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            docs.bytes = rewriteArchive(requireNotNull(docs.bytes), 2) { it.remove("textAlignment") }
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            assertEquals(restoredSettings.copy(textAlignment = ReadingTextAlignment.PUBLISHER),
                target.settings.settings.first())
            assertEquals(2, count(target.db, "books"))
            assertArrayEquals(epubBytes, File(target.books, "$epubId.epub").readBytes())
            assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `version three archive restores publisher and start alignment from actual ZIP files`() = runBlocking {
        for (alignment in listOf(ReadingTextAlignment.PUBLISHER, ReadingTextAlignment.START)) {
            val preferences = restoredSettings.copy(textAlignment = alignment)
            val source = fixture("alignment-${alignment.name}-source", preferences)
            val target = fixture("alignment-${alignment.name}-target", restoredSettings)
            val docs = MemoryDocuments()
            try {
                seed(source)
                assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
                docs.bytes = rewriteArchive(requireNotNull(docs.bytes), 3) {}
                assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
                assertEquals(preferences, target.settings.settings.first())
                assertEquals(2, count(target.db, "books"))
                assertArrayEquals(epubBytes, File(target.books, "$epubId.epub").readBytes())
                assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
            } finally { source.db.close(); target.db.close() }
        }
    }

    @Test fun `archive scroll must match its version and invalid fields cannot write any data`() = runBlocking {
        val source = fixture("scroll-invalid-source", restoredSettings)
        val target = fixture("scroll-invalid-target", EpubSettings(theme = ReadingTheme.DARK, scroll = true))
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            val original = rewriteArchive(requireNotNull(docs.bytes), 2) { it.remove("textAlignment") }
            val invalid = listOf(
                rewriteArchive(original, 2) { it.put("scroll", JSONObject.NULL) },
                rewriteArchive(original, 2) { it.put("scroll", "true") },
                rewriteArchive(original, 2) { it.put("scroll", 1) },
                rewriteArchive(original, 2) { it.remove("scroll") },
                rewriteArchive(original, 2) { it.put("futureSetting", true) },
                rewriteArchive(original, 1) {}, // A v1 header cannot carry the sixth field.
            )
            invalid.forEach { bytes ->
                docs.bytes = bytes
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE),
                    target.backup(docs).restore("content://backup"))
                assertEquals(0, count(target.db, "books"))
                assertTrue(target.books.listFiles().isNullOrEmpty())
                assertFalse(target.journal.exists())
                assertEquals(0, target.settings.saveAttempts)
                assertEquals(EpubSettings(theme = ReadingTheme.DARK, scroll = true),
                    target.settings.settings.first())
            }
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `archive alignment requires exact version fields and known enum strings before any persistent writes`() = runBlocking {
        val source = fixture("alignment-invalid-source", restoredSettings)
        val previous = EpubSettings(theme = ReadingTheme.DARK, scroll = true,
            textAlignment = ReadingTextAlignment.START)
        val target = fixture("alignment-invalid-target", previous)
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            val original = requireNotNull(docs.bytes)
            val invalid = listOf(
                rewriteArchive(original, 3) { it.put("textAlignment", JSONObject.NULL) },
                rewriteArchive(original, 3) { it.put("textAlignment", "UNKNOWN") },
                rewriteArchive(original, 3) { it.put("textAlignment", "publisher") },
                rewriteArchive(original, 3) { it.put("textAlignment", "") },
                rewriteArchive(original, 3) { it.put("textAlignment", 1) },
                rewriteArchive(original, 3) { it.put("textAlignment", true) },
                rewriteArchive(original, 3) { it.remove("textAlignment") },
                rewriteArchive(original, 3) { it.remove("scroll") },
                rewriteArchive(original, 3) { it.remove("font") },
                rewriteArchive(original, 3) { it.put("futureSetting", true) },
                rewriteArchive(original, 3) { it.put("scroll", JSONObject.NULL) },
                rewriteArchive(original, 3) { it.put("scroll", "true") },
                rewriteArchive(original, 3) { it.put("scroll", 1) },
                rewriteArchive(original, 2) {}, // Version two cannot carry alignment.
                rewriteArchive(original, 2) { it.remove("textAlignment"); it.remove("font") },
                rewriteArchive(original, 1) { it.remove("scroll") }, // Alignment cannot stand in for scroll.
                rewriteArchive(original, 1) {
                    it.remove("scroll"); it.remove("textAlignment"); it.remove("font")
                },
            )
            invalid.forEach { bytes ->
                docs.bytes = bytes
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE),
                    target.backup(docs).restore("content://backup"))
                roomTables.forEach { table -> assertEquals(table, 0, count(target.db, table)) }
                assertTrue(target.books.listFiles().isNullOrEmpty())
                assertTrue(target.stage.listFiles().isNullOrEmpty())
                assertFalse(target.journal.exists())
                assertEquals(0, target.settings.saveAttempts)
                assertEquals(previous, target.settings.settings.first())
            }
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `archive format version remains strict and rejects future numeric versions`() = runBlocking {
        val source = fixture("format-version-source", restoredSettings)
        val target = fixture("format-version-target", EpubSettings())
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            val original = requireNotNull(docs.bytes)
            listOf((BackupRecordsCodec.FORMAT_VERSION + 1) to BackupFailure.UNSUPPORTED_VERSION, "2" to BackupFailure.INVALID_ARCHIVE,
                2.5 to BackupFailure.INVALID_ARCHIVE).forEach { (version, expected) ->
                docs.bytes = rewriteArchive(original, version) {}
                assertEquals(BackupResult.Failed(expected), target.backup(docs).restore("content://backup"))
                assertEquals(0, count(target.db, "books"))
                assertTrue(target.books.listFiles().isNullOrEmpty())
                assertEquals(0, target.settings.saveAttempts)
            }
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `occupied library is unchanged`() = runBlocking {
        val source = fixture("occupied-source", restoredSettings)
        val target = fixture("occupied-target", EpubSettings())
        val docs = MemoryDocuments()
        try {
            seed(source)
            seed(target)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            assertEquals(BackupResult.Failed(BackupFailure.NOT_EMPTY),
                target.backup(docs).restore("content://backup"))
            assertEquals(2, count(target.db, "books"))
            assertEquals(EpubSettings(), target.settings.settings.first())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `truncated archive cannot write books Room or settings`() = runBlocking {
        val source = fixture("truncated-source", restoredSettings)
        val target = fixture("truncated-target", EpubSettings())
        val docs = MemoryDocuments()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            docs.bytes = docs.bytes!!.copyOf(docs.bytes!!.size / 2)
            val result = target.backup(docs).restore("content://backup")
            assertTrue(result is BackupResult.Failed)
            assertEquals(0, count(target.db, "books"))
            assertEquals(EpubSettings(), target.settings.settings.first())
            assertTrue(target.books.listFiles().isNullOrEmpty())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `settings failure rolls back committed Room rows and files`() = runBlocking {
        val source = fixture("settings-source", restoredSettings)
        val previous = EpubSettings(theme = ReadingTheme.DARK, scroll = true,
            textAlignment = ReadingTextAlignment.START)
        val target = fixture("settings-target", previous)
        val docs = MemoryDocuments()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            target.settings.failNextSave = true
            assertEquals(BackupResult.Failed(BackupFailure.IO),
                target.backup(docs).restore("content://backup"))
            assertEquals(0, count(target.db, "books"))
            assertTrue(target.books.listFiles().isNullOrEmpty())
            assertEquals(previous, target.settings.settings.first())
            assertEquals(2, target.settings.saveAttempts) // Failed commit followed by journal rollback.
            assertFalse(target.journal.exists())
        } finally { source.db.close(); target.db.close() }
    }

    @Test fun `failure after durable DataStore save rolls back alignment from written journal and preserves device keys`() = runBlocking {
        val source = fixture("datastore-rollback-source", restoredSettings)
        val previous = EpubSettings(fontSizePercent = 140, theme = ReadingTheme.DARK, scroll = true,
            textAlignment = ReadingTextAlignment.START, paragraphSpacingPercent = 150, wordSpacingPercent = 0)
        val target = fixture("datastore-rollback-target", previous)
        val docs = MemoryDocuments()
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            File(temporary.root, "alignment-rollback.preferences_pb")
        }
        val preferences = DataStoreEpubSettings(store)
        val library = LibraryPreferences(LibraryLayout.LIST, LibrarySort.TITLE)
        val unrelated = stringPreferencesKey("unrelated_device_key")
        var attempts = 0
        var writtenPrevious: JSONObject? = null
        val failAfterFirstSave = object : EpubSettingsRepository {
            override val settings = preferences.settings
            override suspend fun save(settings: EpubSettings) {
                attempts++
                preferences.save(settings)
                if (attempts == 1) {
                    writtenPrevious = JSONObject(target.journal.readText(Charsets.UTF_8))
                        .getJSONObject("previousSettings")
                    throw IOException("simulated failure after durable settings write")
                }
            }
        }
        try {
            seed(source)
            preferences.save(previous)
            preferences.saveKeepScreenOn(true)
            preferences.savePdfNightMode(true)
            preferences.saveLibraryPreferences(library)
            store.edit { it[unrelated] = "preserve me" }
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            val backup = DefaultLibraryBackup(target.db, target.books, target.stage, target.journal,
                failAfterFirstSave, docs, Mutex())
            assertEquals(BackupResult.Failed(BackupFailure.IO), backup.restore("content://backup"))
            assertEquals(2, attempts)
            assertEquals(9, requireNotNull(writtenPrevious).length())
            assertEquals(150, requireNotNull(writtenPrevious).getInt("paragraphSpacingPercent"))
            assertEquals(0, requireNotNull(writtenPrevious).getInt("wordSpacingPercent"))
            assertEquals("START", requireNotNull(writtenPrevious).get("textAlignment"))
            assertEquals(true, requireNotNull(writtenPrevious).get("scroll"))
            assertEquals(previous, preferences.settings.first())
            assertTrue(preferences.keepScreenOn.first())
            assertTrue(preferences.pdfNightMode.first())
            assertEquals(library, preferences.libraryPreferences.first())
            assertEquals("preserve me", store.data.first()[unrelated])
            roomTables.forEach { table -> assertEquals(table, 0, count(target.db, table)) }
            assertTrue(target.books.listFiles().isNullOrEmpty())
            assertFalse(target.journal.exists())
        } finally { job.cancelAndJoin(); source.db.close(); target.db.close() }
    }

    @Test fun `failed target stream reports a retained partial document`() = runBlocking {
        val source = fixture("write-source", restoredSettings)
        val docs = MemoryDocuments().apply { failOutput = true; allowDelete = false }
        try {
            seed(source)
            assertEquals(BackupResult.Failed(BackupFailure.IO, partialFileMayRemain = true),
                source.backup(docs).create("content://backup"))
        } finally { source.db.close() }
    }

    @Test fun `pending journal after process restart rolls back rows files and settings`() = runBlocking {
        val parent = temporary.newFolder("journal-restart")
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = File(parent, "library.db").absolutePath
        val previous = EpubSettings(fontSizePercent = 140, theme = ReadingTheme.DARK, scroll = true,
            textAlignment = ReadingTextAlignment.START, paragraphSpacingPercent = 150, wordSpacingPercent = 0)
        val first = Fixture(
            Room.databaseBuilder(context, LibraryDatabase::class.java, dbName).build(),
            File(parent, "books").apply { mkdir() }, File(parent, "stage"),
            File(parent, "restore.pending"), MemorySettings(previous),
        )
        seed(first)
        first.settings.save(restoredSettings)
        first.journal.writeText(pendingJournal(previous), Charsets.UTF_8)
        File(first.stage, "restore-stale/nested").mkdirs()
        first.db.close()

        val restarted = first.copy(
            db = Room.databaseBuilder(context, LibraryDatabase::class.java, dbName).build(),
            settings = MemorySettings(restoredSettings),
        )
        try {
            restarted.backup(MemoryDocuments()).recoverPendingRestore()
            assertEquals(0, count(restarted.db, "books"))
            assertEquals(0, count(restarted.db, "book_notes"))
            assertTrue(restarted.books.listFiles().isNullOrEmpty())
            assertEquals(previous, restarted.settings.settings.first())
            assertFalse(restarted.journal.exists())
            assertTrue(File(restarted.stage, "restore-stale/nested").isDirectory)
            restarted.backup(MemoryDocuments()).recoverPendingRestore()
        } finally { restarted.db.close() }
    }

    @Test fun `legacy journal recovery restores paged settings and preserves actual device preferences`() = runBlocking {
        val fixture = fixture("legacy-journal-device-settings", EpubSettings())
        val previous = EpubSettings(theme = ReadingTheme.LIGHT)
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            File(temporary.root, "legacy-recovery.preferences_pb")
        }
        val preferences = DataStoreEpubSettings(store)
        val library = LibraryPreferences(LibraryLayout.LIST, LibrarySort.TITLE)
        val unrelated = stringPreferencesKey("unrelated_device_key")
        try {
            seed(fixture)
            preferences.save(restoredSettings)
            preferences.saveKeepScreenOn(true)
            preferences.savePdfNightMode(true)
            preferences.saveLibraryPreferences(library)
            store.edit { it[unrelated] = "preserve legacy key" }
            fixture.journal.writeText(pendingJournal(previous, settingsVersion = 1), Charsets.UTF_8)
            DefaultLibraryBackup(fixture.db, fixture.books, fixture.stage, fixture.journal,
                preferences, MemoryDocuments(), Mutex()).recoverPendingRestore()
            assertEquals(previous, preferences.settings.first())
            assertFalse(preferences.settings.first().scroll)
            assertEquals(ReadingTextAlignment.PUBLISHER, preferences.settings.first().textAlignment)
            assertTrue(preferences.keepScreenOn.first())
            assertTrue(preferences.pdfNightMode.first())
            assertEquals(library, preferences.libraryPreferences.first())
            assertEquals("preserve legacy key", store.data.first()[unrelated])
            assertEquals(0, count(fixture.db, "books"))
            assertTrue(fixture.books.listFiles().isNullOrEmpty())
            assertFalse(fixture.journal.exists())
        } finally { job.cancelAndJoin(); fixture.db.close() }
    }

    @Test fun `six and seven field journals recover scroll alignment and unrelated actual device preferences`() = runBlocking {
        for (settingsVersion in listOf(2, 3)) {
            val fixture = fixture("alignment-journal-$settingsVersion", EpubSettings())
            val previous = EpubSettings(fontSizePercent = 135, theme = ReadingTheme.LIGHT, scroll = true,
                textAlignment = ReadingTextAlignment.START)
            val expected = if (settingsVersion == 2) previous.copy(textAlignment = ReadingTextAlignment.PUBLISHER)
                else previous
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
                File(temporary.root, "alignment-journal-$settingsVersion.preferences_pb")
            }
            val preferences = DataStoreEpubSettings(store)
            val library = LibraryPreferences(LibraryLayout.LIST, LibrarySort.TITLE)
            val unrelated = stringPreferencesKey("unrelated_device_key")
            try {
                seed(fixture)
                preferences.save(restoredSettings)
                preferences.saveKeepScreenOn(true)
                preferences.savePdfNightMode(true)
                preferences.saveLibraryPreferences(library)
                store.edit { it[unrelated] = "preserve version $settingsVersion" }
                fixture.journal.writeText(pendingJournal(previous, settingsVersion), Charsets.UTF_8)
                val recovery = DefaultLibraryBackup(fixture.db, fixture.books, fixture.stage, fixture.journal,
                    preferences, MemoryDocuments(), Mutex())
                recovery.recoverPendingRestore()
                assertEquals(expected, preferences.settings.first())
                assertTrue(preferences.settings.first().scroll)
                assertTrue(preferences.keepScreenOn.first())
                assertTrue(preferences.pdfNightMode.first())
                assertEquals(library, preferences.libraryPreferences.first())
                assertEquals("preserve version $settingsVersion", store.data.first()[unrelated])
                roomTables.forEach { table -> assertEquals(table, 0, count(fixture.db, table)) }
                assertTrue(fixture.books.listFiles().isNullOrEmpty())
                assertFalse(fixture.journal.exists())
                recovery.recoverPendingRestore()
                assertEquals(expected, preferences.settings.first())
            } finally { job.cancelAndJoin(); fixture.db.close() }
        }
    }

    @Test fun `invalid journal scroll cannot delete existing rows files or change settings`() = runBlocking {
        val fixture = fixture("invalid-journal-scroll", restoredSettings)
        try {
            seed(fixture)
            for (invalid in listOf(JSONObject.NULL, "true", 1)) {
                val journal = JSONObject(pendingJournal())
                journal.getJSONObject("previousSettings").put("scroll", invalid)
                fixture.journal.writeText(journal.toString(), Charsets.UTF_8)
                assertEquals(BackupResult.Failed(BackupFailure.RECOVERY_REQUIRED),
                    fixture.backup(MemoryDocuments()).create("content://backup"))
                assertEquals(2, count(fixture.db, "books"))
                assertArrayEquals(epubBytes, File(fixture.books, "$epubId.epub").readBytes())
                assertArrayEquals(pdfBytes, File(fixture.books, "$pdfId.pdf").readBytes())
                assertEquals(restoredSettings, fixture.settings.settings.first())
                assertEquals(0, fixture.settings.saveAttempts)
                assertTrue(fixture.journal.exists())
            }
        } finally { fixture.db.close() }
    }

    @Test fun `invalid journal alignment and incomplete legacy shapes preserve all existing data`() = runBlocking {
        val fixture = fixture("invalid-journal-alignment", restoredSettings)
        val edits = listOf<(JSONObject) -> Unit>(
            { it.put("textAlignment", JSONObject.NULL) },
            { it.put("textAlignment", "UNKNOWN") },
            { it.put("textAlignment", "publisher") },
            { it.put("textAlignment", "") },
            { it.put("textAlignment", 1) },
            { it.put("textAlignment", true) },
            { it.remove("scroll") }, // Alignment without scroll is not a legacy shape.
            { it.put("futureSetting", true) },
            { it.remove("font") },
            { it.remove("textAlignment"); it.put("scroll", JSONObject.NULL) },
            { it.remove("textAlignment"); it.put("scroll", "true") },
            { it.remove("textAlignment"); it.put("scroll", 1) },
            { it.remove("textAlignment"); it.remove("font") },
            { it.remove("textAlignment"); it.remove("scroll"); it.remove("font") },
            { it.remove("textAlignment"); it.remove("scroll"); it.put("futureSetting", true) },
        )
        try {
            seed(fixture)
            val originalCounts = roomTables.associateWith { count(fixture.db, it) }
            edits.forEach { edit ->
                val journal = JSONObject(pendingJournal())
                edit(journal.getJSONObject("previousSettings"))
                val bytes = journal.toString().toByteArray(Charsets.UTF_8)
                fixture.journal.writeBytes(bytes)
                val docs = MemoryDocuments()
                assertEquals(BackupResult.Failed(BackupFailure.RECOVERY_REQUIRED),
                    fixture.backup(docs).create("content://backup"))
                originalCounts.forEach { (table, expected) -> assertEquals(table, expected, count(fixture.db, table)) }
                assertArrayEquals(epubBytes, File(fixture.books, "$epubId.epub").readBytes())
                assertArrayEquals(pdfBytes, File(fixture.books, "$pdfId.pdf").readBytes())
                assertEquals(restoredSettings, fixture.settings.settings.first())
                assertEquals(0, fixture.settings.saveAttempts)
                assertArrayEquals(bytes, fixture.journal.readBytes())
                assertTrue(docs.bytes == null)
            }
        } finally { fixture.db.close() }
    }

    @Test fun `backup refuses unknown table column or database version`() = runBlocking {
        val docs = MemoryDocuments()
        val addedTable = fixture("schema-table", EpubSettings())
        val addedColumn = fixture("schema-column", EpubSettings())
        val changedVersion = fixture("schema-version", EpubSettings())
        try {
            addedTable.db.openHelper.writableDatabase.execSQL(
                "CREATE TABLE future_annotations (id TEXT NOT NULL PRIMARY KEY)")
            addedColumn.db.openHelper.writableDatabase.execSQL(
                "ALTER TABLE books ADD COLUMN futureField TEXT")
            changedVersion.db.openHelper.writableDatabase.execSQL("PRAGMA user_version = ${BackupRecordsCodec.DATABASE_VERSION + 1}")
            listOf(addedTable, addedColumn, changedVersion).forEach { fixture ->
                assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION),
                    fixture.backup(docs).create("content://backup"))
            }
            assertTrue(docs.bytes == null)
        } finally {
            addedTable.db.close(); addedColumn.db.close(); changedVersion.db.close()
        }
    }

    @Test fun `archive rejects invalid model ranges and enums`() = runBlocking {
        val fixture = fixture("invalid-record-values", EpubSettings())
        try {
            seed(fixture)
            val records = withContext(Dispatchers.IO) {
                BackupRecordsCodec.snapshot(fixture.db, restoredSettings)
            }
            listOf(
                Triple("books", "pageCount", -1),
                Triple("book_epub_font_size_overrides", "fontSizePercent", 900),
                Triple("book_epub_theme_overrides", "theme", "UNKNOWN"),
                Triple("observed_text", "startOffset", -2),
                Triple("epub_passages", "anchorVersion", 99),
            ).forEach { (table, field, invalidValue) ->
                val row = records.rows.getValue(table).first()
                val original = row.get(field)
                row.put(field, invalidValue)
                val failure = runCatching {
                    BackupRecordsCodec.decode(BackupRecordsCodec.encode(records))
                }.exceptionOrNull()
                assertEquals(BackupFailure.INVALID_ARCHIVE, (failure as BackupProblem).reason)
                row.put(field, original)
            }
        } finally { fixture.db.close() }
    }

    @Test fun `invalid UTF8 journal leaves existing data for manual recovery`() = runBlocking {
        val fixture = fixture("invalid-journal", EpubSettings())
        val docs = MemoryDocuments()
        try {
            seed(fixture)
            fixture.journal.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            assertEquals(BackupResult.Failed(BackupFailure.RECOVERY_REQUIRED),
                fixture.backup(docs).create("content://backup"))
            assertEquals(2, count(fixture.db, "books"))
            assertTrue(fixture.journal.exists())
        } finally { fixture.db.close() }
    }

    @Test fun `stale cache cleanup failure cannot close a library without pending restore`() = runBlocking {
        val fixture = fixture("stale-stage", EpubSettings())
        try {
            seed(fixture)
            File(fixture.stage, "restore-stale/nested").mkdirs()
            fixture.backup(MemoryDocuments()).recoverPendingRestore()
            assertEquals(2, count(fixture.db, "books"))
            assertTrue(File(fixture.books, "$epubId.epub").isFile)
            assertTrue(File(fixture.stage, "restore-stale/nested").isDirectory)
        } finally { fixture.db.close() }
    }

    @Test fun `stage cleanup exception cannot conceal restore failure`() = runBlocking {
        val parent = temporary.newFolder("throwing-stage")
        val context = ApplicationProvider.getApplicationContext<Application>()
        val stage = object : File(parent, "stage") {
            var canonicalReads = 0
            override fun getCanonicalFile(): File {
                if (++canonicalReads > 1) throw IOException("simulated cache cleanup failure")
                return super.getCanonicalFile()
            }
        }
        val fixture = Fixture(
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build(),
            File(parent, "books").apply { mkdir() }, stage,
            File(parent, "restore.pending"), MemorySettings(EpubSettings()),
        )
        val docs = MemoryDocuments().apply { bytes = byteArrayOf(1, 2, 3) }
        try {
            assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE),
                fixture.backup(docs).restore("content://backup"))
            assertEquals(0, count(fixture.db, "books"))
        } finally { fixture.db.close() }
    }

    @Test fun `all archive versions preserve actual device goal after restore and reopen`() = runBlocking {
        val source = fixture("goal-source", restoredSettings)
        val docs = MemoryDocuments()
        try {
            seed(source)
            withGoalStore(File(temporary.root, "goal-source.preferences_pb")) { preferences, _ ->
                preferences.save(restoredSettings)
                preferences.saveDailyMinutes(91)
                assertEquals(BackupResult.Created(2), DefaultLibraryBackup(source.db, source.books,
                    source.stage, source.journal, preferences, docs, Mutex()).create("content://backup"))
            }
            val original = requireNotNull(docs.bytes)
            val entries = archiveEntries(original)
            val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(26, manifest.getInt("formatVersion"))
            assertEquals(26, manifest.getInt("databaseVersion"))
            val settingsJson = JSONObject(entries.getValue("records.json").toString(Charsets.UTF_8))
                .getJSONObject("settings")
            assertEquals(archiveSettingsKeys, settingsJson.keys().asSequence().toSet())
            assertFalse(settingsJson.has(goalKey.name))
            for (version in 1..10) for (minutes in listOf<Int?>(37, null)) {
                val target = fixture("gr-$version-${minutes ?: "off"}", EpubSettings())
                val file = File(temporary.root, "gr-$version-${minutes ?: "off"}.preferences_pb")
                val expected = restoredSettings.copy(scroll = version >= 2,
                    textAlignment = if (version >= 3) ReadingTextAlignment.JUSTIFY else ReadingTextAlignment.PUBLISHER)
                try {
                    docs.bytes = rewriteArchive(original, version) {
                        if (version < 2) it.remove("scroll")
                        if (version < 3) it.remove("textAlignment")
                    }
                    withGoalStore(file) { preferences, store ->
                        seedGoalDevicePreferences(preferences, store, minutes)
                        val deviceBefore = goalDeviceValues(store.data.first())
                        assertEquals(BackupResult.Restored(2), DefaultLibraryBackup(target.db, target.books,
                            target.stage, target.journal, preferences, docs, Mutex()).restore("content://backup"))
                        assertEquals(expected, preferences.settings.first())
                        assertEquals(minutes, preferences.dailyMinutes.first())
                        assertEquals(minutes != null, store.data.first().contains(goalKey))
                        assertEquals(deviceBefore, goalDeviceValues(store.data.first()))
                        assertArrayEquals(epubBytes, File(target.books, "$epubId.epub").readBytes())
                        assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
                        assertEquals(0, count(target.db, "reading_duration_receipts"))
                    }
                    withGoalStore(file) { preferences, store ->
                        assertEquals(minutes, preferences.dailyMinutes.first())
                        assertEquals(minutes != null, store.data.first().contains(goalKey))
                        assertEquals(expected, preferences.settings.first())
                        assertGoalDevicePreferences(preferences, store)
                    }
                } finally { target.db.close() }
            }
        } finally { source.db.close() }
    }

    @Test fun `durable settings rollback preserves local goal and excludes it from the written journal`() = runBlocking {
        val source = fixture("goal-rollback-source", restoredSettings)
        val docs = MemoryDocuments()
        val previous = EpubSettings(140, theme = ReadingTheme.DARK, scroll = true,
            textAlignment = ReadingTextAlignment.START)
        try {
            seed(source)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            for (minutes in listOf<Int?>(37, null)) {
                val target = fixture("gb-${minutes ?: "off"}", previous)
                val file = File(temporary.root, "gb-${minutes ?: "off"}.preferences_pb")
                try {
                    withGoalStore(file) { preferences, store ->
                        preferences.save(previous)
                        seedGoalDevicePreferences(preferences, store, minutes)
                        val deviceBefore = goalDeviceValues(store.data.first())
                        var attempts = 0
                        var writtenPrevious: JSONObject? = null
                        val failing = object : EpubSettingsRepository {
                            override val settings = preferences.settings
                            override suspend fun save(settings: EpubSettings) {
                                attempts++
                                preferences.save(settings)
                                if (attempts == 1) {
                                    writtenPrevious = JSONObject(target.journal.readText(Charsets.UTF_8))
                                        .getJSONObject("previousSettings")
                                    // The first settings write really committed before rollback is requested.
                                    assertEquals(restoredSettings, preferences.settings.first())
                                    throw IOException("controlled failure after durable settings save")
                                }
                            }
                        }
                        val backup = DefaultLibraryBackup(target.db, target.books, target.stage, target.journal,
                            failing, docs, Mutex())
                        assertEquals(BackupResult.Failed(BackupFailure.IO), backup.restore("content://backup"))
                        assertEquals(2, attempts)
                        assertEquals(archiveSettingsKeys, requireNotNull(writtenPrevious).keys().asSequence().toSet())
                        assertFalse(requireNotNull(writtenPrevious).has(goalKey.name))
                        assertEquals(previous, preferences.settings.first())
                        assertEquals(minutes, preferences.dailyMinutes.first())
                        assertEquals(deviceBefore, goalDeviceValues(store.data.first()))
                        (roomTables + "reading_duration_receipts").forEach { assertEquals(it, 0, count(target.db, it)) }
                        assertTrue(target.books.listFiles().isNullOrEmpty())
                        assertFalse(target.journal.exists())
                    }
                    withGoalStore(file) { preferences, store ->
                        assertEquals(previous, preferences.settings.first())
                        assertEquals(minutes, preferences.dailyMinutes.first())
                        assertEquals(minutes != null, store.data.first().contains(goalKey))
                        assertGoalDevicePreferences(preferences, store)
                    }
                } finally { target.db.close() }
            }
        } finally { source.db.close() }
    }

    @Test fun `all legacy settings journals retain device goal through reopen recovery and repeat recovery`() = runBlocking {
        val previous = EpubSettings(140, theme = ReadingTheme.DARK, scroll = true,
            textAlignment = ReadingTextAlignment.START)
        for (settingsVersion in 1..3) for (minutes in listOf<Int?>(37, null)) {
            val target = fixture("gj-$settingsVersion-${minutes ?: "off"}", restoredSettings)
            val file = File(temporary.root, "gj-$settingsVersion-${minutes ?: "off"}.preferences_pb")
            val expected = previous.copy(scroll = settingsVersion >= 2,
                textAlignment = if (settingsVersion >= 3) ReadingTextAlignment.START else ReadingTextAlignment.PUBLISHER)
            try {
                seed(target)
                withGoalStore(file) { preferences, store ->
                    preferences.save(restoredSettings)
                    seedGoalDevicePreferences(preferences, store, minutes)
                }
                // No active DataStore instance remains when a later startup reads the journal.
                target.journal.writeText(pendingJournal(previous, settingsVersion), Charsets.UTF_8)
                val journalSettings = JSONObject(target.journal.readText(Charsets.UTF_8)).getJSONObject("previousSettings")
                assertEquals(settingsVersion + 4, journalSettings.length())
                assertFalse(journalSettings.has(goalKey.name))
                withGoalStore(file) { preferences, store ->
                    val deviceBefore = goalDeviceValues(store.data.first())
                    val backup = DefaultLibraryBackup(target.db, target.books, target.stage, target.journal,
                        preferences, MemoryDocuments(), Mutex())
                    backup.recoverPendingRestore()
                    assertEquals(expected, preferences.settings.first())
                    assertEquals(minutes, preferences.dailyMinutes.first())
                    assertEquals(deviceBefore, goalDeviceValues(store.data.first()))
                    (roomTables + "reading_duration_receipts").forEach { assertEquals(it, 0, count(target.db, it)) }
                    assertTrue(target.books.listFiles().isNullOrEmpty())
                    assertFalse(target.journal.exists())
                    val recoveredBytes = file.readBytes()
                    backup.recoverPendingRestore()
                    assertArrayEquals(recoveredBytes, file.readBytes())
                }
                withGoalStore(file) { preferences, store ->
                    assertEquals(expected, preferences.settings.first())
                    assertEquals(minutes, preferences.dailyMinutes.first())
                    assertEquals(minutes != null, store.data.first().contains(goalKey))
                    assertGoalDevicePreferences(preferences, store)
                }
            } finally { target.db.close() }
        }
    }

    @Test fun `goal fields remain outside archive and recovery journal whitelists`() = runBlocking {
        val source = fixture("goal-whitelist-source", restoredSettings)
        val target = fixture("goal-whitelist-target", EpubSettings())
        val file = File(temporary.root, "goal-whitelist.preferences_pb")
        val previous = EpubSettings(theme = ReadingTheme.DARK)
        val docs = MemoryDocuments()
        try {
            seed(source)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            val original = requireNotNull(docs.bytes)
            withGoalStore(file) { preferences, store ->
                preferences.save(previous)
                seedGoalDevicePreferences(preferences, store, 37)
                val originalPreferences = file.readBytes()
                var writes = 0
                val counted = object : EpubSettingsRepository {
                    override val settings = preferences.settings
                    override suspend fun save(settings: EpubSettings) { writes++; preferences.save(settings) }
                }
                val backup = DefaultLibraryBackup(target.db, target.books, target.stage, target.journal,
                    counted, docs, Mutex())
                for (version in 1..17) for (ownedKey in listOf(goalKey.name, "weekly_reading_goal_minutes")) {
                    docs.bytes = rewriteArchive(original, version) {
                        if (version < 2) it.remove("scroll")
                        if (version < 3) it.remove("textAlignment")
                        it.put(ownedKey, 91)
                    }
                    assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), backup.restore("content://backup"))
                    assertEquals(0, writes)
                    assertEquals(0, count(target.db, "books"))
                    assertTrue(target.books.listFiles().isNullOrEmpty())
                    assertEquals(37, preferences.dailyMinutes.first())
                    assertArrayEquals(originalPreferences, file.readBytes())
                }
                seed(target)
                val badJournal = JSONObject(pendingJournal(previous))
                badJournal.getJSONObject("previousSettings").put(goalKey.name, 91)
                target.journal.writeText(badJournal.toString(), Charsets.UTF_8)
                assertEquals(BackupResult.Failed(BackupFailure.RECOVERY_REQUIRED), backup.create("content://backup"))
                assertEquals(0, writes)
                assertEquals(2, count(target.db, "books"))
                assertArrayEquals(epubBytes, File(target.books, "$epubId.epub").readBytes())
                assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
                assertTrue(target.journal.exists())
                assertEquals(previous, preferences.settings.first())
                assertEquals(37, preferences.dailyMinutes.first())
                assertArrayEquals(originalPreferences, file.readBytes())
            }
            withGoalStore(file) { preferences, store ->
                assertEquals(previous, preferences.settings.first())
                assertEquals(37, preferences.dailyMinutes.first())
                assertGoalDevicePreferences(preferences, store)
            }
        } finally { source.db.close(); target.db.close() }
    }

    private suspend fun withGoalStore(file: File, block: suspend (DataStoreEpubSettings, DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try { block(DataStoreEpubSettings(store), store) } finally { job.cancelAndJoin() }
    }

    private suspend fun seedGoalDevicePreferences(preferences: DataStoreEpubSettings, store: DataStore<Preferences>, minutes: Int?) {
        preferences.saveDailyMinutes(minutes)
        preferences.saveWeeklyMinutes(137)
        preferences.saveOrientation(ReaderOrientation.PORTRAIT)
        preferences.saveKeepScreenOn(true)
        preferences.savePdfNightMode(true)
        preferences.saveLibraryPreferences(LibraryPreferences(LibraryLayout.LIST, LibrarySort.TITLE))
        store.edit { it[stringPreferencesKey("unrelated_goal_device_key")] = "İğne 🌿" }
    }

    private suspend fun assertGoalDevicePreferences(preferences: DataStoreEpubSettings, store: DataStore<Preferences>) {
        assertEquals(137, preferences.weeklyMinutes.first())
        assertEquals(ReaderOrientation.PORTRAIT, preferences.orientation.first())
        assertTrue(preferences.keepScreenOn.first())
        assertTrue(preferences.pdfNightMode.first())
        assertEquals(LibraryPreferences(LibraryLayout.LIST, LibrarySort.TITLE), preferences.libraryPreferences.first())
        assertEquals("İğne 🌿", store.data.first()[stringPreferencesKey("unrelated_goal_device_key")])
    }

    private fun goalDeviceValues(preferences: Preferences) = preferences.asMap().filterKeys { it.name in setOf(
        goalKey.name, "weekly_reading_goal_minutes", "reader_orientation", "reader_keep_screen_on", "reader_pdf_night_mode",
        "library_layout", "library_sort", "unrelated_goal_device_key",
    ) }

    private val goalKey = intPreferencesKey("daily_reading_goal_minutes")
    private val archiveSettingsKeys = setOf("fontSizePercent", "font", "lineHeightPercent", "marginsPercent", "theme", "scroll", "textAlignment", "paragraphSpacingPercent", "wordSpacingPercent")

    private fun pendingJournal(previous: EpubSettings = EpubSettings(),
        settingsVersion: Int = BackupRecordsCodec.FORMAT_VERSION): String = JSONObject()
        .put("books", JSONArray().put(JSONObject()
            .put("id", epubId).put("storageName", "$epubId.epub")
            .put("format", "EPUB").put("byteSize", epubBytes.size)
            .put("sha256", hash(epubBytes)))
            .put(JSONObject().put("id", pdfId).put("storageName", "$pdfId.pdf")
                .put("format", "PDF").put("byteSize", pdfBytes.size)
                .put("sha256", hash(pdfBytes))))
        .put("previousSettings", BackupRecordsCodec.encodeSettings(previous).apply {
            if (settingsVersion <= 12) asPreSpacingSettings()
            if (settingsVersion < 3) remove("textAlignment")
            if (settingsVersion < 2) remove("scroll")
        })
        .toString()

    private fun archiveEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    /** Recalculate integrity metadata so malformed preferences reach the actual settings validator. */
    private fun rewriteArchive(bytes: ByteArray, version: Any, edit: (JSONObject) -> Unit): ByteArray {
        val entries = archiveEntries(bytes)
        val records = JSONObject(entries.getValue("records.json").toString(Charsets.UTF_8))
        if (version is Int && version in 1..20) records.getJSONObject("tables").remove("book_pdf_navigation_preferences")
        if (version is Int && version in 1..19) records.getJSONObject("tables").remove("book_cbz_view_preferences")
        if (version is Int && version in 1..18) records.getJSONObject("tables").remove("book_epub_reading_mode_overrides")
        if (version is Int && version in 1..17) records.getJSONObject("tables").remove("book_epub_text_alignment_overrides")
        if (version is Int && version in 1..14) records.getJSONObject("tables").remove("book_completion_events")
        if (version is Int && version in 1..12) records.asPreBookSpacingTables().getJSONObject("settings").asPreSpacingSettings()
        edit(records.getJSONObject("settings"))
        if (version is Int && version in 1..10) stripWordReviewTables(records.getJSONObject("tables"))
        if (version is Int && version in 1..3) records.getJSONObject("tables").remove("book_organizations")
        if (version is Int && version in 1..4) records.getJSONObject("tables").remove("book_covers")
        if (version is Int && version in 1..9) records.getJSONObject("tables").also(::stripWordReviewTables).remove("pdf_annotations")
        if (version is Int && version in 1..8) records.getJSONObject("tables").remove("epub_range_passages")
        if (version is Int && version in 1..7) records.getJSONObject("tables").remove("book_pdf_view_preferences")
        if (version is Int && version in 1..6) records.getJSONObject("tables").remove("reading_duration_receipts")
        if (version is Int && version in 1..5) {
            val passages = records.getJSONObject("tables").getJSONArray("epub_passages")
            for (index in 0 until passages.length()) passages.getJSONObject(index).apply {
                remove("highlightColor"); remove("attachedNote"); remove("revision")
            }
        }
        val rewritten = records.toString().toByteArray(Charsets.UTF_8)
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("formatVersion", version).put("recordsSize", rewritten.size)
            .put("recordsSha256", hash(rewritten))
        if (version is Int && version in 1..3) manifest.put("databaseVersion", 12)
        if (version == 4) manifest.put("databaseVersion", 13)
        if (version == 5) manifest.put("databaseVersion", 14)
        if (version == 6) manifest.put("databaseVersion", 15)
        if (version == 7) manifest.put("databaseVersion", 16)
        if (version == 8) manifest.put("databaseVersion", 17)
        if (version == 9) manifest.put("databaseVersion", 18)
        if (version == 10) manifest.put("databaseVersion", 19)
        if (version == 14) manifest.put("databaseVersion", 21)
        if (version is Int && version in 15..17) manifest.put("databaseVersion", 22)
        if (version == 18) manifest.put("databaseVersion", 23)
        if (version == 19) manifest.put("databaseVersion", 24)
        if (version == 20) manifest.put("databaseVersion", 25)
        if (version is Int && version in 11..13) manifest.put("databaseVersion", 20)
        if (version is Int && version in 1..4) { manifest.remove("coverCount"); manifest.remove("covers") }
        entries["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        entries["records.json"] = rewritten
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private data class Fixture(
        val db: LibraryDatabase,
        val books: File,
        val stage: File,
        val journal: File,
        val settings: MemorySettings,
    ) {
        fun backup(docs: BackupDocumentProvider) = DefaultLibraryBackup(
            db, books, stage, journal, settings, docs, Mutex(),
        )
    }

    private fun fixture(name: String, preferences: EpubSettings): Fixture {
        val parent = temporary.newFolder(name)
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Fixture(
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build(),
            File(parent, "books").apply { mkdir() }, File(parent, "stage"),
            File(parent, "restore.pending"), MemorySettings(preferences),
        )
    }

    private suspend fun seed(fixture: Fixture) {
        val epubHash = hash(epubBytes)
        val pdfHash = hash(pdfBytes)
        File(fixture.books, "$epubId.epub").writeBytes(epubBytes)
        File(fixture.books, "$pdfId.pdf").writeBytes(pdfBytes)
        fixture.db.books().insert(BookEntity(epubId, "Leaf", "Author", "EPUB", "leaf.epub",
            "$epubId.epub", epubBytes.size.toLong(), epubHash, 10, null))
        fixture.db.books().insert(BookEntity(pdfId, "Pages", null, "PDF", "pages.pdf",
            "$pdfId.pdf", pdfBytes.size.toLong(), pdfHash, 20, 4))
        fixture.db.reading().save(ReadingProgressEntity(epubId, epubHash, 1, "{}", "chapter", 30))
        fixture.db.reading().observe(listOf(ObservedTextEntity(epubId, epubHash, 1,
            "chapter", "/html/body/p", 0, 4, "a".repeat(64))))
        fixture.db.bookmarks().add(BookmarkEntity("bookmark", epubId, epubHash, "loc", "Mark",
            "{}", "chapter", 1, 40))
        fixture.db.bookThemes().save(BookEpubThemeOverrideEntity(epubId, epubHash, "DARK"))
        fixture.db.bookFontSizes().save(BookEpubFontSizeOverrideEntity(epubId, epubHash, 125))
        fixture.db.bookFonts().save(BookEpubFontOverrideEntity(epubId, epubHash, "SERIF"))
        fixture.db.bookMargins().save(BookEpubMarginOverrideEntity(epubId, epubHash, 120))
        fixture.db.bookLineHeights().save(BookEpubLineSpacingOverrideEntity(epubId, epubHash, null))
        fixture.db.savedWords().add(SavedWordEntity("word", epubId, epubHash, "leaf", "yaprak", 50))
        fixture.db.bookNotes().add(BookNoteEntity("note", epubId, epubHash, "EPUB note", 60))
        fixture.db.bookNotes().add(BookNoteEntity("pdf-note", pdfId, pdfHash, "PDF page note", 61, 2))
        fixture.db.epubPassages().add(EpubPassageEntity("passage", epubId, epubHash, 1,
            "chapter", "b".repeat(64), 0, 4, "leaf", 70))
    }

    private fun count(db: LibraryDatabase, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class MemorySettings(initial: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(initial)
        var failNextSave = false
        var saveAttempts = 0
            private set
        override suspend fun save(settings: EpubSettings) {
            saveAttempts++
            if (failNextSave) {
                failNextSave = false
                throw IOException("simulated preference failure")
            }
            this.settings.value = settings
        }
    }

    private class MemoryDocuments : BackupDocumentProvider {
        var bytes: ByteArray? = null
        var failOutput = false
        var allowDelete = true
        override fun accepts(uri: String) = uri.startsWith("content://")
        override fun openInput(uri: String): InputStream = ByteArrayInputStream(bytes ?: error("No archive"))
        override fun openOutput(uri: String): OutputStream = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failOutput) throw IOException("simulated write failure")
                super.write(b, off, len)
            }
            override fun close() {
                super.close()
                bytes = toByteArray()
            }
        }
        override fun delete(uri: String): Boolean {
            if (allowDelete) bytes = null
            return allowDelete
        }
    }
}

package com.yaprak.reader.data.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.BookEntity
import com.yaprak.reader.data.persistence.EpubPassageEntity
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.ReadingDurationReceiptEntity
import com.yaprak.reader.data.persistence.RoomBookPdfViewPreferencesRepository
import com.yaprak.reader.domain.settings.PdfNavigationMode
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.backup.BackupResult
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
import java.time.Instant
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfViewPreferencesBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val pdfId = "11111111-1111-4111-8111-111111111111"
    private val epubId = "22222222-2222-4222-8222-222222222222"
    private val otherId = "33333333-3333-4333-8333-333333333333"
    private val pdfBytes = "%PDF-1.4\npdf view preferences".toByteArray()
    private val epubBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4)
    private val otherBytes = "%PDF-1.4\nanother PDF".toByteArray()
    private val restoredSettings = EpubSettings(155, theme = ReadingTheme.SEPIA, scroll = true,
        textAlignment = ReadingTextAlignment.JUSTIFY)
    private val previousSettings = EpubSettings(theme = ReadingTheme.DARK)
    private val preferenceTable = "book_pdf_view_preferences"
    private val preferenceKeys = setOf("bookId", "contentSha256", "horizontalPercent", "verticalPercent", "fitMode", "doublePagePreferred")
    private val settingsKeys = setOf("fontSizePercent", "font", "lineHeightPercent", "marginsPercent", "theme", "scroll", "textAlignment", "paragraphSpacingPercent", "wordSpacingPercent")

    @Test fun `v9 ZIP reopen retains PDF fields`() = runBlocking {
        val source = fixture("roundtrip-source", restoredSettings)
        var target = fixture("roundtrip-target", previousSettings)
        val docs = Documents()
        try {
            seed(source)
            addBook(source, otherId, otherBytes, "PDF")
            savePreferences(source, otherId, hash(otherBytes), 0, 20, "PAGE", 0)
            assertEquals(BackupResult.Created(3), source.backup(docs).create("content://backup"))
            val archive = entries(requireNotNull(docs.bytes))
            val manifest = json(archive.getValue("manifest.json"))
            assertEquals(26, manifest.getInt("formatVersion"))
            assertEquals(26, manifest.getInt("databaseVersion"))
            val records = json(archive.getValue("records.json"))
            assertEquals(settingsKeys, records.getJSONObject("settings").keys().asSequence().toSet())
            assertEquals(2, records.getJSONObject("tables").getJSONArray(preferenceTable).length())
            assertEquals(preferenceKeys, preferenceRow(records).keys().asSequence().toSet())
            assertEquals(BackupResult.Restored(3), target.backup(docs).restore("content://backup"))
            target.db.close()
            target = target.copy(db = open(target.databaseName))
            assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(target, pdfId))
            assertEquals(listOf(hash(otherBytes), 0, 20, "PAGE", 0), preferences(target, otherId))
            assertNull(preferences(target, epubId))
            assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
            assertArrayEquals(epubBytes, File(target.books, "$epubId.epub").readBytes())
            assertEquals(restoredSettings, target.settings.settings.first())
            assertFalse(target.journal.exists())
        } finally { source.close(); target.close() }
    }

    @Test fun `legacy 1 to 7 restore no PDF rows`() = runBlocking {
        val source = fixture("legacy-source", restoredSettings)
        val docs = Documents()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            val original = requireNotNull(docs.bytes)
            for (version in 1..7) {
                val target = fixture("legacy-$version", previousSettings)
                try {
                    docs.bytes = legacy(original, version)
                    assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
                    assertEquals(0, preferenceCount(target))
                    assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
                    assertArrayEquals(epubBytes, File(target.books, "$epubId.epub").readBytes())
                    assertEquals(restoredSettings.copy(scroll = version >= 2,
                        textAlignment = if (version >= 3) ReadingTextAlignment.JUSTIFY else ReadingTextAlignment.PUBLISHER),
                        target.settings.settings.first())
                    // Exporting immediately cannot fabricate defaults for books the reader has not verified.
                    assertEquals(BackupResult.Created(2), target.backup(docs).create("content://backup"))
                    assertEquals(0, json(entries(requireNotNull(docs.bytes)).getValue("records.json"))
                        .getJSONObject("tables").getJSONArray(preferenceTable).length())
                } finally { target.close() }
            }
        } finally { source.close() }
    }

    @Test fun `header tables and version pairs are strict`() = runBlocking {
        val source = fixture("headers-source")
        val target = fixture("headers-target", previousSettings)
        val docs = Documents()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            val original = requireNotNull(docs.bytes)
            for (version in 1..7) for (empty in listOf(false, true)) {
                docs.bytes = legacy(original, version, removePdfPreferences = false).let { archive ->
                    if (empty) rewrite(archive) { _, records -> records.getJSONObject("tables").put(preferenceTable, JSONArray()) } else archive
                }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target, previousSettings)
            }
            for ((format, database) in listOf(7 to 17, 8 to 16, 12 to 21, 8 to 18)) {
                docs.bytes = rewrite(original) { manifest, _ -> manifest.put("formatVersion", format).put("databaseVersion", database) }
                assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), target.backup(docs).restore("content://backup"))
                assertEmpty(target, previousSettings)
            }
        } finally { source.close(); target.close() }
    }

    @Test fun `v7 retains duration and annotations`() = runBlocking {
        val source = fixture("v7-data-source", restoredSettings)
        var target = fixture("v7-data-target")
        val docs = Documents()
        val start = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli()
        val receipt = ReadingDurationReceiptEntity("44444444-4444-4444-8444-444444444444", pdfId,
            hash(pdfBytes), "2026-10-01", "UTC", start, start + 5000, 5000)
        val passage = EpubPassageEntity("v7-passage", epubId, hash(epubBytes), 1, "OPS/chapter.xhtml",
            "b".repeat(64), 0, 4, "leaf", 20, "GREEN", "Exact saved note", 7)
        try {
            seed(source)
            source.db.readingDurations().insert(receipt)
            source.db.epubPassages().add(passage)
            source.backup(docs).create("content://backup")
            docs.bytes = legacy(requireNotNull(docs.bytes), 7)
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            target.db.close()
            target = target.copy(db = open(target.databaseName))
            assertEquals(listOf(receipt), target.db.readingDurations().all())
            assertEquals(listOf(passage), target.db.epubPassages().observe(epubId, hash(epubBytes)).first())
            assertEquals(0, preferenceCount(target))
            assertEquals(restoredSettings, target.settings.settings.first())
        } finally { source.close(); target.close() }
    }

    @Test fun `invalid PDF records reject before writes`() = runBlocking {
        val source = fixture("tamper-source")
        val target = fixture("tamper-target", previousSettings)
        val docs = Documents()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            val original = requireNotNull(docs.bytes)
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("horizontalPercent", -1) }, { it.put("horizontalPercent", 21) },
                { it.put("horizontalPercent", 1.5) }, { it.put("horizontalPercent", "8") },
                { it.put("horizontalPercent", true) }, { it.put("horizontalPercent", JSONObject.NULL) },
                { it.put("verticalPercent", -1) }, { it.put("verticalPercent", 21) },
                { it.put("verticalPercent", "12") }, { it.put("verticalPercent", 1.5) },
                { it.put("fitMode", "width") }, { it.put("fitMode", "UNKNOWN") },
                { it.put("fitMode", 1) }, { it.put("fitMode", JSONObject.NULL) },
                { it.put("doublePagePreferred", -1) }, { it.put("doublePagePreferred", 2) },
                { it.put("doublePagePreferred", true) }, { it.put("doublePagePreferred", "1") },
                { it.put("doublePagePreferred", 1.5) }, { it.put("doublePagePreferred", JSONObject.NULL) },
                { it.put("contentSha256", "a".repeat(64)) }, { it.put("contentSha256", hash(pdfBytes).uppercase()) },
                { it.put("bookId", otherId) },
                { it.put("bookId", epubId).put("contentSha256", hash(epubBytes)) },
                { it.remove("fitMode") }, { it.put("futurePreference", 1) },
            )
            for (edit in edits) {
                docs.bytes = rewrite(original) { _, records -> edit(preferenceRow(records)) }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target, previousSettings)
                assertEquals(0, target.settings.attempts)
            }
            val tableEdits: List<(JSONObject) -> Unit> = listOf(
                { it.remove(preferenceTable) },
                { it.put(preferenceTable, JSONObject()) },
                { it.getJSONArray(preferenceTable).put(JSONObject(preferenceRow(it).toString())) },
            )
            for (edit in tableEdits) {
                docs.bytes = rewrite(original) { _, records -> edit(records.getJSONObject("tables")) }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target, previousSettings)
                assertEquals(0, target.settings.attempts)
            }
        } finally { source.close(); target.close() }
    }

    @Test fun `invalid durable PDF values reject export`() = runBlocking {
        val source = fixture("sql-invalid-source")
        val docs = Documents()
        try {
            seed(source)
            val edits = listOf(
                "horizontalPercent = 21", "horizontalPercent = 1.5", "verticalPercent = -1",
                "verticalPercent = 'bad'", "fitMode = 'width'", "doublePagePreferred = 2",
                "doublePagePreferred = 0.5", "doublePagePreferred = 'true'", "contentSha256 = '${"a".repeat(64)}'",
                "bookId = '$epubId', contentSha256 = '${hash(epubBytes)}'",
            )
            for (edit in edits) {
                source.db.openHelper.writableDatabase.execSQL("UPDATE $preferenceTable SET $edit")
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), source.backup(docs).create("content://backup"))
                assertNull(docs.bytes)
                source.db.openHelper.writableDatabase.execSQL("DELETE FROM $preferenceTable")
                savePreferences(source)
            }
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
        } finally { source.close() }
    }

    @Test fun `PDF schema key and cascade are required`() = runBlocking {
        val definitions = listOf(
            "bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, horizontalPercent INTEGER NOT NULL, verticalPercent INTEGER NOT NULL, fitMode TEXT NOT NULL, doublePagePreferred INTEGER NOT NULL, PRIMARY KEY(bookId)",
            "bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, horizontalPercent INTEGER NOT NULL, verticalPercent INTEGER NOT NULL, fitMode TEXT NOT NULL, doublePagePreferred INTEGER NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE NO ACTION",
            "bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, horizontalPercent INTEGER NOT NULL, verticalPercent INTEGER NOT NULL, fitMode TEXT NOT NULL, doublePagePreferred INTEGER NOT NULL, PRIMARY KEY(bookId, contentSha256), FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE",
            "bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, horizontalPercent TEXT NOT NULL, verticalPercent INTEGER NOT NULL, fitMode TEXT NOT NULL, doublePagePreferred INTEGER NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE",
            "bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, horizontalPercent INTEGER, verticalPercent INTEGER NOT NULL, fitMode TEXT NOT NULL, doublePagePreferred INTEGER NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE",
        )
        for ((index, definition) in definitions.withIndex()) {
            val source = fixture("schema-$index")
            val docs = Documents()
            try {
                source.db.openHelper.writableDatabase.execSQL("DROP TABLE $preferenceTable")
                source.db.openHelper.writableDatabase.execSQL("CREATE TABLE $preferenceTable ($definition)")
                assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), source.backup(docs).create("content://backup"))
                assertNull(docs.bytes)
            } finally { source.close() }
        }
    }

    @Test fun `durable settings failure rolls PDF rows back`() = runBlocking {
        val source = fixture("rollback-source", restoredSettings)
        val target = fixture("rollback-target", previousSettings)
        val docs = Documents()
        var journal: JSONObject? = null
        val failing = object : EpubSettingsRepository {
            override val settings = target.settings.settings
            override suspend fun save(settings: EpubSettings) {
                target.settings.save(settings)
                if (target.settings.attempts == 1) {
                    assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(target, pdfId))
                    journal = JSONObject(target.journal.readText(Charsets.UTF_8))
                    throw IOException("controlled failure after durable preference save")
                }
            }
        }
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            assertEquals(BackupResult.Failed(BackupFailure.IO), target.backup(docs, settings = failing).restore("content://backup"))
            assertEmpty(target, previousSettings)
            assertEquals(2, target.settings.attempts)
            assertEquals(setOf("books", "covers", "previousSettings"), requireNotNull(journal).keys().asSequence().toSet())
            assertEquals(settingsKeys, requireNotNull(journal).getJSONObject("previousSettings").keys().asSequence().toSet())
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(target, pdfId))
        } finally { source.close(); target.close() }
    }

    @Test fun `reopened recovery cascades matching PDF`() = runBlocking {
        var target = fixture("recovery", restoredSettings)
        try {
            seed(target)
            addBook(target, otherId, otherBytes, "PDF")
            savePreferences(target, otherId, hash(otherBytes), 3, 4, "PAGE", 0)
            target.journal.writeText(pendingJournal(), Charsets.UTF_8)
            target.db.close()
            target = target.copy(db = open(target.databaseName))
            target.backup(Documents()).recoverPendingRestore()
            assertNull(target.db.books().findById(pdfId))
            assertNull(preferences(target, pdfId))
            assertEquals(listOf(hash(otherBytes), 3, 4, "PAGE", 0), preferences(target, otherId))
            assertFalse(File(target.books, "$pdfId.pdf").exists())
            assertArrayEquals(otherBytes, File(target.books, "$otherId.pdf").readBytes())
            assertEquals(previousSettings, target.settings.settings.first())
            assertFalse(target.journal.exists())
            target.backup(Documents()).recoverPendingRestore()
            assertEquals(listOf(hash(otherBytes), 3, 4, "PAGE", 0), preferences(target, otherId))
        } finally { target.close() }
    }

    @Test fun `recovery retains changed content hash`() = runBlocking {
        val target = fixture("changed-recovery", restoredSettings)
        try {
            seed(target)
            target.journal.writeText(pendingJournal(), Charsets.UTF_8)
            target.db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ? WHERE id = ?", arrayOf(hash(otherBytes), pdfId))
            target.db.openHelper.writableDatabase.execSQL("UPDATE $preferenceTable SET contentSha256 = ?, fitMode = 'PAGE', doublePagePreferred = 0 WHERE bookId = ?", arrayOf(hash(otherBytes), pdfId))
            File(target.books, "$pdfId.pdf").writeBytes(otherBytes)
            target.backup(Documents()).recoverPendingRestore()
            assertEquals(listOf(hash(otherBytes), 8, 12, "PAGE", 0), preferences(target, pdfId))
            assertArrayEquals(otherBytes, File(target.books, "$pdfId.pdf").readBytes())
            assertFalse(target.journal.exists())
        } finally { target.close() }
    }

    @Test fun `bad journal retains PDF files and rows`() = runBlocking {
        val target = fixture("bad-journal", restoredSettings)
        val docs = Documents()
        try {
            seed(target)
            val malformed = JSONObject(pendingJournal()).put("pdfPreferences", JSONArray())
            target.journal.writeText(malformed.toString(), Charsets.UTF_8)
            val journalBytes = target.journal.readBytes()
            assertEquals(BackupResult.Failed(BackupFailure.RECOVERY_REQUIRED), target.backup(docs).create("content://backup"))
            assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(target, pdfId))
            assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
            assertArrayEquals(journalBytes, target.journal.readBytes())
            assertEquals(restoredSettings, target.settings.settings.first())
            assertNull(docs.bytes)
        } finally { target.close() }
    }

    @Test fun `input cancellation permits restore retry`() = runBlocking {
        val source = fixture("cancel-input-source")
        val target = fixture("cancel-input-target", previousSettings)
        val docs = Documents()
        val mutex = Mutex()
        try {
            seed(source)
            source.backup(docs).create("content://backup")
            docs.beforeInput = { throw CancellationException("controlled restore cancellation") }
            assertTrue(runCatching { target.backup(docs, mutex).restore("content://backup") }.exceptionOrNull() is CancellationException)
            assertEmpty(target, previousSettings)
            assertFalse(mutex.isLocked)
            docs.beforeInput = null
            assertEquals(BackupResult.Restored(2), target.backup(docs, mutex).restore("content://backup"))
            assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(target, pdfId))
        } finally { source.close(); target.close() }
    }

    @Test fun `export cancellation retains PDF rows`() = runBlocking {
        val source = fixture("cancel-output-source")
        val docs = Documents(beforeOutput = { throw CancellationException("controlled export cancellation") })
        val mutex = Mutex()
        try {
            seed(source)
            assertTrue(runCatching { source.backup(docs, mutex).create("content://backup") }.exceptionOrNull() is CancellationException)
            assertEquals(1, docs.deletes)
            assertNull(docs.bytes)
            assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(source, pdfId))
            assertFalse(source.journal.exists())
            assertFalse(mutex.isLocked)
            assertEquals(BackupResult.Created(2), source.backup(Documents(), mutex).create("content://backup"))
        } finally { source.close() }
    }

    @Test fun `commit cancellation completes atomic restore`() = runBlocking {
        val source = fixture("cancel-commit-source", restoredSettings)
        val target = fixture("cancel-commit-target", previousSettings)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val docs = Documents()
        val mutex = Mutex()
        val guardedSettings = object : EpubSettingsRepository {
            override val settings = target.settings.settings
            override suspend fun save(settings: EpubSettings) {
                assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(target, pdfId))
                assertEquals(1L, navigationCount(target))
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                target.settings.save(settings)
            }
        }
        var restore: Deferred<BackupResult>? = null
        try {
            seed(source)
            RoomBookPdfViewPreferencesRepository(source.db).saveNavigation(pdfId, hash(pdfBytes), PdfNavigationMode.VERTICAL_SCROLL)
            source.backup(docs).create("content://backup")
            restore = async(Dispatchers.IO) { target.backup(docs, mutex, guardedSettings).restore("content://backup") }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            restore.cancel()
            release.countDown()
            restore.join()
            assertTrue(restore.isCancelled)
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, RoomBookPdfViewPreferencesRepository(target.db).observe(pdfId, hash(pdfBytes)).first().navigationMode)
            assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(target, pdfId))
            assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
            assertEquals(restoredSettings, target.settings.settings.first())
            assertFalse(target.journal.exists())
            assertTrue(target.stage.listFiles().isNullOrEmpty())
            assertFalse(mutex.isLocked)
            assertEquals(BackupResult.Created(2), target.backup(Documents(), mutex).create("content://backup"))
        } finally { release.countDown(); restore?.cancelAndJoin(); source.close(); target.close() }
    }

    @Test fun `mutex snapshots PDF before waiting writer`() = runBlocking {
        val source = fixture("mutex-source")
        val mutex = Mutex()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val docs = Documents(beforeOutput = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        var backup: Deferred<BackupResult>? = null
        var writer: Deferred<Unit>? = null
        try {
            seed(source)
            backup = async(Dispatchers.IO) { source.backup(docs, mutex).create("content://backup") }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            val attempted = CompletableDeferred<Unit>()
            writer = async(Dispatchers.IO) {
                attempted.complete(Unit)
                mutex.withLock { source.db.openHelper.writableDatabase.execSQL("UPDATE $preferenceTable SET horizontalPercent = 20, fitMode = 'PAGE', doublePagePreferred = 0") }
            }
            attempted.await()
            assertFalse(writer.isCompleted)
            release.countDown()
            assertEquals(BackupResult.Created(2), backup.await())
            writer.await()
            val row = preferenceRow(json(entries(requireNotNull(docs.bytes)).getValue("records.json")))
            assertEquals(8, row.getInt("horizontalPercent"))
            assertEquals("WIDTH", row.getString("fitMode"))
            assertEquals(1, row.getInt("doublePagePreferred"))
            assertEquals(listOf(hash(pdfBytes), 20, 12, "PAGE", 0), preferences(source, pdfId))
        } finally { release.countDown(); backup?.cancelAndJoin(); writer?.cancelAndJoin(); source.close() }
    }

    @Test fun horizontalModeSurvivesActualBackupRestoreAndAnOlderHeaderRejectsBeforeTargetMutation() = runBlocking {
        val source = fixture("horizontal-source", restoredSettings)
        var target = fixture("horizontal-target", previousSettings)
        val docs = Documents()
        try {
            seed(source)
            RoomBookPdfViewPreferencesRepository(source.db).saveNavigation(pdfId, hash(pdfBytes), PdfNavigationMode.HORIZONTAL_SCROLL)
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            val current = requireNotNull(docs.bytes)
            val downgraded = rewrite(current) { manifest, _ -> manifest.put("formatVersion", 23) }
            docs.bytes = downgraded
            assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
            assertTrue(target.db.books().observeAll().first().isEmpty()); assertEquals(previousSettings, target.settings.settings.first())
            docs.bytes = current
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            target.db.close(); target = target.copy(db = open(target.databaseName))
            val restored = RoomBookPdfViewPreferencesRepository(target.db).observe(pdfId, hash(pdfBytes)).first()
            assertEquals(PdfNavigationMode.HORIZONTAL_SCROLL, restored.navigationMode)
            assertEquals(8, restored.horizontalPercent); assertEquals(12, restored.verticalPercent)
            assertEquals(PdfFitPreference.WIDTH, restored.fitMode); assertTrue(restored.doublePagePreferred)
            assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
            assertEquals(restoredSettings, target.settings.settings.first()); assertFalse(target.journal.exists())
        } finally { source.close(); target.close() }
    }

    @Test fun navigationModesAndNavigationOnlyRowSurviveActualZipRestoreAndRoomReopen() = runBlocking {
        val source = fixture("navigation-source", restoredSettings)
        var target = fixture("navigation-target", previousSettings)
        val docs = Documents()
        try {
            seed(source); addBook(source, otherId, otherBytes, "PDF")
            val repository = RoomBookPdfViewPreferencesRepository(source.db)
            repository.saveNavigation(pdfId, hash(pdfBytes), PdfNavigationMode.VERTICAL_SCROLL)
            repository.saveNavigation(otherId, hash(otherBytes), PdfNavigationMode.VERTICAL_SCROLL)
            repository.saveNavigation(otherId, hash(otherBytes), PdfNavigationMode.PAGED)
            assertNull(preferences(source, otherId))
            assertEquals(BackupResult.Created(3), source.backup(docs).create("content://backup"))
            val archive = entries(requireNotNull(docs.bytes))
            val manifest = json(archive.getValue("manifest.json"))
            assertEquals(26, manifest.getInt("formatVersion")); assertEquals(26, manifest.getInt("databaseVersion"))
            val rows = json(archive.getValue("records.json")).getJSONObject("tables").getJSONArray("book_pdf_navigation_preferences")
            assertEquals(2, rows.length())
            for (i in 0 until rows.length()) assertEquals(setOf("bookId", "contentSha256", "mode"), rows.getJSONObject(i).keys().asSequence().toSet())
            assertEquals(BackupResult.Restored(3), target.backup(docs).restore("content://backup"))
            target.db.close(); target = target.copy(db = open(target.databaseName))
            val restored = RoomBookPdfViewPreferencesRepository(target.db)
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, restored.observe(pdfId, hash(pdfBytes)).first().navigationMode)
            assertEquals(PdfNavigationMode.PAGED, restored.observe(otherId, hash(otherBytes)).first().navigationMode)
            assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(target, pdfId))
            assertNull(preferences(target, otherId))
            assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
            assertArrayEquals(otherBytes, File(target.books, "$otherId.pdf").readBytes())
            assertEquals(restoredSettings, target.settings.settings.first()); assertFalse(target.journal.exists())
        } finally { source.close(); target.close() }
    }

    @Test fun actualFormat20RestoresItsOldFieldsWithoutInventingNavigationAndRejectsForgedNewTables() = runBlocking {
        val source = fixture("navigation-old-source"); val docs = Documents()
        try {
            seed(source)
            RoomBookPdfViewPreferencesRepository(source.db).saveNavigation(pdfId, hash(pdfBytes), PdfNavigationMode.VERTICAL_SCROLL)
            source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            val oldArchive = rewrite(original) { manifest, records ->
                manifest.put("formatVersion", 20).put("databaseVersion", 25)
                records.getJSONObject("tables").remove("book_pdf_navigation_preferences")
            }
            val target = fixture("navigation-old-target")
            try {
                docs.bytes = oldArchive
                assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
                assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(target, pdfId))
                assertEquals(PdfNavigationMode.PAGED, RoomBookPdfViewPreferencesRepository(target.db).observe(pdfId, hash(pdfBytes)).first().navigationMode)
                assertEquals(0L, navigationCount(target))
            } finally { target.close() }
            for (empty in listOf(false, true)) {
                val rejected = fixture("navigation-forged-$empty", previousSettings)
                try {
                    docs.bytes = rewrite(original) { manifest, records ->
                        manifest.put("formatVersion", 20).put("databaseVersion", 25)
                        if (empty) records.getJSONObject("tables").put("book_pdf_navigation_preferences", JSONArray())
                    }
                    assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), rejected.backup(docs).restore("content://backup"))
                    assertEmpty(rejected, previousSettings); assertEquals(0L, navigationCount(rejected))
                } finally { rejected.close() }
            }
        } finally { source.close() }
    }

    @Test fun malformedStaleWrongKindMissingAndDuplicateNavigationRowsRejectWholeZipBeforeWrites() = runBlocking {
        val source = fixture("navigation-corrupt-source"); val target = fixture("navigation-corrupt-target", previousSettings)
        val docs = Documents()
        try {
            seed(source)
            RoomBookPdfViewPreferencesRepository(source.db).saveNavigation(pdfId, hash(pdfBytes), PdfNavigationMode.VERTICAL_SCROLL)
            source.backup(docs).create("content://backup"); val original = requireNotNull(docs.bytes)
            val edits: List<(JSONObject) -> Unit> = listOf(
                { it.put("mode", "scroll") }, { it.put("mode", "VERTICAL_SCROLL ") }, { it.put("mode", "HORIZONTAL") },
                { it.put("mode", true) }, { it.put("mode", 1) }, { it.put("mode", JSONObject.NULL) },
                { it.remove("mode") }, { it.put("extra", "value") }, { it.put("contentSha256", "b".repeat(64)) },
                { it.put("bookId", epubId).put("contentSha256", hash(epubBytes)) }, { it.put("bookId", otherId) })
            for (edit in edits) {
                docs.bytes = rewrite(original) { _, records -> edit(records.getJSONObject("tables").getJSONArray("book_pdf_navigation_preferences").getJSONObject(0)) }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target, previousSettings); assertEquals(0L, navigationCount(target))
            }
            for (duplicate in listOf(false, true)) {
                docs.bytes = rewrite(original) { _, records ->
                    val tables = records.getJSONObject("tables")
                    if (duplicate) tables.getJSONArray("book_pdf_navigation_preferences").apply { put(JSONObject(getJSONObject(0).toString())) }
                    else tables.remove("book_pdf_navigation_preferences")
                }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target, previousSettings); assertEquals(0L, navigationCount(target))
            }
        } finally { source.close(); target.close() }
    }

    @Test fun navigationRestoreSettingsFailureRollsBackFilesAndBothPdfTablesAndCanBeRetried() = runBlocking {
        val source = fixture("navigation-rollback-source", restoredSettings); val target = fixture("navigation-rollback-target", previousSettings)
        val docs = Documents(); var fail = true
        val settings = object : EpubSettingsRepository {
            override val settings = target.settings.settings
            override suspend fun save(settings: EpubSettings) {
                if (fail) { fail = false; throw IOException("controlled settings restore") }
                target.settings.save(settings)
            }
        }
        try {
            seed(source)
            RoomBookPdfViewPreferencesRepository(source.db).saveNavigation(pdfId, hash(pdfBytes), PdfNavigationMode.VERTICAL_SCROLL)
            source.backup(docs).create("content://backup")
            assertTrue(target.backup(docs, settings = settings).restore("content://backup") is BackupResult.Failed)
            assertEmpty(target, previousSettings); assertEquals(0L, navigationCount(target))
            assertEquals(BackupResult.Restored(2), target.backup(docs, settings = settings).restore("content://backup"))
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, RoomBookPdfViewPreferencesRepository(target.db).observe(pdfId, hash(pdfBytes)).first().navigationMode)
            assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(target, pdfId))
        } finally { source.close(); target.close() }
    }

    @Test fun corruptNavigationInActualRoomRefusesExportWithoutWritingAnArchiveOrRepairingTheRow() = runBlocking {
        val source = fixture("navigation-bad-export"); val docs = Documents()
        try {
            seed(source)
            val edits = listOf("mode = 'vertical_scroll'", "mode = x'5041474544'",
                "contentSha256 = '${"b".repeat(64)}'", "bookId = '$epubId', contentSha256 = '${hash(epubBytes)}'")
            for (edit in edits) {
                source.db.openHelper.writableDatabase.execSQL("INSERT INTO book_pdf_navigation_preferences VALUES (?, ?, ?)", arrayOf(pdfId, hash(pdfBytes), "VERTICAL_SCROLL"))
                source.db.openHelper.writableDatabase.execSQL("UPDATE book_pdf_navigation_preferences SET $edit")
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), source.backup(docs).create("content://backup"))
                assertNull(docs.bytes); assertEquals(1L, navigationCount(source))
                assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(source, pdfId))
                source.db.openHelper.writableDatabase.execSQL("DELETE FROM book_pdf_navigation_preferences")
            }
        } finally { source.close() }
    }

    @Test fun navigationSchemaRequiresItsOwnPrimaryKeyAndBookCascadeBeforeExport() = runBlocking {
        val definitions = listOf(
            "bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, mode TEXT NOT NULL, PRIMARY KEY(bookId)",
            "bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, mode TEXT NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE NO ACTION",
            "bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, mode TEXT NOT NULL, PRIMARY KEY(bookId, contentSha256), FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE",
            "bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, mode INTEGER NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE")
        for ((i, definition) in definitions.withIndex()) {
            val source = fixture("navigation-schema-$i"); val docs = Documents()
            try {
                seed(source)
                source.db.openHelper.writableDatabase.execSQL("DROP TABLE book_pdf_navigation_preferences")
                source.db.openHelper.writableDatabase.execSQL("CREATE TABLE book_pdf_navigation_preferences ($definition)")
                assertEquals(BackupResult.Failed(BackupFailure.UNSUPPORTED_VERSION), source.backup(docs).create("content://backup"))
                assertNull(docs.bytes); assertEquals(listOf(hash(pdfBytes), 8, 12, "WIDTH", 1), preferences(source, pdfId))
            } finally { source.close() }
        }
    }

    @Test fun pdfCoverageInjectedIntoOtherwiseValidCurrentAndLegacyZipsIsRejectedBeforeRestore() = runBlocking {
        val source = fixture("coverage-source")
        val target = fixture("coverage-target", previousSettings)
        val docs = Documents()
        try {
            seed(source)
            source.db.reading().observe(listOf(com.yaprak.reader.data.persistence.ObservedTextEntity(
                epubId, hash(epubBytes), 1, "one.xhtml", "0/1", 0, 4, "b".repeat(64))))
            assertEquals(BackupResult.Created(2), source.backup(docs).create("content://backup"))
            val original = requireNotNull(docs.bytes)
            for (version in listOf(21, 20, 1)) {
                val archive = if (version == 1) legacy(original, 1) else original
                docs.bytes = rewrite(archive) { manifest, records ->
                    if (version == 20) {
                        manifest.put("formatVersion", 20).put("databaseVersion", 25)
                        records.getJSONObject("tables").remove("book_pdf_navigation_preferences")
                    }
                    records.getJSONObject("tables").getJSONArray("observed_text").getJSONObject(0)
                        .put("bookId", pdfId).put("contentSha256", hash(pdfBytes))
                }
                assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), target.backup(docs).restore("content://backup"))
                assertEmpty(target, previousSettings)
            }
            docs.bytes = original
            assertEquals(BackupResult.Restored(2), target.backup(docs).restore("content://backup"))
            assertEquals(1, target.db.reading().observed(epubId, hash(epubBytes)).size)
            assertTrue(target.db.reading().observed(pdfId, hash(pdfBytes)).isEmpty())
        } finally { source.close(); target.close() }
    }

    @Test fun livePdfCoverageCannotBeExportedAsAValidArchive() = runBlocking {
        val source = fixture("coverage-export")
        val docs = Documents()
        try {
            seed(source)
            source.db.reading().observe(listOf(com.yaprak.reader.data.persistence.ObservedTextEntity(
                pdfId, hash(pdfBytes), 1, "one.xhtml", "0/1", 0, 4, "b".repeat(64))))
            assertEquals(BackupResult.Failed(BackupFailure.INVALID_ARCHIVE), source.backup(docs).create("content://backup"))
            assertNull(docs.bytes)
            assertEquals(1, source.db.reading().observed(pdfId, hash(pdfBytes)).size)
            assertFalse(source.journal.exists())
        } finally { source.close() }
    }

    @Test fun originalPdfResumeAnchorAndLegacyPageOnlyPositionSurviveActualZipAndDatabaseReopen() = runBlocking {
        val source = fixture("resume-source")
        var target = fixture("resume-target")
        val docs = Documents()
        try {
            seed(source); addBook(source, otherId, otherBytes, "PDF")
            val position = com.yaprak.reader.formats.pdf.PdfPosition.encode(com.yaprak.reader.formats.pdf.PdfResumePosition(2, 645_321))
            val legacyPosition = com.yaprak.reader.formats.pdf.PdfPosition.encode(1)
            val reading = com.yaprak.reader.data.persistence.RoomReadingStateRepository(source.db)
            reading.record(pdfId, hash(pdfBytes), com.yaprak.reader.domain.reader.ReadingSnapshot(position))
            reading.record(otherId, hash(otherBytes), com.yaprak.reader.domain.reader.ReadingSnapshot(legacyPosition))
            RoomBookPdfViewPreferencesRepository(source.db).saveNavigation(pdfId, hash(pdfBytes), PdfNavigationMode.VERTICAL_SCROLL)
            assertEquals(BackupResult.Created(3), source.backup(docs).create("content://backup"))
            assertEquals(BackupResult.Restored(3), target.backup(docs).restore("content://backup"))
            target.db.close(); target = target.copy(db = open(target.databaseName))
            val restored = com.yaprak.reader.data.persistence.RoomReadingStateRepository(target.db)
            assertEquals(position, restored.resume(pdfId, hash(pdfBytes)))
            assertEquals(legacyPosition, restored.resume(otherId, hash(otherBytes)))
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, RoomBookPdfViewPreferencesRepository(target.db).observe(pdfId, hash(pdfBytes)).first().navigationMode)
            assertTrue(target.db.reading().observed(pdfId, hash(pdfBytes)).isEmpty())
            assertTrue(target.db.reading().observed(otherId, hash(otherBytes)).isEmpty())
            assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
        } finally { source.close(); target.close() }
    }

    @Test fun originalPdfPointBookmarksAndLegacyBookmarksSurviveActualZipWithIndependentResume() = runBlocking {
        val source = fixture("bookmark-source")
        var target = fixture("bookmark-target")
        val docs = Documents()
        try {
            seed(source); addBook(source, otherId, otherBytes, "PDF")
            val point = com.yaprak.reader.domain.bookmarks.BookmarkTarget("Sayfa 3 · okuma noktası",
                com.yaprak.reader.formats.pdf.PdfPosition.encode(com.yaprak.reader.formats.pdf.PdfResumePosition(2, 645_321)))
            val legacy = com.yaprak.reader.domain.bookmarks.BookmarkTarget("Sayfa 2",
                com.yaprak.reader.formats.pdf.PdfPosition.encode(1))
            val bookmarks = com.yaprak.reader.data.persistence.RoomBookmarkRepository(source.db)
            bookmarks.add(pdfId, hash(pdfBytes), point); bookmarks.add(otherId, hash(otherBytes), legacy)
            val resume = com.yaprak.reader.formats.pdf.PdfPosition.encode(0)
            com.yaprak.reader.data.persistence.RoomReadingStateRepository(source.db)
                .record(pdfId, hash(pdfBytes), com.yaprak.reader.domain.reader.ReadingSnapshot(resume))
            RoomBookPdfViewPreferencesRepository(source.db).saveNavigation(pdfId, hash(pdfBytes), PdfNavigationMode.VERTICAL_SCROLL)
            assertEquals(BackupResult.Created(3), source.backup(docs).create("content://backup"))
            assertEquals(BackupResult.Restored(3), target.backup(docs).restore("content://backup"))
            target.db.close(); target = target.copy(db = open(target.databaseName))
            val restored = com.yaprak.reader.data.persistence.RoomBookmarkRepository(target.db)
            assertEquals(point, restored.observe(pdfId, hash(pdfBytes)).first().single().target)
            assertEquals(legacy, restored.observe(otherId, hash(otherBytes)).first().single().target)
            assertEquals(resume, com.yaprak.reader.data.persistence.RoomReadingStateRepository(target.db).resume(pdfId, hash(pdfBytes)))
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL,
                RoomBookPdfViewPreferencesRepository(target.db).observe(pdfId, hash(pdfBytes)).first().navigationMode)
            assertTrue(target.db.reading().observed(pdfId, hash(pdfBytes)).isEmpty())
            assertTrue(target.db.reading().observed(otherId, hash(otherBytes)).isEmpty())
            assertArrayEquals(pdfBytes, File(target.books, "$pdfId.pdf").readBytes())
            assertArrayEquals(otherBytes, File(target.books, "$otherId.pdf").readBytes())
        } finally { source.close(); target.close() }
    }

    private fun navigationCount(target: Fixture): Long = target.db.openHelper.readableDatabase
        .compileStatement("SELECT COUNT(*) FROM book_pdf_navigation_preferences").simpleQueryForLong()

    private data class Fixture(val db: LibraryDatabase, val databaseName: String, val books: File,
        val stage: File, val journal: File, val settings: Settings) {
        fun backup(docs: BackupDocumentProvider, mutex: Mutex = Mutex(), settings: EpubSettingsRepository = this.settings) =
            DefaultLibraryBackup(db, books, stage, journal, settings, docs, mutex)
        fun close() { db.close(); ApplicationProvider.getApplicationContext<Application>().deleteDatabase(databaseName) }
    }

    private fun open(name: String): LibraryDatabase {
        val parent = requireNotNull(context.getDatabasePath(name).parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create test database directory" }
        return Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
    }
    private fun fixture(name: String, settings: EpubSettings = EpubSettings()): Fixture {
        val parent = temporary.newFolder(name)
        val databaseName = "pv-${UUID.randomUUID().toString().take(8)}.db"
        return Fixture(open(databaseName), databaseName, File(parent, "books").apply { mkdir() },
            File(parent, "stage"), File(parent, "restore.pending"), Settings(settings))
    }
    private suspend fun seed(target: Fixture) {
        addBook(target, pdfId, pdfBytes, "PDF")
        addBook(target, epubId, epubBytes, "EPUB")
        savePreferences(target)
    }
    private suspend fun addBook(target: Fixture, id: String, bytes: ByteArray, format: String) {
        val storageName = "$id.${format.lowercase()}"
        target.db.books().insert(BookEntity(id, "$format title", "Author", format, storageName,
            storageName, bytes.size.toLong(), hash(bytes), 10, if (format == "PDF") 4 else null))
        File(target.books, storageName).writeBytes(bytes)
    }
    private fun savePreferences(target: Fixture, id: String = pdfId, sha256: String = hash(pdfBytes),
        horizontal: Int = 8, vertical: Int = 12, fit: String = "WIDTH", doublePage: Int = 1) {
        target.db.openHelper.writableDatabase.execSQL("INSERT INTO $preferenceTable " +
            "(bookId, contentSha256, horizontalPercent, verticalPercent, fitMode, doublePagePreferred) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, sha256, horizontal, vertical, fit, doublePage))
    }
    private fun preferences(target: Fixture, id: String): List<Any?>? = target.db.openHelper.readableDatabase.query(
        "SELECT contentSha256, horizontalPercent, verticalPercent, fitMode, doublePagePreferred FROM $preferenceTable WHERE bookId = ?", arrayOf(id)).use { cursor ->
        if (!cursor.moveToFirst()) null else listOf<Any?>(cursor.getString(0), cursor.getInt(1), cursor.getInt(2), cursor.getString(3), cursor.getInt(4))
    }
    private fun preferenceCount(target: Fixture): Int = target.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $preferenceTable").use {
        it.moveToFirst(); it.getInt(0)
    }
    private suspend fun assertEmpty(target: Fixture, settings: EpubSettings) {
        assertTrue(target.db.books().observeAll().first().isEmpty())
        assertEquals(0, preferenceCount(target))
        assertTrue(target.books.listFiles().isNullOrEmpty())
        assertTrue(target.stage.listFiles().isNullOrEmpty())
        assertFalse(target.journal.exists())
        assertEquals(settings, target.settings.settings.first())
    }
    private fun pendingJournal(): String = JSONObject().put("books", JSONArray().put(JSONObject()
        .put("id", pdfId).put("storageName", "$pdfId.pdf").put("format", "PDF")
        .put("byteSize", pdfBytes.size).put("sha256", hash(pdfBytes))))
        .put("previousSettings", BackupRecordsCodec.encodeSettings(previousSettings)).toString()
    private fun preferenceRow(records: JSONObject): JSONObject =
        (records.optJSONObject("tables") ?: records).getJSONArray(preferenceTable).getJSONObject(0)
    private fun legacy(bytes: ByteArray, version: Int, removePdfPreferences: Boolean = true): ByteArray = rewrite(bytes) { manifest, records ->
        manifest.put("formatVersion", version).put("databaseVersion", when (version) {
            1, 2, 3 -> 12; 4 -> 13; 5 -> 14; 6 -> 15; 7 -> 16; else -> error("Not legacy")
        })
        val tables = records.getJSONObject("tables")
        stripWordReviewTables(tables); tables.remove("pdf_annotations"); tables.remove("epub_range_passages")
        if (removePdfPreferences) tables.remove(preferenceTable)
        if (version <= 6) tables.remove("reading_duration_receipts")
        if (version <= 5) {
            val passages = tables.getJSONArray("epub_passages")
            for (index in 0 until passages.length()) passages.getJSONObject(index).apply {
                remove("highlightColor"); remove("attachedNote"); remove("revision")
            }
        }
        if (version <= 4) { tables.remove("book_covers"); manifest.remove("coverCount"); manifest.remove("covers") }
        if (version <= 3) tables.remove("book_organizations")
        records.asPreBookSpacingTables().getJSONObject("settings").asPreSpacingSettings().apply { if (version < 3) remove("textAlignment"); if (version < 2) remove("scroll") }
    }
    /** Recompute archive integrity so tests reach the semantic validator after tampering. */
    private fun rewrite(bytes: ByteArray, edit: (JSONObject, JSONObject) -> Unit): ByteArray {
        val archive = entries(bytes)
        val manifest = json(archive.getValue("manifest.json"))
        val records = json(archive.getValue("records.json"))
        edit(manifest, records)
        val encoded = records.toString().toByteArray(Charsets.UTF_8)
        manifest.put("recordsSize", encoded.size).put("recordsSha256", hash(encoded))
        archive["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        archive["records.json"] = encoded
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            archive.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
        } }.toByteArray()
    }
    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { result ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes(); zip.closeEntry() }
        }
    }
    private fun json(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Settings(initial: EpubSettings) : EpubSettingsRepository {
        override val settings = MutableStateFlow(initial)
        var attempts = 0
        override suspend fun save(settings: EpubSettings) { attempts++; this.settings.value = settings }
    }
    private class Documents(private val beforeOutput: (() -> Unit)? = null) : BackupDocumentProvider {
        var bytes: ByteArray? = null
        var deletes = 0
        var beforeInput: (() -> Unit)? = null
        override fun accepts(uri: String) = uri.startsWith("content://")
        override fun openInput(uri: String): InputStream { beforeInput?.invoke(); return ByteArrayInputStream(requireNotNull(bytes)) }
        override fun openOutput(uri: String): OutputStream {
            beforeOutput?.invoke()
            return object : ByteArrayOutputStream() { override fun close() { super.close(); bytes = toByteArray() } }
        }
        override fun delete(uri: String): Boolean { deletes++; bytes = null; return true }
    }
}

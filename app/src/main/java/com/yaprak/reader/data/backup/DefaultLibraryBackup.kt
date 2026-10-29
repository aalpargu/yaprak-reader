package com.yaprak.reader.data.backup

import androidx.room.withTransaction
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.covers.LocalCoverFiles
import com.yaprak.reader.formats.CustomCoverBitmapDecoder
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.backup.BackupResult
import com.yaprak.reader.domain.backup.LibraryBackup
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.EpubSettingsRepository
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Streaming book files; bounded metadata and an explicit rollback journal for the commit gap. */
class DefaultLibraryBackup(
    private val database: LibraryDatabase,
    private val booksDirectory: File,
    private val stagingDirectory: File,
    private val journalFile: File,
    private val settings: EpubSettingsRepository,
    private val documents: BackupDocumentProvider,
    private val fileMutex: Mutex,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val coversDirectory: File = File(booksDirectory.parentFile, "covers"),
    private val cbrCache: com.yaprak.reader.formats.cbr.CbrPublicationCache = com.yaprak.reader.formats.cbr.CbrPublicationCache(stagingDirectory.parentFile!!),
) : LibraryBackup {
    private val coverFiles = LocalCoverFiles(coversDirectory)
    override suspend fun create(destinationUri: String): BackupResult = withContext(io) {
        fileMutex.withLock {
            var targetMayExist = false
            try {
                recoverOrRequireRepair()
                if (!documents.accepts(destinationUri)) throw BackupProblem(BackupFailure.ACCESS)
                val snapshot = BackupRecordsCodec.snapshot(database, settings.settings.first())
                val originalNames = snapshot.rows.getValue("books").associate { it.getString("id") to it.getString("originalName") }
                val recordsBytes = BackupRecordsCodec.encode(snapshot)
                val manifestBytes = BackupRecordsCodec.encodeManifest(
                    snapshot, recordsBytes, sha256(recordsBytes),
                )
                targetMayExist = true
                documents.openOutput(destinationUri).use { output ->
                    ZipOutputStream(output.buffered()).use { zip ->
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(manifestBytes)
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("records.json"))
                        zip.write(recordsBytes)
                        zip.closeEntry()
                        snapshot.books.forEach { book ->
                            currentCoroutineContext().ensureActive()
                            zip.putNextEntry(ZipEntry(book.entryName))
                            writeBook(zip, book, originalNames.getValue(book.id))
                            zip.closeEntry()
                        }
                        snapshot.covers.forEach { cover ->
                            currentCoroutineContext().ensureActive()
                            zip.putNextEntry(ZipEntry(cover.entryName))
                            writeCover(zip, cover)
                            zip.closeEntry()
                        }
                    }
                }
                BackupResult.Created(snapshot.books.size)
            } catch (error: CancellationException) {
                if (targetMayExist) withContext(NonCancellable + io) { deleteBestEffort(destinationUri) }
                throw error
            } catch (error: Exception) {
                val reason = failure(error)
                BackupResult.Failed(reason,
                    partialFileMayRemain = targetMayExist && !deleteBestEffort(destinationUri))
            }
        }
    }

    override suspend fun restore(sourceUri: String): BackupResult = withContext(io) {
        fileMutex.withLock {
            var stage: File? = null
            try {
                recoverOrRequireRepair()
                if (!documents.accepts(sourceUri)) throw BackupProblem(BackupFailure.ACCESS)
                if (!emptyLibrary()) throw BackupProblem(BackupFailure.NOT_EMPTY)
                stage = newStageDirectory()
                val archive = documents.openInput(sourceUri).use { input -> readArchive(input, stage) }
                currentCoroutineContext().ensureActive()
                // A second check protects against database writes not coordinated by fileMutex.
                if (!emptyLibrary()) throw BackupProblem(BackupFailure.NOT_EMPTY)
                val previous = settings.settings.first()
                withContext(NonCancellable + io) {
                    try {
                        commit(archive, stage, previous)
                    } catch (error: Exception) {
                        if (journalFile.exists()) {
                            try { recoverLocked() }
                            catch (recovery: Exception) {
                                error.addSuppressed(recovery)
                                throw BackupProblem(BackupFailure.RECOVERY_REQUIRED)
                            }
                        }
                        throw error
                    }
                }
                BackupResult.Restored(archive.records.books.size)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                BackupResult.Failed(failure(error))
            } finally {
                // This is cache cleanup; it must not conceal a required rollback failure.
                try { stage?.let(::cleanStage) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Startup can retry abandoned stage cleanup. */ }
            }
        }
    }

    override suspend fun recoverPendingRestore() = withContext(io) {
        fileMutex.withLock { recoverLocked() }
    }

    private suspend fun recoverOrRequireRepair() {
        try { recoverLocked() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw BackupProblem(BackupFailure.RECOVERY_REQUIRED) }
    }

    private data class StagedArchive(val records: BackupRecords)
    private data class PendingRestore(val books: List<ArchiveBook>, val covers: List<ArchiveCover>,
        val previousSettings: EpubSettings)

    private suspend fun readArchive(input: InputStream, stage: File): StagedArchive {
        var manifest: BackupRecordsCodec.Manifest? = null
        var records: BackupRecords? = null
        var originalNames = emptyMap<String, String>()
        val seen = HashSet<String>()
        val stagedNames = HashSet<String>()
        var entryCount = 0
        var totalBookBytes = 0L
        var totalCoverBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (++entryCount > BackupRecordsCodec.MAX_BOOKS * 2 + 2 || entry.isDirectory ||
                    !seen.add(entry.name)) throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                when {
                    entryCount == 1 && entry.name == "manifest.json" -> {
                        manifest = BackupRecordsCodec.decodeManifest(
                            readBounded(zip, BackupRecordsCodec.MAX_MANIFEST_BYTES))
                    }
                    entryCount == 2 && entry.name == "records.json" -> {
                        val bytes = readBounded(zip, BackupRecordsCodec.MAX_RECORDS_BYTES)
                        val expected = manifest ?: throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                        if (bytes.size.toLong() != expected.recordsSize ||
                            sha256(bytes) != expected.recordsHash) {
                            throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                        }
                        records = BackupRecordsCodec.decode(bytes, expected.formatVersion)
                        originalNames = records!!.rows.getValue("books").associate { it.getString("id") to it.getString("originalName") }
                        if (records?.books != expected.books || records?.covers != expected.covers)
                            throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                    }
                    entryCount > 2 -> {
                        val expectedBook = manifest?.books?.firstOrNull { it.entryName == entry.name }
                        val expectedCover = manifest?.covers?.firstOrNull { it.entryName == entry.name }
                        if (expectedBook == null && expectedCover == null)
                            throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                        if (records == null) throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                        val expectedSize = expectedBook?.byteSize ?: expectedCover!!.byteSize
                        val expectedHash = expectedBook?.sha256 ?: expectedCover!!.sha256
                        val storageName = expectedBook?.storageName ?: expectedCover!!.storageName
                        if (entry.size >= 0 && entry.size != expectedSize)
                            throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                        val target = File(stage, storageName)
                        if (target.canonicalFile.parentFile != stage.canonicalFile || target.exists())
                            throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                        val size = target.outputStream().use { output ->
                            copyFile(zip, output, expectedSize, expectedHash).also { output.fd.sync() }
                        }
                        if (expectedBook != null) {
                            val archiveVersion = manifest?.formatVersion ?: throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                            if (!validBackupBookSignature(target, expectedBook.format, originalNames.getValue(expectedBook.id), archiveVersion))
                                throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                            totalBookBytes += size
                            if (totalBookBytes > BackupRecordsCodec.MAX_TOTAL_BOOK_BYTES)
                                throw BackupProblem(BackupFailure.TOO_LARGE)
                        } else {
                            validateCoverImage(target, expectedCover!!, BackupFailure.INVALID_ARCHIVE)
                            totalCoverBytes += size
                            if (totalCoverBytes > BackupRecordsCodec.MAX_TOTAL_COVER_BYTES)
                                throw BackupProblem(BackupFailure.TOO_LARGE)
                        }
                        stagedNames += entry.name
                    }
                    else -> throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                }
                zip.closeEntry()
            }
        }
        val finalManifest = manifest ?: throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
        val finalRecords = records ?: throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
        val expectedEntries = finalManifest.books.mapTo(HashSet()) { it.entryName }
        finalManifest.covers.mapTo(expectedEntries) { it.entryName }
        if (stagedNames != expectedEntries)
            throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
        for (book in finalRecords.books.filter { it.format in setOf("CBZ", "CBR") }) {
            val archive = try {
                if (book.format == "CBR") cbrCache.acquire(File(stage, book.storageName)).use { lease ->
                    if (lease.sourceHash != book.sha256) throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                    lease.archive
                } else com.yaprak.reader.formats.cbz.CbzArchive.read(File(stage, book.storageName))
            } catch (_: com.yaprak.reader.formats.cbz.CbzException) { throw BackupProblem(BackupFailure.INVALID_ARCHIVE) }
              catch (_: com.yaprak.reader.formats.cbr.CbrException) { throw BackupProblem(BackupFailure.INVALID_ARCHIVE) }
            val row = finalRecords.rows.getValue("books").single { it.getString("id") == book.id }
            if (row.getInt("pageCount") != archive.pages.size) throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
            for (table in listOf("reading_progress", "bookmarks")) for (location in finalRecords.rows.getValue(table).filter { it.getString("bookId") == book.id }) {
                if (com.yaprak.reader.formats.cbz.CbzPosition.decode(com.yaprak.reader.domain.reader.ReadingLocation(
                        location.getString("locatorJson"), location.getString("resourceHref"), location.getInt("locatorVersion")), archive, com.yaprak.reader.domain.library.BookFormat.valueOf(book.format)) == null)
                    throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
            }
        }
        return StagedArchive(finalRecords)
    }

    private suspend fun writeBook(zip: ZipOutputStream, book: ArchiveBook, originalName: String) {
        val file = File(booksDirectory, book.storageName)
        if (file.canonicalFile.parentFile != booksDirectory.canonicalFile || !file.isFile ||
            file.length() != book.byteSize) throw BackupProblem(BackupFailure.CHANGED_SOURCE)
        if (book.format in setOf("FB2", "DOCX") && !validBackupBookSignature(file, book.format, originalName)) throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
        writeFile(zip, file, book.byteSize, book.sha256)
    }

    private suspend fun writeCover(zip: ZipOutputStream, cover: ArchiveCover) {
        val file = coverFiles.resolve(cover.storageName)
        if (!file.isFile || file.length() != cover.byteSize)
            throw BackupProblem(BackupFailure.CHANGED_SOURCE)
        validateCoverImage(file, cover, BackupFailure.CHANGED_SOURCE)
        writeFile(zip, file, cover.byteSize, cover.sha256)
    }

    private suspend fun validateCoverImage(file: File, cover: ArchiveCover, failure: BackupFailure) {
        val bytes = file.inputStream().use { readBounded(it, BackupRecordsCodec.MAX_COVER_BYTES.toInt()) }
        val metadata = CustomCoverBitmapDecoder.validate(bytes) ?: throw BackupProblem(failure)
        if (bytes.size.toLong() != cover.byteSize || sha256(bytes) != cover.sha256 ||
            metadata.mediaType != cover.mediaType || metadata.width != cover.width || metadata.height != cover.height)
            throw BackupProblem(failure)
    }

    private suspend fun writeFile(zip: ZipOutputStream, file: File, byteSize: Long, sha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                if (count == 0) continue
                if (size > byteSize - count) throw BackupProblem(BackupFailure.CHANGED_SOURCE)
                zip.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                size += count
            }
        }
        if (size != byteSize || hex(digest.digest()) != sha256)
            throw BackupProblem(BackupFailure.CHANGED_SOURCE)
    }

    private suspend fun copyFile(input: InputStream, output: OutputStream, byteSize: Long, sha256: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            if (count == 0) continue
            if (size > byteSize - count) throw BackupProblem(BackupFailure.TOO_LARGE)
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            size += count
        }
        if (size != byteSize || hex(digest.digest()) != sha256)
            throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
        return size
    }

    private suspend fun commit(archive: StagedArchive, stage: File, previous: EpubSettings) {
        val books = archive.records.books
        val covers = archive.records.covers
        if (!booksDirectory.isDirectory && !booksDirectory.mkdirs()) throw IOException("Cannot create books directory")
        books.forEach { book ->
            val target = File(booksDirectory, book.storageName)
            if (target.canonicalFile.parentFile != booksDirectory.canonicalFile || target.exists())
                throw IOException("Book file already exists")
        }
        if (covers.isNotEmpty() && !coversDirectory.isDirectory && !coversDirectory.mkdirs())
            throw IOException("Cannot create covers directory")
        covers.forEach { if (coverFiles.resolve(it.storageName).exists()) throw IOException("Cover file already exists") }
        writeJournal(PendingRestore(books, covers, previous))
        books.forEach { book ->
            val source = File(stage, book.storageName)
            val target = File(booksDirectory, book.storageName)
            if (!source.renameTo(target)) throw IOException("Cannot commit backup book")
        }
        covers.forEach { cover ->
            if (!File(stage, cover.storageName).renameTo(coverFiles.resolve(cover.storageName)))
                throw IOException("Cannot commit backup cover")
        }
        database.withTransaction {
            if (!emptyLibrary()) throw BackupProblem(BackupFailure.NOT_EMPTY)
            BackupRecordsCodec.insert(database, archive.records)
        }
        settings.save(archive.records.settings)
        if (!journalFile.delete()) throw IOException("Cannot complete restore journal")
    }

    private suspend fun recoverLocked() {
        if (!journalFile.exists()) {
            cleanDiscardedArtifacts()
            return
        }
        val pending = readJournal()
        database.withTransaction {
            pending.books.forEach { book ->
                val row = database.books().findById(book.id)
                // Book deletion also rolls back imported PDF view preferences through their CASCADE FK.
                if (row != null && row.sha256 == book.sha256 && row.storageName == book.storageName)
                    database.books().deleteExact(book.id, book.sha256)
            }
        }
        val retained = database.books().storedFileNames().toSet()
        pending.books.forEach { book ->
            if (book.storageName !in retained) {
                val file = File(booksDirectory, book.storageName)
                if (file.canonicalFile.parentFile != booksDirectory.canonicalFile ||
                    (file.exists() && (!file.isFile || !file.delete()))) {
                    throw IOException("Cannot clean restored book")
                }
            }
        }
        val retainedCovers = database.bookCovers().storedFileNames().toSet()
        pending.covers.forEach { cover ->
            if (cover.storageName !in retainedCovers && !coverFiles.removeCommitted(cover.storageName))
                throw IOException("Cannot clean restored cover")
        }
        settings.save(pending.previousSettings)
        if (!journalFile.delete()) throw IOException("Cannot remove restore journal")
        cleanDiscardedArtifacts()
    }

    /** Cache and journal drafts carry no committed data once there is no pending journal. */
    private fun cleanDiscardedArtifacts() {
        try { File(journalFile.parentFile, journalFile.name + ".tmp").delete() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A later attempt may remove this draft. */ }
        try { cleanAbandonedStages() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Stale cache cannot justify closing a valid library. */ }
    }

    private fun writeJournal(pending: PendingRestore) {
        if (journalFile.exists()) throw BackupProblem(BackupFailure.RECOVERY_REQUIRED)
        val parent = journalFile.parentFile ?: throw IOException("Journal needs parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create journal directory")
        val books = JSONArray()
        pending.books.forEach { book ->
            books.put(JSONObject().put("id", book.id).put("storageName", book.storageName)
                .put("format", book.format).put("byteSize", book.byteSize).put("sha256", book.sha256))
        }
        val bytes = JSONObject().put("books", books)
            .put("covers", JSONArray(pending.covers.map(BackupRecordsCodec::encodeCover)))
            .put("previousSettings", BackupRecordsCodec.encodeSettings(pending.previousSettings))
            .toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > BackupRecordsCodec.MAX_MANIFEST_BYTES)
            throw BackupProblem(BackupFailure.TOO_LARGE)
        val draft = File(parent, journalFile.name + ".tmp")
        try {
            draft.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!draft.renameTo(journalFile)) throw IOException("Cannot commit restore journal")
        } finally {
            draft.delete()
        }
    }

    private suspend fun readJournal(): PendingRestore {
        val bytes = journalFile.inputStream().use { readBounded(it, BackupRecordsCodec.MAX_MANIFEST_BYTES) }
        val root = try { JSONObject(BackupRecordsCodec.strictUtf8(bytes)) }
            catch (_: Exception) { throw BackupProblem(BackupFailure.RECOVERY_REQUIRED) }
        val keys = root.keys().asSequence().toSet()
        if (keys != setOf("books", "previousSettings") && keys != setOf("books", "covers", "previousSettings"))
            throw BackupProblem(BackupFailure.RECOVERY_REQUIRED)
        val booksArray = root.optJSONArray("books") ?: throw BackupProblem(BackupFailure.RECOVERY_REQUIRED)
        if (booksArray.length() > BackupRecordsCodec.MAX_BOOKS) throw BackupProblem(BackupFailure.RECOVERY_REQUIRED)
        val books = ArrayList<ArchiveBook>(booksArray.length())
        for (index in 0 until booksArray.length()) {
            val row = booksArray.optJSONObject(index) ?: throw BackupProblem(BackupFailure.RECOVERY_REQUIRED)
            val size = row.opt("byteSize")
            if (row.length() != 5 || row.opt("id") !is String ||
                row.opt("storageName") !is String || row.opt("format") !is String ||
                row.opt("sha256") !is String || size !is Number || size is Float || size is Double)
                throw BackupProblem(BackupFailure.RECOVERY_REQUIRED)
            val byteSize = size.toString().toLongOrNull()
                ?: throw BackupProblem(BackupFailure.RECOVERY_REQUIRED)
            val book = ArchiveBook(row.getString("id"), row.getString("storageName"),
                row.getString("format"), byteSize, row.getString("sha256"))
            books += book
        }
        try { BackupRecordsCodec.validateBooks(books) }
        catch (_: BackupProblem) { throw BackupProblem(BackupFailure.RECOVERY_REQUIRED) }
        val settingsObject = root.optJSONObject("previousSettings")
            ?: throw BackupProblem(BackupFailure.RECOVERY_REQUIRED)
        val previous = try { BackupRecordsCodec.decodeSettings(settingsObject) }
            catch (_: Exception) { throw BackupProblem(BackupFailure.RECOVERY_REQUIRED) }
        val covers = try {
            if (root.has("covers")) {
                val array = root.optJSONArray("covers") ?: throw BackupProblem(BackupFailure.RECOVERY_REQUIRED)
                if (array.length() > BackupRecordsCodec.MAX_BOOKS) throw BackupProblem(BackupFailure.RECOVERY_REQUIRED)
                (0 until array.length()).map {
                    BackupRecordsCodec.decodeCover(array.optJSONObject(it) ?: throw BackupProblem(BackupFailure.RECOVERY_REQUIRED))
                }.also { BackupRecordsCodec.validateCovers(it, books) }
            } else emptyList()
        } catch (_: Exception) { throw BackupProblem(BackupFailure.RECOVERY_REQUIRED) }
        return PendingRestore(books, covers, previous)
    }

    private fun emptyLibrary(): Boolean = database.openHelper.writableDatabase
        .query("SELECT EXISTS(SELECT 1 FROM books)").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0) == 0
        }

    private fun newStageDirectory(): File {
        if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs())
            throw IOException("Cannot create backup staging directory")
        val stage = File(stagingDirectory, "restore-${UUID.randomUUID()}")
        if (stage.canonicalFile.parentFile != stagingDirectory.canonicalFile || !stage.mkdir())
            throw IOException("Cannot create backup stage")
        return stage
    }

    private fun cleanStage(stage: File) {
        if (stage.canonicalFile.parentFile != stagingDirectory.canonicalFile) return
        stage.listFiles()?.forEach { child ->
            if (child.canonicalFile.parentFile == stage.canonicalFile && child.isFile) child.delete()
        }
        stage.delete()
    }

    private fun cleanAbandonedStages() {
        stagingDirectory.listFiles()?.forEach { stage ->
            if (stage.isDirectory && stage.name.startsWith("restore-") &&
                stage.canonicalFile.parentFile == stagingDirectory.canonicalFile) {
                stage.listFiles()?.forEach { child ->
                    if (child.canonicalFile.parentFile != stage.canonicalFile || !child.isFile ||
                        !child.delete()) throw IOException("Cannot clean abandoned backup stage")
                }
                if (!stage.delete()) throw IOException("Cannot clean abandoned backup stage")
            }
        }
    }

    private suspend fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            if (count == 0) continue
            if (output.size() > limit - count) throw BackupProblem(BackupFailure.TOO_LARGE)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun deleteBestEffort(uri: String): Boolean = try { documents.delete(uri) }
        catch (_: Exception) { false }

    private fun failure(error: Exception): BackupFailure = when (error) {
        is BackupProblem -> error.reason
        is ZipException, is EOFException -> BackupFailure.INVALID_ARCHIVE
        is SecurityException, is FileNotFoundException -> BackupFailure.ACCESS
        else -> BackupFailure.IO
    }

    private fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

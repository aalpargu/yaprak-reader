package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.library.BookStudyExporter
import com.yaprak.reader.domain.library.MAX_STUDY_EXPORT_BYTES
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.library.StudyExportResult
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DefaultBookStudyExporter(
    private val source: BookStudySource,
    private val documents: ExportDocumentProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fileMutex: Mutex = Mutex(),
    private val pdfRenderer: StudyPdfRenderer? = null,
) : BookStudyExporter {
    override suspend fun export(bookId: String, contentSha256: String, destinationUri: String): StudyExportResult =
        export(bookId, contentSha256, destinationUri, StudyExportFormat.TEXT)

    override suspend fun export(
        bookId: String,
        contentSha256: String,
        destinationUri: String,
        format: StudyExportFormat,
    ): StudyExportResult {
        // Validation is synchronous and precedes suspension, so cancelled IO dispatch can
        // clean an accepted SAF-created target without ever touching an unaccepted URI.
        val accepted = try { documents.accepts(destinationUri) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (!accepted) return StudyExportResult.Failed(StudyExportFailure.ACCESS)
        return try {
            withContext(io) {
                val snapshot = prepare(bookId, contentSha256, format)
                currentCoroutineContext().ensureActive()
                if (snapshot.bytes.size > MAX_STUDY_EXPORT_BYTES) throw StudyExportException(StudyExportFailure.TOO_LARGE)
                if (snapshot.noteCount == 0 && snapshot.wordCount == 0 && snapshot.passageCount == 0) {
                    throw StudyExportException(StudyExportFailure.NO_RECORDS)
                }
                if (snapshot.bytes.isEmpty() || snapshot.noteCount < 0 || snapshot.wordCount < 0 || snapshot.passageCount < 0) {
                    throw StudyExportException(StudyExportFailure.IO)
                }
                if (format == StudyExportFormat.ANKI_TSV &&
                    (snapshot.noteCount != 0 || snapshot.passageCount != 0 || snapshot.wordCount == 0)) {
                    throw StudyExportException(StudyExportFailure.IO)
                }
                // The transaction and shared file mutex finish before provider IO begins.
                val target = try { documents.open(destinationUri) }
                catch (_: FileNotFoundException) { throw StudyExportException(StudyExportFailure.ACCESS) }
                target.use { output ->
                    var offset = 0
                    while (offset < snapshot.bytes.size) {
                        currentCoroutineContext().ensureActive()
                        val size = minOf(64 * 1024, snapshot.bytes.size - offset)
                        output.write(snapshot.bytes, offset, size)
                        offset += size
                    }
                }
                currentCoroutineContext().ensureActive()
                StudyExportResult.Exported(snapshot.noteCount, snapshot.wordCount, snapshot.passageCount,
                    snapshot.ankiCardCount)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + io) { deleteBestEffort(destinationUri) }
            throw cancelled
        } catch (error: StudyExportException) {
            failed(destinationUri, error.reason)
        } catch (_: SecurityException) {
            failed(destinationUri, StudyExportFailure.ACCESS)
        } catch (_: Exception) {
            failed(destinationUri, StudyExportFailure.IO)
        }
    }

    /** Provider IO and PDF layout both run after the coherent database snapshot releases its lock. */
    private suspend fun prepare(bookId: String, contentSha256: String, format: StudyExportFormat): PreparedStudyExport {
        if (format == StudyExportFormat.OBSIDIAN_ZIP) {
            val records = fileMutex.withLock { source.snapshotRecords(bookId, contentSha256) }
            currentCoroutineContext().ensureActive()
            val bytes = renderObsidianBookStudyZip(bookId, contentSha256, records)
            return PreparedStudyExport(bytes, records.noteCount, records.wordCount, records.passageCount)
        }
        if (format == StudyExportFormat.NOTION_CSV) {
            val records = fileMutex.withLock { source.snapshotRecords(bookId, contentSha256) }
            currentCoroutineContext().ensureActive()
            val bytes = renderNotionStudyCsv(records)
            return PreparedStudyExport(bytes, records.noteCount, records.wordCount, records.passageCount)
        }
        if (format == StudyExportFormat.ANKI_STUDY_TSV) {
            val records = fileMutex.withLock { source.snapshotRecords(bookId, contentSha256) }
            currentCoroutineContext().ensureActive()
            val bytes = renderAnkiStudyCards(records)
            return PreparedStudyExport(bytes, records.noteCount, records.wordCount, records.passageCount,
                records.recordCount)
        }
        if (format == StudyExportFormat.PDF) {
            val renderer = pdfRenderer ?: throw StudyExportException(StudyExportFailure.UNSUPPORTED_FORMAT)
            val records = fileMutex.withLock { source.snapshotRecords(bookId, contentSha256) }
            currentCoroutineContext().ensureActive()
            validateStudyRecordsSnapshot(records)
            if (records.recordCount == 0) throw StudyExportException(StudyExportFailure.NO_RECORDS)
            val bytes = renderer.render(records)
            currentCoroutineContext().ensureActive()
            return PreparedStudyExport(bytes, records.noteCount, records.wordCount, records.passageCount)
        }
        val text = fileMutex.withLock { source.snapshot(bookId, contentSha256, format) }
        return PreparedStudyExport(text.utf8, text.noteCount, text.wordCount, text.passageCount)
    }

    private suspend fun failed(uri: String, reason: StudyExportFailure) = withContext(NonCancellable + io) {
        StudyExportResult.Failed(reason, partialFileMayRemain = !deleteBestEffort(uri))
    }

    private fun deleteBestEffort(uri: String): Boolean = try { documents.delete(uri) }
    catch (_: Exception) { false }
}

private data class PreparedStudyExport(val bytes: ByteArray, val noteCount: Int, val wordCount: Int,
    val passageCount: Int, val ankiCardCount: Int? = null)

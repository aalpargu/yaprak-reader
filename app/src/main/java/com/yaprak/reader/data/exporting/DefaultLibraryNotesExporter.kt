package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportResult
import com.yaprak.reader.domain.library.MAX_STUDY_EXPORT_BYTES
import com.yaprak.reader.domain.notes.*
import java.io.FileNotFoundException
import kotlinx.coroutines.*

/** Fresh coherent query capture ends before rendering or provider IO. No original book files are read. */
class DefaultLibraryNotesExporter(
    private val source: LibraryNotesExportSource,
    private val documents: ExportDocumentProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pdfRenderer: LibraryNotesPdfRenderer? = null,
) : LibraryNotesExporter {
    override suspend fun export(query: String, destinationUri: String, format: LibraryNotesExportFormat): StudyExportResult {
        val accepted = try { documents.accepts(destinationUri) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (!accepted) return StudyExportResult.Failed(StudyExportFailure.ACCESS)
        return try {
            withContext(io) {
                LibraryNotesExportBudget(query)
                val renderer = if (format == LibraryNotesExportFormat.PDF) pdfRenderer
                    ?: throw LibraryNotesExportException(StudyExportFailure.UNSUPPORTED_FORMAT) else null
                val snapshot = source.snapshot(query)
                currentCoroutineContext().ensureActive()
                if (snapshot.query != query) throw LibraryNotesExportException(StudyExportFailure.IO)
                validateLibraryNotesExportSnapshot(snapshot)
                val bytes = when (format) {
                    LibraryNotesExportFormat.PDF -> checkNotNull(renderer).render(snapshot)
                    LibraryNotesExportFormat.NOTION_CSV -> renderNotionLibraryNotesCsv(snapshot)
                    LibraryNotesExportFormat.ANKI_TSV -> renderAnkiLibraryNotes(snapshot)
                    LibraryNotesExportFormat.OBSIDIAN_ZIP -> renderObsidianLibraryNotesZip(snapshot)
                    LibraryNotesExportFormat.TEXT, LibraryNotesExportFormat.MARKDOWN -> renderLibraryNotesExport(snapshot, format)
                }
                currentCoroutineContext().ensureActive()
                if (bytes.size > MAX_STUDY_EXPORT_BYTES) throw LibraryNotesExportException(StudyExportFailure.TOO_LARGE)
                if (bytes.isEmpty() || format == LibraryNotesExportFormat.PDF &&
                    (bytes.size < 8 || !bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))))
                    throw LibraryNotesExportException(StudyExportFailure.IO)
                val target = try { documents.open(destinationUri) }
                catch (_: FileNotFoundException) { throw LibraryNotesExportException(StudyExportFailure.ACCESS) }
                target.use { output ->
                    var offset = 0
                    while (offset < bytes.size) {
                        currentCoroutineContext().ensureActive()
                        val length = minOf(64 * 1024, bytes.size - offset)
                        output.write(bytes, offset, length); offset += length
                    }
                }
                currentCoroutineContext().ensureActive()
                StudyExportResult.Exported(snapshot.entries.size, 0, 0)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + io) { delete(destinationUri) }; throw cancelled
        } catch (error: LibraryNotesExportException) { failed(destinationUri, error.reason) }
        catch (error: StudyExportException) { failed(destinationUri, error.reason) }
        catch (_: SecurityException) { failed(destinationUri, StudyExportFailure.ACCESS) }
        catch (_: Exception) { failed(destinationUri, StudyExportFailure.IO) }
    }
    private suspend fun failed(uri: String, reason: StudyExportFailure) = withContext(NonCancellable + io) {
        StudyExportResult.Failed(reason, partialFileMayRemain = !delete(uri))
    }
    private fun delete(uri: String): Boolean = try { documents.delete(uri) } catch (_: Exception) { false }
}

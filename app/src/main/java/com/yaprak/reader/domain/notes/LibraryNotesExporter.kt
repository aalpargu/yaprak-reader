package com.yaprak.reader.domain.notes

import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportResult
import java.util.Collections

enum class LibraryNotesExportFormat(val mimeType: String, val extension: String) {
    TEXT("text/plain", "txt"), MARKDOWN("text/markdown", "md"), PDF("application/pdf", "pdf"),
    NOTION_CSV("text/csv", "csv"),
    ANKI_TSV("text/plain", "txt"),
    OBSIDIAN_ZIP("application/zip", "zip")
}

const val MAX_LIBRARY_NOTES_EXPORT_RECORDS = 1000
const val MAX_LIBRARY_NOTES_EXPORT_SOURCE_BYTES = 1024 * 1024

/** Actual metadata of the same current-book joined row, not a page count inferred from an anchor. */
data class LibraryNoteExportEntry(val note: LibraryNote, val bookPageCount: Int?)

class LibraryNotesExportSnapshot(val query: String, entries: List<LibraryNoteExportEntry>) {
    init {
        if (entries.size > MAX_LIBRARY_NOTES_EXPORT_RECORDS)
            throw LibraryNotesExportException(StudyExportFailure.TOO_LARGE)
    }
    val entries: List<LibraryNoteExportEntry> = Collections.unmodifiableList(ArrayList(entries))
}

class LibraryNotesExportException(val reason: StudyExportFailure) : Exception(reason.name)

fun interface LibraryNotesExportSource {
    /** One current coherent read; never returns a truncated UI page. */
    suspend fun snapshot(query: String): LibraryNotesExportSnapshot
}

fun interface LibraryNotesExporter {
    suspend fun export(query: String, destinationUri: String, format: LibraryNotesExportFormat): StudyExportResult
}

package com.yaprak.reader.data.backup

import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.formats.fb2.Fb2MetadataReader
import com.yaprak.reader.formats.fb2.hasFb2ZipSignature
import com.yaprak.reader.formats.txt.StrictTxt
import com.yaprak.reader.formats.txt.TxtException
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Restore checks the original container against its archive version, before persistent writes. */
internal suspend fun validBackupBookSignature(file: File, format: String, originalName: String,
    archiveVersion: Int = BackupRecordsCodec.FORMAT_VERSION): Boolean {
    currentCoroutineContext().ensureActive()
    if (archiveVersion !in 1..BackupRecordsCodec.FORMAT_VERSION) return false
    val bytes = file.inputStream().use { input ->
        val header = ByteArray(8)
        var count = 0
        while (count < header.size) {
            val next = input.read(header, count, header.size - count)
            if (next == -1) break
            count += next
        }
        header.copyOf(count)
    }
    return when (format) {
        "PDF" -> bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
        "EPUB", "CBZ" -> hasFb2ZipSignature(bytes)
        "CBR" -> archiveVersion >= 26 && com.yaprak.reader.formats.cbr.hasCbrSignature(bytes)
        "FB2" -> if (archiveVersion < 22 || hasFb2ZipSignature(bytes) && archiveVersion < 23 ||
            hasFb2ZipSignature(bytes) != originalName.endsWith(".fb2.zip", ignoreCase = true)) false
            else try { Fb2MetadataReader().read(file); true } catch (_: BookImportException) { false }
        "TXT" -> try { StrictTxt.read(file); true } catch (_: TxtException) { false }
        "DOCX" -> if (archiveVersion < 25 || !originalName.endsWith(".docx", ignoreCase = true) || !hasFb2ZipSignature(bytes)) false
            else try { com.yaprak.reader.formats.docx.DocxMetadataReader().read(file); true } catch (_: BookImportException) { false }
        else -> false
    }
}

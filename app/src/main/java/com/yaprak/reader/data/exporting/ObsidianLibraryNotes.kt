package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.library.MAX_STUDY_EXPORT_BYTES
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.notes.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Detached coherent records only. No original file, account, vault configuration or automatic upload. */
internal suspend fun renderObsidianLibraryNotesZip(snapshot: LibraryNotesExportSnapshot): ByteArray {
    val context = currentCoroutineContext()
    context.ensureActive()
    validateLibraryNotesExportSnapshot(snapshot)
    val groups = snapshot.entries.groupBy { it.note.bookId }
    val files = groups.map { (id, entries) ->
        val first = entries.first()
        if (entries.any { it.note.contentSha256 != first.note.contentSha256 || it.note.bookTitle != first.note.bookTitle ||
                it.note.bookAuthors != first.note.bookAuthors || it.note.bookFormat != first.note.bookFormat ||
                it.bookPageCount != first.bookPageCount })
            throw LibraryNotesExportException(StudyExportFailure.IO)
        ObsidianBookFile("Books/${obsidianBookIdentity(id)}.md", entries)
    }.sortedBy { it.path }
    if (files.map { it.path }.toSet().size != files.size) throw LibraryNotesExportException(StudyExportFailure.IO)
    val output = ObsidianZipBytes(context)
    var uncompressed = 0
    ZipOutputStream(output, Charsets.UTF_8).use { zip ->
        fun entry(path: String, bytes: ByteArray) {
            context.ensureActive()
            if (bytes.size > MAX_STUDY_EXPORT_BYTES - uncompressed)
                throw LibraryNotesExportException(StudyExportFailure.TOO_LARGE)
            uncompressed += bytes.size
            zip.putNextEntry(ZipEntry(path).apply { time = 0L })
            var offset = 0
            while (offset < bytes.size) {
                context.ensureActive()
                val length = minOf(32 * 1024, bytes.size - offset)
                zip.write(bytes, offset, length); offset += length
            }
            zip.closeEntry()
        }
        entry("Yaprak.md", obsidianLibraryIndex(snapshot, files))
        for (file in files) {
            context.ensureActive()
            val first = file.entries.first().note
            val header = BoundedStudyText().apply {
                append("---\n", "title: ", obsidianYamlScalar(first.bookTitle), "\n",
                    "aliases: [", obsidianYamlScalar(first.bookTitle), "]\n",
                    "authors: ", obsidianYamlScalar(first.bookAuthors ?: ""), "\n",
                    "book_format: ", obsidianYamlScalar(first.bookFormat.name), "\n",
                    "yaprak_book_id_sha256: ", obsidianYamlScalar(obsidianBookIdentity(first.bookId)), "\n",
                    "yaprak_content_sha256: ", obsidianYamlScalar(first.contentSha256), "\n",
                    "record_count: ", file.entries.size.toString(), "\n---\n\n")
            }.bytes()
            val body = renderLibraryNotesExport(LibraryNotesExportSnapshot(snapshot.query, file.entries),
                LibraryNotesExportFormat.MARKDOWN)
            if (body.size > MAX_STUDY_EXPORT_BYTES - header.size) throw LibraryNotesExportException(StudyExportFailure.TOO_LARGE)
            entry(file.path, header + body)
        }
    }
    context.ensureActive()
    return output.bytes()
}

private data class ObsidianBookFile(val path: String, val entries: List<LibraryNoteExportEntry>)
internal fun obsidianBookIdentity(id: String): String = MessageDigest.getInstance("SHA-256")
    .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

/** JSON-compatible double-quoted YAML scalars; embedded lines cannot create properties or tags. */
internal fun obsidianYamlScalar(text: String): String = buildString {
    append('"')
    for (char in text) when (char) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (char.isISOControl() || char == '\u2028' || char == '\u2029')
            append("\\u", char.code.toString(16).padStart(4, '0')) else append(char)
    }
    append('"')
}

private fun obsidianLibraryIndex(snapshot: LibraryNotesExportSnapshot, files: List<ObsidianBookFile>): ByteArray {
    val output = BoundedStudyText()
    output.append("# Yaprak · Obsidian\n\n",
        "Bu paket, seçilen kapsamın kayıtlarını kitap başına Markdown dosyasına ayırır. ",
        "ZIP dosyasını Obsidian Importer içinde Markdown (.md, .zip) olarak içe aktarabilir ",
        "veya paketi açıp Markdown dosyalarını kasana kopyalayabilirsin. ",
        "Kitapların özgün dosyaları ve kasa ayarları bu pakete eklenmez.\n\n",
        "Kitap: ", files.size.toString(), " · Kayıt: ", snapshot.entries.size.toString(), "\n\n",
        "Arama kapsamı:\n")
    val query = snapshot.query.ifEmpty { "Tüm notlar" }
    var longest = 0; var run = 0
    for (char in query) { run = if (char == '`') run + 1 else 0; longest = maxOf(longest, run) }
    val fence = "`".repeat(maxOf(3, longest + 1))
    output.append(fence, "\n", query, "\n", fence, "\n\n", "## Kitaplar\n\n")
    for (file in files) {
        val label = obsidianMarkdownLinkLabel(file.entries.first().note.bookTitle)
        output.append("- [", label, "](", file.path, ") · ", file.entries.size.toString(), " kayıt\n")
    }
    return output.bytes()
}

/** Both the complete expanded Markdown and actual compressed container retain an8MiB budget. */
internal class ObsidianZipBytes(private val context: CoroutineContext) : OutputStream() {
    private val output = ByteArrayOutputStream()
    override fun write(value: Int) {
        context.ensureActive()
        if (output.size() == MAX_STUDY_EXPORT_BYTES) throw LibraryNotesExportException(StudyExportFailure.TOO_LARGE)
        output.write(value)
    }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        context.ensureActive()
        if (length > MAX_STUDY_EXPORT_BYTES - output.size()) throw LibraryNotesExportException(StudyExportFailure.TOO_LARGE)
        output.write(buffer, offset, length)
    }
    fun bytes(): ByteArray = output.toByteArray()
}

internal fun obsidianMarkdownLinkLabel(text: String): String {
    val title = text.map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }
        .joinToString("").replace(Regex(" +"), " ").trim().ifEmpty { "Başlıksız kitap" }
    return buildString {
        for (char in title) {
            if (char in "\\`*_{}[]()#+-.!|<>~") append('\\')
            append(char)
        }
    }
}

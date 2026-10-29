package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.library.MAX_STUDY_EXPORT_BYTES
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.notes.LibraryNotesExportException
import com.yaprak.reader.domain.passages.isWellFormedEpubAnnotationText
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Actual typed saved records; a TXT export is never parsed or substituted for word/quote fields. */
internal suspend fun renderObsidianBookStudyZip(bookId: String, hash: String, records: StudyRecordsSnapshot): ByteArray {
    val context = currentCoroutineContext()
    context.ensureActive()
    if (bookId.length !in 1..1000 || bookId.isBlank() || '\u0000' in bookId ||
        !isWellFormedEpubAnnotationText(bookId) || !STUDY_SOURCE_HASH.matches(hash))
        throw StudyExportException(StudyExportFailure.IO)
    validateStudyRecordsSnapshot(records)
    val identity = obsidianBookIdentity(bookId)
    val path = "Books/$identity.md"
    val writer = studyExportTextWriter(StudyExportFormat.MARKDOWN, records.book.title, records.book.authors, records.book.format.name)
    records.notes.forEachIndexed { index, note -> context.ensureActive(); writer.note(index + 1, note.text, note.pdfPageIndex?.toLong()) }
    writer.endNotes(records.notes.size)
    records.words.forEachIndexed { index, word -> context.ensureActive(); writer.word(index + 1, word.english, word.turkish) }
    writer.endWords(records.words.size)
    records.passages.forEachIndexed { index, passage ->
        context.ensureActive()
        writer.passage(index + 1, passage.text, passage.anchor.resourceHref, passage.highlightColor, passage.attachedNote)
    }
    writer.endPassages(records.passages.size)
    records.pdfAnnotations.forEachIndexed { index, annotation ->
        context.ensureActive()
        writer.pdfAnnotation(records.passages.size + index + 1, annotation.text, annotation.anchor.pageIndex,
            annotation.highlightColor, annotation.attachedNote)
    }
    val properties = BoundedStudyText().apply {
        append("---\ntitle: ", obsidianYamlScalar(records.book.title), "\naliases: [", obsidianYamlScalar(records.book.title),
            "]\nauthors: ", obsidianYamlScalar(records.book.authors ?: ""), "\nbook_format: ", obsidianYamlScalar(records.book.format.name),
            "\nyaprak_book_id_sha256: ", obsidianYamlScalar(identity), "\nyaprak_content_sha256: ", obsidianYamlScalar(hash),
            "\nrecord_count: ", records.recordCount.toString(), "\n---\n\n")
    }.bytes()
    val body = writer.bytes()
    val index = BoundedStudyText().apply {
        append("# Yaprak · Kitap çalışma kayıtları\n\n[", obsidianMarkdownLinkLabel(records.book.title), "](", path, ")\n\n",
            "Not: ", records.noteCount.toString(), " · Kelime: ", records.wordCount.toString(),
            " · Alıntı/vurgu: ", records.passageCount.toString(), "\n\n",
            "Bu paket, yalnız bu kitabın kayıtlı çalışma verilerinin anlık kopyasıdır. ",
            "ZIP dosyasını Obsidian Importer içinde Markdown (.md, .zip) olarak içe aktarabilir veya açıp kasana kopyalayabilirsin.\n")
    }.bytes()
    if (properties.size.toLong() + body.size + index.size > MAX_STUDY_EXPORT_BYTES)
        throw StudyExportException(StudyExportFailure.TOO_LARGE)
    val output = ObsidianZipBytes(context)
    try {
        ZipOutputStream(output, Charsets.UTF_8).use { zip ->
            for ((name, bytes) in listOf("Yaprak.md" to index, path to (properties + body))) {
                context.ensureActive()
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                var offset = 0
                while (offset < bytes.size) {
                    context.ensureActive()
                    val length = minOf(32 * 1024, bytes.size - offset)
                    zip.write(bytes, offset, length); offset += length
                }
                zip.closeEntry()
            }
        }
    } catch (failure: LibraryNotesExportException) { throw StudyExportException(failure.reason) }
    context.ensureActive()
    return output.bytes()
}

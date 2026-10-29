package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.notes.*
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.reader.SourceTextAnchor
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ObsidianLibraryNotesTest {
    private fun entry(id: String = "note", bookId: String = "book", title: String = "İstanbul 🌿", text: String = "  Tam not\n雪  ") =
        LibraryNoteExportEntry(LibraryNote(id, bookId, "a".repeat(64), title, "Deniz", BookFormat.TXT, text, 1), null)
    private fun entries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream(), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse(entry.isDirectory); assertFalse(containsKey(entry.name))
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8)); zip.closeEntry()
            }
        }
    }

    @Test fun sameNamedBooksRemainSeparateWithCompleteLiteralRecordsAndValidRelativeIndexLinks() = runBlocking {
        val notes = listOf(entry("first", "one"), entry("second", "one", text = "İkinci not"), entry("third", "two"))
        val files = entries(renderObsidianLibraryNotesZip(LibraryNotesExportSnapshot("", notes)))
        assertEquals(3, files.size)
        val books = files.filterKeys { it.startsWith("Books/") }
        assertTrue(books.keys.all { Regex("Books/[0-9a-f]{64}\\.md").matches(it) })
        assertEquals(2, books.size)
        assertTrue(books.values.single { it.contains("İkinci not") }.contains("  Tam not\n雪  "))
        val index = requireNotNull(files["Yaprak.md"])
        for (path in books.keys) assertTrue(index.contains("]($path)"))
        assertTrue(index.contains("Kitap: 2 · Kayıt: 3"))
        assertEquals(3, books.values.sumOf { Regex("(?m)^## Not ").findAll(it).count() })
        assertFalse(files.keys.any { it.contains("İstanbul") || it.contains("one") || it.contains("two") })
    }

    @Test fun filenamesStayStableAcrossMetadataRenameAndRecordOrdering() = runBlocking {
        val original = listOf(entry("1", "b1"), entry("2", "b2"))
        val first = entries(renderObsidianLibraryNotesZip(LibraryNotesExportSnapshot("", original))).keys
        val renamed = original.reversed().map { it.copy(note = it.note.copy(bookTitle = "Yeni başlık")) }
        assertEquals(first, entries(renderObsidianLibraryNotesZip(LibraryNotesExportSnapshot("", renamed))).keys)
    }

    @Test fun maliciousNamesCannotCreatePathsPropertiesOrActiveMarkdownEmbeds() = runBlocking<Unit> {
        val payload = "../CON: [link](file:///x) ![[embed]]\n---\ntags: [injected]\n\"🌿\""
        val note = entry(bookId = "../../outside", title = payload, text = "```\n![[private]]\n<script>x</script>")
        val archive = renderObsidianLibraryNotesZip(LibraryNotesExportSnapshot("![[query]]", listOf(note)))
        val files = entries(archive)
        val book = files.entries.single { it.key.startsWith("Books/") }.value
        assertFalse(files.keys.any { ".." in it || ':' in it || '\\' in it })
        val header = book.substringBefore("\n---\n")
        assertFalse(header.lineSequence().any { it.startsWith("tags:") })
        val encoded = header.lineSequence().single { it.startsWith("title: ") }.removePrefix("title: ")
        assertEquals(payload, JSONObject("{\"value\":$encoded}").getString("value"))
        assertTrue(book.contains("````\n```\n![[private]]\n<script>x</script>\n````"))
        assertFalse(requireNotNull(files["Yaprak.md"]).contains("[link](file:///x)"))
        System.getProperty("yaprak.screenshotDir")?.let { screenshots ->
            java.io.File(java.io.File(screenshots).parentFile, "fixtures/obsidian-0.157.0-sample.zip")
                .apply { parentFile!!.mkdirs(); writeBytes(archive) }
        }
    }

    @Test fun yamlScalarPreservesQuotesBackslashesUnicodeAndControlCharacters() {
        for (text in listOf("", "null", "true", "123", "a: [b]", "\"\\\n\r\t\b\u0085\u2028🌿雪")) {
            val encoded = obsidianYamlScalar(text)
            assertEquals(text, JSONObject("{\"value\":$encoded}").getString("value"))
            assertFalse(encoded.contains('\n')); assertFalse(encoded.contains('\r'))
        }
    }
    @Test fun mixedBookNotesAndOriginalEpubPdfQuotesKeepTheirActualSourceMetadata() = runBlocking {
        val quote = "Özgün 🌿 alıntı"
        val epub = entry("epub", "e").copy(note = entry("epub", "e").note.copy(bookFormat = BookFormat.EPUB,
            source = LibraryNoteSource.EPUB_PASSAGE, sourceText = quote,
            sourceAnchor = SourceTextAnchor("OPS/part.xhtml", 0, quote.length, "b".repeat(64)), highlightColor = EpubHighlightColor.PINK))
        val pdf = entry("pdf", "p").copy(bookPageCount = 10, note = entry("pdf", "p").note.copy(bookFormat = BookFormat.PDF,
            source = LibraryNoteSource.PDF_ANNOTATION, sourceText = quote, pdfPageIndex = 3,
            pdfHighlightColor = com.yaprak.reader.domain.pdfannotations.PdfHighlightColor.BLUE,
            pdfSourceAnchor = com.yaprak.reader.reader.PdfSourceAnchor(3, 200, 500,
                com.yaprak.reader.reader.PdfPagePoint(10, 20), com.yaprak.reader.reader.PdfPagePoint(60, 20),
                listOf(com.yaprak.reader.reader.PdfTextBounds(10.0, 10.0, 60.0, 30.0)))))
        val files = entries(renderObsidianLibraryNotesZip(LibraryNotesExportSnapshot("quote", listOf(entry(), epub, pdf))))
        assertEquals(4, files.size)
        val bodies = files.filterKeys { it != "Yaprak.md" }.values
        assertEquals(2, bodies.count { quote in it })
        assertTrue(bodies.any { "OPS/part.xhtml" in it && "Pembe" in it })
        assertTrue(bodies.any { "PDF sayfası:\n```\n4\n```" in it && "Mavi" in it })
    }

    @Test fun conflictingMetadataOrContentForOneBookFailsWithoutPublishingAnAmbiguousFile() = runBlocking {
        val first = entry("one")
        val second = entry("two")
        for (changed in listOf(second.note.copy(bookTitle = "Changed"), second.note.copy(bookAuthors = null),
            second.note.copy(contentSha256 = "b".repeat(64)), second.note.copy(bookFormat = BookFormat.FB2))) {
            val error = runCatching { renderObsidianLibraryNotesZip(LibraryNotesExportSnapshot("", listOf(first, second.copy(note = changed)))) }.exceptionOrNull()
            assertEquals(StudyExportFailure.IO, (error as? LibraryNotesExportException)?.reason)
        }
    }

    @Test fun exactThousandRecordsAreExportedBeyondTheTwoHundredRowUiWindow() = runBlocking {
        val notes = List(1000) { entry("record-$it", text = "saved-$it") }
        val files = entries(renderObsidianLibraryNotesZip(LibraryNotesExportSnapshot("", notes)))
        assertEquals(2, files.size)
        val body = files.values.single { "record_count: 1000" in it }
        assertTrue(body.contains("## Not 1000\n")); assertTrue(body.contains("saved-999"))
        assertEquals(1000, Regex("(?m)^## Not ").findAll(body).count())
    }

    @Test fun oneThousandBooksHaveOneThousandSafeFilesAndEveryIndexLinkResolves() = runBlocking {
        val notes = List(1000) { entry("record-$it", bookId = "book-$it") }
        val files = entries(renderObsidianLibraryNotesZip(LibraryNotesExportSnapshot("", notes)))
        assertEquals(1001, files.size)
        val index = files.getValue("Yaprak.md")
        val targets = Regex("\\]\\((Books/[0-9a-f]{64}\\.md)\\)").findAll(index).map { it.groupValues[1] }.toList()
        assertEquals(1000, targets.size); assertEquals(files.keys - "Yaprak.md", targets.toSet())
    }

    @Test fun emptyDuplicateMalformedAndOversizedSnapshotsNeverBecomeSuccessArchives() = runBlocking {
        for ((snapshot, reason) in listOf(
            LibraryNotesExportSnapshot("", emptyList()) to StudyExportFailure.NO_RECORDS,
            LibraryNotesExportSnapshot("", listOf(entry(), entry())) to StudyExportFailure.IO,
            LibraryNotesExportSnapshot("", listOf(entry(title = "bad\uD800"))) to StudyExportFailure.IO,
            LibraryNotesExportSnapshot("", List(600) { entry("large-$it", text = "雪".repeat(1000)) }) to StudyExportFailure.TOO_LARGE,
        )) {
            val error = runCatching { renderObsidianLibraryNotesZip(snapshot) }.exceptionOrNull()
            assertEquals(error.toString(), reason, when (error) {
                is LibraryNotesExportException -> error.reason
                is StudyExportException -> error.reason
                else -> null
            })
        }
    }
    private class Documents : ExportDocumentProvider {
        val bytes = ByteArrayOutputStream()
        var opens = 0; var deletes = 0; var closed = false; var deletesConfirmed = true
        var onWrite: () -> Unit = {}
        override fun accepts(uri: String) = uri == "content://owned/notes"
        override fun open(uri: String): OutputStream {
            opens++
            return object : OutputStream() {
                override fun write(value: Int) { bytes.write(value); onWrite() }
                override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes.write(buffer, offset, length); onWrite() }
                override fun close() { closed = true }
            }
        }
        override fun delete(uri: String): Boolean { deletes++; return deletesConfirmed }
    }

    @Test fun actualExporterUsesOneFreshExactQueryAndWritesACompleteArchiveToItsCreatedTarget() = runBlocking {
        val docs = Documents(); val calls = mutableListOf<String>()
        val exporter = DefaultLibraryNotesExporter(LibraryNotesExportSource { query ->
            calls += query; LibraryNotesExportSnapshot(query, listOf(entry(text = "Fresh matching note")))
        }, docs, Dispatchers.Unconfined)
        assertEquals(StudyExportResult.Exported(1, 0, 0), exporter.export("Fresh", "content://owned/notes", LibraryNotesExportFormat.OBSIDIAN_ZIP))
        assertEquals(listOf("Fresh"), calls); assertEquals(1, docs.opens); assertEquals(0, docs.deletes); assertTrue(docs.closed)
        val files = entries(docs.bytes.toByteArray())
        assertTrue(files.values.any { "Fresh matching note" in it }); assertEquals(2, files.size)
        assertEquals("application/zip", LibraryNotesExportFormat.OBSIDIAN_ZIP.mimeType)
    }

    @Test fun wrongQueryAndRejectedUriCannotOpenOrWriteTheProvider() = runBlocking {
        val docs = Documents(); var snapshots = 0
        val exporter = DefaultLibraryNotesExporter(LibraryNotesExportSource {
            snapshots++; LibraryNotesExportSnapshot("other", listOf(entry()))
        }, docs, Dispatchers.Unconfined)
        assertEquals(StudyExportResult.Failed(StudyExportFailure.ACCESS), exporter.export("q", "file:///unowned", LibraryNotesExportFormat.OBSIDIAN_ZIP))
        assertEquals(0, snapshots); assertEquals(0, docs.deletes)
        assertEquals(StudyExportResult.Failed(StudyExportFailure.IO), exporter.export("q", "content://owned/notes", LibraryNotesExportFormat.OBSIDIAN_ZIP))
        assertEquals(0, docs.opens); assertEquals(1, docs.deletes); assertEquals(0, docs.bytes.size())
    }

    @Test fun failedArchiveWriteClosesAndReportsWhetherItsPartialTargetWasRemoved() = runBlocking {
        for (confirmed in listOf(true, false)) {
            val docs = Documents().apply { deletesConfirmed = confirmed; onWrite = { throw java.io.IOException("Write failed") } }
            val exporter = DefaultLibraryNotesExporter(LibraryNotesExportSource { query -> LibraryNotesExportSnapshot(query, listOf(entry())) }, docs, Dispatchers.Unconfined)
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO, partialFileMayRemain = !confirmed),
                exporter.export("", "content://owned/notes", LibraryNotesExportFormat.OBSIDIAN_ZIP))
            assertEquals(1, docs.deletes); assertTrue(docs.closed); assertTrue(docs.bytes.size() > 0)
        }
    }

    @Test fun cancellationAfterWritingClosesDeletesAndPropagatesFromTheExporter() = runTest {
        val docs = Documents(); var thrown: Throwable? = null; var success: StudyExportResult? = null
        val exporter = DefaultLibraryNotesExporter(LibraryNotesExportSource { query -> LibraryNotesExportSnapshot(query, listOf(entry())) }, docs, StandardTestDispatcher(testScheduler))
        val operation = launch {
            try { success = exporter.export("", "content://owned/notes", LibraryNotesExportFormat.OBSIDIAN_ZIP) }
            catch (failure: Throwable) { thrown = failure }
        }
        docs.onWrite = { operation.cancel() }; runCurrent(); operation.join()
        assertTrue(thrown is CancellationException); assertNull(success)
        assertTrue(docs.closed); assertEquals(1, docs.deletes); assertTrue(docs.bytes.size() > 0)
    }

    @Test fun alreadyCancelledEncoderDoesNotReplaceCancellationWithAnEmptySnapshotError() = runTest {
        var thrown: Throwable? = null
        val operation = launch {
            currentCoroutineContext().cancel()
            try { renderObsidianLibraryNotesZip(LibraryNotesExportSnapshot("", emptyList())) }
            catch (failure: Throwable) { thrown = failure }
        }
        runCurrent(); operation.join(); assertTrue(thrown is CancellationException)
    }
}

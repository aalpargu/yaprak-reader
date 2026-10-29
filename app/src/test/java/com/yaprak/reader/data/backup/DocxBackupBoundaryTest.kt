package com.yaprak.reader.data.backup

import android.app.Application
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.formats.docx.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocxBackupBoundaryTest {
    @get:Rule val temp = TemporaryFolder()
    private val id = UUID.randomUUID().toString()
    private fun original() = requireNotNull(javaClass.getResourceAsStream("/docx/python-docx-numbered.docx")).use { it.readBytes() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val tableNames = listOf("books","reading_progress","observed_text","bookmarks",
        "book_epub_theme_overrides","book_epub_font_size_overrides","book_epub_font_overrides",
        "book_epub_text_alignment_overrides","book_epub_reading_mode_overrides","book_cbz_view_preferences",
        "book_epub_margin_overrides","book_epub_line_spacing_overrides","book_epub_spacing_overrides",
        "saved_words","book_notes","epub_passages","book_organizations","book_completion_events","book_covers",
        "reading_duration_receipts","book_pdf_view_preferences","book_pdf_navigation_preferences","epub_range_passages",
        "pdf_annotations","word_review_events","word_schedules")
    private fun records(): BackupRecords {
        val bytes = original(); val sha = hash(bytes)
        val book = ArchiveBook(id, "$id.docx", "DOCX", bytes.size.toLong(), sha)
        val rows = tableNames.associateWith { emptyList<JSONObject>() }.toMutableMap()
        rows["books"] = listOf(JSONObject().put("id",id).put("title","Owned DOCX").put("authors",JSONObject.NULL)
            .put("format","DOCX").put("originalName","source.docx").put("storageName",book.storageName)
            .put("byteSize",book.byteSize).put("sha256",sha).put("importedAt",1).put("pageCount",JSONObject.NULL))
        rows["book_epub_text_alignment_overrides"] = listOf(JSONObject().put("bookId",id).put("contentSha256",sha).put("alignment","JUSTIFY"))
        rows["book_epub_reading_mode_overrides"] = listOf(JSONObject().put("bookId",id).put("contentSha256",sha).put("mode","PAGED"))
        rows["book_epub_spacing_overrides"] = listOf(JSONObject().put("bookId",id).put("contentSha256",sha)
            .put("paragraphMode","CUSTOM").put("paragraphSpacingPercent",150).put("wordMode","PUBLISHER").put("wordSpacingPercent",JSONObject.NULL))
        return BackupRecords(listOf(book), EpubSettings(), rows)
    }

    @Test fun currentDocxManifestAndRecordsPreserveOriginalIdentityAndReflowableAppearance() {
        val records = records(); val encoded = BackupRecordsCodec.encode(records)
        val decoded = BackupRecordsCodec.decode(encoded,25)
        assertEquals(records.books,decoded.books)
        assertEquals("DOCX",decoded.rows.getValue("books").single().getString("format"))
        assertEquals("JUSTIFY",decoded.rows.getValue("book_epub_text_alignment_overrides").single().getString("alignment"))
        assertEquals("PAGED",decoded.rows.getValue("book_epub_reading_mode_overrides").single().getString("mode"))
        val manifest = BackupRecordsCodec.encodeManifest(records,encoded,hash(encoded))
        assertEquals(26,BackupRecordsCodec.decodeManifest(manifest).formatVersion)
        assertEquals(26,JSONObject(manifest.toString(Charsets.UTF_8)).getInt("databaseVersion"))
    }

    @Test fun everyOlderHeaderRejectsDocxBooksInBothRecordsAndManifestBeforeRestoreMutation() {
        val records = records(); val encoded = BackupRecordsCodec.encode(records)
        val manifest = JSONObject(BackupRecordsCodec.encodeManifest(records,encoded,hash(encoded)).toString(Charsets.UTF_8))
        for(version in 1..24) {
            val failure = runCatching { BackupRecordsCodec.decode(encoded,version) }.exceptionOrNull()
            assertTrue("DOCX records under old header$version",failure is BackupProblem)
            manifest.put("formatVersion",version)
            val problem = runCatching { BackupRecordsCodec.decodeManifest(manifest.toString().toByteArray()) }.exceptionOrNull()
            assertTrue(problem is BackupProblem)
        }
    }

    @Test fun fullOriginalSourceValidationRequiresHeaderTwentyFiveAndMatchingDocxName() = runBlocking {
        val bytes = original(); val file = temp.newFile().apply { writeBytes(bytes) }
        assertTrue(validBackupBookSignature(file,"DOCX","SOURCE.DOCX",25))
        for(version in 1..24) assertFalse(validBackupBookSignature(file,"DOCX","source.docx",version))
        assertFalse(validBackupBookSignature(file,"DOCX","source.zip",25))
        assertFalse(validBackupBookSignature(file,"DOCX","source.docx.zip",25))
        assertTrue(validBackupBookSignature(file,"DOCX","source.docx",26))
        assertFalse(validBackupBookSignature(file,"DOCX","source.docx",27))
        assertArrayEquals(bytes,file.readBytes())
        assertEquals(listOf(file.name),file.parentFile!!.listFiles()!!.map { it.name })
    }

    @Test fun zipMagicCannotAuthorizeCorruptionOtherOfficeFormatsOrUnrenderableContent() = runBlocking {
        val file = temp.newFile()
        val unsupported = DocxFixtures.zip(DocxFixtures.parts(body = DocxFixtures.word("<w:p><w:r><w:t>text</w:t><w:drawing/></w:r></w:p>")))
        val wrongType = DocxFixtures.parts().toMutableMap().apply { this["[Content_Types].xml"] = getValue("[Content_Types].xml").replace(DOCX_MAIN_TYPE,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml") }
        for(bytes in listOf(byteArrayOf(0x50,0x4b,3,4,0), original()+byteArrayOf(0),unsupported,DocxFixtures.zip(wrongType))) {
            file.writeBytes(bytes); assertFalse(validBackupBookSignature(file,"DOCX","source.docx",25))
        }
    }

    @Test fun archiveBudgetAndWrongStorageIdentityAreRejected() {
        val source=records()
        val oversized=source.books.single().copy(byteSize=MAX_DOCX_PACKAGE_BYTES.toLong()+1)
        val failure=runCatching { BackupRecordsCodec.validateBooks(listOf(oversized),25) }.exceptionOrNull()
        assertEquals(BackupFailure.TOO_LARGE,(failure as BackupProblem).reason)
        val wrong=source.books.single().copy(storageName="$id.epub")
        assertEquals(BackupFailure.INVALID_ARCHIVE,(runCatching { BackupRecordsCodec.validateBooks(listOf(wrong),25) }.exceptionOrNull() as BackupProblem).reason)
    }

    @Test fun docxDoesNotAcquireOriginalEpubObservedTextOrSourceRangeAuthorityInBackupRecords() {
        val base=BackupRecordsCodec.encode(records())
        val json=JSONObject(base.toString(Charsets.UTF_8)); val tables=json.getJSONObject("tables")
        tables.getJSONArray("observed_text").put(JSONObject().put("bookId",id).put("contentSha256",hash(original()))
            .put("resourceHref","Text/document.xhtml").put("nodePath","0")
            .put("startOffset",0).put("endOffset",3).put("textSha256","a".repeat(64)).put("extractionVersion",1))
        val originalBook=tables.getJSONArray("books").getJSONObject(0)
        originalBook.put("format","EPUB").put("storageName","$id.epub").put("originalName","source.epub")
        assertEquals(1,BackupRecordsCodec.decode(json.toString().toByteArray(),25).rows.getValue("observed_text").size)
        originalBook.put("format","DOCX").put("storageName","$id.docx").put("originalName","source.docx")
        val problem=runCatching { BackupRecordsCodec.decode(json.toString().toByteArray(),25) }.exceptionOrNull()
        assertEquals(BackupFailure.INVALID_ARCHIVE,(problem as BackupProblem).reason)
    }

    @Test fun cancelledSourceValidationPropagatesCancellationWithoutWritingAValidationArtifact() = runBlocking {
        val file=temp.newFile().apply { writeBytes(original()) }; var preserved=false
        val child=launch(start=CoroutineStart.UNDISPATCHED) { cancel(); try { validBackupBookSignature(file,"DOCX","book.docx",25) }
            catch (_: CancellationException) { preserved=true } }
        child.join(); assertTrue(preserved); assertEquals(listOf(file.name),file.parentFile!!.listFiles()!!.map { it.name })
    }
}

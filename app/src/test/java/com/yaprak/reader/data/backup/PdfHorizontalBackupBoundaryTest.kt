package com.yaprak.reader.data.backup

import android.app.Application
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.settings.EpubSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfHorizontalBackupBoundaryTest {
    private val id = "e418b59e-fbf1-44cc-9275-2ef802689e04"
    private val hash = "a".repeat(64)
    // An owned complete archive fixture, independent of a Room/native SQLite runtime.
    private val tableNames = listOf("books", "reading_progress", "observed_text", "bookmarks",
        "book_epub_theme_overrides", "book_epub_font_size_overrides", "book_epub_font_overrides",
        "book_epub_text_alignment_overrides", "book_epub_reading_mode_overrides", "book_cbz_view_preferences",
        "book_epub_margin_overrides", "book_epub_line_spacing_overrides", "book_epub_spacing_overrides",
        "saved_words", "book_notes", "epub_passages", "book_organizations", "book_completion_events",
        "book_covers", "reading_duration_receipts", "book_pdf_view_preferences", "book_pdf_navigation_preferences",
        "epub_range_passages", "pdf_annotations", "word_review_events", "word_schedules")
    private fun records(mode: Any = "HORIZONTAL_SCROLL"): BackupRecords {
        val book = ArchiveBook(id, "$id.pdf", "PDF", 10, hash)
        val rows = tableNames.associateWith { emptyList<JSONObject>() }.toMutableMap()
        rows["books"] = listOf(JSONObject().put("id", id).put("title", "Owned PDF").put("authors", JSONObject.NULL)
            .put("format", "PDF").put("originalName", "owned.pdf").put("storageName", "$id.pdf")
            .put("byteSize", 10).put("sha256", hash).put("importedAt", 1).put("pageCount", 3))
        rows["book_pdf_view_preferences"] = listOf(JSONObject().put("bookId", id).put("contentSha256", hash)
            .put("horizontalPercent", 8).put("verticalPercent", 12).put("fitMode", "WIDTH").put("doublePagePreferred", 1))
        rows["book_pdf_navigation_preferences"] = listOf(JSONObject().put("bookId", id).put("contentSha256", hash).put("mode", mode))
        return BackupRecords(listOf(book), EpubSettings(), rows)
    }
    @Test fun currentHeaderAndRecordsRoundTripExactHorizontalModeAndUnrelatedFields() {
        val records = records()
        val bytes = BackupRecordsCodec.encode(records)
        val decoded = BackupRecordsCodec.decode(bytes, 24)
        assertEquals("HORIZONTAL_SCROLL", decoded.rows.getValue("book_pdf_navigation_preferences").single().getString("mode"))
        val view = decoded.rows.getValue("book_pdf_view_preferences").single()
        assertEquals(8, view.getInt("horizontalPercent")); assertEquals(12, view.getInt("verticalPercent"))
        assertEquals("WIDTH", view.getString("fitMode")); assertEquals(1, view.getInt("doublePagePreferred"))
        val manifest = BackupRecordsCodec.encodeManifest(records, bytes, hash)
        assertEquals(26, BackupRecordsCodec.decodeManifest(manifest).formatVersion)
        assertEquals(26, JSONObject(manifest.toString(Charsets.UTF_8)).getInt("databaseVersion"))
    }
    @Test fun horizontalRecordsUnderEveryPreviouslyCapableHeaderFailRatherThanSilentlyChangingMode() {
        val bytes = BackupRecordsCodec.encode(records())
        for (version in 21..23) {
            val problem = runCatching { BackupRecordsCodec.decode(bytes, version) }.exceptionOrNull()
            assertEquals(BackupFailure.INVALID_ARCHIVE, (problem as BackupProblem).reason)
        }
    }
    @Test fun oldPagedAndVerticalRecordsRemainValidAndUnknownOrNonTextModesFail() {
        for (mode in listOf("PAGED", "VERTICAL_SCROLL")) for (version in 21..24) {
            val decoded = BackupRecordsCodec.decode(BackupRecordsCodec.encode(records(mode)), version)
            assertEquals(mode, decoded.rows.getValue("book_pdf_navigation_preferences").single().getString("mode"))
        }
        for (mode in listOf<Any>("horizontal", "", "SIDEWAYS", 1, JSONObject.NULL)) {
            val problem = runCatching { BackupRecordsCodec.decode(BackupRecordsCodec.encode(records(mode)), 24) }.exceptionOrNull()
            assertEquals(BackupFailure.INVALID_ARCHIVE, (problem as BackupProblem).reason)
        }
    }
    @Test fun horizontalModeCannotTargetAnotherHashOrAnEpubOrADuplicateBookRow() {
        for (change in 0..2) {
            val bytes = BackupRecordsCodec.encode(records())
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val tables = json.getJSONObject("tables")
            val nav = tables.getJSONArray("book_pdf_navigation_preferences")
            when (change) {
                0 -> nav.getJSONObject(0).put("contentSha256", "b".repeat(64))
                1 -> tables.getJSONArray("books").getJSONObject(0).put("format", "EPUB").put("pageCount", JSONObject.NULL)
                2 -> nav.put(JSONObject(nav.getJSONObject(0).toString()))
            }
            val problem = runCatching { BackupRecordsCodec.decode(json.toString().toByteArray(), 24) }.exceptionOrNull()
            assertEquals(BackupFailure.INVALID_ARCHIVE, (problem as BackupProblem).reason)
        }
    }
}

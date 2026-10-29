package com.yaprak.reader.data.backup

import android.content.ContentValues
import android.database.Cursor
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.BookOrganizationEntity
import com.yaprak.reader.data.persistence.toOrganization
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubRangeAnchorCodec
import com.yaprak.reader.domain.passages.MAX_EPUB_PASSAGE_LENGTH
import com.yaprak.reader.domain.passages.MAX_EPUB_ANNOTATIONS_PER_BOOK
import com.yaprak.reader.domain.passages.MAX_EPUB_ATTACHED_NOTE_LENGTH
import com.yaprak.reader.domain.passages.isWellFormedEpubAnnotationText
import com.yaprak.reader.domain.pdfannotations.PdfAnchorCodec
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.pdfannotations.MAX_PDF_ANNOTATIONS_PER_BOOK
import com.yaprak.reader.domain.pdfannotations.validatePdfAnnotation
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.MAX_PDF_CROP_PERCENT
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.domain.settings.PdfNavigationMode
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.domain.settings.ReadingTextAlignment
import com.yaprak.reader.domain.settings.BookReadingMode
import com.yaprak.reader.domain.settings.ReadingTheme
import com.yaprak.reader.domain.statistics.MAX_READING_DURATION_RECEIPTS
import com.yaprak.reader.domain.statistics.ReadingDurationReceipt
import com.yaprak.reader.domain.statistics.validateReadingDurationReceipt
import com.yaprak.reader.domain.vocabulary.*
import java.util.UUID
import java.net.URI
import java.util.concurrent.Callable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

internal class BackupProblem(val reason: BackupFailure) : Exception(reason.name)

internal data class ArchiveBook(
    val id: String, val storageName: String, val format: String,
    val byteSize: Long, val sha256: String,
) {
    val entryName: String get() = "books/$storageName"
}

internal data class ArchiveCover(
    val bookId: String, val contentSha256: String, val coverId: String,
    val storageName: String, val mediaType: String, val byteSize: Long,
    val sha256: String, val width: Int, val height: Int,
) {
    val entryName: String get() = "covers/$storageName"
}

internal data class BackupRecords(
    val books: List<ArchiveBook>,
    val settings: EpubSettings,
    val rows: Map<String, List<JSONObject>>,
    val covers: List<ArchiveCover> = emptyList(),
)

/** Explicit v26 whitelist, with frozen v12..v25 shapes for legacy archives. */
internal object BackupRecordsCodec {
    const val FORMAT_VERSION = 26
    private val legacyThemeNames = setOf("SYSTEM", "LIGHT", "SEPIA", "DARK")
    const val DATABASE_VERSION = 26
    const val MAX_BOOKS = 1000
    const val MAX_BOOK_BYTES = 512L * 1024 * 1024
    const val MAX_TOTAL_BOOK_BYTES = 8L * 1024 * 1024 * 1024
    const val MAX_COVER_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_COVER_BYTES = 512L * 1024 * 1024
    const val MAX_RECORDS_BYTES = 32 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_ROWS = 250_000
    private const val MAX_FIELD_CHARS = 1_000_000
    private val hashPattern = Regex("[0-9a-f]{64}")

    private enum class Kind { STRING, INT, LONG }
    private data class Column(val name: String, val kind: Kind, val nullable: Boolean = false)
    private data class Table(val name: String, val columns: List<Column>, val key: List<String>)

    private fun s(name: String, nullable: Boolean = false) = Column(name, Kind.STRING, nullable)
    private fun i(name: String, nullable: Boolean = false) = Column(name, Kind.INT, nullable)
    private fun l(name: String) = Column(name, Kind.LONG)

    private val tables = listOf(
        Table("books", listOf(s("id"), s("title"), s("authors", true), s("format"),
            s("originalName"), s("storageName"), l("byteSize"), s("sha256"),
            l("importedAt"), i("pageCount", true)), listOf("id")),
        Table("reading_progress", listOf(s("bookId"), s("contentSha256"), i("locatorVersion"),
            s("locatorJson"), s("resourceHref"), l("updatedAt")), listOf("bookId")),
        Table("observed_text", listOf(s("bookId"), s("contentSha256"), i("extractionVersion"),
            s("resourceHref"), s("nodePath"), i("startOffset"), i("endOffset"),
            s("textSha256")), listOf("bookId", "contentSha256", "extractionVersion",
            "resourceHref", "nodePath", "startOffset", "endOffset", "textSha256")),
        Table("bookmarks", listOf(s("id"), s("bookId"), s("contentSha256"),
            s("locationKey"), s("label"), s("locatorJson"), s("resourceHref"),
            i("locatorVersion"), l("createdAt")), listOf("id")),
        Table("book_epub_theme_overrides", listOf(s("bookId"), s("contentSha256"), s("theme")), listOf("bookId")),
        Table("book_epub_font_size_overrides", listOf(s("bookId"), s("contentSha256"),
            i("fontSizePercent")), listOf("bookId")),
        Table("book_epub_font_overrides", listOf(s("bookId"), s("contentSha256"), s("font")), listOf("bookId")),
        Table("book_epub_text_alignment_overrides", listOf(s("bookId"), s("contentSha256"), s("alignment")), listOf("bookId")),
        Table("book_epub_reading_mode_overrides", listOf(s("bookId"), s("contentSha256"), s("mode")), listOf("bookId")),
        Table("book_cbz_view_preferences", listOf(s("bookId"), s("contentSha256"), i("doublePagePreferred")), listOf("bookId")),
        Table("book_epub_margin_overrides", listOf(s("bookId"), s("contentSha256"),
            i("marginsPercent")), listOf("bookId")),
        Table("book_epub_line_spacing_overrides", listOf(s("bookId"), s("contentSha256"),
            i("lineHeightPercent", true)), listOf("bookId")),
        Table("book_epub_spacing_overrides", listOf(s("bookId"), s("contentSha256"),
            s("paragraphMode"), i("paragraphSpacingPercent", true),
            s("wordMode"), i("wordSpacingPercent", true)), listOf("bookId")),
        Table("saved_words", listOf(s("id"), s("bookId"), s("contentSha256"),
            s("english"), s("turkish"), l("createdAt"), l("revision")), listOf("id")),
        Table("book_notes", listOf(s("id"), s("bookId"), s("contentSha256"),
            s("text"), l("createdAt"), i("pdfPageIndex", true)), listOf("id")),
        Table("epub_passages", listOf(s("id"), s("bookId"), s("contentSha256"),
            i("anchorVersion"), s("resourceHref"), s("nodeTextSha256"),
            i("startOffset"), i("endOffset"), s("text"), l("createdAt"),
            s("highlightColor", true), s("attachedNote", true), l("revision")), listOf("id")),
        Table("book_organizations", listOf(s("bookId"), s("contentSha256"),
            s("readingStatus"), i("favorite"), s("series", true), s("genre", true),
            s("tagsJson"), s("collectionsJson")), listOf("bookId")),
        Table("book_completion_events", listOf(s("id"), s("bookId"), s("contentSha256"),
            l("completedAt"), s("localDate"), s("zoneId")), listOf("id")),
        Table("book_covers", listOf(s("bookId"), s("contentSha256"), s("coverId"),
            s("storageName"), s("mediaType"), l("byteSize"), s("sha256"),
            i("width"), i("height")), listOf("bookId")),
        Table("reading_duration_receipts", listOf(s("id"), s("bookId"), s("contentSha256"),
            s("localDate"), s("zoneId"), l("startedAt"), l("endedAt"), l("elapsedMillis")), listOf("id")),
        Table("book_pdf_view_preferences", listOf(s("bookId"), s("contentSha256"),
            i("horizontalPercent"), i("verticalPercent"), s("fitMode"), i("doublePagePreferred")), listOf("bookId")),
        Table("book_pdf_navigation_preferences", listOf(s("bookId"), s("contentSha256"), s("mode")), listOf("bookId")),
        Table("epub_range_passages", listOf(s("id"), s("bookId"), s("contentSha256"),
            i("anchorVersion"), s("resourceHref"), s("anchorJson"), s("anchorKey"), s("text"),
            l("createdAt"), s("highlightColor", true), s("attachedNote", true), l("revision")), listOf("id")),
        Table("pdf_annotations", listOf(s("id"), s("bookId"), s("contentSha256"),
            i("anchorVersion"), i("pageIndex"), s("anchorJson"), s("anchorKey"), s("selectionKey"),
            s("text"), l("createdAt"), s("highlightColor", true), s("attachedNote", true),
            l("revision")), listOf("id")),
        Table("word_review_events", listOf(s("id"), s("wordId"), s("bookId"), s("contentSha256"),
            l("wordRevision"), s("fingerprint"), s("english"), s("turkish"), l("wordCreatedAt"),
            l("previousVersion"), l("version"), i("step"), l("reviewedAt"), l("dueAt"), s("zoneId"),
            s("rating"), i("algorithm")), listOf("id")),
        Table("word_schedules", listOf(s("wordId"), s("bookId"), s("contentSha256"), l("wordRevision"),
            s("fingerprint"), l("version"), i("step"), l("reviewedAt"), l("dueAt"), s("zoneId"),
            s("lastEventId"), i("algorithm")), listOf("wordId")),
    )
    private val v25Tables = tables.filterNot { it.name == "book_pdf_navigation_preferences" }
    private val v24Tables = v25Tables.filterNot { it.name == "book_cbz_view_preferences" }
    private val v23Tables = v24Tables.filterNot { it.name == "book_epub_reading_mode_overrides" }
    private val v22Tables = v23Tables.filterNot { it.name == "book_epub_text_alignment_overrides" }
    private val v21Tables = v22Tables.filterNot { it.name == "book_completion_events" }
    private val v20Tables = v21Tables.filterNot { it.name == "book_epub_spacing_overrides" }
    private val v19Tables = v20Tables.filterNot { it.name in setOf("word_review_events", "word_schedules") }.map {
        if (it.name == "saved_words") it.copy(columns = it.columns.filterNot { column -> column.name == "revision" }) else it
    }
    private val v18Tables = v19Tables.filter { it.name != "pdf_annotations" }
    private val v17Tables = v18Tables.filter { it.name != "epub_range_passages" }
    private val v16Tables = v17Tables.filter { it.name != "book_pdf_view_preferences" }
    private val v15Tables = v16Tables.filter { it.name != "reading_duration_receipts" }
    private val v14Tables = v15Tables.map { table ->
        if (table.name == "epub_passages") table.copy(columns = table.columns.filterNot {
            it.name in setOf("highlightColor", "attachedNote", "revision")
        }) else table
    }
    private val v13Tables = v14Tables.filter { it.name != "book_covers" }
    private val legacyTables = v13Tables.filter { it.name != "book_organizations" }

    fun snapshot(database: LibraryDatabase, settings: EpubSettings): BackupRecords {
        val rows = database.runInTransaction(Callable {
            validateSchema(database)
            var rowCount = 0
            var approxBytes = 0L
            tables.associate { table ->
                val names = table.columns.joinToString(",") { it.name }
                val list = mutableListOf<JSONObject>()
                database.openHelper.writableDatabase.query("SELECT $names FROM ${table.name}").use { cursor ->
                    while (cursor.moveToNext()) {
                        if (++rowCount > MAX_ROWS) throw BackupProblem(BackupFailure.TOO_LARGE)
                        val row = JSONObject()
                        table.columns.forEachIndexed { index, column ->
                            val value: Any = when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(index).also {
                                    if (it.length > MAX_FIELD_CHARS) throw BackupProblem(BackupFailure.TOO_LARGE)
                                }
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                else -> throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
                            }
                            row.put(column.name, value)
                        }
                        approxBytes += row.toString().toByteArray(Charsets.UTF_8).size + 2L
                        if (approxBytes > MAX_RECORDS_BYTES) throw BackupProblem(BackupFailure.TOO_LARGE)
                        list += row
                    }
                }
                table.name to list.toList()
            }
        })
        return validate(BackupRecords(booksFromRows(rows.getValue("books")), settings, rows,
            coversFromRows(rows.getValue("book_covers"))))
    }

    fun encode(records: BackupRecords): ByteArray {
        val tableObject = JSONObject()
        tables.forEach { table ->
            tableObject.put(table.name, JSONArray(records.rows.getValue(table.name)))
        }
        val root = JSONObject()
            .put("settings", encodeSettings(records.settings))
            .put("tables", tableObject)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_RECORDS_BYTES) throw BackupProblem(BackupFailure.TOO_LARGE)
        return bytes
    }

    fun decode(bytes: ByteArray, formatVersion: Int = FORMAT_VERSION): BackupRecords {
        if (formatVersion !in 1..FORMAT_VERSION) throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        if (bytes.size > MAX_RECORDS_BYTES) throw BackupProblem(BackupFailure.TOO_LARGE)
        val root = try { JSONObject(strictUtf8(bytes)) }
            catch (_: Exception) { throw BackupProblem(BackupFailure.INVALID_ARCHIVE) }
        exactKeys(root, setOf("settings", "tables"))
        val settings = decodeSettings(root.optJSONObject("settings") ?: invalid(), formatVersion)
        val tableObject = root.optJSONObject("tables") ?: invalid()
        val archiveTables = when (formatVersion) {
            1, 2, 3 -> legacyTables
            4 -> v13Tables
            5 -> v14Tables
            6 -> v15Tables
            7 -> v16Tables
            8 -> v17Tables
            9 -> v18Tables
            10 -> v19Tables
            11, 12, 13 -> v20Tables
            14 -> v21Tables
            15, 16, 17 -> v22Tables
            18 -> v23Tables
            19 -> v24Tables
            20 -> v25Tables
            else -> tables
        }
        // New tables under an old header are rejected, never silently dropped.
        exactKeys(tableObject, archiveTables.map { it.name }.toSet())
        var count = 0
        val decodedRows = archiveTables.associate { table ->
            val array = tableObject.optJSONArray(table.name) ?: invalid()
            val list = ArrayList<JSONObject>(array.length())
            for (index in 0 until array.length()) {
                if (++count > MAX_ROWS) throw BackupProblem(BackupFailure.TOO_LARGE)
                val row = array.optJSONObject(index) ?: invalid()
                // Check the old row shape before hydrating defaults. A forged old header cannot drop new fields.
                exactKeys(row, table.columns.map { it.name }.toSet())
                // Horizontal navigation is new archive semantics, never admitted under an older header.
                if (formatVersion <= 23 && table.name == "book_pdf_navigation_preferences" &&
                    string(row, "mode") == PdfNavigationMode.HORIZONTAL_SCROLL.name) invalid()
                if (formatVersion <= 11 && table.name == "book_epub_theme_overrides" &&
                    string(row, "theme") !in legacyThemeNames) invalid()
                list += if (formatVersion <= 5 && table.name == "epub_passages") {
                    JSONObject(row.toString()).put("highlightColor", JSONObject.NULL)
                        .put("attachedNote", JSONObject.NULL).put("revision", 0)
                } else if (formatVersion <= 10 && table.name == "saved_words") JSONObject(row.toString()).put("revision", 0) else row
            }
            table.name to list.toList()
        }
        // Missing organization rows hydrate as defaults in RoomLibraryRepository.
        val organizedRows = if (formatVersion <= 3) decodedRows + ("book_organizations" to emptyList()) else decodedRows
        val coveredRows = if (formatVersion <= 4) organizedRows + ("book_covers" to emptyList()) else organizedRows
        val durationRows = if (formatVersion <= 6) coveredRows + ("reading_duration_receipts" to emptyList()) else coveredRows
        // Legacy archives have no PDF preference proof. A verified reader load supplies defaults later.
        val pdfRows = if (formatVersion <= 7) durationRows + ("book_pdf_view_preferences" to emptyList()) else durationRows
        val rangeRows = if (formatVersion <= 8) pdfRows + ("epub_range_passages" to emptyList()) else pdfRows
        // Legacy page notes contain no native selection geometry and remain independent notes.
        val annotationRows = if (formatVersion <= 9) rangeRows + ("pdf_annotations" to emptyList()) else rangeRows
        val reviewRows = if (formatVersion <= 10) annotationRows + mapOf("word_schedules" to emptyList(), "word_review_events" to emptyList()) else annotationRows
        val spacingRows = if (formatVersion <= 13) reviewRows + ("book_epub_spacing_overrides" to emptyList()) else reviewRows
        val completionRows = if (formatVersion <= 14) spacingRows + ("book_completion_events" to emptyList()) else spacingRows
        val alignmentRows = if (formatVersion <= 17) completionRows + ("book_epub_text_alignment_overrides" to emptyList()) else completionRows
        val modeRows = if (formatVersion <= 18) alignmentRows + ("book_epub_reading_mode_overrides" to emptyList()) else alignmentRows
        val comicRows = if (formatVersion <= 19) modeRows + ("book_cbz_view_preferences" to emptyList()) else modeRows
        val rows = if (formatVersion <= 20) comicRows + ("book_pdf_navigation_preferences" to emptyList()) else comicRows
        validateBooks(booksFromRows(rows.getValue("books")), formatVersion)
        return validate(BackupRecords(booksFromRows(rows.getValue("books")), settings, rows,
            coversFromRows(rows.getValue("book_covers"))))
    }

    fun encodeManifest(records: BackupRecords, recordsBytes: ByteArray, recordsHash: String): ByteArray {
        val books = JSONArray()
        records.books.forEach { book ->
            books.put(JSONObject().put("id", book.id).put("storageName", book.storageName)
                .put("format", book.format).put("byteSize", book.byteSize).put("sha256", book.sha256))
        }
        val bytes = JSONObject().put("formatVersion", FORMAT_VERSION)
            .put("databaseVersion", DATABASE_VERSION).put("bookCount", records.books.size)
            .put("recordsSize", recordsBytes.size).put("recordsSha256", recordsHash)
            .put("books", books).put("coverCount", records.covers.size)
            .put("covers", JSONArray(records.covers.map(::encodeCover))).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MANIFEST_BYTES) throw BackupProblem(BackupFailure.TOO_LARGE)
        return bytes
    }

    data class Manifest(val books: List<ArchiveBook>, val recordsSize: Long, val recordsHash: String,
        val formatVersion: Int, val covers: List<ArchiveCover>)

    fun decodeManifest(bytes: ByteArray): Manifest {
        if (bytes.size > MAX_MANIFEST_BYTES) throw BackupProblem(BackupFailure.TOO_LARGE)
        val root = try { JSONObject(strictUtf8(bytes)) }
            catch (_: Exception) { throw BackupProblem(BackupFailure.INVALID_ARCHIVE) }
        val formatVersion = integer(root, "formatVersion")
        val expectedDatabaseVersion = when (formatVersion) { 1, 2, 3 -> 12; 4 -> 13; 5 -> 14; 6 -> 15; 7 -> 16; 8 -> 17; 9 -> 18; 10 -> 19; 11, 12, 13 -> 20; 14 -> 21; 15, 16, 17 -> 22; 18 -> 23; 19 -> 24; 20 -> 25; 21, 22, 23, 24, 25, 26 -> 26; else -> -1 }
        if (formatVersion !in 1..FORMAT_VERSION ||
            integer(root, "databaseVersion") != expectedDatabaseVersion) {
            throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        }
        val legacyKeys = setOf("formatVersion", "databaseVersion", "bookCount", "recordsSize",
            "recordsSha256", "books")
        exactKeys(root, if (formatVersion >= 5) legacyKeys + setOf("coverCount", "covers") else legacyKeys)
        val array = root.optJSONArray("books") ?: invalid()
        if (array.length() > MAX_BOOKS || integer(root, "bookCount") != array.length()) invalid()
        val books = ArrayList<ArchiveBook>(array.length())
        for (index in 0 until array.length()) {
            val book = array.optJSONObject(index) ?: invalid()
            exactKeys(book, setOf("id", "storageName", "format", "byteSize", "sha256"))
            books += ArchiveBook(string(book, "id"), string(book, "storageName"),
                string(book, "format"), long(book, "byteSize"), string(book, "sha256"))
        }
        validateBooks(books, formatVersion)
        val covers = if (formatVersion >= 5) {
            val coverArray = root.optJSONArray("covers") ?: invalid()
            if (coverArray.length() > MAX_BOOKS || integer(root, "coverCount") != coverArray.length()) invalid()
            (0 until coverArray.length()).map { decodeCover(coverArray.optJSONObject(it) ?: invalid()) }
        } else emptyList()
        validateCovers(covers, books)
        val size = long(root, "recordsSize")
        if (size < 0 || size > MAX_RECORDS_BYTES) throw BackupProblem(BackupFailure.TOO_LARGE)
        val hash = string(root, "recordsSha256")
        if (!hashPattern.matches(hash)) invalid()
        return Manifest(books, size, hash, formatVersion, covers)
    }

    fun insert(database: LibraryDatabase, records: BackupRecords) {
        validateSchema(database)
        val sql = database.openHelper.writableDatabase
        tables.forEach { table ->
            records.rows.getValue(table.name).forEach { row ->
                val values = ContentValues(table.columns.size)
                table.columns.forEach { column ->
                    val raw = row.get(column.name)
                    when {
                        raw === JSONObject.NULL -> values.putNull(column.name)
                        column.kind == Kind.STRING -> values.put(column.name, raw as String)
                        column.kind == Kind.INT -> values.put(column.name, (raw as Number).toInt())
                        else -> values.put(column.name, (raw as Number).toLong())
                    }
                }
                if (sql.insert(table.name, 0, values) == -1L) invalid()
            }
        }
    }

    /** Fail closed when a Room upgrade adds data that this explicit whitelist cannot export. */
    internal fun validateSchema(database: LibraryDatabase) {
        val sql = database.openHelper.writableDatabase
        val version = sql.query("PRAGMA user_version").use { cursor ->
            if (!cursor.moveToFirst()) throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
            cursor.getInt(0)
        }
        if (version != DATABASE_VERSION) throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        val expected = tables.map { it.name }.toSet()
        val actual = sql.query("SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')")
            .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        if (actual != expected) throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        tables.forEach { table ->
            val columns = sql.query("PRAGMA table_info(${table.name})").use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        put(cursor.getString(1), Pair(cursor.getString(2).uppercase(), cursor.getInt(3) != 0))
                    }
                }
            }
            val expectedColumns = table.columns.associate { column ->
                column.name to Pair(if (column.kind == Kind.STRING) "TEXT" else "INTEGER", !column.nullable)
            }
            if (columns != expectedColumns) throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        }
        // Restore rollback relies on this exact key/cascade; matching column names alone is insufficient.
        val navigationPrimaryKey = sql.query("PRAGMA table_info(book_pdf_navigation_preferences)").use { cursor ->
            buildMap { while (cursor.moveToNext()) if (cursor.getInt(5) != 0) put(cursor.getString(1), cursor.getInt(5)) }
        }
        val navigationForeignKeys = sql.query("PRAGMA foreign_key_list(book_pdf_navigation_preferences)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(listOf(cursor.getString(2), cursor.getString(3),
                cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7))) }
        }
        if (navigationPrimaryKey != mapOf("bookId" to 1) ||
            navigationForeignKeys != listOf(listOf("books", "bookId", "id", "NO ACTION", "CASCADE", "NONE")))
            throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        val comicPrimaryKey = sql.query("PRAGMA table_info(book_cbz_view_preferences)").use { cursor ->
            buildMap { while (cursor.moveToNext()) if (cursor.getInt(5) != 0) put(cursor.getString(1), cursor.getInt(5)) }
        }
        val comicForeignKeys = sql.query("PRAGMA foreign_key_list(book_cbz_view_preferences)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(listOf(cursor.getString(2), cursor.getString(3),
                cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7))) }
        }
        if (comicPrimaryKey != mapOf("bookId" to 1) ||
            comicForeignKeys != listOf(listOf("books", "bookId", "id", "NO ACTION", "CASCADE", "NONE")))
            throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        validateWordReviewSchema(sql)
        val completionKeys = sql.query("PRAGMA table_info(book_completion_events)").use { cursor ->
            buildMap { while (cursor.moveToNext()) if (cursor.getInt(5) != 0) put(cursor.getString(1), cursor.getInt(5)) }
        }
        val completionForeign = sql.query("PRAGMA foreign_key_list(book_completion_events)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(listOf(cursor.getString(2), cursor.getString(3),
                cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7))) }
        }
        if (completionKeys != mapOf("id" to 1) ||
            completionForeign != listOf(listOf("books", "bookId", "id", "NO ACTION", "CASCADE", "NONE")))
            throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        val pdfPrimaryKey = sql.query("PRAGMA table_info(book_pdf_view_preferences)").use { cursor ->
            buildMap { while (cursor.moveToNext()) if (cursor.getInt(5) != 0) put(cursor.getString(1), cursor.getInt(5)) }
        }
        val pdfForeignKeys = sql.query("PRAGMA foreign_key_list(book_pdf_view_preferences)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(listOf(cursor.getString(2), cursor.getString(3),
                    cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7)))
            }
        }
        if (pdfPrimaryKey != mapOf("bookId" to 1) ||
            pdfForeignKeys != listOf(listOf("books", "bookId", "id", "NO ACTION", "CASCADE", "NONE"))) {
            throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        }
        val rangePrimaryKey = sql.query("PRAGMA table_info(epub_range_passages)").use { cursor ->
            buildMap { while (cursor.moveToNext()) if (cursor.getInt(5) != 0) put(cursor.getString(1), cursor.getInt(5)) }
        }
        val rangeForeignKeys = sql.query("PRAGMA foreign_key_list(epub_range_passages)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(listOf(cursor.getString(2), cursor.getString(3),
                    cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7)))
            }
        }
        val rangeIndexName = "index_epub_range_passages_bookId_contentSha256_anchorKey"
        val rangeIndexShape = sql.query("PRAGMA index_list(epub_range_passages)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) if (cursor.getString(1) == rangeIndexName)
                    add(listOf<Any?>(cursor.getInt(2), cursor.getString(3), cursor.getInt(4)))
            }
        }
        val rangeIndexColumns = sql.query("PRAGMA index_xinfo($rangeIndexName)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) if (cursor.getInt(5) == 1)
                    add(listOf<Any?>(cursor.getString(2), cursor.getInt(3), cursor.getString(4)))
            }
        }
        if (rangePrimaryKey != mapOf("id" to 1) ||
            rangeForeignKeys != listOf(listOf("books", "bookId", "id", "NO ACTION", "CASCADE", "NONE")) ||
            rangeIndexShape != listOf(listOf<Any?>(1, "c", 0)) ||
            rangeIndexColumns != listOf(listOf<Any?>("bookId", 0, "BINARY"),
                listOf<Any?>("contentSha256", 0, "BINARY"), listOf<Any?>("anchorKey", 0, "BINARY"))) {
            throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        }
        val annotationPrimaryKey = sql.query("PRAGMA table_info(pdf_annotations)").use { cursor ->
            buildMap { while (cursor.moveToNext()) if (cursor.getInt(5) != 0) put(cursor.getString(1), cursor.getInt(5)) }
        }
        val annotationForeignKeys = sql.query("PRAGMA foreign_key_list(pdf_annotations)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(listOf(cursor.getString(2), cursor.getString(3),
                    cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7)))
            }
        }
        val annotationIndex = "index_pdf_annotations_bookId_contentSha256_selectionKey"
        val annotationIndexShape = sql.query("PRAGMA index_list(pdf_annotations)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) if (cursor.getString(1) == annotationIndex)
                    add(listOf<Any?>(cursor.getInt(2), cursor.getString(3), cursor.getInt(4)))
            }
        }
        val annotationIndexColumns = sql.query("PRAGMA index_xinfo($annotationIndex)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) if (cursor.getInt(5) == 1)
                    add(listOf<Any?>(cursor.getString(2), cursor.getInt(3), cursor.getString(4)))
            }
        }
        if (annotationPrimaryKey != mapOf("id" to 1) ||
            annotationForeignKeys != listOf(listOf("books", "bookId", "id", "NO ACTION", "CASCADE", "NONE")) ||
            annotationIndexShape != listOf(listOf<Any?>(1, "c", 0)) ||
            annotationIndexColumns != listOf(listOf<Any?>("bookId", 0, "BINARY"),
                listOf<Any?>("contentSha256", 0, "BINARY"), listOf<Any?>("selectionKey", 0, "BINARY"))) {
            throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        }
    }

    private fun validateWordReviewSchema(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
        mapOf("word_schedules" to "wordId", "word_review_events" to "id").forEach { (table, primary) ->
            val keys = sql.query("PRAGMA table_info($table)").use { cursor ->
                buildMap { while (cursor.moveToNext()) if (cursor.getInt(5) != 0) put(cursor.getString(1), cursor.getInt(5)) }
            }
            val foreign = sql.query("PRAGMA foreign_key_list($table)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(listOf(cursor.getString(2), cursor.getString(3), cursor.getString(4),
                    cursor.getString(5), cursor.getString(6), cursor.getString(7))) }
            }
            if (keys != mapOf(primary to 1) || foreign != listOf(listOf("saved_words", "wordId", "id", "NO ACTION", "CASCADE", "NONE")))
                throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        }
        val index = "index_word_review_events_wordId_version"
        val unique = sql.query("PRAGMA index_list(word_review_events)").use { cursor ->
            buildList { while (cursor.moveToNext()) if (cursor.getString(1) == index) add(listOf<Any>(cursor.getInt(2), cursor.getString(3), cursor.getInt(4))) }
        }
        val columns = sql.query("PRAGMA index_xinfo($index)").use { cursor ->
            buildList { while (cursor.moveToNext()) if (cursor.getInt(5) == 1) add(listOf<Any>(cursor.getString(2), cursor.getInt(3), cursor.getString(4))) }
        }
        if (unique != listOf(listOf<Any>(1, "c", 0)) || columns != listOf(listOf<Any>("wordId", 0, "BINARY"), listOf<Any>("version", 0, "BINARY")))
            throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
    }

    private fun validate(records: BackupRecords): BackupRecords {
        if (records.rows.keys != tables.map { it.name }.toSet()) invalid()
        if (records.rows.getValue("reading_duration_receipts").size > MAX_READING_DURATION_RECEIPTS) {
            throw BackupProblem(BackupFailure.TOO_LARGE)
        }
        if (records.rows.getValue("word_review_events").size > MAX_WORD_REVIEW_EVENTS) throw BackupProblem(BackupFailure.TOO_LARGE)
        if (records.rows.getValue("book_completion_events").size >
            com.yaprak.reader.domain.statistics.MAX_BOOK_COMPLETION_EVENTS) throw BackupProblem(BackupFailure.TOO_LARGE)
        validateBooks(records.books)
        validateCovers(records.covers, records.books)
        if (coversFromRows(records.rows.getValue("book_covers")) != records.covers) invalid()
        val booksById = records.books.associateBy { it.id }
        val bookRowsById = records.rows.getValue("books").associateBy { string(it, "id") }
        var rowCount = 0
        tables.forEach { table ->
            val seen = HashSet<List<Any?>>()
            records.rows.getValue(table.name).forEach { row ->
                if (++rowCount > MAX_ROWS) throw BackupProblem(BackupFailure.TOO_LARGE)
                exactKeys(row, table.columns.map { it.name }.toSet())
                table.columns.forEach { column ->
                    val value = row.opt(column.name)
                    if (value == null || value === JSONObject.NULL) {
                        if (!column.nullable) invalid()
                    } else when (column.kind) {
                        Kind.STRING -> if (value !is String) invalid() else if (value.length > MAX_FIELD_CHARS)
                            throw BackupProblem(BackupFailure.TOO_LARGE)
                        Kind.INT -> integer(row, column.name)
                        Kind.LONG -> long(row, column.name)
                    }
                }
                val key = table.key.map { row.get(it) }
                if (!seen.add(key)) invalid()
                if (table.name != "books") {
                    val book = booksById[string(row, "bookId")] ?: invalid()
                    if (string(row, "contentSha256") != book.sha256) invalid()
                }
                validateRowSemantics(table.name, row, bookRowsById)
            }
        }
        if (records.rows.getValue("books").size != records.books.size) invalid()
        val bookmarkKeys = HashSet<List<Any>>()
        records.rows.getValue("bookmarks").forEach { row ->
            if (!bookmarkKeys.add(listOf(row.get("bookId"), row.get("contentSha256"), row.get("locationKey")))) invalid()
        }
        val passageKeys = HashSet<List<Any>>()
        val annotationCounts = HashMap<String, Int>()
        val passageIds = HashSet<String>()
        records.rows.getValue("epub_passages").forEach { row ->
            if (!passageIds.add(string(row, "id"))) invalid()
            if (!passageKeys.add(listOf(row.get("bookId"), row.get("contentSha256"),
                    row.get("anchorVersion"), row.get("resourceHref"), row.get("nodeTextSha256"),
                    row.get("startOffset"), row.get("endOffset")))) invalid()
            if (row.opt("highlightColor") !== JSONObject.NULL || row.opt("attachedNote") !== JSONObject.NULL) {
                val bookId = string(row, "bookId")
                val count = (annotationCounts[bookId] ?: 0) + 1
                if (count > MAX_EPUB_ANNOTATIONS_PER_BOOK) throw BackupProblem(BackupFailure.TOO_LARGE)
                annotationCounts[bookId] = count
            }
        }
        val rangeKeys = HashSet<List<Any>>()
        records.rows.getValue("epub_range_passages").forEach { row ->
            if (!passageIds.add(string(row, "id")) ||
                !rangeKeys.add(listOf(row.get("bookId"), row.get("contentSha256"), row.get("anchorKey")))) invalid()
            if (row.opt("highlightColor") !== JSONObject.NULL || row.opt("attachedNote") !== JSONObject.NULL) {
                val bookId = string(row, "bookId")
                val count = (annotationCounts[bookId] ?: 0) + 1
                if (count > MAX_EPUB_ANNOTATIONS_PER_BOOK) throw BackupProblem(BackupFailure.TOO_LARGE)
                annotationCounts[bookId] = count
            }
        }
        val pdfSelectionKeys = HashSet<List<Any>>()
        val pdfCounts = HashMap<String, Int>()
        records.rows.getValue("pdf_annotations").forEach { row ->
            if (!pdfSelectionKeys.add(listOf(row.get("bookId"), row.get("contentSha256"), row.get("selectionKey")))) invalid()
            val bookId = string(row, "bookId")
            val count = (pdfCounts[bookId] ?: 0) + 1
            if (count > MAX_PDF_ANNOTATIONS_PER_BOOK) throw BackupProblem(BackupFailure.TOO_LARGE)
            pdfCounts[bookId] = count
        }
        validateWordReviews(records)
        return records
    }

    private fun reviewSchedule(row: JSONObject) = WordSchedule(string(row, "wordId"), string(row, "bookId"),
        string(row, "contentSha256"), long(row, "wordRevision"), string(row, "fingerprint"), long(row, "version"),
        integer(row, "step"), long(row, "reviewedAt"), long(row, "dueAt"), string(row, "zoneId"),
        string(row, "lastEventId"), integer(row, "algorithm"))
    private fun reviewEvent(row: JSONObject) = WordReviewEvent(string(row, "id"), string(row, "wordId"),
        string(row, "bookId"), string(row, "contentSha256"), long(row, "wordRevision"), string(row, "fingerprint"),
        string(row, "english"), string(row, "turkish"), long(row, "wordCreatedAt"), long(row, "previousVersion"),
        long(row, "version"), integer(row, "step"), long(row, "reviewedAt"), long(row, "dueAt"),
        string(row, "zoneId"), WordRating.valueOf(string(row, "rating")), integer(row, "algorithm"))

    private fun validateWordReviews(records: BackupRecords) {
        try {
            val words = records.rows.getValue("saved_words").associateBy { string(it, "id") }
            val schedules = records.rows.getValue("word_schedules").map(::reviewSchedule).associateBy { it.wordId }
            val events = records.rows.getValue("word_review_events").map(::reviewEvent).groupBy { it.wordId }
            if (schedules.keys != events.keys) invalid()
            events.forEach { (id, history) ->
                val parent = words[id] ?: invalid()
                var prior: WordSchedule? = null
                history.sortedBy { it.version }.forEach { event ->
                    if (event.bookId != string(parent, "bookId") || event.contentSha256 != string(parent, "contentSha256") ||
                        event.wordCreatedAt != long(parent, "createdAt") || event.wordRevision > long(parent, "revision") ||
                        (prior != null && event.wordRevision < prior!!.wordRevision)) invalid()
                    if (event.wordRevision == long(parent, "revision") &&
                        (event.english != string(parent, "english") || event.turkish != string(parent, "turkish"))) invalid()
                    val word = SavedWord(id, event.english, event.turkish, event.wordCreatedAt, event.wordRevision)
                    val entry = LibraryWord(word, event.bookId, event.contentSha256, "history", null)
                    val planned = planWordReview(WordReviewCommand(event.id, WordReviewCard(entry, prior), event.rating),
                        event.reviewedAt, java.time.ZoneId.of(event.zoneId))
                    if (planned != event) invalid()
                    prior = event.schedule()
                }
                if (schedules[id] != prior) invalid()
            }
        } catch (problem: BackupProblem) { throw problem }
        catch (_: Exception) { invalid() }
    }
    private fun validateRowSemantics(
        table: String, row: JSONObject, books: Map<String, JSONObject>,
    ) {
        when (table) {
            "book_completion_events" -> try {
                com.yaprak.reader.domain.statistics.validateBookCompletionEvent(
                    com.yaprak.reader.domain.statistics.BookCompletionEvent(string(row, "id"),
                        string(row, "bookId"), string(row, "contentSha256"), long(row, "completedAt"),
                        string(row, "localDate"), string(row, "zoneId")))
            } catch (_: Exception) { invalid() }
            "saved_words" -> if (long(row, "revision") < 0) invalid()
            "word_schedules" -> try { validateWordSchedule(reviewSchedule(row)) } catch (_: Exception) { invalid() }
            "word_review_events" -> try { validateWordReviewEvent(reviewEvent(row)) } catch (_: Exception) { invalid() }
            "pdf_annotations" -> {
                val book = books[string(row, "bookId")] ?: invalid()
                val id = string(row, "id")
                if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id ||
                    string(book, "format") != "PDF" || integer(row, "anchorVersion") != 1 ||
                    long(row, "createdAt") < 0 || long(row, "revision") < 0) invalid()
                try {
                    val anchor = PdfAnchorCodec.verify(string(row, "anchorJson"), string(row, "anchorKey"))
                    val text = string(row, "text")
                    val color = if (row.opt("highlightColor") === JSONObject.NULL) null
                        else PdfHighlightColor.valueOf(string(row, "highlightColor"))
                    val note = if (row.opt("attachedNote") === JSONObject.NULL) null else string(row, "attachedNote")
                    validatePdfAnnotation(text, color, note)
                    if (integer(row, "pageIndex") != anchor.pageIndex ||
                        anchor.pageIndex !in 0 until integer(book, "pageCount") ||
                        string(row, "selectionKey") != PdfAnchorCodec.selectionKey(anchor, text)) invalid()
                } catch (_: Exception) { invalid() }
            }
            "epub_range_passages" -> {
                val passage = string(row, "text")
                val id = string(row, "id")
                if (id.isBlank() || id.length > 1000 ||
                    integer(row, "anchorVersion") != 2 || !safePassageHref(string(row, "resourceHref")) ||
                    string(books[string(row, "bookId")] ?: invalid(), "format") != "EPUB" ||
                    passage.length !in 1..MAX_EPUB_PASSAGE_LENGTH || passage.isBlank() ||
                    !isWellFormedEpubAnnotationText(passage) || long(row, "revision") < 0) invalid()
                try {
                    EpubRangeAnchorCodec.verify(string(row, "resourceHref"), string(row, "anchorJson"),
                        string(row, "anchorKey"))
                } catch (_: Exception) { invalid() }
                if (row.opt("highlightColor") !== JSONObject.NULL &&
                    string(row, "highlightColor") !in EpubHighlightColor.entries.map { it.name }) invalid()
                if (row.opt("attachedNote") !== JSONObject.NULL) {
                    val note = string(row, "attachedNote")
                    if (note.length !in 1..MAX_EPUB_ATTACHED_NOTE_LENGTH || note.isBlank() ||
                        !isWellFormedEpubAnnotationText(note)) invalid()
                }
            }
            "book_pdf_view_preferences" -> {
                if (string(books[string(row, "bookId")] ?: invalid(), "format") != "PDF" ||
                    integer(row, "horizontalPercent") !in 0..MAX_PDF_CROP_PERCENT ||
                    integer(row, "verticalPercent") !in 0..MAX_PDF_CROP_PERCENT ||
                    string(row, "fitMode") !in PdfFitPreference.entries.map { it.name } ||
                    integer(row, "doublePagePreferred") !in 0..1) invalid()
            }
            "book_pdf_navigation_preferences" -> {
                if (string(books[string(row, "bookId")] ?: invalid(), "format") != "PDF" ||
                    string(row, "mode") !in PdfNavigationMode.entries.map { it.name }) invalid()
            }
            "reading_duration_receipts" -> {
                try {
                    validateReadingDurationReceipt(ReadingDurationReceipt(string(row, "id"), string(row, "bookId"),
                        string(row, "contentSha256"), string(row, "localDate"), string(row, "zoneId"),
                        long(row, "startedAt"), long(row, "endedAt"), long(row, "elapsedMillis")))
                } catch (_: Exception) { invalid() }
            }
            "books" -> {
                val page = row.opt("pageCount")
                if (string(row, "format") == "PDF") {
                    if (page === JSONObject.NULL || integer(row, "pageCount") <= 0) invalid()
                } else if (string(row, "format") in setOf("CBZ", "CBR")) {
                    if (page === JSONObject.NULL || integer(row, "pageCount") !in 1..2000) invalid()
                } else if (page !== JSONObject.NULL) invalid()
            }
            "reading_progress", "bookmarks" -> {
                if (integer(row, "locatorVersion") != 1 ||
                    string(row, "locatorJson").length !in 1..64_000) invalid()
                if (table == "bookmarks" &&
                    (string(row, "label").isBlank() || string(row, "label").length > 200)) invalid()
            }
            "observed_text" -> {
                if (string(books[string(row, "bookId")] ?: invalid(), "format") != "EPUB") invalid()
                if (integer(row, "extractionVersion") != 1 ||
                    integer(row, "startOffset") < 0 ||
                    integer(row, "endOffset") <= integer(row, "startOffset") ||
                    !hashPattern.matches(string(row, "textSha256"))) invalid()
            }
            "book_epub_theme_overrides" ->
                if (string(row, "theme") !in ReadingTheme.entries.map { it.name }) invalid()
            "book_epub_font_overrides" ->
                if (string(row, "font") !in ReadingFont.entries.map { it.name }) invalid()
            "book_epub_text_alignment_overrides" -> {
                if (string(books[string(row, "bookId")] ?: invalid(), "format") !in setOf("EPUB", "TXT", "FB2", "DOCX") ||
                    string(row, "alignment") !in ReadingTextAlignment.entries.map { it.name }) invalid()
            }
            "book_epub_reading_mode_overrides" -> {
                if (string(books[string(row, "bookId")] ?: invalid(), "format") !in setOf("EPUB", "TXT", "FB2", "DOCX") ||
                    string(row, "mode") !in BookReadingMode.entries.map { it.name }) invalid()
            }
            "book_cbz_view_preferences" -> {
                if (string(books[string(row, "bookId")] ?: invalid(), "format") !in setOf("CBZ", "CBR") ||
                    integer(row, "doublePagePreferred") !in 0..1) invalid()
            }
            "book_epub_font_size_overrides" ->
                if (integer(row, "fontSizePercent") !in 75..200) invalid()
            "book_epub_margin_overrides" ->
                if (integer(row, "marginsPercent") !in 50..200) invalid()
            "book_epub_line_spacing_overrides" ->
                if (row.opt("lineHeightPercent") !== JSONObject.NULL &&
                    integer(row, "lineHeightPercent") !in 100..220) invalid()
            "book_epub_spacing_overrides" -> {
                if (string(books[string(row, "bookId")] ?: invalid(), "format") !in setOf("EPUB", "TXT", "FB2", "DOCX")) invalid()
                fun validateChoice(modeKey: String, valueKey: String, maximum: Int) {
                    when (string(row, modeKey)) {
                        "INHERIT", "PUBLISHER" -> if (row.opt(valueKey) !== JSONObject.NULL) invalid()
                        "CUSTOM" -> {
                            val percent = integer(row, valueKey)
                            if (percent !in 0..maximum || percent % 10 != 0) invalid()
                        }
                        else -> invalid()
                    }
                }
                validateChoice("paragraphMode", "paragraphSpacingPercent", 200)
                validateChoice("wordMode", "wordSpacingPercent", 100)
                if (string(row, "paragraphMode") == "INHERIT" && string(row, "wordMode") == "INHERIT") invalid()
            }
            "book_notes" -> {
                val page = row.opt("pdfPageIndex")
                if (page !== JSONObject.NULL) {
                    val book = books[string(row, "bookId")] ?: invalid()
                    if (string(book, "format") != "PDF" ||
                        integer(row, "pdfPageIndex") !in 0 until integer(book, "pageCount")) invalid()
                }
            }
            "epub_passages" -> {
                val start = integer(row, "startOffset")
                val end = integer(row, "endOffset")
                val passage = string(row, "text")
                if (integer(row, "anchorVersion") != 1 || start < 0 || end <= start ||
                    end > 4096 || end - start != passage.length || passage.isBlank() ||
                    !hashPattern.matches(string(row, "nodeTextSha256")) ||
                    !safePassageHref(string(row, "resourceHref")) ||
                    string(books[string(row, "bookId")] ?: invalid(), "format") != "EPUB" ||
                    long(row, "revision") < 0) invalid()
                if (row.opt("highlightColor") !== JSONObject.NULL &&
                    string(row, "highlightColor") !in EpubHighlightColor.entries.map { it.name }) invalid()
                if ((row.opt("highlightColor") !== JSONObject.NULL || row.opt("attachedNote") !== JSONObject.NULL) &&
                    !isWellFormedEpubAnnotationText(passage)) invalid()
                if (row.opt("attachedNote") !== JSONObject.NULL) {
                    val note = string(row, "attachedNote")
                    if (note.length !in 1..MAX_EPUB_ATTACHED_NOTE_LENGTH || note.isBlank() ||
                        !isWellFormedEpubAnnotationText(note)) invalid()
                }
            }
            "book_organizations" -> {
                val favorite = integer(row, "favorite")
                if (favorite !in 0..1) invalid()
                fun nullable(name: String): String? = if (row.opt(name) === JSONObject.NULL) null else string(row, name)
                try {
                    BookOrganizationEntity(string(row, "bookId"), string(row, "contentSha256"),
                        string(row, "readingStatus"), favorite == 1, nullable("series"), nullable("genre"),
                        string(row, "tagsJson"), string(row, "collectionsJson")).toOrganization()
                } catch (_: Exception) { invalid() }
            }
        }
    }

    private fun booksFromRows(rows: List<JSONObject>): List<ArchiveBook> = rows.map { row ->
        ArchiveBook(string(row, "id"), string(row, "storageName"), string(row, "format"),
            long(row, "byteSize"), string(row, "sha256"))
    }

    private fun coversFromRows(rows: List<JSONObject>): List<ArchiveCover> = rows.map(::decodeCover)

    fun encodeCover(cover: ArchiveCover): JSONObject = JSONObject()
        .put("bookId", cover.bookId).put("contentSha256", cover.contentSha256)
        .put("coverId", cover.coverId).put("storageName", cover.storageName)
        .put("mediaType", cover.mediaType).put("byteSize", cover.byteSize)
        .put("sha256", cover.sha256).put("width", cover.width).put("height", cover.height)

    fun decodeCover(json: JSONObject): ArchiveCover {
        exactKeys(json, setOf("bookId", "contentSha256", "coverId", "storageName", "mediaType",
            "byteSize", "sha256", "width", "height"))
        return ArchiveCover(string(json, "bookId"), string(json, "contentSha256"),
            string(json, "coverId"), string(json, "storageName"), string(json, "mediaType"),
            long(json, "byteSize"), string(json, "sha256"), integer(json, "width"), integer(json, "height"))
    }

    fun validateCovers(covers: List<ArchiveCover>, books: List<ArchiveBook>) {
        if (covers.size > MAX_BOOKS || covers.size > books.size) invalid()
        val booksById = books.associateBy { it.id }
        val bookIds = HashSet<String>()
        val ids = HashSet<String>()
        val names = HashSet<String>()
        var total = 0L
        covers.forEach { cover ->
            val book = booksById[cover.bookId] ?: invalid()
            val extension = when (cover.mediaType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> invalid()
            }
            if (cover.contentSha256 != book.sha256 ||
                runCatching { UUID.fromString(cover.coverId).toString() }.getOrNull() != cover.coverId ||
                cover.storageName != "${cover.coverId}.$extension" || !hashPattern.matches(cover.sha256) ||
                !bookIds.add(cover.bookId) || !ids.add(cover.coverId) || !names.add(cover.storageName) ||
                cover.width !in 1..16_384 || cover.height !in 1..16_384 ||
                cover.width.toLong() * cover.height > 40_000_000L) invalid()
            if (cover.byteSize !in 1L..MAX_COVER_BYTES) throw BackupProblem(BackupFailure.TOO_LARGE)
            total += cover.byteSize
            if (total > MAX_TOTAL_COVER_BYTES) throw BackupProblem(BackupFailure.TOO_LARGE)
        }
    }

    fun validateBooks(books: List<ArchiveBook>, formatVersion: Int = FORMAT_VERSION) {
        if (formatVersion !in 1..FORMAT_VERSION) throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        if (books.size > MAX_BOOKS) throw BackupProblem(BackupFailure.TOO_LARGE)
        val ids = HashSet<String>()
        val names = HashSet<String>()
        val hashes = HashSet<String>()
        var total = 0L
        books.forEach { book ->
            if (runCatching { UUID.fromString(book.id).toString() }.getOrNull() != book.id ||
                book.format !in (when (formatVersion) { 26 -> setOf("EPUB", "PDF", "TXT", "CBZ", "CBR", "FB2", "DOCX"); 25 -> setOf("EPUB", "PDF", "TXT", "CBZ", "FB2", "DOCX"); 22, 23, 24 -> setOf("EPUB", "PDF", "TXT", "CBZ", "FB2"); in 17..21 -> setOf("EPUB", "PDF", "TXT", "CBZ"); 16 -> setOf("EPUB", "PDF", "TXT"); else -> setOf("EPUB", "PDF") }) ||
                book.storageName != "${book.id}.${book.format.lowercase()}" ||
                !hashPattern.matches(book.sha256) ||
                !ids.add(book.id) || !names.add(book.storageName) || !hashes.add(book.sha256)) invalid()
            if (book.byteSize !in 1L..MAX_BOOK_BYTES) throw BackupProblem(BackupFailure.TOO_LARGE)
            if (book.format == "TXT" && book.byteSize > com.yaprak.reader.formats.txt.MAX_TXT_BYTES)
                throw BackupProblem(BackupFailure.TOO_LARGE)
            if (book.format == "FB2" && book.byteSize > com.yaprak.reader.formats.fb2.MAX_FB2_BYTES)
                throw BackupProblem(BackupFailure.TOO_LARGE)
            if (book.format == "DOCX" && book.byteSize > com.yaprak.reader.formats.docx.MAX_DOCX_PACKAGE_BYTES)
                throw BackupProblem(BackupFailure.TOO_LARGE)
            total += book.byteSize
            if (total > MAX_TOTAL_BOOK_BYTES) throw BackupProblem(BackupFailure.TOO_LARGE)
        }
    }

    fun encodeSettings(settings: EpubSettings) = JSONObject()
        .put("fontSizePercent", settings.fontSizePercent)
        .put("font", settings.font.name)
        .put("lineHeightPercent", settings.lineHeightPercent ?: JSONObject.NULL)
        .put("marginsPercent", settings.marginsPercent)
        .put("theme", settings.theme.name)
        .put("scroll", settings.scroll)
        .put("textAlignment", settings.textAlignment.name)
        .put("paragraphSpacingPercent", settings.paragraphSpacingPercent ?: JSONObject.NULL)
        .put("wordSpacingPercent", settings.wordSpacingPercent ?: JSONObject.NULL)

    /** Archives require exact version fields; journals accept only complete 5/6/7/9-field shapes. */
    fun decodeSettings(json: JSONObject, formatVersion: Int? = null): EpubSettings {
        val version = when (formatVersion) {
            null -> when {
                json.has("paragraphSpacingPercent") || json.has("wordSpacingPercent") -> 13
                json.has("textAlignment") -> 3
                json.has("scroll") -> 2
                else -> 1
            }
            in 1..FORMAT_VERSION -> formatVersion
            else -> throw BackupProblem(BackupFailure.UNSUPPORTED_VERSION)
        }
        val keys = setOf("fontSizePercent", "font", "lineHeightPercent", "marginsPercent", "theme")
        exactKeys(json, when (version) {
            1 -> keys
            2 -> keys + "scroll"
            in 13..FORMAT_VERSION -> keys + setOf("scroll", "textAlignment", "paragraphSpacingPercent", "wordSpacingPercent")
            else -> keys + setOf("scroll", "textAlignment")
        })
        if (formatVersion != null && formatVersion <= 11 && string(json, "theme") !in legacyThemeNames) invalid()
        val scroll = if (version >= 2) json.opt("scroll") as? Boolean ?: invalid() else false
        val line = if (json.opt("lineHeightPercent") === JSONObject.NULL) null
            else integer(json, "lineHeightPercent")
        return try {
            val alignment = if (version >= 3) ReadingTextAlignment.valueOf(string(json, "textAlignment"))
                else ReadingTextAlignment.PUBLISHER
            EpubSettings(integer(json, "fontSizePercent"), ReadingFont.valueOf(string(json, "font")),
                line, integer(json, "marginsPercent"), ReadingTheme.valueOf(string(json, "theme")),
                scroll = scroll, textAlignment = alignment,
                paragraphSpacingPercent = if (version < 13 || json.opt("paragraphSpacingPercent") === JSONObject.NULL) null
                    else integer(json, "paragraphSpacingPercent"),
                wordSpacingPercent = if (version < 13 || json.opt("wordSpacingPercent") === JSONObject.NULL) null
                    else integer(json, "wordSpacingPercent"))
        } catch (_: Exception) { invalid() }
    }

    private fun exactKeys(json: JSONObject, expected: Set<String>) {
        val actual = mutableSetOf<String>()
        val keys = json.keys()
        while (keys.hasNext()) actual += keys.next()
        if (actual != expected) invalid()
    }

    private fun safePassageHref(href: String): Boolean {
        if (href.isEmpty() || href.length > 4096 || href.contains('\\') ||
            href.any(Char::isISOControl) || Regex("%(?:2[fF]|5[cC])").containsMatchIn(href)) return false
        return try {
            val uri = URI(href)
            val path = uri.path ?: return false
            !uri.isAbsolute && !uri.isOpaque && uri.rawAuthority == null && uri.rawQuery == null &&
                uri.rawFragment == null && path.isNotEmpty() && !path.startsWith('/') &&
                !path.contains('\\') && path.split('/').none {
                    it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl)
                } && uri.rawPath == href
        } catch (_: Exception) { false }
    }

    private fun string(json: JSONObject, name: String): String {
        val value = json.opt(name)
        if (value !is String || value.length > MAX_FIELD_CHARS) invalid()
        return value
    }

    private fun integer(json: JSONObject, name: String): Int {
        val value = long(json, name)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) invalid()
        return value.toInt()
    }

    private fun long(json: JSONObject, name: String): Long {
        val value = json.opt(name)
        if (value !is Number || value is Float || value is Double) invalid()
        return value.toString().toLongOrNull() ?: invalid()
    }

    internal fun strictUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { invalid() }

    private fun invalid(): Nothing = throw BackupProblem(BackupFailure.INVALID_ARCHIVE)
}

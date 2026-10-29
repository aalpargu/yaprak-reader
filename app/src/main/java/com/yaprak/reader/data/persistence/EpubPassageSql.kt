package com.yaprak.reader.data.persistence

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.domain.passages.*
import com.yaprak.reader.reader.EpubSourceAnchor
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceTextAnchor

/** Shared strict row boundary for reader lists, global notes and exports. No source-provenance claims. */
internal object EpubPassageSql {
    const val LEGACY = "epub_passages"
    const val RANGE = "epub_range_passages"

    fun table(anchor: EpubSourceAnchor): String = when (anchor) {
        is SourceTextAnchor -> LEGACY
        is SourceRangeAnchor -> RANGE
    }

    fun checkDistinctIds(sql: SupportSQLiteDatabase) {
        sql.query("SELECT 1 FROM epub_passages AS old INNER JOIN epub_range_passages AS newer " +
            "ON old.id = newer.id LIMIT 1").use {
            check(!it.moveToFirst()) { "Conflicting EPUB passage identities" }
        }
    }

    fun validate(text: String, anchor: EpubSourceAnchor) {
        require(text.length in 1..MAX_EPUB_PASSAGE_LENGTH && text.isNotBlank()) {
            "EPUB passage must contain 1–$MAX_EPUB_PASSAGE_LENGTH UTF-16 code units"
        }
        require(isSafeEpubPassageHref(anchor.resourceHref)) { "EPUB passage resource invalid" }
        when (anchor) {
            is SourceTextAnchor -> {
                require(anchor.start >= 0 && anchor.end > anchor.start && anchor.end <= MAX_EPUB_PASSAGE_LENGTH &&
                    anchor.end - anchor.start == text.length) { "EPUB passage range mismatch" }
                require(Regex("[0-9a-f]{64}").matches(anchor.nodeTextSha256)) { "EPUB passage node hash invalid" }
            }
            is SourceRangeAnchor -> {
                // Native preview may contain paragraph separators absent from the original slices.
                require(isWellFormedEpubAnnotationText(text)) { "Invalid EPUB range preview" }
                EpubRangeAnchorCodec.encode(anchor)
            }
        }
    }

    fun validateNote(note: String?) {
        require(note == null || (note.length in 1..MAX_EPUB_ATTACHED_NOTE_LENGTH && note.isNotBlank() &&
            isWellFormedEpubAnnotationText(note))) {
            "EPUB attached note must contain 1–$MAX_EPUB_ATTACHED_NOTE_LENGTH UTF-16 code units"
        }
    }

    fun read(cursor: Cursor, range: Boolean): EpubPassage {
        fun string(name: String): String {
            val column = cursor.getColumnIndexOrThrow(name)
            check(cursor.getType(column) == Cursor.FIELD_TYPE_STRING) { "Invalid EPUB passage field" }
            return cursor.getString(column)
        }
        fun integer(name: String): Long {
            val column = cursor.getColumnIndexOrThrow(name)
            check(cursor.getType(column) == Cursor.FIELD_TYPE_INTEGER) { "Invalid EPUB passage field" }
            return cursor.getLong(column)
        }
        fun optional(name: String) = if (cursor.isNull(cursor.getColumnIndexOrThrow(name))) null else string(name)
        val id = string("id").also { check(it.isNotBlank() && it.length <= 1000) }
        string("bookId").also { check(it.isNotBlank() && it.length <= 1000) }
        val hash = string("contentSha256")
        val version = integer("anchorVersion")
        val href = string("resourceHref")
        val anchor = if (range) {
            check(version == 2L && Regex("[0-9a-f]{64}").matches(hash)) { "Invalid EPUB range version or hash" }
            EpubRangeAnchorCodec.verify(href, string("anchorJson"), string("anchorKey"))
        } else {
            check(version == 1L)
            val start = integer("startOffset").also { check(it in 0..MAX_EPUB_PASSAGE_LENGTH.toLong()) }.toInt()
            val end = integer("endOffset").also { check(it in 0..MAX_EPUB_PASSAGE_LENGTH.toLong()) }.toInt()
            SourceTextAnchor(href, start, end, string("nodeTextSha256"))
        }
        val text = string("text")
        validate(text, anchor)
        val color = optional("highlightColor")?.let(EpubHighlightColor::valueOf)
        val note = optional("attachedNote").also(::validateNote)
        if (color != null || note != null) check(isWellFormedEpubAnnotationText(text)) { "Invalid EPUB annotation text" }
        val revision = integer("revision").also { check(it >= 0) { "Invalid EPUB passage revision" } }
        return EpubPassage(id, text, anchor, integer("createdAt"), color, note, revision)
    }

    fun current(sql: SupportSQLiteDatabase, bookId: String, hash: String,
        checkActive: () -> Unit, includeUnknownLegacy: Boolean = false): List<EpubPassage> {
        val values = ArrayList<EpubPassage>()
        scanCurrent(sql, bookId, hash, checkActive, includeUnknownLegacy) { values += it }
        return java.util.Collections.unmodifiableList(values)
    }

    fun scanCurrent(sql: SupportSQLiteDatabase, bookId: String, hash: String, checkActive: () -> Unit,
        includeUnknownLegacy: Boolean = false, requireEpubBook: Boolean = true, consume: (EpubPassage) -> Unit) {
        checkActive()
        checkDistinctIds(sql)
        sql.query(SimpleSQLiteQuery("""
            SELECT passage.* FROM (
              SELECT id,bookId,contentSha256,anchorVersion,resourceHref,nodeTextSha256,startOffset,endOffset,
                     text,createdAt,highlightColor,attachedNote,revision,NULL AS anchorJson,NULL AS anchorKey,0 AS rangeRecord
              FROM epub_passages ${if (!includeUnknownLegacy) "WHERE anchorVersion = 1" else ""}
              UNION ALL
              SELECT id,bookId,contentSha256,anchorVersion,resourceHref,NULL,NULL,NULL,
                     text,createdAt,highlightColor,attachedNote,revision,anchorJson,anchorKey,1
              FROM epub_range_passages
            ) AS passage INNER JOIN books AS book ON book.id = passage.bookId
            WHERE passage.bookId = ? AND passage.contentSha256 = ? AND book.sha256 = ?
            ${if (requireEpubBook) "AND book.format = 'EPUB'" else ""}
            ORDER BY passage.createdAt DESC, passage.id DESC
        """.trimIndent(), arrayOf(bookId, hash, hash))).use { cursor ->
            while (true) {
                checkActive()
                if (!cursor.moveToNext()) break
                checkActive()
                consume(read(cursor, cursor.getInt(cursor.getColumnIndexOrThrow("rangeRecord")) == 1))
            }
        }
        checkActive()
    }
}

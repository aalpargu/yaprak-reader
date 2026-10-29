package com.yaprak.reader.data.persistence

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.reader.PdfSourceAnchor
import java.util.Collections
import java.util.UUID

/** One strict interpretation is shared by Room, export and library note search. */
internal object PdfAnnotationSql {
    const val TABLE = "pdf_annotations"
    fun read(cursor: Cursor, textColumn: String = "text", noteColumn: String = "attachedNote",
        pageColumn: String = "pageIndex"): PdfAnnotation {
        fun string(name: String): String {
            val index = cursor.getColumnIndexOrThrow(name); check(cursor.getType(index) == Cursor.FIELD_TYPE_STRING)
            return cursor.getString(index)
        }
        fun integer(name: String): Long {
            val index = cursor.getColumnIndexOrThrow(name); check(cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER)
            return cursor.getLong(index)
        }
        fun optional(name: String) = if (cursor.isNull(cursor.getColumnIndexOrThrow(name))) null else string(name)
        val id = string("id"); check(UUID.fromString(id).toString() == id)
        check(string("bookId").length in 1..1000 && string("bookId").isNotBlank())
        check(Regex("[0-9a-f]{64}").matches(string("contentSha256")))
        check(integer("anchorVersion") == PdfSourceAnchor.VERSION.toLong())
        val anchor = PdfAnchorCodec.verify(string("anchorJson"), string("anchorKey"))
        check(integer(pageColumn) == anchor.pageIndex.toLong())
        val color = optional("highlightColor")?.let(PdfHighlightColor::valueOf)
        val note = optional(noteColumn)
        val text = string(textColumn); validatePdfAnnotation(text, color, note)
        check(PdfAnchorCodec.selectionKey(anchor, text) == string("selectionKey"))
        val created = integer("createdAt").also { check(it >= 0) }
        val revision = integer("revision").also { check(it >= 0) }
        return PdfAnnotation(id, text, anchor, created, color, note, revision)
    }
    fun current(sql: SupportSQLiteDatabase, bookId: String, hash: String, checkActive: () -> Unit): List<PdfAnnotation> {
        val result = ArrayList<PdfAnnotation>()
        scan(sql, bookId, hash, checkActive) { result += it }
        return Collections.unmodifiableList(result)
    }
    fun scan(sql: SupportSQLiteDatabase, bookId: String, hash: String, checkActive: () -> Unit,
        consume: (PdfAnnotation) -> Unit) {
        checkActive()
        sql.query(SimpleSQLiteQuery("""SELECT item.*, book.pageCount AS actualPageCount, book.format AS actualFormat
            FROM pdf_annotations AS item INNER JOIN books AS book ON book.id = item.bookId
            WHERE item.bookId = ? AND item.contentSha256 = ? AND book.sha256 = ?
            ORDER BY item.createdAt DESC, item.id DESC""", arrayOf(bookId, hash, hash))).use { cursor ->
            var count = 0
            while (true) {
                checkActive(); if (!cursor.moveToNext()) break
                check(++count <= MAX_PDF_ANNOTATIONS_PER_BOOK)
                check(cursor.getType(cursor.getColumnIndexOrThrow("actualFormat")) == Cursor.FIELD_TYPE_STRING &&
                    cursor.getString(cursor.getColumnIndexOrThrow("actualFormat")) == "PDF")
                val pageCountIndex = cursor.getColumnIndexOrThrow("actualPageCount")
                check(cursor.getType(pageCountIndex) == Cursor.FIELD_TYPE_INTEGER)
                val annotation = read(cursor)
                check(annotation.anchor.pageIndex.toLong() in 0 until cursor.getLong(pageCountIndex))
                consume(annotation)
            }
        }
        checkActive()
    }
}

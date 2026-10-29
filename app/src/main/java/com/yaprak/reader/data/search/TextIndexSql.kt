package com.yaprak.reader.data.search

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat

/** Synchronous statements keep the framework SQLite transaction on its owning thread.
 * Room owns schema/FTS triggers/invalidation; a public Room connection lease owns each transaction.
 */
internal class TextIndexSql(private val sql: SupportSQLiteDatabase, private val checkActive: () -> Unit) {
    fun books(): List<TextIndexBookEntity> = sql.query("SELECT * FROM text_index_books ORDER BY bookId").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                checkActive()
                add(cursor.book())
            }
        }
    }

    /** An explicit rebuild may remove a malformed derived ledger, including incorrect SQL types. */
    fun pruneBooks(shouldRemove: (TextIndexBookEntity?) -> Boolean) {
        val obsolete = sql.query("SELECT rowid AS cacheRowId, * FROM text_index_books").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    checkActive()
                    val row = try { cursor.book() } catch (_: LibraryTextIndexException) { null }
                    if (shouldRemove(row)) add(cursor.long("cacheRowId"))
                }
            }
        }
        for (id in obsolete) { checkActive(); sql.execSQL("DELETE FROM text_index_books WHERE rowid = ?", arrayOf(id)) }
    }

    fun chunk(id: Long): TextIndexChunkEntity? = sql.query(SimpleSQLiteQuery(
        "SELECT * FROM text_index_chunks WHERE rowid = ? LIMIT 1", arrayOf(id))).use { cursor ->
        checkActive()
        if (cursor.moveToFirst()) cursor.chunk() else null
    }

    /** Read bounded header evidence in complete global chunk order; no text retained by the catalog. */
    fun sections(book: Book, ledger: TextIndexBookEntity): List<BookSourceSection> =
        sql.query(SimpleSQLiteQuery("""SELECT bookId, contentSha256, generation, readingOrderIndex,
            chunkIndex, resourceHref, resourceSha256, chapterTitle, sourceFormat, pdfPageIndex
            FROM text_index_chunks WHERE bookId = ? ORDER BY chunkIndex, rowid""".trimIndent(),
            arrayOf(book.id))).use { cursor ->
            val scope = BookTextScope(book.id, book.sha256)
            val sections = ArrayList<BookSourceSection>()
            var expectedChunk = 0
            while (cursor.moveToNext()) {
                checkActive()
                if (cursor.text("bookId") != book.id || cursor.text("contentSha256") != book.sha256 ||
                    cursor.text("generation") != ledger.generation || cursor.text("sourceFormat") != book.format.name ||
                    cursor.integer("chunkIndex") != expectedChunk ||
                    expectedChunk >= MAX_INDEX_BOOK_CHUNKS) sourceFailure()
                val order = cursor.integer("readingOrderIndex")
                val previous = sections.lastOrNull()
                // Validate a resource header at its first chunk, not at this later row's chunk.
                // DOCX has one complete document resource whose first chunk must remain zero.
                // Every actual row index is independently checked against expectedChunk above.
                val firstChunk = if (previous?.readingOrderIndex == order) previous.firstChunkIndex else expectedChunk
                val item = BookSourceSection(scope, ledger.generation, order,
                    cursor.text("resourceHref"), cursor.text("resourceSha256"), cursor.optionalText("chapterTitle"),
                    firstChunk, 1, book.format, cursor.optionalInteger("pdfPageIndex"))
                if (!item.isValid || book.format == BookFormat.PDF && book.pageCount != null &&
                    item.readingOrderIndex >= book.pageCount) sourceFailure()
                if (previous?.readingOrderIndex == item.readingOrderIndex) {
                    if (previous.resourceHref != item.resourceHref || previous.resourceSha256 != item.resourceSha256 ||
                        previous.chapterTitle != item.chapterTitle) sourceFailure()
                    sections[sections.lastIndex] = previous.copy(chunkCount = previous.chunkCount + 1)
                } else {
                    if (sections.size >= MAX_INDEX_BOOK_RESOURCES) sourceFailure()
                    sections += item
                }
                expectedChunk++
            }
            if (expectedChunk != ledger.chunkCount || !validBookSourceSections(scope, sections)) sourceFailure()
            sections
        }

    /** One selected resource, never keyword filtering. LIMIT+1 proves oversize without a partial success. */
    fun sectionChunks(bookId: String, readingOrderIndex: Int): List<TextIndexChunkEntity> =
        sql.query(SimpleSQLiteQuery("""SELECT * FROM text_index_chunks
            WHERE bookId = ? AND readingOrderIndex = ? ORDER BY chunkIndex, rowid LIMIT ?""".trimIndent(),
            arrayOf<Any>(bookId, readingOrderIndex, MAX_COMPLETE_BOOK_SOURCE_REFERENCES + 1))).use { cursor ->
            buildList { while (cursor.moveToNext()) { checkActive(); add(cursor.chunk()) } }
        }

    /** Exact chapter plan indexes only; original stored hits remain unchanged. */
    fun chapterChunks(bookId: String, indexes: List<Int>): List<TextIndexChunkEntity> {
        if (indexes.size !in 1..MAX_COMPLETE_BOOK_SOURCE_REFERENCES ||
            indexes.any { it !in 0 until MAX_INDEX_BOOK_CHUNKS } ||
            indexes.zipWithNext().any { (a, b) -> a >= b }) sourceFailure()
        val bindings = ArrayList<Any>().apply { add(bookId); addAll(indexes); add(MAX_COMPLETE_BOOK_SOURCE_REFERENCES + 1) }
        val query = "SELECT * FROM text_index_chunks WHERE bookId = ? AND chunkIndex IN (" +
            placeholders(indexes.size) + ") ORDER BY chunkIndex, rowid LIMIT ?"
        return sql.query(SimpleSQLiteQuery(query, bindings.toTypedArray())).use { cursor ->
            buildList { while (cursor.moveToNext()) { checkActive(); add(cursor.chunk()) } }
        }
    }

    fun find(expression: String, bookIds: List<String>, limit: Int): List<TextIndexChunkEntity> {
        require(bookIds.isNotEmpty() && bookIds.size <= 400)
        val bindings = ArrayList<Any>(bookIds.size + 2).apply { add(expression); addAll(bookIds); add(limit) }
        return sql.query(SimpleSQLiteQuery("""SELECT c.* FROM text_index_fts
            JOIN text_index_chunks c ON c.rowid = text_index_fts.rowid
            WHERE text_index_fts MATCH ? AND c.bookId IN (${placeholders(bookIds.size)})
            ORDER BY c.bookId, c.readingOrderIndex, c.chunkIndex LIMIT ?""".trimIndent(), bindings.toTypedArray())).use { cursor ->
            buildList { while (cursor.moveToNext()) { checkActive(); add(cursor.chunk()) } }
        }
    }

    fun count(expression: String, bookIds: List<String>): Int {
        require(bookIds.isNotEmpty() && bookIds.size <= 400)
        val bindings = ArrayList<Any>(bookIds.size + 1).apply { add(expression); addAll(bookIds) }
        return sql.query(SimpleSQLiteQuery("""SELECT COUNT(*) AS matches FROM text_index_fts
            JOIN text_index_chunks c ON c.rowid = text_index_fts.rowid
            WHERE text_index_fts MATCH ? AND c.bookId IN (${placeholders(bookIds.size)})""".trimIndent(), bindings.toTypedArray())).use { cursor ->
            checkActive()
            if (!cursor.moveToFirst()) sourceFailure()
            cursor.integer("matches").also { if (it !in 0..MAX_INDEX_LIBRARY_CHUNKS) sourceFailure() }
        }
    }

    fun deleteBook(id: String) {
        checkActive()
        sql.execSQL("DELETE FROM text_index_books WHERE bookId = ?", arrayOf(id))
    }

    fun insertBook(book: TextIndexBookEntity) {
        checkActive()
        sql.execSQL("""INSERT INTO text_index_books
            (bookId, contentSha256, extractionVersion, generation, state, chunkCount, stringBytes, sourceFormat)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(), arrayOf<Any>(book.bookId, book.contentSha256,
            book.extractionVersion, book.generation, book.state, book.chunkCount, book.stringBytes, book.sourceFormat))
    }

    fun insertChunks(chunks: List<TextIndexChunkEntity>) {
        for (chunk in chunks) {
            checkActive()
            // Omitting rowid retains SQLite AUTOINCREMENT and the Room external-content sync triggers.
            sql.execSQL("""INSERT INTO text_index_chunks
                (bookId, contentSha256, generation, readingOrderIndex, chunkIndex, resourceHref,
                 resourceSha256, chapterTitle, text, searchText, sourceFormat, pdfPageIndex)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                arrayOf<Any?>(chunk.bookId, chunk.contentSha256, chunk.generation, chunk.readingOrderIndex,
                    chunk.chunkIndex, chunk.resourceHref, chunk.resourceSha256, chunk.chapterTitle, chunk.text,
                    chunk.searchText, chunk.sourceFormat, chunk.pdfPageIndex))
        }
    }

    private fun Cursor.chunk() = TextIndexChunkEntity(long("rowid"), text("bookId"), text("contentSha256"),
        text("generation"), integer("readingOrderIndex"), integer("chunkIndex"), text("resourceHref"),
        text("resourceSha256"), optionalText("chapterTitle"), text("text"), text("searchText"),
        text("sourceFormat"), optionalInteger("pdfPageIndex"))

    private fun Cursor.book() = TextIndexBookEntity(text("bookId"), text("contentSha256"),
        integer("extractionVersion"), text("generation"), text("state"), integer("chunkCount"), long("stringBytes"),
        text("sourceFormat"))

    private fun Cursor.text(name: String): String {
        val index = getColumnIndexOrThrow(name)
        if (getType(index) != Cursor.FIELD_TYPE_STRING) sourceFailure()
        return getString(index)
    }
    private fun Cursor.optionalText(name: String): String? =
        if (isNull(getColumnIndexOrThrow(name))) null else text(name)
    private fun Cursor.long(name: String): Long {
        val index = getColumnIndexOrThrow(name)
        if (getType(index) != Cursor.FIELD_TYPE_INTEGER) sourceFailure()
        return getLong(index)
    }
    private fun Cursor.integer(name: String): Int = long(name).also {
        if (it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) sourceFailure()
    }.toInt()
    private fun Cursor.optionalInteger(name: String): Int? =
        if (isNull(getColumnIndexOrThrow(name))) null else integer(name)
    private fun placeholders(count: Int) = List(count) { "?" }.joinToString(",")
    private fun sourceFailure(): Nothing = throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
}

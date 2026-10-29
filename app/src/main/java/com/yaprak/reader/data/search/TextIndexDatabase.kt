package com.yaprak.reader.data.search

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Derived cache, physically separate from library.db and excluded from the user's backup. */
@Database(entities = [TextIndexBookEntity::class, TextIndexChunkEntity::class, TextIndexFtsEntity::class],
    version = 2, exportSchema = true)
abstract class TextIndexDatabase : RoomDatabase() {
    abstract fun index(): TextIndexDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE text_index_books ADD COLUMN sourceFormat TEXT NOT NULL DEFAULT 'EPUB'")
                db.execSQL("ALTER TABLE text_index_chunks ADD COLUMN sourceFormat TEXT NOT NULL DEFAULT 'EPUB'")
                db.execSQL("ALTER TABLE text_index_chunks ADD COLUMN pdfPageIndex INTEGER")
                // Room temporarily drops external-content FTS sync triggers during a migration;
                // their recreation must be paired with rebuilding from the preserved content rows.
                db.execSQL("INSERT INTO text_index_fts(text_index_fts) VALUES('rebuild')")
            }
        }
    }
}

@Entity(tableName = "text_index_books")
data class TextIndexBookEntity(
    @PrimaryKey val bookId: String,
    val contentSha256: String,
    val extractionVersion: Int,
    val generation: String,
    val state: String,
    val chunkCount: Int,
    val stringBytes: Long,
    @ColumnInfo(defaultValue = "'EPUB'") val sourceFormat: String = "EPUB",
)

@Entity(tableName = "text_index_chunks", foreignKeys = [ForeignKey(
    entity = TextIndexBookEntity::class, parentColumns = ["bookId"], childColumns = ["bookId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index("bookId"), Index(value = ["bookId", "chunkIndex"], unique = true)])
data class TextIndexChunkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val id: Long = 0,
    val bookId: String,
    val contentSha256: String,
    val generation: String,
    val readingOrderIndex: Int,
    val chunkIndex: Int,
    val resourceHref: String,
    val resourceSha256: String,
    val chapterTitle: String?,
    val text: String,
    val searchText: String,
    @ColumnInfo(defaultValue = "'EPUB'") val sourceFormat: String = "EPUB",
    val pdfPageIndex: Int? = null,
)

@Fts4(contentEntity = TextIndexChunkEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=0"])
@Entity(tableName = "text_index_fts")
data class TextIndexFtsEntity(@PrimaryKey @ColumnInfo(name = "rowid") val id: Long, val searchText: String)

@Dao
interface TextIndexDao {
    @Query("SELECT * FROM text_index_books ORDER BY bookId")
    suspend fun books(): List<TextIndexBookEntity>

    @Query("DELETE FROM text_index_books WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: String)

    @Insert suspend fun insertBook(book: TextIndexBookEntity)
    @Insert suspend fun insertChunks(chunks: List<TextIndexChunkEntity>)

    @Query("""SELECT c.* FROM text_index_fts JOIN text_index_chunks c ON c.rowid = text_index_fts.rowid
        WHERE text_index_fts MATCH :expression AND c.bookId IN (:bookIds)
        ORDER BY c.bookId, c.readingOrderIndex, c.chunkIndex LIMIT :limit""")
    suspend fun find(expression: String, bookIds: List<String>, limit: Int): List<TextIndexChunkEntity>

    @Query("""SELECT COUNT(*) FROM text_index_fts JOIN text_index_chunks c ON c.rowid = text_index_fts.rowid
        WHERE text_index_fts MATCH :expression AND c.bookId IN (:bookIds)""")
    suspend fun count(expression: String, bookIds: List<String>): Int

    @Query("SELECT * FROM text_index_chunks WHERE rowid = :id LIMIT 1")
    suspend fun chunk(id: Long): TextIndexChunkEntity?
}

package com.yaprak.reader.data.persistence

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** SQL type tags are validated before Room's numeric getters can hide a malformed source value. */
data class BookPdfViewPreferencesStoredRecord(
    val currentBookId: String,
    val currentContentSha256: String,
    val currentFormat: String,
    val currentBookIdType: String,
    val currentContentSha256Type: String,
    val currentFormatType: String,
    @Embedded val preferences: BookPdfViewPreferencesEntity?,
    val storedBookIdType: String,
    val storedContentSha256Type: String,
    val horizontalType: String,
    val verticalType: String,
    val fitType: String,
    val spreadType: String,
    val horizontalValue: Long?,
    val verticalValue: Long?,
    val spreadValue: Long?,
    @Embedded(prefix = "navigation_") val navigation: BookPdfNavigationPreferencesEntity?,
    val navigationBookIdType: String,
    val navigationContentSha256Type: String,
    val navigationModeType: String,
)

internal const val BOOK_PDF_VIEW_PREFERENCES_QUERY = """
        SELECT book.id AS currentBookId, book.sha256 AS currentContentSha256,
            book.format AS currentFormat, typeof(book.id) AS currentBookIdType,
            typeof(book.sha256) AS currentContentSha256Type, typeof(book.format) AS currentFormatType,
            preference.bookId, preference.contentSha256, preference.horizontalPercent,
            preference.verticalPercent, preference.fitMode, preference.doublePagePreferred,
            typeof(preference.bookId) AS storedBookIdType,
            typeof(preference.contentSha256) AS storedContentSha256Type,
            typeof(preference.horizontalPercent) AS horizontalType,
            typeof(preference.verticalPercent) AS verticalType,
            typeof(preference.fitMode) AS fitType,
            typeof(preference.doublePagePreferred) AS spreadType,
            preference.horizontalPercent AS horizontalValue,
            preference.verticalPercent AS verticalValue,
            preference.doublePagePreferred AS spreadValue,
            navigation.bookId AS navigation_bookId,
            navigation.contentSha256 AS navigation_contentSha256,
            navigation.mode AS navigation_mode,
            typeof(navigation.bookId) AS navigationBookIdType,
            typeof(navigation.contentSha256) AS navigationContentSha256Type,
            typeof(navigation.mode) AS navigationModeType
        FROM books AS book
        LEFT JOIN book_pdf_view_preferences AS preference ON preference.bookId = book.id
        LEFT JOIN book_pdf_navigation_preferences AS navigation ON navigation.bookId = book.id
        WHERE book.id = :bookId
        LIMIT 1
    """

@Dao
interface BookPdfViewPreferencesDao {
    @Query(BOOK_PDF_VIEW_PREFERENCES_QUERY)
    suspend fun findForBook(bookId: String): BookPdfViewPreferencesStoredRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BookPdfViewPreferencesEntity)

    @Query("SELECT * FROM book_pdf_view_preferences ORDER BY bookId")
    suspend fun all(): List<BookPdfViewPreferencesEntity>
}

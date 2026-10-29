package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.yaprak.reader.domain.library.BookOrganization
import com.yaprak.reader.domain.library.BookReadingStatus
import org.json.JSONArray

@Entity(
    tableName = "book_organizations",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class BookOrganizationEntity(
    @PrimaryKey val bookId: String,
    val contentSha256: String,
    val readingStatus: String,
    val favorite: Boolean,
    val series: String?,
    val genre: String?,
    val tagsJson: String,
    val collectionsJson: String,
)

internal fun BookOrganization.toOrganizationEntity(bookId: String, sha256: String): BookOrganizationEntity {
    val value = normalized()
    return BookOrganizationEntity(bookId, sha256, value.readingStatus.name, value.favorite,
        value.series, value.genre, JSONArray(value.tags).toString(), JSONArray(value.collections).toString())
}

internal fun BookOrganizationEntity.toOrganization(): BookOrganization {
    fun labels(json: String): List<String> {
        val array = JSONArray(json)
        require(array.length() <= BookOrganization.MAX_LABEL_COUNT)
        return List(array.length()) { index ->
            val value = array.get(index)
            require(value is String)
            value
        }
    }
    val value = BookOrganization(BookReadingStatus.valueOf(readingStatus), favorite, series, genre,
        labels(tagsJson), labels(collectionsJson))
    require(value.normalized() == value)
    return value
}

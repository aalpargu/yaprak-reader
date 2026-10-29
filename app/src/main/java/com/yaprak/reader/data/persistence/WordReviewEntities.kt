package com.yaprak.reader.data.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "word_schedules", foreignKeys = [ForeignKey(entity = SavedWordEntity::class,
    parentColumns = ["id"], childColumns = ["wordId"], onDelete = ForeignKey.CASCADE)])
data class WordScheduleEntity(
    @PrimaryKey val wordId: String, val bookId: String, val contentSha256: String,
    val wordRevision: Long, val fingerprint: String, val version: Long, val step: Int,
    val reviewedAt: Long, val dueAt: Long, val zoneId: String, val lastEventId: String, val algorithm: Int,
)

@Entity(tableName = "word_review_events", foreignKeys = [ForeignKey(entity = SavedWordEntity::class,
    parentColumns = ["id"], childColumns = ["wordId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["wordId", "version"], unique = true)])
data class WordReviewEventEntity(
    @PrimaryKey val id: String, val wordId: String, val bookId: String, val contentSha256: String,
    val wordRevision: Long, val fingerprint: String, val english: String, val turkish: String,
    val wordCreatedAt: Long, val previousVersion: Long, val version: Long, val step: Int,
    val reviewedAt: Long, val dueAt: Long, val zoneId: String, val rating: String, val algorithm: Int,
)

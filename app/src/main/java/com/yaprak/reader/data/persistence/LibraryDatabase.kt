package com.yaprak.reader.data.persistence

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookEntity::class, ReadingProgressEntity::class, ObservedTextEntity::class, BookmarkEntity::class, BookEpubThemeOverrideEntity::class, BookEpubFontSizeOverrideEntity::class, BookEpubFontOverrideEntity::class, BookEpubTextAlignmentOverrideEntity::class, BookEpubReadingModeOverrideEntity::class, BookEpubMarginOverrideEntity::class, BookEpubLineSpacingOverrideEntity::class, BookEpubSpacingOverrideEntity::class, SavedWordEntity::class, BookNoteEntity::class, EpubPassageEntity::class, BookOrganizationEntity::class, BookCoverEntity::class, ReadingDurationReceiptEntity::class, BookPdfViewPreferencesEntity::class, BookPdfNavigationPreferencesEntity::class, BookCbzViewPreferencesEntity::class, EpubRangePassageEntity::class, PdfAnnotationEntity::class, WordScheduleEntity::class, WordReviewEventEntity::class, BookCompletionEventEntity::class], version = 26, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun reading(): ReadingDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun bookThemes(): BookEpubThemeOverrideDao
    abstract fun bookFontSizes(): BookEpubFontSizeOverrideDao
    abstract fun bookFonts(): BookEpubFontOverrideDao
    abstract fun bookTextAlignments(): BookEpubTextAlignmentOverrideDao
    abstract fun bookReadingModes(): BookEpubReadingModeOverrideDao
    abstract fun bookMargins(): BookEpubMarginOverrideDao
    abstract fun bookLineHeights(): BookEpubLineSpacingOverrideDao
    abstract fun bookSpacings(): BookEpubSpacingOverrideDao
    abstract fun savedWords(): SavedWordDao
    abstract fun bookNotes(): BookNoteDao
    abstract fun epubPassages(): EpubPassageDao
    abstract fun bookCovers(): BookCoverDao
    abstract fun readingDurations(): ReadingDurationReceiptDao
    abstract fun bookPdfViewPreferences(): BookPdfViewPreferencesDao

    companion object {
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add only: existing crop/fit/spread and all reading records remain unchanged.
                db.execSQL("CREATE TABLE IF NOT EXISTS book_pdf_navigation_preferences (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, mode TEXT NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // New table only; no choice is inferred from existing reader records.
                db.execSQL("CREATE TABLE IF NOT EXISTS book_cbz_view_preferences (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, doublePagePreferred INTEGER NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS book_epub_reading_mode_overrides (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, mode TEXT NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing settings and reading records remain unchanged; no alignment is inferred.
                db.execSQL("CREATE TABLE IF NOT EXISTS book_epub_text_alignment_overrides (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, alignment TEXT NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS book_completion_events (id TEXT NOT NULL, bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, completedAt INTEGER NOT NULL, localDate TEXT NOT NULL, zoneId TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_completion_events_bookId_contentSha256_completedAt ON book_completion_events(bookId, contentSha256, completedAt)")
            }
        }
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS book_epub_spacing_overrides (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, paragraphMode TEXT NOT NULL, paragraphSpacingPercent INTEGER, wordMode TEXT NOT NULL, wordSpacingPercent INTEGER, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_words ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS word_schedules (wordId TEXT NOT NULL, bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, wordRevision INTEGER NOT NULL, fingerprint TEXT NOT NULL, version INTEGER NOT NULL, step INTEGER NOT NULL, reviewedAt INTEGER NOT NULL, dueAt INTEGER NOT NULL, zoneId TEXT NOT NULL, lastEventId TEXT NOT NULL, algorithm INTEGER NOT NULL, PRIMARY KEY(wordId), FOREIGN KEY(wordId) REFERENCES saved_words(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS word_review_events (id TEXT NOT NULL, wordId TEXT NOT NULL, bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, wordRevision INTEGER NOT NULL, fingerprint TEXT NOT NULL, english TEXT NOT NULL, turkish TEXT NOT NULL, wordCreatedAt INTEGER NOT NULL, previousVersion INTEGER NOT NULL, version INTEGER NOT NULL, step INTEGER NOT NULL, reviewedAt INTEGER NOT NULL, dueAt INTEGER NOT NULL, zoneId TEXT NOT NULL, rating TEXT NOT NULL, algorithm INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(wordId) REFERENCES saved_words(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_word_review_events_wordId_version ON word_review_events (wordId, version)")
            }
        }
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add only; existing books, annotations, view preferences and receipts remain unchanged.
                db.execSQL("CREATE TABLE IF NOT EXISTS pdf_annotations (id TEXT NOT NULL, bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, anchorVersion INTEGER NOT NULL, pageIndex INTEGER NOT NULL, anchorJson TEXT NOT NULL, anchorKey TEXT NOT NULL, selectionKey TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, highlightColor TEXT, attachedNote TEXT, revision INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pdf_annotations_bookId_contentSha256_selectionKey ON pdf_annotations (bookId, contentSha256, selectionKey)")
            }
        }
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing single-node records stay byte-for-byte unchanged; no paths are inferred.
                db.execSQL("CREATE TABLE IF NOT EXISTS epub_range_passages (id TEXT NOT NULL, bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, anchorVersion INTEGER NOT NULL, resourceHref TEXT NOT NULL, anchorJson TEXT NOT NULL, anchorKey TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, highlightColor TEXT, attachedNote TEXT, revision INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_epub_range_passages_bookId_contentSha256_anchorKey ON epub_range_passages (bookId, contentSha256, anchorKey)")
            }
        }
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Old PDFs retain the original default view until a user explicitly saves a choice.
                db.execSQL("CREATE TABLE IF NOT EXISTS book_pdf_view_preferences (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, horizontalPercent INTEGER NOT NULL, verticalPercent INTEGER NOT NULL, fitMode TEXT NOT NULL, doublePagePreferred INTEGER NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Old books gain no inferred reading time. Each cumulative receipt has one identity.
                db.execSQL("CREATE TABLE IF NOT EXISTS reading_duration_receipts (id TEXT NOT NULL, bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, localDate TEXT NOT NULL, zoneId TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, elapsedMillis INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_duration_receipts_bookId_contentSha256_localDate ON reading_duration_receipts (bookId, contentSha256, localDate)")
            }
        }
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing saved passages remain plain: no implicit color or source-linked note.
                db.execSQL("ALTER TABLE epub_passages ADD COLUMN highlightColor TEXT")
                db.execSQL("ALTER TABLE epub_passages ADD COLUMN attachedNote TEXT")
                db.execSQL("ALTER TABLE epub_passages ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS book_covers (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, coverId TEXT NOT NULL, storageName TEXT NOT NULL, mediaType TEXT NOT NULL, byteSize INTEGER NOT NULL, sha256 TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_book_covers_coverId ON book_covers (coverId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_book_covers_storageName ON book_covers (storageName)")
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Missing organization rows represent the untouched default organization.
                db.execSQL("CREATE TABLE IF NOT EXISTS book_organizations (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, readingStatus TEXT NOT NULL, favorite INTEGER NOT NULL, series TEXT, genre TEXT, tagsJson TEXT NOT NULL, collectionsJson TEXT NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep all existing books, progress, vocabulary, and notes in place.
                db.execSQL("CREATE TABLE IF NOT EXISTS epub_passages (id TEXT NOT NULL, bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, anchorVersion INTEGER NOT NULL, resourceHref TEXT NOT NULL, nodeTextSha256 TEXT NOT NULL, startOffset INTEGER NOT NULL, endOffset INTEGER NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_epub_passages_bookId_contentSha256_anchorVersion_resourceHref_nodeTextSha256_startOffset_endOffset ON epub_passages (bookId, contentSha256, anchorVersion, resourceHref, nodeTextSha256, startOffset, endOffset)")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing book-wide notes remain unanchored. No row is rebuilt or dropped.
                db.execSQL("ALTER TABLE book_notes ADD COLUMN pdfPageIndex INTEGER")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS book_notes (id TEXT NOT NULL, bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_notes_bookId_contentSha256 ON book_notes (bookId, contentSha256)")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS saved_words (id TEXT NOT NULL, bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, english TEXT NOT NULL, turkish TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_saved_words_bookId_contentSha256 ON saved_words (bookId, contentSha256)")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS book_epub_line_spacing_overrides (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, lineHeightPercent INTEGER, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS book_epub_margin_overrides (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, marginsPercent INTEGER NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS book_epub_font_overrides (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, font TEXT NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS book_epub_font_size_overrides (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, fontSizePercent INTEGER NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS book_epub_theme_overrides (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, theme TEXT NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id TEXT NOT NULL, bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, locationKey TEXT NOT NULL, label TEXT NOT NULL, locatorJson TEXT NOT NULL, resourceHref TEXT NOT NULL, locatorVersion INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bookmarks_bookId_contentSha256_locationKey ON bookmarks (bookId, contentSha256, locationKey)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reading_progress (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, locatorVersion INTEGER NOT NULL, locatorJson TEXT NOT NULL, resourceHref TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS observed_text (bookId TEXT NOT NULL, contentSha256 TEXT NOT NULL, extractionVersion INTEGER NOT NULL, resourceHref TEXT NOT NULL, nodePath TEXT NOT NULL, startOffset INTEGER NOT NULL, endOffset INTEGER NOT NULL, textSha256 TEXT NOT NULL, PRIMARY KEY(bookId, contentSha256, extractionVersion, resourceHref, nodePath, startOffset, endOffset, textSha256), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
    }
}

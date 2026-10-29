package com.yaprak.reader.data

import com.yaprak.reader.data.persistence.RoomBookTextAlignmentRepository
import com.yaprak.reader.data.persistence.RoomBookReadingModeRepository
import com.yaprak.reader.domain.settings.BookReadingMode
import com.yaprak.reader.domain.settings.ReadingTextAlignment

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.domain.settings.BookSpacingChoice
import com.yaprak.reader.domain.settings.BookSpacingSettings
import com.yaprak.reader.data.persistence.RoomBookSpacingRepository
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomBookAppearanceResetRepository
import com.yaprak.reader.data.persistence.RoomBookFontRepository
import com.yaprak.reader.data.persistence.RoomBookFontSizeRepository
import com.yaprak.reader.data.persistence.RoomBookLineHeightRepository
import com.yaprak.reader.data.persistence.RoomBookMarginsRepository
import com.yaprak.reader.data.persistence.RoomBookThemeRepository
import com.yaprak.reader.data.persistence.RoomBookmarkRepository
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.data.persistence.RoomReadingStateRepository
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ObservedTextSpan
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.settings.BookLineHeightChoice
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.domain.settings.ReadingTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BookAppearanceResetPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val book = Book("book", "Existing", null, BookFormat.EPUB, "book.epub", "book.epub", 100, "hash", 1)
    private val other = book.copy(id = "other", title = "Other", storageName = "other.epub", sha256 = "other-hash")

    @Test fun `reset preserves data after reopen`() = runBlocking {
        val name = "ar-${UUID.randomUUID().toString().take(8)}.db"
        try {
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                val library = RoomLibraryRepository(db.books())
                library.add(book)
                library.add(other)
                seedAppearance(db, book)
                seedAppearance(db, other)
                val reading = RoomReadingStateRepository(db)
                listOf(book, other).forEach { current ->
                    reading.record(current.id, current.sha256, ReadingSnapshot(
                        ReadingLocation("saved-${current.id}", "one.xhtml"),
                        listOf(ObservedTextSpan("one.xhtml", "0/0", 0, 5, "a".repeat(64)))
                    ))
                    RoomBookmarkRepository(db).add(current.id, current.sha256,
                        BookmarkTarget("Bookmark ${current.id}", ReadingLocation("saved-${current.id}", "one.xhtml")))
                }
                RoomBookAppearanceResetRepository(db).reset(book.id, book.sha256)
                assertClearedAndPreserved(db)
            }
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
                assertClearedAndPreserved(db)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun `reset rejects changed hash PDF and missing book without deleting choices`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            val pdf = book.copy(id = "pdf", title = "PDF", format = BookFormat.PDF,
                storageName = "pdf.pdf", originalName = "pdf.pdf", sha256 = "pdf-hash", pageCount = 2)
            val library = RoomLibraryRepository(db.books())
            library.add(book)
            library.add(pdf)
            seedAppearance(db, book)
            val reset = RoomBookAppearanceResetRepository(db)
            assertNotNull(runCatching { reset.reset(book.id, "changed-hash") }.exceptionOrNull())
            assertNotNull(runCatching { reset.reset(pdf.id, pdf.sha256) }.exceptionOrNull())
            assertNotNull(runCatching { reset.reset("missing", "hash") }.exceptionOrNull())
            assertAppearancePresent(db, book)
            assertEquals(pdf, library.findById(pdf.id))
        }
    }

    @Test fun `failure deleting last choice rolls back all nine preferences`() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
            RoomLibraryRepository(db.books()).add(book)
            seedAppearance(db, book)
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("""
                CREATE TRIGGER reject_book_spacing_reset
                BEFORE DELETE ON book_epub_reading_mode_overrides
                BEGIN SELECT RAISE(ABORT, 'simulated delete failure'); END
            """.trimIndent())
            val reset = RoomBookAppearanceResetRepository(db)
            assertNotNull(runCatching { reset.reset(book.id, book.sha256) }.exceptionOrNull())
            assertAppearancePresent(db, book)
            sqlite.execSQL("DROP TRIGGER reject_book_spacing_reset")
            reset.reset(book.id, book.sha256)
            assertAppearanceInherited(db, book)
        }
    }

    private suspend fun seedAppearance(db: LibraryDatabase, current: Book) {
        RoomBookReadingModeRepository(db).save(current.id, current.sha256, BookReadingMode.SCROLL)
        RoomBookTextAlignmentRepository(db).save(current.id, current.sha256, ReadingTextAlignment.JUSTIFY)
        RoomBookThemeRepository(db).save(current.id, current.sha256, ReadingTheme.SEPIA)
        RoomBookFontSizeRepository(db).save(current.id, current.sha256, 125)
        RoomBookFontRepository(db).save(current.id, current.sha256, ReadingFont.SERIF)
        RoomBookMarginsRepository(db).save(current.id, current.sha256, 150)
        RoomBookLineHeightRepository(db).save(current.id, current.sha256, BookLineHeightChoice.Publisher)
        RoomBookSpacingRepository(db).save(current.id, current.sha256, BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Publisher))
    }

    private suspend fun assertAppearancePresent(db: LibraryDatabase, current: Book) {
        assertEquals(BookReadingMode.SCROLL, RoomBookReadingModeRepository(db).observe(current.id, current.sha256).first())
        assertEquals(ReadingTextAlignment.JUSTIFY, RoomBookTextAlignmentRepository(db).observe(current.id, current.sha256).first())
        assertEquals(BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Publisher), RoomBookSpacingRepository(db).observe(current.id, current.sha256).first())
        assertEquals(ReadingTheme.SEPIA, RoomBookThemeRepository(db).observe(current.id, current.sha256).first())
        assertEquals(125, RoomBookFontSizeRepository(db).observe(current.id, current.sha256).first())
        assertEquals(ReadingFont.SERIF, RoomBookFontRepository(db).observe(current.id, current.sha256).first())
        assertEquals(150, RoomBookMarginsRepository(db).observe(current.id, current.sha256).first())
        assertEquals(BookLineHeightChoice.Publisher, RoomBookLineHeightRepository(db).observe(current.id, current.sha256).first())
    }

    private suspend fun assertAppearanceInherited(db: LibraryDatabase, current: Book) {
        assertNull(RoomBookReadingModeRepository(db).observe(current.id, current.sha256).first())
        assertNull(RoomBookTextAlignmentRepository(db).observe(current.id, current.sha256).first())
        assertEquals(BookSpacingSettings(), RoomBookSpacingRepository(db).observe(current.id, current.sha256).first())
        assertNull(RoomBookThemeRepository(db).observe(current.id, current.sha256).first())
        assertNull(RoomBookFontSizeRepository(db).observe(current.id, current.sha256).first())
        assertNull(RoomBookFontRepository(db).observe(current.id, current.sha256).first())
        assertNull(RoomBookMarginsRepository(db).observe(current.id, current.sha256).first())
        assertEquals(BookLineHeightChoice.Inherit,
            RoomBookLineHeightRepository(db).observe(current.id, current.sha256).first())
        listOf("book_epub_theme_overrides", "book_epub_font_size_overrides", "book_epub_font_overrides",
            "book_epub_margin_overrides", "book_epub_line_spacing_overrides", "book_epub_spacing_overrides", "book_epub_text_alignment_overrides", "book_epub_reading_mode_overrides").forEach { table ->
            assertEquals(0L, db.openHelper.writableDatabase.compileStatement(
                "SELECT COUNT(*) FROM $table WHERE bookId = 'book'"
            ).simpleQueryForLong())
        }
    }

    private suspend fun assertClearedAndPreserved(db: LibraryDatabase) {
        assertAppearanceInherited(db, book)
        assertAppearancePresent(db, other)
        val library = RoomLibraryRepository(db.books())
        val reading = RoomReadingStateRepository(db)
        val bookmarks = RoomBookmarkRepository(db)
        listOf(book, other).forEach { current ->
            assertEquals(current, library.findById(current.id))
            assertEquals("saved-${current.id}", reading.resume(current.id, current.sha256)?.locatorJson)
            assertEquals(1, db.reading().observed(current.id, current.sha256).size)
            assertEquals("Bookmark ${current.id}", bookmarks.observe(current.id, current.sha256).first().single().target.label)
        }
    }
}

package com.yaprak.reader.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.data.persistence.RoomReadingDurationRepository
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.statistics.ReadingDurationReceipt
import com.yaprak.reader.domain.statistics.readingPageKey
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadingPagesPersistenceTest {
    private val hash = "a".repeat(64)
    private val book = Book("book", "Kitap", null, BookFormat.PDF, "b.pdf", "b.pdf", 10, hash, 1, 50)

    @Test fun pageKeysUseRealPagesOrReadiumPositionsAndNeverGuess() {
        assertEquals("page:3", readingPageKey(ReadingLocation("""{"format":"pdf","version":1,"pageIndex":3}""", "pdf")))
        assertEquals("page:0", readingPageKey(ReadingLocation("""{"format":"cbz","version":1,"pageIndex":0,"entry":"1.png"}""", "cbz/1.png")))
        assertEquals("position:12", readingPageKey(ReadingLocation("""{"href":"c.xhtml","locations":{"position":12,"progression":0.2}}""", "c.xhtml")))
        assertNull(readingPageKey(ReadingLocation("""{"href":"c.xhtml","locations":{"progression":0.2}}""", "c.xhtml")))
        assertNull(readingPageKey(ReadingLocation("not json", "x")))
    }

    @Test fun pagesAdvanceByMaxPerReceiptAndOldReceiptsStayUnknown() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            RoomLibraryRepository(db.books()).add(book)
            val repository = RoomReadingDurationRepository(db)
            fun receipt(id: String, date: String, elapsed: Long, pages: Int?): ReadingDurationReceipt {
                val start = java.time.LocalDate.parse(date).atTime(10, 0)
                    .atZone(java.time.ZoneId.of("Europe/Istanbul")).toInstant().toEpochMilli()
                return ReadingDurationReceipt(id, book.id, hash, date, "Europe/Istanbul", start, start + elapsed, elapsed, pages)
            }
            val counted = UUID.randomUUID().toString(); val unknown = UUID.randomUUID().toString()
            repository.record(receipt(counted, "2026-11-20", 60_000, 4))
            repository.record(receipt(counted, "2026-11-20", 120_000, 7))
            repository.record(receipt(counted, "2026-11-20", 120_000, 5)) // a late, smaller checkpoint never lowers it
            repository.record(receipt(unknown, "2026-11-21", 30_000, null))
            val snapshot = repository.observe().first()
            assertEquals(7, snapshot.days.single { it.localDate == "2026-11-20" }.pages)
            assertNull(snapshot.days.single { it.localDate == "2026-11-21" }.pages)
            assertEquals(7, snapshot.books.single().pages)
            assertEquals(150_000L, snapshot.totalMillis)
        } finally { db.close() }
    }
}

package com.yaprak.reader.data

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.statistics.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ReadingDurationContractsTest {
    private val start = Instant.parse("2026-10-05T09:00:00Z").toEpochMilli()
    private val receipt = ReadingDurationReceipt("0df6c356-6a9d-4de6-af38-4ca89e7f4f76", "book",
        "a".repeat(64), "2026-10-05", "Europe/Istanbul", start, start + 5_000, 5_000)

    @Test fun canonicalIdentityAndRealDateRequired() {
        validateReadingDurationReceipt(receipt)
        listOf(receipt.copy(id = "0DF6C356-6A9D-4DE6-AF38-4CA89E7F4F76"),
            receipt.copy(id = "1-1-1-1-1"), receipt.copy(bookId = "\n"),
            receipt.copy(bookId = "b".repeat(201)), receipt.copy(contentSha256 = "A".repeat(64)),
            receipt.copy(localDate = "2026-02-30"), receipt.copy(localDate = "2026-10-06"),
            receipt.copy(localDate = "2026-1-5"), receipt.copy(zoneId = "missing/zone"),
            receipt.copy(zoneId = ""), receipt.copy(zoneId = "UTC\n")).forEach(::invalid)
    }

    @Test fun elapsedIsMonotonicAndNotInventedFromWallClock() {
        validateReadingDurationReceipt(receipt.copy(endedAt = start, elapsedMillis = 9_000))
        listOf(receipt.copy(elapsedMillis = 0), receipt.copy(elapsedMillis = -1),
            receipt.copy(elapsedMillis = MAX_READING_DURATION_MILLIS + 1),
            receipt.copy(startedAt = -1), receipt.copy(endedAt = start - 1),
            receipt.copy(endedAt = MAX_READING_DURATION_TIMESTAMP + 1)).forEach(::invalid)
        validateReadingDurationReceipt(receipt.copy(elapsedMillis = MAX_READING_DURATION_MILLIS))
    }

    @Test fun civilMidnightEndpointIsAllowedButNextDayIsNot() {
        val midnight = Instant.parse("2026-10-05T21:00:00Z").toEpochMilli()
        validateReadingDurationReceipt(receipt.copy(endedAt = midnight))
        invalid(receipt.copy(endedAt = midnight + 1))
    }

    @Test fun dstDaysUseActualZoneMidnightAndUtcIsAccepted() {
        val springStart = Instant.parse("2026-03-29T00:00:00Z").toEpochMilli()
        val springEnd = Instant.parse("2026-03-29T22:00:00Z").toEpochMilli()
        val spring = receipt.copy(localDate = "2026-03-29", zoneId = "Europe/Berlin",
            startedAt = springStart, endedAt = springEnd, elapsedMillis = 5_000)
        validateReadingDurationReceipt(spring)
        invalid(spring.copy(endedAt = springEnd + 1))
        validateReadingDurationReceipt(receipt.copy(zoneId = "UTC"))
    }

    @Test fun timestampBoundsAndLongAggregateDoNotOverflow() {
        val upper = receipt.copy(localDate = "9999-12-31", zoneId = "UTC",
            startedAt = MAX_READING_DURATION_TIMESTAMP, endedAt = MAX_READING_DURATION_TIMESTAMP,
            elapsedMillis = 1)
        validateReadingDurationReceipt(upper)
        invalid(upper.copy(startedAt = MAX_READING_DURATION_TIMESTAMP + 1))
        assertEquals(17_280_000_000_000L, MAX_READING_DURATION_TOTAL_MILLIS)
    }

    @Test fun durableSnapshotOwnsImmutableCopies() {
        val book = Book("book", "Title", null, BookFormat.EPUB, "a.epub", "b.epub", 1,
            "a".repeat(64), 0)
        val days = mutableListOf(ReadingDurationDay("2026-10-05", 5_000))
        val books = mutableListOf(BookReadingDuration(book, 5_000))
        val snapshot = ReadingDurationSnapshot(days, books, 5_000)
        days.clear(); books.clear()
        assertEquals(1, snapshot.days.size); assertEquals(1, snapshot.books.size)
        try { (snapshot.days as MutableList<ReadingDurationDay>).clear(); fail("Mutable snapshot") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(5_000L, snapshot.totalMillis)
    }

    private fun invalid(value: ReadingDurationReceipt) {
        try { validateReadingDurationReceipt(value); fail("Invalid receipt accepted") }
        catch (failure: ReadingDurationException) { assertEquals(ReadingDurationFailure.INVALID_RECORD, failure.reason) }
    }
}

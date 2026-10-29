package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.statistics.*
import com.yaprak.reader.feature.statistics.*
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class ReadingStatisticsViewModelTest {
    private val book = Book("book", "Kitap 🌿", "Deniz", BookFormat.EPUB, "book.epub", "book.epub", 1, "a".repeat(64), 1)
    private val other = book.copy(id = "other", title = "Sıfır süreli kitap")
    private val initial = ZonedDateTime.parse("2026-10-05T12:00:00+03:00[Europe/Istanbul]")
    private fun snapshot(vararg days: Pair<String, Long>): ReadingDurationSnapshot {
        val total = days.sumOf { it.second }
        return ReadingDurationSnapshot(days.map { ReadingDurationDay(it.first, it.second) },
            listOf(BookReadingDuration(book, total), BookReadingDuration(other, 0)), total)
    }
    private inner class Repository : ReadingDurationRepository {
        val source = MutableStateFlow(snapshot("2026-10-05" to 61_999L, "2026-10-04" to 1_001L,
            "2026-09-29" to 2_000L, "2026-09-28" to 4_000L, "2026-10-06" to 8_000L))
        var subscriptions = 0
        var activeCollectors = 0
        var records = 0
        var failure: ReadingDurationFailure? = null
        var flowOverride: (() -> Flow<ReadingDurationSnapshot>)? = null
        override fun observe(): Flow<ReadingDurationSnapshot> = flowOverride?.invoke() ?: flow {
            subscriptions++; activeCollectors++
            try {
                failure?.let { throw ReadingDurationException(it) }
                source.collect { emit(it) }
            } finally { activeCollectors-- }
        }
        override suspend fun record(receipt: ReadingDurationReceipt) { records++ }
    }
    private class MutableClock(var now: ZonedDateTime) { fun read() = now }

    private suspend fun TestScope.withModel(active: Boolean = true,
        start: ZonedDateTime = initial,
        block: suspend TestScope.(ReadingStatisticsViewModel, Repository, MutableClock) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val source = Repository()
        val clock = MutableClock(start)
        try {
            val model = ReadingStatisticsViewModel(source, clock::read).also { store.put("stats", it) }
            if (active) model.setActive(true)
            runCurrent()
            block(model, source, clock)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun `seven day average uses fixed calendar denominator and current status counts zero duration completed books`() = runTest {
        withModel { model, repository, clock ->
            assertEquals(9_285L, model.state.value.dailyAverageMillis)
            assertEquals(3, model.state.value.readingDaysLastSeven)
            assertEquals(0, model.state.value.completedBookCount)
            val existing = repository.source.value
            repository.source.value = ReadingDurationSnapshot(existing.days, listOf(
                BookReadingDuration(book, existing.totalMillis), BookReadingDuration(other.copy(organization =
                    com.yaprak.reader.domain.library.BookOrganization(readingStatus = com.yaprak.reader.domain.library.BookReadingStatus.COMPLETED)), 0)), existing.totalMillis)
            runCurrent(); assertEquals(1, model.state.value.completedBookCount); assertEquals(9_285L, model.state.value.dailyAverageMillis)
            clock.now = initial.plusDays(1); model.refreshDate(); runCurrent()
            assertEquals(10_142L, model.state.value.dailyAverageMillis); assertEquals(3, model.state.value.readingDaysLastSeven)
            assertEquals(1, repository.subscriptions); assertEquals(0, repository.records)
        }
    }

    @Test fun `reading series reperiods stored civil days across date and zone without new records or subscriptions`() = runTest {
        withModel { model, repository, clock ->
            val saved = model.state.value.snapshot
            assertEquals(ReadingStreak(2, 2, initial.toLocalDate()), model.state.value.readingStreak)
            clock.now = initial.plusDays(1); model.refreshDate(); runCurrent()
            assertEquals(ReadingStreak(3, 3, initial.toLocalDate().plusDays(1)), model.state.value.readingStreak)
            clock.now = ZonedDateTime.parse("2026-10-07T00:10:00+09:00[Asia/Tokyo]")
            model.refreshDate(); runCurrent()
            assertEquals(ReadingStreak(3, 3, LocalDate.parse("2026-10-06")), model.state.value.readingStreak)
            clock.now = initial.minusDays(1); model.refreshDate(); runCurrent()
            assertEquals(ReadingStreak(1, 2, LocalDate.parse("2026-10-04")), model.state.value.readingStreak)
            assertSame(saved, model.state.value.snapshot); assertEquals(1, repository.subscriptions); assertEquals(0, repository.records)
        }
    }

    @Test fun `series follows only the current valid snapshot and is unavailable while paused loading or failed`() = runTest {
        withModel { model, repository, _ ->
            repository.source.value = snapshot("2026-10-04" to 1L, "2026-10-05" to 0L, "2026-10-06" to 1L)
            runCurrent(); assertEquals(ReadingStreak(1, 1, LocalDate.parse("2026-10-04")), model.state.value.readingStreak)
            repository.source.value = snapshot("2026-10-05" to 0L)
            runCurrent(); assertEquals(ReadingStreak(0, 0, null), model.state.value.readingStreak)
            model.setActive(false); assertNull(model.state.value.readingStreak)
            repository.failure = ReadingDurationFailure.RECOVERY
            model.setActive(true); assertNull(model.state.value.readingStreak); runCurrent()
            assertEquals(ReadingDurationFailure.RECOVERY, model.state.value.failure); assertNull(model.state.value.readingStreak)
            repository.failure = null; model.retry(); assertNull(model.state.value.readingStreak); runCurrent()
            assertEquals(ReadingStreak(0, 0, null), model.state.value.readingStreak); assertEquals(0, repository.records)
        }
    }

    @Test fun `completed status removal updates count without inferring completion from time and missing snapshot stays unavailable`() = runTest {
        withModel { model, repository, _ ->
            val existing = repository.source.value
            val completed = book.copy(organization = com.yaprak.reader.domain.library.BookOrganization(
                readingStatus = com.yaprak.reader.domain.library.BookReadingStatus.COMPLETED))
            repository.source.value = ReadingDurationSnapshot(emptyList(), listOf(BookReadingDuration(completed, 0)), 0)
            runCurrent(); assertEquals(1, model.state.value.completedBookCount); assertEquals(0, model.state.value.readingDaysLastSeven)
            repository.source.value = existing; runCurrent(); assertEquals(0, model.state.value.completedBookCount)
            model.setActive(false); assertNull(model.state.value.snapshot); assertEquals(0, repository.records)
        }
    }

    @Test fun `today and last seven include only recorded local labels and total preserves older and future records`() = runTest {
        withModel { model, repository, _ ->
            assertEquals(61_999L, model.state.value.todayMillis)
            assertEquals(LocalDate.parse("2026-10-05"), model.state.value.weekStart)
            assertEquals(61_999L, model.state.value.currentWeekMillis)
            assertEquals(65_000L, model.state.value.lastSevenMillis)
            assertEquals(77_000L, model.state.value.snapshot!!.totalMillis)
            assertEquals(0, repository.records)
            assertEquals(1, repository.subscriptions)
            assertEquals(listOf(book, other), model.state.value.snapshot!!.books.map { it.book })
            val calendar = model.state.value.calendar
            assertEquals(28, calendar.size)
            assertEquals(LocalDate.parse("2026-10-05"), calendar.first().date)
            assertEquals(LocalDate.parse("2026-09-08"), calendar.last().date)
            assertEquals(61_999L, calendar.first().elapsedMillis)
            assertNull(calendar.single { it.date == LocalDate.parse("2026-10-03") }.elapsedMillis)
        }
    }

    @Test fun `date and zone refresh reperiod same saved snapshot without another database subscription`() = runTest {
        withModel { model, repository, clock ->
            val saved = model.state.value.snapshot
            clock.now = ZonedDateTime.parse("2026-10-06T00:10:00+09:00[Asia/Tokyo]")
            model.refreshDate(); runCurrent()
            assertSame(saved, model.state.value.snapshot)
            assertEquals("Asia/Tokyo", model.state.value.zoneId)
            assertEquals(LocalDate.parse("2026-10-06"), model.state.value.today)
            assertEquals(8_000L, model.state.value.todayMillis)
            assertEquals(69_999L, model.state.value.currentWeekMillis)
            assertEquals(71_000L, model.state.value.lastSevenMillis)
            assertEquals(77_000L, model.state.value.snapshot!!.totalMillis)
            assertEquals(1, repository.subscriptions)
            assertEquals("2026-10-05", model.state.value.snapshot!!.days.first().localDate)
            clock.now = initial.minusDays(50)
            model.refreshDate(); runCurrent()
            assertEquals(0L, model.state.value.todayMillis)
            assertEquals(0L, model.state.value.lastSevenMillis)
            assertEquals(77_000L, model.state.value.snapshot!!.totalMillis)
            assertEquals(1, repository.subscriptions)
        }
    }

    @Test fun `scheduled midnight recomputes today and DST midnight uses civil day duration`() = runTest {
        val start = ZonedDateTime.parse("2026-03-29T00:00:00+01:00[Europe/Berlin]")
        withModel(start = start) { model, repository, clock ->
            repository.source.value = snapshot("2026-03-29" to 5_000L, "2026-03-30" to 1_000L)
            runCurrent()
            assertEquals(5_000L, model.state.value.currentWeekMillis)
            val next = start.toLocalDate().plusDays(1).atStartOfDay(start.zone)
            val wait = Duration.between(start.toInstant(), next.toInstant()).toMillis()
            assertEquals(23L * 60L * 60L * 1000L, wait)
            clock.now = next
            advanceTimeBy(wait - 1); runCurrent()
            assertEquals(start.toLocalDate(), model.state.value.today)
            advanceTimeBy(1); runCurrent()
            assertEquals(next.toLocalDate(), model.state.value.today)
            assertEquals(LocalDate.parse("2026-03-30"), model.state.value.weekStart)
            assertEquals(1_000L, model.state.value.currentWeekMillis)
            assertEquals(0, repository.records)
            assertEquals(1, repository.subscriptions)
        }
        val autumn = ZonedDateTime.parse("2026-10-25T00:00:00+02:00[Europe/Berlin]")
        assertEquals(25L * 60L * 60L * 1000L,
            Duration.between(autumn.toInstant(), autumn.toLocalDate().plusDays(1).atStartOfDay(autumn.zone).toInstant()).toMillis())
    }

    @Test fun `pause cancels date clock and observation while resume rechecks current zone and persisted source`() = runTest {
        withModel { model, repository, clock ->
            model.setActive(false); runCurrent()
            assertEquals(0, repository.activeCollectors)
            val oldDate = model.state.value.today
            clock.now = initial.plusDays(3)
            advanceTimeBy(3L * 24L * 60L * 60L * 1000L); runCurrent()
            assertEquals(oldDate, model.state.value.today)
            repository.source.value = snapshot("2026-10-08" to 2_500L)
            model.refreshDate(); model.retry(); runCurrent()
            assertEquals(1, repository.subscriptions)
            model.setActive(true); runCurrent()
            assertEquals(clock.now.toLocalDate(), model.state.value.today)
            assertEquals(2_500L, model.state.value.todayMillis)
            assertEquals(2, repository.subscriptions)
        }
    }

    @Test fun `old Activity clock pause and disposal cannot change latest owner or refresh its period`() = runTest {
        withModel { model, repository, clock ->
            val first = Any(); val second = Any()
            model.attachLifecycleOwner(first); model.setLifecycleOwnerActive(first, true); runCurrent()
            model.attachLifecycleOwner(second); model.setLifecycleOwnerActive(second, true); runCurrent()
            val subscriptions = repository.subscriptions
            val before = model.state.value.today
            clock.now = initial.plusDays(2)
            model.refreshDate(first); model.setLifecycleOwnerActive(first, false); model.detachLifecycleOwner(first); runCurrent()
            assertTrue(model.isLifecycleOwnerActive(second))
            assertEquals(before, model.state.value.today)
            assertEquals(subscriptions, repository.subscriptions)
            model.refreshDate(second); runCurrent()
            assertEquals(clock.now.toLocalDate(), model.state.value.today)
            assertEquals(subscriptions, repository.subscriptions)
        }
    }

    @Test fun `all typed source failures remain distinct from empty results and exact retry revision reloads`() = runTest {
        withModel { model, repository, _ ->
            for (failure in ReadingDurationFailure.entries) {
                repository.failure = failure
                model.retry(); runCurrent()
                assertEquals(failure, model.state.value.failure)
                assertNull(model.state.value.snapshot)
                assertFalse(model.state.value.loading)
                val revision = model.state.value.revision
                repository.failure = null
                model.retry(revision - 1); runCurrent()
                assertEquals(failure, model.state.value.failure)
                model.retry(revision); runCurrent()
                assertNull(model.state.value.failure)
                assertNotNull(model.state.value.snapshot)
            }
        }
    }

    @Test fun `duplicate dates invalid totals and malformed calendar data fail visibly instead of inventing period counts`() = runTest {
        withModel { model, repository, _ ->
            val invalid = listOf(
                ReadingDurationSnapshot(listOf(ReadingDurationDay("2026-10-05", 1), ReadingDurationDay("2026-10-05", 1)),
                    listOf(BookReadingDuration(book, 2)), 2),
                ReadingDurationSnapshot(listOf(ReadingDurationDay("2026-02-30", 1)), listOf(BookReadingDuration(book, 1)), 1),
                ReadingDurationSnapshot(listOf(ReadingDurationDay("2026-10-05", -1)), listOf(BookReadingDuration(book, -1)), -1),
                ReadingDurationSnapshot(listOf(ReadingDurationDay("2026-10-05", 1)), listOf(BookReadingDuration(book, 2)), 1),
                ReadingDurationSnapshot(emptyList(), listOf(BookReadingDuration(book, 0), BookReadingDuration(book, 0)), 0))
            invalid.forEach { value ->
                repository.source.value = value
                model.retry(); runCurrent()
                assertEquals(ReadingDurationFailure.SOURCE, model.state.value.failure)
                assertNull(model.state.value.snapshot)
            }
            repository.source.value = snapshot()
            model.retry(); runCurrent()
            assertEquals(0L, model.state.value.todayMillis)
            assertEquals(0L, model.state.value.snapshot!!.totalMillis)
            assertNull(model.state.value.failure)
            assertTrue(model.state.value.calendar.all { it.elapsedMillis == null })
        }
    }

    @Test fun `late cancellation ignoring source cannot overwrite a replacement generation or paused screen`() = runTest {
        withModel(active = false) { model, repository, _ ->
            val release = CompletableDeferred<Unit>()
            val old = snapshot("2026-10-05" to 999L)
            repository.flowOverride = { object : Flow<ReadingDurationSnapshot> {
                override suspend fun collect(collector: FlowCollector<ReadingDurationSnapshot>) {
                    withContext(NonCancellable) { release.await(); collector.emit(old) }
                }
            } }
            model.setActive(true); runCurrent()
            repository.flowOverride = null
            model.setActive(false); model.setActive(true); runCurrent()
            val fresh = model.state.value.snapshot
            release.complete(Unit); runCurrent()
            assertSame(fresh, model.state.value.snapshot)
            assertEquals(61_999L, model.state.value.todayMillis)
            assertEquals(0, repository.records)
        }
    }

    @Test fun `duration formatting parts retain Long hours and never round fractional seconds upward`() {
        assertEquals(ReadingDurationParts(0, 0, 0), readingDurationParts(0))
        assertEquals(ReadingDurationParts(0, 0, 0), readingDurationParts(999))
        assertEquals(ReadingDurationParts(0, 0, 1), readingDurationParts(1_999))
        assertEquals(ReadingDurationParts(0, 1, 1), readingDurationParts(61_999))
        assertEquals(ReadingDurationParts(1, 1, 1), readingDurationParts(3_661_999))
        assertEquals(4_800_000L, readingDurationParts(MAX_READING_DURATION_TOTAL_MILLIS).hours)
        assertEquals(Long.MAX_VALUE / 1000L / 3600L, readingDurationParts(Long.MAX_VALUE).hours)
        assertThrows(IllegalArgumentException::class.java) { readingDurationParts(-1) }
    }
}

package com.yaprak.reader.feature.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.statistics.*
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ReadingCalendarDay(val date: LocalDate, val elapsedMillis: Long?)
internal data class ReadingStatisticsState(
    val today: LocalDate,
    val zoneId: String,
    val snapshot: ReadingDurationSnapshot? = null,
    val loading: Boolean = true,
    val failure: ReadingDurationFailure? = null,
    val active: Boolean = false,
    val revision: Long = 0,
) {
    val readingStreak: ReadingStreak? get() =
        if (active && !loading && failure == null) snapshot?.let { readingStreak(it.days, today) } else null
    val weekStart: LocalDate get() = today.minusDays((today.dayOfWeek.value - 1).toLong())
    /** Monday through today; future stored dates cannot contribute to the current goal. */
    val currentWeekMillis: Long get() = snapshot?.days?.filter {
        val date = LocalDate.parse(it.localDate)
        date >= weekStart && date <= today
    }?.sumOf { it.elapsedMillis } ?: 0L
    val todayMillis: Long get() = snapshot?.days?.firstOrNull { it.localDate == today.toString() }?.elapsedMillis ?: 0L
    private val lastSevenDays: List<ReadingDurationDay> get() = snapshot?.days?.filter {
        val date = LocalDate.parse(it.localDate)
        date >= today.minusDays(6) && date <= today
    }.orEmpty()
    val lastSevenMillis: Long get() = lastSevenDays.sumOf { it.elapsedMillis }
    /** All seven calendar days are in the denominator, including days without a saved receipt. */
    val dailyAverageMillis: Long get() = lastSevenMillis / 7
    val readingDaysLastSeven: Int get() = lastSevenDays.count { it.elapsedMillis > 0 }
    /** Current explicit metadata status, not a historical completion date or inferred progress. */
    val completedBookCount: Int get() = snapshot?.books?.count {
        it.book.organization.readingStatus == com.yaprak.reader.domain.library.BookReadingStatus.COMPLETED
    } ?: 0
    val calendar: List<ReadingCalendarDay> get() {
        val days = snapshot?.days?.associate { it.localDate to it.elapsedMillis }.orEmpty()
        return (0L..27L).map { offset -> today.minusDays(offset).let { ReadingCalendarDay(it, days[it.toString()]) } }
    }
}

/** Only persisted observations reach the UI; a date change reperiods the same immutable snapshot. */
internal class ReadingStatisticsViewModel(
    private val repository: ReadingDurationRepository,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) : ViewModel() {
    private val initialDate = clock()
    private val mutable = MutableStateFlow(ReadingStatisticsState(initialDate.toLocalDate(), initialDate.zone.id))
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var midnight: Job? = null
    private var generation = 0L
    private var clockGeneration = 0L
    private var owner: Any? = null

    fun attachLifecycleOwner(value: Any) {
        if (owner === value) return
        owner = value
        setActive(false)
    }
    fun setLifecycleOwnerActive(value: Any, active: Boolean) {
        if (owner === value) setActive(active)
    }
    fun isLifecycleOwnerActive(value: Any) = owner === value && state.value.active
    fun detachLifecycleOwner(value: Any) {
        if (owner !== value) return
        owner = null
        setActive(false)
    }
    fun setActive(active: Boolean) {
        if (state.value.active == active) return
        mutable.update { it.copy(active = active) }
        if (active) refreshDate() else { clockGeneration++; midnight?.cancel(); midnight = null }
        observe()
    }
    fun refreshDate(expectedOwner: Any? = owner) {
        if (expectedOwner !== owner || !state.value.active) return
        val now = clock()
        mutable.update { it.copy(today = now.toLocalDate(), zoneId = now.zone.id) }
        val request = ++clockGeneration
        midnight?.cancel()
        val waitMillis = Duration.between(now.toInstant(), now.toLocalDate().plusDays(1)
            .atStartOfDay(now.zone).toInstant()).toMillis().coerceAtLeast(1L)
        midnight = viewModelScope.launch {
            delay(waitMillis)
            if (request == clockGeneration && owner === expectedOwner && state.value.active) refreshDate(expectedOwner)
        }
    }
    fun retry(revision: Long = state.value.revision) {
        if (state.value.active && state.value.revision == revision) observe()
    }

    private fun observe() {
        val request = ++generation
        observation?.cancel()
        observation = null
        mutable.update { it.copy(snapshot = null, loading = true, failure = null, revision = request) }
        if (!state.value.active) return
        observation = viewModelScope.launch {
            try {
                repository.observe().collect { value ->
                    if (request != generation || !state.value.active) return@collect
                    validateSnapshot(value)
                    val snapshot = ReadingDurationSnapshot(value.days, value.books, value.totalMillis)
                    mutable.update { it.copy(snapshot = snapshot, loading = false, failure = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (request == generation && state.value.active)
                    mutable.update { it.copy(snapshot = null, loading = false,
                        failure = (failure as? ReadingDurationException)?.reason ?: ReadingDurationFailure.SOURCE) }
            }
        }
    }

    private fun validateSnapshot(value: ReadingDurationSnapshot) {
        require(value.totalMillis in 0..MAX_READING_DURATION_TOTAL_MILLIS)
        require(value.days.map { it.localDate }.distinct().size == value.days.size)
        require(value.books.map { it.book.id }.distinct().size == value.books.size)
        value.days.forEach {
            require(it.localDate.length == 10 && LocalDate.parse(it.localDate).toString() == it.localDate)
            require(it.elapsedMillis in 0..MAX_READING_DURATION_TOTAL_MILLIS)
        }
        value.books.forEach { require(it.totalMillis in 0..MAX_READING_DURATION_TOTAL_MILLIS) }
        require(value.days.fold(0L) { sum, day -> Math.addExact(sum, day.elapsedMillis) } == value.totalMillis)
        require(value.books.fold(0L) { sum, book -> Math.addExact(sum, book.totalMillis) } == value.totalMillis)
    }
}

/** Whole seconds are a lower bound; never round a saved fraction upward or narrow to Int. */
internal data class ReadingDurationParts(val hours: Long, val minutes: Long, val seconds: Long)
internal fun readingDurationParts(millis: Long): ReadingDurationParts {
    require(millis >= 0)
    val seconds = millis / 1000L
    return ReadingDurationParts(seconds / 3600L, seconds / 60L % 60L, seconds % 60L)
}

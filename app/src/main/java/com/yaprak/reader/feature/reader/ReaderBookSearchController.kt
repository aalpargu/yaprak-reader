package com.yaprak.reader.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yaprak.reader.reader.BookSearchException
import com.yaprak.reader.reader.BookSearchFailure
import com.yaprak.reader.reader.BookSearchHit
import com.yaprak.reader.reader.BookTextSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Search iterators remain tracked until completion so the Activity's exit barrier can join them. */
internal class ReaderBookSearchController(private val scope: CoroutineScope,
    private val currentOwner: () -> ReaderNavigationOwner?) {
    var state by mutableStateOf(BookSearchState())
        private set
    private var owner: ReaderNavigationOwner? = null
    private var source: BookTextSearch? = null
    private val jobs = mutableListOf<Job>()
    private var generation = 0L
    val active: Boolean get() = jobs.any { it.isActive }

    private fun isCurrent(): Boolean = owner?.matches(currentOwner()) == true &&
        source != null && currentOwner()?.surface?.bookSearch === source

    fun open(source: BookTextSearch, draft: BookSearchState? = null): Boolean {
        val admitted = currentOwner() ?: return false
        if (admitted.surface.bookSearch !== source) return false
        stopJobs(); generation++
        owner = admitted
        this.source = source
        state = draft ?: BookSearchState(phase = if (source.supported) BookSearchPhase.Idle else BookSearchPhase.Unsupported)
        return true
    }

    fun edit(value: String, requested: Boolean) {
        if (!requested || !isCurrent()) return
        val limited = limitBookSearchQuery(value)
        if (limited == state.query) return
        stopJobs(); generation++
        state = BookSearchState(query = limited,
            phase = if (source?.supported == false) BookSearchPhase.Unsupported else BookSearchPhase.Idle)
    }

    fun run(canSearch: () -> Boolean) {
        val source = source ?: return
        if (!isCurrent() || !canSearch()) return
        if (!source.supported) { state = state.copy(phase = BookSearchPhase.Unsupported, navigationFailed = false); return }
        val query = state.query.trim()
        if (query.isEmpty()) return
        stopJobs()
        val revision = ++generation
        state = state.copy(phase = BookSearchPhase.Searching, navigationFailed = false)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val phase = try {
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || !canSearch()) return@launch
                BookSearchPhase.Ready(source.find(query))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: BookSearchException) { when (failure.reason) {
                BookSearchFailure.UNSUPPORTED -> BookSearchPhase.Unsupported
                BookSearchFailure.LIMIT -> BookSearchPhase.Limit
                else -> BookSearchPhase.Failed
            } }
            catch (_: Exception) { BookSearchPhase.Failed }
            currentCoroutineContext().ensureActive()
            if (revision == generation && this@ReaderBookSearchController.source === source && isCurrent() && canSearch())
                state = state.copy(phase = phase)
        }
        jobs.removeAll { it.isCompleted }
        jobs += job
        job.start()
    }

    fun select(hit: BookSearchHit, canNavigate: () -> Boolean, navigate: (BookTextSearch, BookSearchHit) -> Boolean): Boolean {
        val source = source ?: return false
        val results = (state.phase as? BookSearchPhase.Ready)?.results ?: return false
        if (!isCurrent() || !canNavigate() || results.hits.none { it === hit }) return false
        val accepted = navigate(source, hit)
        if (!accepted && isCurrent() && canNavigate()) state = state.copy(navigationFailed = true)
        return accepted
    }

    fun stopJobs(): List<Job> {
        jobs.removeAll { it.isCompleted }
        return jobs.toList().also { unfinished -> unfinished.forEach { it.cancel() } }
    }

    fun cancel() {
        stopJobs(); generation++
        state = state.copy(phase = BookSearchPhase.Idle, navigationFailed = false)
    }

    fun close() { cancel(); source = null; owner = null; state = BookSearchState() }
}

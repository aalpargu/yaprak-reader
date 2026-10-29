package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.vocabulary.*
import com.yaprak.reader.feature.wordreview.*
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WordReviewViewModelTest {
    private val entry = LibraryWord(SavedWord("word", "leaf", "yaprak", 1), "book", "a".repeat(64), "Kitap", null)
    private val card = WordReviewCard(entry, null)
    private val snapshot = WordReviewSnapshot(listOf(card), 1, 1, 1, emptyList(), 0)
    private val now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
    private inner class Source : WordReviewRepository {
        var result = snapshot
        val commands = mutableListOf<WordReviewCommand>()
        var loadAction: suspend () -> WordReviewSnapshot = { result }
        var rateAction: suspend (WordReviewCommand) -> WordReviewEvent = { command ->
            planWordReview(command, now, ZoneOffset.UTC).also { result = WordReviewSnapshot(emptyList(), 0, 0, 1,
                listOf(WordReviewHistoryEntry(it, "Kitap", false)), 1) }
        }
        override suspend fun load() = loadAction()
        override suspend fun rate(command: WordReviewCommand): WordReviewEvent { commands += command; return rateAction(command) }
    }
    private suspend fun TestScope.fixture(test: suspend TestScope.(WordReviewViewModel, Source, Any) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore(); val source = Source(); val owner = Any()
        try {
            val model = WordReviewViewModel(source).also { models.put("review", it) }
            model.attach(owner); model.active(owner, true); runCurrent()
            test(model, source, owner)
        } finally { models.clear(); Dispatchers.resetMain() }
    }
    private fun reveal(model: WordReviewViewModel) { model.command(model.state.value.revision, "start"); model.command(model.state.value.revision, "reveal") }
    @Test fun openingStartingAndRevealingDoNotRecordAndOnlyCurrentRevealedRatingWritesOnce() = runTest {
        fixture { model, source, _ ->
            model.rate(model.state.value.revision, WordRating.GOOD); assertTrue(source.commands.isEmpty())
            reveal(model); assertTrue(source.commands.isEmpty())
            val revision = model.state.value.revision
            model.rate(revision, WordRating.GOOD); model.rate(revision, WordRating.EASY); runCurrent()
            assertEquals(1, source.commands.size); assertNotNull(model.state.value.receipt); assertNull(model.state.value.current)
            assertEquals(1, model.state.value.snapshot!!.historyCount)
        }
    }
    @Test fun staleRenderedRevisionCannotRateRevealedCardAfterReloadOrAba() = runTest {
        fixture { model, source, _ ->
            reveal(model); val revision = model.state.value.revision
            model.reload(revision); runCurrent(); reveal(model)
            model.rate(revision, WordRating.GOOD); runCurrent(); assertTrue(source.commands.isEmpty())
            model.rate(model.state.value.revision, WordRating.GOOD); runCurrent(); assertEquals(1, source.commands.size)
        }
    }
    @Test fun sameFailedAnswerRetriesItsUuidAndCannotSwitchRatingOrCard() = runTest {
        fixture { model, source, _ ->
            reveal(model); source.rateAction = { throw WordReviewException(WordReviewFailure.SOURCE) }
            model.rate(model.state.value.revision, WordRating.GOOD); runCurrent()
            val first = source.commands.single(); assertEquals(first, model.state.value.pending)
            model.rate(model.state.value.revision, WordRating.EASY); runCurrent(); assertEquals(1, source.commands.size)
            source.rateAction = { planWordReview(it, now, ZoneOffset.UTC).also { source.result = source.result.copy(cards = emptyList(), dueCount = 0, newCount = 0) } }
            model.retry(model.state.value.revision); runCurrent(); assertEquals(first, source.commands.last()); assertNotNull(model.state.value.receipt)
        }
    }
    @Test fun lateNonCancellableRatingAfterPauseCannotRepopulateNewOwnerAndResumeReloadsDurableData() = runTest {
        fixture { model, source, owner ->
            val finish = CompletableDeferred<Unit>(); reveal(model)
            source.rateAction = { command -> withContext(NonCancellable) { finish.await(); planWordReview(command, now, ZoneOffset.UTC) } }
            model.rate(model.state.value.revision, WordRating.GOOD); runCurrent()
            model.active(owner, false); assertNull(model.state.value.snapshot)
            val next = Any(); model.attach(next); model.active(next, true); runCurrent()
            finish.complete(Unit); runCurrent()
            assertNull(model.state.value.receipt); assertNull(model.state.value.pending); assertFalse(model.isActive(owner))
            assertTrue(model.isActive(next)); assertEquals(snapshot, model.state.value.snapshot)
        }
    }
    @Test fun postCommitLoadFailureDoesNotRestoreOldCardOrOfferDuplicateRating() = runTest {
        fixture { model, source, _ ->
            reveal(model); source.loadAction = { throw WordReviewException(WordReviewFailure.SOURCE) }
            model.rate(model.state.value.revision, WordRating.GOOD); runCurrent()
            assertNotNull(model.state.value.receipt); assertNull(model.state.value.snapshot); assertNull(model.state.value.pending)
            model.rate(model.state.value.revision, WordRating.GOOD); runCurrent(); assertEquals(1, source.commands.size)
            source.loadAction = { source.result }; model.reload(model.state.value.revision); runCurrent()
            assertNull(model.state.value.current); assertEquals(1, model.state.value.snapshot!!.historyCount)
        }
    }
    @Test fun invalidSourceCountsAndForgedReceiptAreFailuresAndRealCancellationIsNotSuccess() = runTest {
        fixture { model, source, owner ->
            source.result = snapshot.copy(dueCount = 0)
            model.reload(model.state.value.revision); runCurrent(); assertNull(model.state.value.snapshot)
            source.result = snapshot; model.reload(model.state.value.revision); runCurrent(); reveal(model)
            source.rateAction = { planWordReview(it, now, ZoneOffset.UTC).copy(english = "forged") }
            model.rate(model.state.value.revision, WordRating.GOOD); runCurrent(); assertNull(model.state.value.receipt)
            model.active(owner, false); model.active(owner, true); runCurrent(); reveal(model)
            source.rateAction = { awaitCancellation() }
            model.rate(model.state.value.revision, WordRating.GOOD); runCurrent(); model.detach(owner); runCurrent()
            assertNull(model.state.value.receipt); assertFalse(model.state.value.active)
        }
    }
}

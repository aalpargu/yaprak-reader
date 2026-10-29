package com.yaprak.reader.domain

import com.yaprak.reader.domain.vocabulary.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class WordReviewTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
    private val entry = LibraryWord(SavedWord("leaf", "leaf", "yaprak", 1), "book", "a".repeat(64), "Kitap", null)
    private fun command(rating: WordRating, schedule: WordSchedule? = null, word: LibraryWord = entry) =
        WordReviewCommand(UUID.randomUUID().toString(), WordReviewCard(word, schedule), rating)
    @Test fun firstRatingsHaveDisclosedIntervalsAndOnlyExplicitPlanningProducesAnEvent() {
        val expected = mapOf(WordRating.AGAIN to 600000L, WordRating.HARD to 86400000L,
            WordRating.GOOD to 86400000L, WordRating.EASY to 3 * 86400000L)
        expected.forEach { (rating, interval) ->
            val c = command(rating); val event = planWordReview(c, now, zone)
            assertEquals(interval, event.dueAt - now); assertEquals(1L, event.version)
            assertTrue(event.matches(c)); validateWordReviewEvent(event)
        }
    }
    @Test fun ladderAdvancesAndSaturatesWhileAgainResetsAndHardShortens() {
        var event = planWordReview(command(WordRating.GOOD), now, zone)
        for (step in 1..9) {
            event = planWordReview(command(WordRating.GOOD, event.schedule()), event.dueAt, zone)
            assertEquals(step.coerceAtMost(6), event.step)
        }
        val hard = planWordReview(command(WordRating.HARD, event.schedule()), event.dueAt, zone)
        assertEquals(5, hard.step)
        val again = planWordReview(command(WordRating.AGAIN, hard.schedule()), hard.dueAt, zone)
        assertEquals(-1, again.step); assertEquals(600000L, again.dueAt - again.reviewedAt)
    }
    @Test fun calendarDaysFollowDstButTenMinutesUseElapsedInstantTime() {
        val berlin = ZoneId.of("Europe/Berlin")
        val spring = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli()
        val autumn = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli()
        assertEquals(23 * 3600000L, calculateReviewDue(0, spring, berlin) - spring)
        assertEquals(25 * 3600000L, calculateReviewDue(0, autumn, berlin) - autumn)
        assertEquals(600000L, calculateReviewDue(-1, spring, berlin) - spring)
    }
    @Test fun editsIncludingAbaStartAtFirstStepWhileRetainingScheduleVersion() {
        val prior = planWordReview(command(WordRating.EASY), now, zone).schedule()
        val edited = entry.copy(word = entry.word.copy(turkish = "sayfa", revision = 1))
        val next = planWordReview(command(WordRating.GOOD, prior, edited), now + 1, zone)
        assertEquals(0, next.step); assertEquals(2L, next.version)
        val aba = entry.copy(word = entry.word.copy(revision = 2))
        assertNotEquals(wordReviewFingerprint(entry.word), wordReviewFingerprint(aba.word))
        assertNull(WordReviewCard(aba, next.schedule()).currentSchedule)
        assertFalse(next.matches(command(WordRating.GOOD, prior, aba).copy(id = next.id)))
    }
    @Test fun rollbackEarlyReviewInvalidZonesBoundsAndTamperedReceiptsFail() {
        val prior = planWordReview(command(WordRating.GOOD), now, zone)
        fails(WordReviewFailure.CLOCK) { planWordReview(command(WordRating.GOOD, prior.schedule()), now - 1, zone) }
        fails(WordReviewFailure.STALE) { planWordReview(command(WordRating.GOOD, prior.schedule()), now + 1, zone) }
        fails(WordReviewFailure.CLOCK) { calculateReviewDue(6, 4102444799999L, zone) }
        fails(WordReviewFailure.CLOCK) { planWordReview(command(WordRating.GOOD), Long.MIN_VALUE, zone) }
        fails(WordReviewFailure.INVALID) { validateWordReviewEvent(prior.copy(dueAt = prior.dueAt + 1)) }
        fails(WordReviewFailure.INVALID) { validateWordReviewEvent(prior.copy(zoneId = "invalid/zone")) }
        fails(WordReviewFailure.INVALID) { validateWordReviewEvent(prior.copy(previousVersion = 4)) }
        fails(WordReviewFailure.INVALID) { validateWordReviewEvent(prior.copy(english = "changed")) }
    }
    @Test fun exactLegacyUtf16DoesNotAliasReplacementOrSeparatorsAndCommandsRejectBadIdentities() {
        assertNotEquals(wordReviewFingerprint(entry.word.copy(english = "x\uD800")), wordReviewFingerprint(entry.word.copy(english = "x?")))
        assertNotEquals(wordReviewFingerprint(entry.word.copy(english = "a", turkish = "bc")), wordReviewFingerprint(entry.word.copy(english = "ab", turkish = "c")))
        fails(WordReviewFailure.INVALID) { planWordReview(command(WordRating.GOOD).copy(id = "not-a-uuid"), now, zone) }
        fails(WordReviewFailure.INVALID) { planWordReview(command(WordRating.GOOD, word = entry.copy(word = entry.word.copy(revision = -1))), now, zone) }
    }
    private fun fails(reason: WordReviewFailure, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (failure: WordReviewException) { assertEquals(reason, failure.reason) }
    }
}

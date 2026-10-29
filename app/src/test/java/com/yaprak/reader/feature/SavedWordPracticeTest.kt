package com.yaprak.reader.feature

import com.yaprak.reader.domain.vocabulary.SavedWord
import com.yaprak.reader.feature.vocabulary.SavedWordPractice
import com.yaprak.reader.feature.vocabulary.savedWordPracticeEntries
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SavedWordPracticeTest {
    private val first = SavedWord("first", "leaf", "yaprak", 3)
    private val second = SavedWord("second", "turn over", "çevir", 2)
    private val third = SavedWord("third", "island", "İstanbul adası", 1)
    private val words = listOf(first, second, third)

    @Test fun `empty or invalid source has no practice card or answer`() {
        val invalid = listOf(first.copy(id = ""), second.copy(english = " "), third.copy(turkish = "\n"))
        for (entries in listOf(emptyList(), invalid)) {
            assertNull(SavedWordPractice.start(entries))
            val old = SavedWordPractice(first.id, first)
            assertNull(old.current(entries))
            assertNull(old.reconcile(entries))
            assertNull(old.reveal(entries))
            assertNull(old.move(entries, 1))
        }
    }

    @Test fun `canonical cards exclude ambiguous IDs and invalid text while preserving order`() {
        val entries = listOf(first, first.copy(turkish = "başka"), second,
            third.copy(id = "blank", english = "\t "), third.copy(id = "long-en", english = "a".repeat(101)),
            third.copy(id = "long-tr", turkish = "a".repeat(201)), third.copy(id = "line-en", english = "a\nb"),
            third.copy(id = "line-tr", turkish = "a\rb"), third.copy(id = " "), third)
        val original = entries.toList()
        assertEquals(listOf(second, third), savedWordPracticeEntries(entries))
        assertEquals(SavedWordPractice(second.id), SavedWordPractice.start(entries))
        assertEquals(original, entries)
        assertTrue(savedWordPracticeEntries(listOf(first, first)).isEmpty())
    }

    @Test fun `valid phrases spacing Unicode and exact length boundaries remain unchanged`() {
        val spaced = first.copy(english = "  turn\tover  ", turkish = "  öteki sayfaya çevir  ")
        val maximum = second.copy(english = "e".repeat(100), turkish = "ş".repeat(200))
        val unicode = third.copy(english = "İ 🌿", turkish = "ı I i İ 🌿")
        val entries = listOf(spaced, maximum, unicode)
        assertEquals(entries, savedWordPracticeEntries(entries))
        assertEquals(spaced, SavedWordPractice.start(entries)?.reveal(entries)?.revealed)
        assertEquals("  turn\tover  ", spaced.english)
    }

    @Test fun `reveal resolves only the selected latest record without changing the source`() {
        val state = SavedWordPractice(second.id)
        val changed = second.copy(turkish = "sayfayı çevir")
        val entries = listOf(first, changed, third)
        val original = entries.toList()
        assertEquals(changed, state.current(entries))
        assertEquals(SavedWordPractice(second.id, changed), state.reveal(entries))
        assertNull(state.revealed)
        assertEquals(original, entries)
        assertEquals(SavedWordPractice(first.id), SavedWordPractice(first.id, second).reconcile(entries))
    }

    @Test fun `reordering preserves unique identity and an unchanged revealed answer`() {
        val state = SavedWordPractice(second.id, second)
        val reordered = listOf(third, first, second)
        assertEquals(state, state.reconcile(reordered))
        assertEquals(second, state.current(reordered))
        assertEquals(SavedWordPractice(first.id), state.move(reordered, -1))
        assertEquals(state, state.move(reordered, 1))
    }

    @Test fun `any selected record update clears its answer while retaining its identity`() {
        val state = SavedWordPractice(second.id, second)
        for (changed in listOf(second.copy(english = "turn back"), second.copy(turkish = "geri çevir"),
            second.copy(createdAt = 20))) {
            val entries = listOf(first, changed, third)
            assertEquals(SavedWordPractice(second.id), state.reconcile(entries))
            assertEquals(SavedWordPractice(second.id), state.move(entries, 0))
            assertEquals(changed, state.reveal(entries)?.revealed)
        }
    }

    @Test fun `removed ambiguous or invalid current record falls back hidden without revealing or skipping`() {
        val state = SavedWordPractice(second.id, second)
        val variants = listOf(listOf(first, third), listOf(first, second, second, third),
            listOf(first, second.copy(english = ""), third))
        for (entries in variants) {
            assertNull(state.current(entries))
            assertEquals(SavedWordPractice(first.id), state.reconcile(entries))
            assertEquals(SavedWordPractice(first.id), state.reveal(entries))
            assertEquals(SavedWordPractice(first.id), state.move(entries, 1))
            assertEquals(SavedWordPractice(first.id), state.move(entries, -1))
        }
    }

    @Test fun `only a real adjacent card change clears the answer and movement stays bounded`() {
        val start = SavedWordPractice(first.id, first)
        assertEquals(start, start.move(words, -1))
        for (invalidDelta in listOf(0, 2, -2, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(start, start.move(words, invalidDelta))
        }
        val middle = requireNotNull(start.move(words, 1))
        assertEquals(SavedWordPractice(second.id), middle)
        assertNull(middle.revealed)
        val last = requireNotNull(middle.move(words, 1)).reveal(words)
        assertEquals(SavedWordPractice(third.id, third), last)
        assertEquals(last, last?.move(words, 1))
        assertEquals(SavedWordPractice(second.id), last?.move(words, -1))
        assertEquals(listOf(first, second, third), words)
    }

    @Test fun `single card bounds retain the answer and a new practice session hides it`() {
        val entries = listOf(second)
        val revealed = SavedWordPractice(second.id, second)
        assertEquals(revealed, revealed.move(entries, -1))
        assertEquals(revealed, revealed.move(entries, 1))
        assertEquals(SavedWordPractice(second.id), SavedWordPractice.start(entries))
        assertEquals(SavedWordPractice(second.id), SavedWordPractice(null, first).reveal(entries))
    }

    @Test fun `shuffle is a changed complete permutation with its first card hidden and source untouched`() {
        val ids = listOf(third.id, first.id, second.id)
        val state = SavedWordPractice(first.id, first, ids)
        val source = words.toList()
        for (seed in 0..12) {
            val shuffled = requireNotNull(state.shuffle(words, Random(seed)))
            val cards = shuffled.cards(words)
            assertEquals(words.size, cards.size)
            assertEquals(words.toSet(), cards.toSet())
            assertEquals(cards.map(SavedWord::id), shuffled.orderIds)
            assertNotEquals(ids, shuffled.orderIds)
            assertEquals(cards.first(), shuffled.current(words))
            assertNull(shuffled.revealed)
        }
        assertEquals(source, words)
        assertEquals(listOf(third.id, first.id, second.id), ids)
        assertEquals(SavedWordPractice(first.id, first, ids), state)
    }

    @Test fun `an unchanged random permutation rotates once and does not retry`() {
        var draws = 0
        val identityRandom = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextInt(until: Int): Int {
                draws++
                return until - 1
            }
        }
        val shuffled = requireNotNull(SavedWordPractice(first.id, first).shuffle(words, identityRandom))
        assertEquals(listOf(second.id, third.id, first.id), shuffled.orderIds)
        assertEquals(second, shuffled.current(words))
        assertNull(shuffled.revealed)
        assertEquals(words.size - 1, draws)
    }

    @Test fun `empty and invalid shuffle has no session while one card is hidden in explicit order`() {
        val state = SavedWordPractice(first.id, first, listOf(third.id, first.id))
        val invalid = listOf(first.copy(id = ""), second.copy(turkish = "\r"), third, third)
        for (entries in listOf(emptyList(), invalid)) {
            assertNull(state.shuffle(entries, Random(3)))
            assertNull(state.inRepositoryOrder(entries))
        }
        val single = requireNotNull(state.shuffle(listOf(second), Random(3)))
        assertEquals(SavedWordPractice(second.id, orderIds = listOf(second.id)), single)
        assertEquals(listOf(second), single.cards(listOf(second)))
        assertEquals(SavedWordPractice(second.id), single.inRepositoryOrder(listOf(second)))
    }

    @Test fun `remembered cards resolve current rows remove stale or invalid IDs and append new cards`() {
        val fourth = SavedWord("fourth", "sprout", "filiz", 4)
        val fifth = SavedWord("fifth", "branch", "dal", 5)
        val changed = first.copy(turkish = "yeni yaprak", createdAt = 6)
        val entries = listOf(fourth, third, changed, second.copy(english = ""),
            third.copy(turkish = "başka ada"), first.copy(id = " "), fifth)
        val original = entries.toList()
        val ids = listOf(third.id, first.id, "missing", first.id, second.id, " ")
        val state = SavedWordPractice(first.id, first, ids)
        val normalized = SavedWordPractice(first.id, orderIds = listOf(first.id, fourth.id, fifth.id))
        assertEquals(listOf(changed, fourth, fifth), state.cards(entries))
        assertEquals(changed, state.current(entries))
        assertEquals(normalized, state.reconcile(entries))
        assertEquals(normalized, state.move(entries, 0))
        assertEquals(normalized.copy(revealed = changed), state.reveal(entries))
        assertEquals(original, entries)
        assertEquals(listOf(third.id, first.id, "missing", first.id, second.id, " "), ids)
    }

    @Test fun `explicit order survives repository reorder reveal and movement including bounds`() {
        val ids = listOf(third.id, first.id, second.id)
        val state = SavedWordPractice(first.id, first, ids)
        val reordered = listOf(second, third, first)
        assertEquals(listOf(third, first, second), state.cards(reordered))
        assertEquals(state, state.reconcile(reordered))
        assertEquals(state, state.reveal(reordered))
        assertEquals(SavedWordPractice(third.id, orderIds = ids), state.move(reordered, -1))
        assertEquals(SavedWordPractice(second.id, orderIds = ids), state.move(reordered, 1))
        for (delta in listOf(0, 2, -2, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(state, state.move(reordered, delta))
        }
        val beginning = SavedWordPractice(third.id, third, ids)
        val end = SavedWordPractice(second.id, second, ids)
        assertEquals(beginning, beginning.move(reordered, -1))
        assertEquals(end, end.move(reordered, 1))
    }

    @Test fun `stale current falls back to first ordered card hidden without skipping or losing order`() {
        val state = SavedWordPractice(second.id, second, listOf(third.id, second.id, first.id))
        val variants = listOf(listOf(first, third), listOf(first, second, second, third),
            listOf(first, second.copy(turkish = ""), third))
        val fallback = SavedWordPractice(third.id, orderIds = listOf(third.id, first.id))
        for (entries in variants) {
            assertNull(state.current(entries))
            assertEquals(fallback, state.reconcile(entries))
            assertEquals(fallback, state.reveal(entries))
            assertEquals(fallback, state.move(entries, -1))
            assertEquals(fallback, state.move(entries, 1))
            assertEquals(fallback, state.move(entries, 0))
        }
    }

    @Test fun `repository order preserves valid identity hides answer and follows subsequent reorder`() {
        val state = SavedWordPractice(second.id, second, listOf(third.id, second.id, first.id))
        val restored = requireNotNull(state.inRepositoryOrder(words))
        assertEquals(SavedWordPractice(second.id), restored)
        assertEquals(words, restored.cards(words))
        val reordered = listOf(second, third, first)
        assertEquals(reordered, restored.cards(reordered))
        assertEquals(SavedWordPractice(third.id), restored.move(reordered, 1))
        assertEquals(SavedWordPractice(first.id), state.inRepositoryOrder(listOf(first, third)))
        assertEquals(second, state.revealed)
    }
}

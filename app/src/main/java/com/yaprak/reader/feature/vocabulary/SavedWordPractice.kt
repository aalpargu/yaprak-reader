package com.yaprak.reader.feature.vocabulary

import com.yaprak.reader.domain.vocabulary.MAX_ENGLISH_WORD_LENGTH
import com.yaprak.reader.domain.vocabulary.MAX_TURKISH_MEANING_LENGTH
import com.yaprak.reader.domain.vocabulary.SavedWord
import kotlin.random.Random

/** Empty order follows the repository; explicit IDs remember only this practice session's order. */
internal data class SavedWordPractice(
    val currentId: String?,
    val revealed: SavedWord? = null,
    val orderIds: List<String> = emptyList(),
) {
    fun cards(entries: List<SavedWord>): List<SavedWord> {
        val canonical = savedWordPracticeEntries(entries)
        if (orderIds.isEmpty()) return canonical
        val byId = canonical.associateBy(SavedWord::id)
        val remembered = orderIds.distinct().mapNotNull(byId::get)
        val rememberedIds = remembered.mapTo(mutableSetOf(), SavedWord::id)
        return remembered + canonical.filter { it.id !in rememberedIds }
    }

    fun current(entries: List<SavedWord>): SavedWord? =
        cards(entries).singleOrNull { it.id == currentId }

    fun reconcile(entries: List<SavedWord>): SavedWordPractice? = reconcileCards(cards(entries))

    fun reveal(entries: List<SavedWord>): SavedWordPractice? {
        val cards = cards(entries)
        val stable = reconcileCards(cards) ?: return null
        val word = cards.singleOrNull { it.id == currentId } ?: return stable
        return stable.copy(revealed = word)
    }

    fun move(entries: List<SavedWord>, delta: Int): SavedWordPractice? {
        val cards = cards(entries)
        val index = cards.indexOfFirst { it.id == currentId }
        val stable = reconcileCards(cards) ?: return null
        // A stale action selects the fallback card without skipping it or exposing its answer.
        if (index < 0) return stable
        if (delta != -1 && delta != 1) return stable
        val next = cards.getOrNull(index + delta) ?: return stable
        return stable.copy(currentId = next.id, revealed = null)
    }

    fun shuffle(entries: List<SavedWord>, random: Random = Random.Default): SavedWordPractice? {
        val currentIds = cards(entries).map(SavedWord::id)
        if (currentIds.isEmpty()) return null
        val shuffled = currentIds.shuffled(random)
        val nextIds = if (shuffled.size > 1 && shuffled == currentIds) {
            shuffled.drop(1) + shuffled.first()
        } else shuffled
        return SavedWordPractice(nextIds.first(), orderIds = nextIds)
    }

    fun inRepositoryOrder(entries: List<SavedWord>): SavedWordPractice? {
        val cards = savedWordPracticeEntries(entries)
        val word = cards.singleOrNull { it.id == currentId } ?: cards.firstOrNull() ?: return null
        return SavedWordPractice(word.id)
    }

    private fun reconcileCards(cards: List<SavedWord>): SavedWordPractice? {
        val current = cards.singleOrNull { it.id == currentId }
        val word = current ?: cards.firstOrNull() ?: return null
        return copy(
            currentId = word.id,
            revealed = revealed?.takeIf { current != null && it == current },
            orderIds = if (orderIds.isEmpty()) emptyList() else cards.map(SavedWord::id),
        )
    }

    companion object {
        fun start(entries: List<SavedWord>): SavedWordPractice? =
            savedWordPracticeEntries(entries).firstOrNull()?.let { SavedWordPractice(it.id) }
    }
}

/** Preserve supplied order; an ambiguous identity cannot be used as a practice target. */
internal fun savedWordPracticeEntries(entries: List<SavedWord>): List<SavedWord> {
    val counts = entries.groupingBy(SavedWord::id).eachCount()
    return entries.filter { word ->
        word.id.isNotBlank() && counts[word.id] == 1 &&
            validPracticeLine(word.english, MAX_ENGLISH_WORD_LENGTH) &&
            validPracticeLine(word.turkish, MAX_TURKISH_MEANING_LENGTH)
    }
}

private fun validPracticeLine(value: String, maximum: Int): Boolean =
    value.trim().length in 1..maximum && '\n' !in value && '\r' !in value

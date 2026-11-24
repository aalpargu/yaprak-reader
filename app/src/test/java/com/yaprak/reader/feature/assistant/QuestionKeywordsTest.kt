package com.yaprak.reader.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionKeywordsTest {
    @Test fun `turkish question drops filler words and narrows from most specific`() {
        val candidates = questionKeywordCandidates("Gandalf Moria madenlerinde ne yaptı?")
        assertEquals("madenlerinde gandalf moria", candidates.first())
        assertTrue(candidates.containsAll(listOf("madenlerinde", "gandalf", "moria")))
        assertTrue(candidates.none { it.split(' ').any { term -> term in setOf("ne", "yaptı") } })
    }

    @Test fun `english question keeps content words`() {
        val candidates = questionKeywordCandidates("What does dependency injection mean in this book?")
        assertEquals("dependency injection mean", candidates.first())
        assertEquals(listOf("dependency injection mean", "dependency injection", "dependency", "injection", "mean"), candidates)
    }

    @Test fun `question made only of filler words has no search terms`() {
        assertTrue(questionKeywordCandidates("Bu ne? Neden?").isEmpty())
        assertTrue(questionKeywordCandidates("   ").isEmpty())
    }
}

package com.yaprak.reader.feature

import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.reader.JumpHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpHistoryTest {
    @Test fun `only accepted jumps with a captured source are recorded`() {
        val history = JumpHistory()
        history.record(location(1), accepted = false)
        history.record(null, accepted = true)
        assertFalse(history.canReturn)

        var called = false
        assertFalse(history.returnTo { called = true; true })
        assertFalse(called)

        history.record(location(1), accepted = true)
        assertTrue(history.canReturn)
        assertTrue(history.returnTo { it == location(1) })
        assertFalse(history.canReturn)
    }

    @Test fun `consecutive identical sources are stored once and returns use latest first`() {
        val history = JumpHistory()
        history.record(location(1), accepted = true)
        history.record(location(1), accepted = true)
        history.record(location(2), accepted = true)

        val returned = mutableListOf<ReadingLocation>()
        assertTrue(history.returnTo { returned += it; true })
        assertTrue(history.returnTo { returned += it; true })
        assertEquals(listOf(location(2), location(1)), returned)
        assertFalse(history.canReturn)
    }

    @Test fun `default history keeps the latest twenty accepted sources`() {
        val history = JumpHistory()
        (1..21).forEach { history.record(location(it), accepted = true) }

        val returned = mutableListOf<ReadingLocation>()
        while (history.returnTo { returned += it; true }) Unit
        assertEquals((21 downTo 2).map(::location), returned)
        assertFalse(history.canReturn)
    }

    @Test fun `failed or throwing return preserves the saved location`() {
        val history = JumpHistory(maxEntries = 2)
        history.record(location(1), accepted = true)

        assertFalse(history.returnTo { false })
        assertTrue(history.canReturn)
        val failure = runCatching { history.returnTo { throw IllegalStateException("navigation failed") } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(history.canReturn)
        assertTrue(history.returnTo { it == location(1) })
        assertFalse(history.canReturn)
    }

    @Test fun `clear forgets the current session trail`() {
        val history = JumpHistory()
        history.record(location(1), accepted = true)
        history.clear()
        assertFalse(history.canReturn)
        assertFalse(history.returnTo { true })
    }

    private fun location(page: Int) = ReadingLocation("page-$page", "chapter-$page")
}

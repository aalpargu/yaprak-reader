package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.reader.ReadingLocation

/** A bounded, session-only trail of locations before explicit reader jumps. */
internal class JumpHistory(private val maxEntries: Int = 20) {
    init { require(maxEntries > 0) }

    private val locations = ArrayDeque<ReadingLocation>()

    val canReturn: Boolean get() = locations.isNotEmpty()

    fun record(source: ReadingLocation?, accepted: Boolean) {
        if (!accepted || source == null || locations.lastOrNull() == source) return
        if (locations.size == maxEntries) locations.removeFirst()
        locations.addLast(source)
    }

    /** Keep the destination available when the renderer rejects or throws during a return. */
    fun returnTo(goTo: (ReadingLocation) -> Boolean): Boolean {
        val location = locations.lastOrNull() ?: return false
        if (!goTo(location)) return false
        locations.removeLast()
        return true
    }

    fun clear() = locations.clear()
}

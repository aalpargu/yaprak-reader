package com.yaprak.reader.feature.reader

import android.view.KeyEvent

/** Owns only complete key presses admitted by the foreground reader. No progress is inferred. */
internal class ReaderVolumeKeyController {
    enum class Direction { PREVIOUS, NEXT }
    private data class Key(val code: Int, val device: Int)
    private val owned = mutableMapOf<Key, Long>()

    fun reset() = owned.clear()

    fun dispatch(event: KeyEvent, canTurn: () -> Boolean, turn: (Direction) -> Unit): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Direction.PREVIOUS
            KeyEvent.KEYCODE_VOLUME_DOWN -> Direction.NEXT
            else -> return false
        }
        val key = Key(event.keyCode, event.deviceId)
        val matches = owned[key] == event.downTime
        return when (event.action) {
            KeyEvent.ACTION_UP -> {
                if (!matches) false else { owned.remove(key); true }
            }
            KeyEvent.ACTION_DOWN -> {
                // Owned repeats/releases stay consumed even if a tool opens during the press.
                if (matches) true
                else if (event.repeatCount != 0 || event.isCanceled) false
                else {
                    owned.remove(key)
                    if (!canTurn()) false else {
                        owned[key] = event.downTime
                        turn(direction)
                        true
                    }
                }
            }
            else -> false
        }
    }
}

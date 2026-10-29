package com.yaprak.reader.feature

import android.app.Application
import android.view.KeyEvent
import com.yaprak.reader.feature.reader.ReaderVolumeKeyController
import com.yaprak.reader.feature.reader.ReaderVolumeKeyController.Direction.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReaderVolumeKeyControllerTest {
    private val controller = ReaderVolumeKeyController()
    private val turns = mutableListOf<ReaderVolumeKeyController.Direction>()
    private var allowed = true
    private fun send(code: Int = KeyEvent.KEYCODE_VOLUME_DOWN, action: Int = KeyEvent.ACTION_DOWN,
        down: Long = 10, repeat: Int = 0, device: Int = 1, flags: Int = 0) =
        controller.dispatch(KeyEvent(down, down + repeat, action, code, repeat, 0, device, 0, flags), { allowed }) { turns += it }

    @Test fun onlyFirstDownTurnsAndMatchingRepeatsAndReleaseStayOwnedAfterModalOpens() {
        assertTrue(send()); assertTrue(send()); assertTrue(send(repeat = 1))
        allowed = false
        assertTrue(send(repeat = 2)); assertTrue(send(action = KeyEvent.ACTION_UP))
        assertFalse(send(action = KeyEvent.ACTION_UP))
        assertEquals(listOf(NEXT), turns)
    }
    @Test fun oppositeKeysAndSeparateDevicesHaveIndependentOwnership() {
        assertTrue(send(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(send(device = 2)); assertTrue(send(device = 1))
        assertTrue(send(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP))
        assertTrue(send(action = KeyEvent.ACTION_UP, device = 2))
        assertTrue(send(action = KeyEvent.ACTION_UP))
        assertEquals(listOf(PREVIOUS, NEXT, NEXT), turns)
    }
    @Test fun unmatchedRepeatsReleasesOtherKeysAndCanceledDownDelegateNormally() {
        assertFalse(send(repeat = 1)); assertFalse(send(action = KeyEvent.ACTION_UP))
        assertFalse(send(KeyEvent.KEYCODE_VOLUME_MUTE)); assertFalse(send(KeyEvent.KEYCODE_A))
        assertFalse(send(action = KeyEvent.ACTION_MULTIPLE))
        assertFalse(send(flags = KeyEvent.FLAG_CANCELED)); assertTrue(turns.isEmpty())
        assertTrue(send()); assertFalse(send(action = KeyEvent.ACTION_UP, down = 11))
        assertFalse(send(action = KeyEvent.ACTION_UP, device = 2))
        assertTrue(send(action = KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED))
        assertEquals(listOf(NEXT), turns)
    }
    @Test fun deniedStartCannotAcquireRepeatOrReleaseAfterReaderBecomesAvailable() {
        allowed = false; assertFalse(send()); allowed = true
        assertFalse(send(repeat = 1)); assertFalse(send(action = KeyEvent.ACTION_UP))
        assertTrue(turns.isEmpty()); assertTrue(send(down = 20)); assertEquals(listOf(NEXT), turns)
    }
    @Test fun resetReleasesOwnershipAndNewDownTimeStartsANewPress() {
        assertTrue(send()); controller.reset()
        assertFalse(send(repeat = 1)); assertFalse(send(action = KeyEvent.ACTION_UP))
        assertTrue(send(down = 20)); assertTrue(send(down = 30))
        assertFalse(send(action = KeyEvent.ACTION_UP, down = 20))
        assertTrue(send(action = KeyEvent.ACTION_UP, down = 30))
        assertEquals(3, turns.size)
    }
    @Test fun pageEdgeRejectionStillConsumesAuthorizedPressWithoutAnotherTurn() {
        var calls = 0
        val down = KeyEvent(10, 10, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0)
        assertTrue(controller.dispatch(down, { true }) { calls++; false })
        assertTrue(controller.dispatch(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), { false }) { calls++ })
        assertEquals(1, calls)
    }
}

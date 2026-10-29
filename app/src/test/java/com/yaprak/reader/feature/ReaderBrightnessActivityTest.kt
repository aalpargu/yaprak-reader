package com.yaprak.reader.feature

import android.os.Bundle
import android.view.WindowManager
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.feature.reader.ReaderActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Activity restoration and window gating, without claiming a ready EPUB/PDF surface. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderBrightnessActivityTest {
    private val choiceKey = "readerBrightnessPercent"

    private fun saveFromUnreadyReader(savedState: Bundle?): Bundle {
        Robolectric.buildActivity(ReaderActivity::class.java).use { controller ->
            // No book identity: production loading can never create a ready reader surface.
            if (savedState == null) controller.setup() else controller.setup(savedState)
            controller.windowFocusChanged(true)
            assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                controller.get().window.attributes.screenBrightness, 0f)
            val output = Bundle().apply { putInt(choiceKey, 99) }
            controller.pause().saveInstanceState(output)
            assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                controller.get().window.attributes.screenBrightness, 0f)
            return output
        }
    }

    @Test fun `restored manual choice survives saving without changing an unready reader window`() {
        val output = saveFromUnreadyReader(Bundle().apply { putInt(choiceKey, 35) })
        assertTrue(output.containsKey(choiceKey))
        assertEquals(35, output.getInt(choiceKey))
    }

    @Test fun `out of range restored choices are discarded rather than clamped or saved`() {
        for (invalid in listOf(9, 101)) {
            val output = saveFromUnreadyReader(Bundle().apply { putInt(choiceKey, invalid) })
            assertFalse(output.containsKey(choiceKey))
        }
    }

    @Test fun `gesture choice starts off and survives recreation only with valid manual brightness`() {
        val key = "readerBrightnessGestures"
        assertFalse(saveFromUnreadyReader(null).getBoolean(key))
        val saved = saveFromUnreadyReader(Bundle().apply { putInt(choiceKey, 35); putBoolean(key, true) })
        assertTrue(saved.getBoolean(key)); assertEquals(35, saved.getInt(choiceKey))
        assertTrue(saveFromUnreadyReader(saved).getBoolean(key))
        assertFalse(saveFromUnreadyReader(Bundle().apply { putBoolean(key, true) }).getBoolean(key))
        assertFalse(saveFromUnreadyReader(Bundle().apply { putInt(choiceKey, 9); putBoolean(key, true) }).getBoolean(key))
    }

    @Test fun `fresh reader starts with system choice and removes a stale output key`() {
        assertFalse(saveFromUnreadyReader(null).containsKey(choiceKey))
    }
}

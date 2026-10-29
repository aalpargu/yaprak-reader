package com.yaprak.reader.feature

import android.os.Bundle
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.reader.EpubImageFilterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Activity Bundle checks; no ready EPUB or WebView is fabricated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubImageFilterActivityTest {
    private val choiceKey = "readerEpubImageFilter"

    private fun saveFromUnreadyReader(savedState: Bundle?): Bundle {
        Robolectric.buildActivity(ReaderActivity::class.java).use { controller ->
            // No book identity: the production loader cannot produce a ready reader surface.
            if (savedState == null) controller.setup() else controller.setup(savedState)
            controller.windowFocusChanged(true)
            val output = Bundle().apply { putString(choiceKey, "STALE_OUTPUT") }
            controller.pause().saveInstanceState(output)
            return output
        }
    }

    @Test fun `fresh and missing saved choices remove a stale output key`() {
        assertFalse(saveFromUnreadyReader(null).containsKey(choiceKey))
        assertFalse(saveFromUnreadyReader(Bundle()).containsKey(choiceKey))
    }

    @Test fun `explicit original choice removes a stale output key`() {
        val output = saveFromUnreadyReader(Bundle().apply {
            putString(choiceKey, EpubImageFilterMode.ORIGINAL.name)
        })
        assertFalse(output.containsKey(choiceKey))
    }

    @Test fun `darken and invert survive saving and another Activity restoration`() {
        for (mode in listOf(EpubImageFilterMode.DARKEN, EpubImageFilterMode.INVERT)) {
            val firstOutput = saveFromUnreadyReader(Bundle().apply { putString(choiceKey, mode.name) })
            assertEquals(mode.name, firstOutput.getString(choiceKey))
            val recreatedOutput = saveFromUnreadyReader(firstOutput)
            assertEquals(mode.name, recreatedOutput.getString(choiceKey))
        }
    }

    @Test fun `unknown empty and null restored choices revert to original`() {
        for (invalid in listOf("FUTURE_MODE", "darken", "", "INVERT ", null)) {
            val output = saveFromUnreadyReader(Bundle().apply { putString(choiceKey, invalid) })
            assertFalse("Invalid choice was saved: $invalid", output.containsKey(choiceKey))
        }
    }

    @Test fun `a previous Activity Bundle choice is not reused by a fresh reader`() {
        val previousOutput = saveFromUnreadyReader(Bundle().apply {
            putString(choiceKey, EpubImageFilterMode.INVERT.name)
        })
        assertEquals(EpubImageFilterMode.INVERT.name, previousOutput.getString(choiceKey))
        assertFalse(saveFromUnreadyReader(null).containsKey(choiceKey))
    }

}

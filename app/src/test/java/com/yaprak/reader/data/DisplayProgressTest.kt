package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.persistence.displayProgressPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DisplayProgressTest {
    @Test fun `paged formats use the saved page against the page count`() {
        assertEquals(0, displayProgressPercent("PDF", 30, """{"format":"pdf","version":1,"pageIndex":0}"""))
        assertEquals(45, displayProgressPercent("PDF", 30, """{"format":"pdf","version":2,"pageIndex":13,"anchorY":0.2}"""))
        assertEquals(100, displayProgressPercent("CBZ", 12, """{"format":"cbz","version":1,"pageIndex":11,"entry":"x"}"""))
        assertEquals(100, displayProgressPercent("PDF", 1, """{"pageIndex":0}"""))
    }

    @Test fun `reflowable formats use the engine total progression`() {
        assertEquals(46, displayProgressPercent("EPUB", null,
            """{"href":"c3.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5,"totalProgression":0.457}}"""))
    }

    @Test fun `anything unknown or out of range shows no percent rather than a guess`() {
        assertNull(displayProgressPercent("EPUB", null, """{"href":"c3.xhtml","locations":{"progression":0.5}}"""))
        assertNull(displayProgressPercent("PDF", 10, """{"pageIndex":12}"""))
        assertNull(displayProgressPercent("PDF", null, """{"pageIndex":3}"""))
        assertNull(displayProgressPercent("EPUB", null, "not json"))
        assertNull(displayProgressPercent("EPUB", null, """{"locations":{"totalProgression":1.7}}"""))
    }
}

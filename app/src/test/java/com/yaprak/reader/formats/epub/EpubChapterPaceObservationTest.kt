package com.yaprak.reader.formats.epub

import org.junit.Assert.*
import org.junit.Test

class EpubChapterPaceObservationTest {
    @Test fun `admitted TOC identity travels with the proven visible SDK resource extent`() {
        val source = EpubReadingPace { 100L }
        source.visible("one.xhtml", .5, EpubPaceRange(.1, .3), 400, 800, "toc-1")
        val actual = source.observations.value
        assertEquals("toc-1", actual.chapterEntryId)
        assertEquals(.2, actual.totalProgression!!, .00000001)
        assertEquals(.3, actual.rangeEnd!!, 0.0)
    }

    @Test fun `missing or malformed TOC identity does not manufacture chapter authority`() {
        val source = EpubReadingPace { 100L }
        for (id in listOf(null, " ", "bad\u0000id", "x".repeat(129))) {
            source.visible("one.xhtml", .5, EpubPaceRange(.1, .3), 400, 800, id)
            assertNull(source.observations.value.chapterEntryId)
            assertEquals(.2, source.observations.value.totalProgression!!, .00000001)
        }
    }

    @Test fun `jump pending clear and disposal revoke chapter identity with the visible point`() {
        val source = EpubReadingPace { 100L }
        source.visible("one.xhtml", .5, EpubPaceRange(.1, .3), 400, 800, "toc-1")
        source.invalidate(waitForPage = true)
        source.visible("one.xhtml", .6, EpubPaceRange(.1, .3), 400, 800, "toc-1")
        assertNull(source.observations.value.chapterEntryId)
        source.pageChanged()
        source.visible("one.xhtml", .6, EpubPaceRange(.1, .3), 400, 800, "toc-1")
        assertEquals("toc-1", source.observations.value.chapterEntryId)
        source.dispose()
        source.visible("one.xhtml", .7, EpubPaceRange(.1, .3), 400, 800, "toc-1")
        assertNull(source.observations.value.chapterEntryId); assertNull(source.observations.value.totalProgression)
    }
}

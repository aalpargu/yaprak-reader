package com.yaprak.reader.feature

import android.app.Application
import androidx.fragment.app.Fragment
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.feature.reader.PdfNightModeState
import com.yaprak.reader.feature.reader.PdfNightModeStatus
import com.yaprak.reader.feature.reader.ReaderPdfNightModeBinding
import com.yaprak.reader.reader.PdfNightModeController
import com.yaprak.reader.reader.ReaderSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReaderPdfNightModeBindingTest {
    @Test fun `loaded choice applies once to each newly created PDF surface`() {
        val binding = ReaderPdfNightModeBinding()
        val first = Surface()
        val second = Surface()
        val loaded = PdfNightModeState(true, PdfNightModeStatus.Ready)
        binding.update(first, BookFormat.PDF, loaded, readerReady = true)
        binding.update(first, BookFormat.PDF, loaded, readerReady = true)
        binding.update(second, BookFormat.PDF, loaded, readerReady = true)
        assertEquals(listOf(true), first.applied)
        assertEquals(listOf(true), second.applied)
        assertEquals(0, first.navigationCount + second.navigationCount)
        assertFalse(first.observed || second.observed)
    }

    @Test fun `default loading and failed reads do not apply unaccepted colors`() {
        val binding = ReaderPdfNightModeBinding()
        val surface = Surface()
        binding.update(surface, BookFormat.PDF, PdfNightModeState(), readerReady = true)
        binding.update(surface, BookFormat.PDF, PdfNightModeState(true, PdfNightModeStatus.Loading), readerReady = true)
        binding.update(surface, BookFormat.PDF, PdfNightModeState(true, PdfNightModeStatus.LoadFailed), readerReady = true)
        assertEquals(emptyList<Boolean>(), surface.applied)
        binding.update(surface, BookFormat.PDF, PdfNightModeState(true, PdfNightModeStatus.Ready), readerReady = true)
        assertEquals(listOf(true), surface.applied)
    }

    @Test fun `pending and failed writes keep latest colors without duplicate engine updates`() {
        val binding = ReaderPdfNightModeBinding()
        val surface = Surface()
        binding.update(surface, BookFormat.PDF, PdfNightModeState(true, PdfNightModeStatus.Saving), readerReady = true)
        binding.update(surface, BookFormat.PDF, PdfNightModeState(true, PdfNightModeStatus.SaveFailed), readerReady = true)
        binding.update(surface, BookFormat.PDF, PdfNightModeState(false, PdfNightModeStatus.Saving), readerReady = true)
        binding.update(surface, BookFormat.PDF, PdfNightModeState(false, PdfNightModeStatus.Ready), readerReady = true)
        assertEquals(listOf(true, false), surface.applied)
        assertEquals(0, surface.navigationCount)
        assertFalse(surface.observed)
    }

    @Test fun `missing capability EPUB and unavailable reader never apply PDF colors`() {
        val binding = ReaderPdfNightModeBinding()
        val surface = Surface()
        val preference = PdfNightModeState(true, PdfNightModeStatus.Ready)
        binding.update(surface, BookFormat.EPUB, preference, readerReady = true)
        binding.update(surface, BookFormat.PDF, preference, readerReady = false)
        binding.update(surface, null, preference, readerReady = true)
        binding.update(null, BookFormat.PDF, preference, readerReady = true)
        binding.update(Surface(capable = false), BookFormat.PDF, preference, readerReady = true)
        assertEquals(emptyList<Boolean>(), surface.applied)
        assertEquals(0, surface.navigationCount)
        assertFalse(surface.observed)
    }

    private class Surface(capable: Boolean = true) : ReaderSurface {
        override val fragment = Fragment()
        val applied = mutableListOf<Boolean>()
        var observed = false
        var navigationCount = 0
        override val pdfNightMode = if (capable) PdfNightModeController { applied += it } else null
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
            observed = true
        }
        override fun forward(): Boolean { navigationCount++; return true }
        override fun backward(): Boolean { navigationCount++; return true }
    }
}

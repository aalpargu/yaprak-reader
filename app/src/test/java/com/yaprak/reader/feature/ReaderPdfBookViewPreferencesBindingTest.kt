package com.yaprak.reader.feature

import android.app.Application
import androidx.fragment.app.Fragment
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.settings.BookPdfViewPreferences
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.domain.settings.PdfNavigationMode
import com.yaprak.reader.feature.reader.PdfBookViewOwner
import com.yaprak.reader.feature.reader.PdfBookViewPreferencesState
import com.yaprak.reader.feature.reader.PdfBookViewStatus
import com.yaprak.reader.feature.reader.ReaderPdfBookViewPreferencesBinding
import com.yaprak.reader.reader.PdfCropController
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.reader.PdfSpreadController
import com.yaprak.reader.reader.PdfViewController
import com.yaprak.reader.reader.ReaderSurface
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReaderPdfBookViewPreferencesBindingTest {
    private val book = Book("book", "PDF", null, BookFormat.PDF, "book.pdf", "book.pdf", 1, "a".repeat(64), 1, pageCount = 4)
    private fun state(value: BookPdfViewPreferences = BookPdfViewPreferences(), command: Long = 0, generation: Long = 1) =
        PdfBookViewPreferencesState(PdfBookViewOwner(book.id, book.sha256, generation), value,
            status = PdfBookViewStatus.APPLYING, revision = 1, fitCommand = command)

    private class Surface : ReaderSurface {
        override val fragment = Fragment()
        val crops = mutableListOf<PdfPageCrop>()
        val fits = mutableListOf<PdfFitPreference>()
        val spreads = mutableListOf<Boolean>()
        var fitFailure: Exception? = null
        var afterCrop: () -> Unit = {}
        override var pdfCrop: PdfCropController? = PdfCropController { crops += it; afterCrop() }
        override var pdfView: PdfViewController? = object : PdfViewController {
            override fun fitPage() { fitFailure?.let { throw it }; fits += PdfFitPreference.PAGE }
            override fun fitWidth() { fitFailure?.let { throw it }; fits += PdfFitPreference.WIDTH }
        }
        override var pdfSpread: PdfSpreadController? = PdfSpreadController { spreads += it }
        override var pdfScroll: com.yaprak.reader.reader.PdfScrollController? = ReaderTestPdfScrollController()
        var observed = false
        var navigation = 0
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) { observed = true }
        override fun forward(): Boolean { navigation++; return true }
        override fun backward(): Boolean { navigation++; return true }
    }

    @Test fun `replacement controllers in the same surface receive current stored view`() {
        val surface = Surface()
        val binding = ReaderPdfBookViewPreferencesBinding()
        val expected = state(BookPdfViewPreferences(8, 12, PdfFitPreference.WIDTH, true))
        assertTrue(binding.apply(surface, book, expected))
        val newCrops = mutableListOf<PdfPageCrop>()
        val newFits = mutableListOf<PdfFitPreference>()
        val newSpreads = mutableListOf<Boolean>()
        surface.pdfCrop = PdfCropController { newCrops += it }
        surface.pdfView = object : PdfViewController {
            override fun fitPage() { newFits += PdfFitPreference.PAGE }
            override fun fitWidth() { newFits += PdfFitPreference.WIDTH }
        }
        surface.pdfSpread = PdfSpreadController { newSpreads += it }
        assertTrue(binding.apply(surface, book, expected))
        assertEquals(listOf(PdfPageCrop(8, 12)), newCrops)
        assertEquals(listOf(PdfFitPreference.WIDTH), newFits)
        assertEquals(listOf(true), newSpreads)
    }

    @Test fun `verified loaded fields apply once to each exact native surface without observing or navigating`() {
        val binding = ReaderPdfBookViewPreferencesBinding()
        val one = Surface(); val two = Surface()
        val preferences = state(BookPdfViewPreferences(5, 8, PdfFitPreference.WIDTH, true))
        assertTrue(binding.apply(one, book, preferences)); assertTrue(binding.apply(one, book, preferences))
        assertTrue(binding.apply(two, book, preferences))
        for (surface in listOf(one, two)) {
            assertEquals(listOf(PdfPageCrop(5, 8)), surface.crops)
            assertEquals(listOf(PdfFitPreference.WIDTH), surface.fits)
            assertEquals(listOf(true), surface.spreads)
            assertEquals(0, surface.navigation); assertFalse(surface.observed)
        }
    }

    @Test fun `repeat fit resets exactly once while crop spread and same surface foreground reload deduplicate`() {
        val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
        assertTrue(binding.apply(surface, book, state()))
        assertTrue(binding.apply(surface, book, state(generation = 2)))
        assertEquals(1, surface.fits.size)
        val repeated = state(command = 1, generation = 2)
        assertTrue(binding.apply(surface, book, repeated)); assertTrue(binding.apply(surface, book, repeated))
        assertEquals(listOf(PdfFitPreference.PAGE, PdfFitPreference.PAGE), surface.fits)
        assertEquals(1, surface.crops.size); assertEquals(1, surface.spreads.size)
    }

    @Test fun `pending failed wrong owner and EPUB state issue no native command`() {
        val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
        for (status in PdfBookViewStatus.entries.filter { it != PdfBookViewStatus.APPLYING })
            assertFalse(binding.apply(surface, book, state().copy(status = status)))
        assertFalse(binding.apply(surface, book.copy(format = BookFormat.EPUB), state()))
        assertFalse(binding.apply(surface, book.copy(id = "other"), state()))
        assertFalse(binding.apply(surface, book.copy(sha256 = "b".repeat(64)), state()))
        assertTrue(surface.crops.isEmpty()); assertTrue(surface.fits.isEmpty()); assertTrue(surface.spreads.isEmpty())
    }

    @Test fun `missing any required PDF controller cannot acknowledge an applied view or begin partial commands`() {
        for (missing in 0..3) {
            val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
            when (missing) { 0 -> surface.pdfCrop = null; 1 -> surface.pdfView = null; 2 -> surface.pdfSpread = null; 3 -> surface.pdfScroll = null }
            assertTrue(runCatching { binding.apply(surface, book, state()) }.exceptionOrNull() is IllegalStateException)
            assertTrue(surface.crops.isEmpty()); assertTrue(surface.fits.isEmpty()); assertTrue(surface.spreads.isEmpty())
        }
    }

    @Test fun horizontalModeRequiresExactControllerAcknowledgementAndDoesNotReplayCropFitOrSpread() {
        val surface = Surface(); val binding = ReaderPdfBookViewPreferencesBinding()
        val modes = mutableListOf<PdfNavigationMode>()
        val mode = kotlinx.coroutines.flow.MutableStateFlow(PdfNavigationMode.PAGED)
        val nativeEnabled = kotlinx.coroutines.flow.MutableStateFlow(false)
        var wrongAcknowledgement = true
        surface.pdfScroll = object : com.yaprak.reader.reader.PdfScrollController {
            override val enabled = nativeEnabled
            override val navigationMode = mode
            override fun apply(enabled: Boolean): Boolean = error("Do not reduce an exact request to a Boolean")
            override fun apply(choice: PdfNavigationMode): Boolean {
                modes += choice
                enabled.value = choice != PdfNavigationMode.PAGED
                mode.value = if (wrongAcknowledgement) PdfNavigationMode.VERTICAL_SCROLL else choice
                return true
            }
        }
        val desired = state(BookPdfViewPreferences(8, 12, PdfFitPreference.WIDTH, true, PdfNavigationMode.HORIZONTAL_SCROLL))
        assertFalse(binding.apply(surface, book, desired)); assertEquals(PdfNavigationMode.VERTICAL_SCROLL, mode.value)
        wrongAcknowledgement = false
        assertTrue(binding.apply(surface, book, desired)); assertEquals(PdfNavigationMode.HORIZONTAL_SCROLL, mode.value)
        assertTrue(binding.apply(surface, book, desired)); assertEquals(2, modes.size)
        assertEquals(1, surface.crops.size); assertEquals(1, surface.fits.size); assertEquals(listOf(true), surface.spreads)
        mode.value = PdfNavigationMode.VERTICAL_SCROLL
        assertTrue(binding.apply(surface, book, desired)); assertEquals(3, modes.size)
        assertEquals(PdfNavigationMode.HORIZONTAL_SCROLL, mode.value); assertEquals(0, surface.navigation)
    }

    @Test fun horizontalRequestNeverFallsBackToVerticalOnALegacyBooleanOnlySurface() {
        val surface = Surface(); val binding = ReaderPdfBookViewPreferencesBinding()
        val controller = surface.pdfScroll as ReaderTestPdfScrollController
        val desired = state(BookPdfViewPreferences(navigationMode = PdfNavigationMode.HORIZONTAL_SCROLL))
        assertFalse(binding.apply(surface, book, desired))
        assertTrue(controller.calls.isEmpty()); assertFalse(controller.enabled.value)
        assertTrue(binding.apply(surface, book, desired.copy(preferences = BookPdfViewPreferences(navigationMode = PdfNavigationMode.VERTICAL_SCROLL))))
        assertEquals(listOf(true), controller.calls)
    }

    @Test fun exactModeControllerReplacementDuringApplyCannotAcknowledgeTheOldSurface() {
        val surface = Surface(); val binding = ReaderPdfBookViewPreferencesBinding()
        val replacement = ReaderTestPdfScrollController()
        surface.pdfScroll = object : com.yaprak.reader.reader.PdfScrollController {
            override val enabled = kotlinx.coroutines.flow.MutableStateFlow(false)
            override val navigationMode = kotlinx.coroutines.flow.MutableStateFlow(PdfNavigationMode.PAGED)
            override fun apply(enabled: Boolean) = false
            override fun apply(mode: PdfNavigationMode): Boolean {
                enabled.value = true; navigationMode.value = mode; surface.pdfScroll = replacement; return true
            }
        }
        assertFalse(binding.apply(surface, book, state(BookPdfViewPreferences(navigationMode = PdfNavigationMode.HORIZONTAL_SCROLL))))
        assertFalse(replacement.enabled.value); assertTrue(replacement.calls.isEmpty())
    }

    @Test fun committedNavigationModesApplyToTheActualControllerWithoutReissuingUnrelatedViewCommands() {
        val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
        val controller = surface.pdfScroll as ReaderTestPdfScrollController
        val paged = state(BookPdfViewPreferences(8, 9, PdfFitPreference.WIDTH, true))
        assertTrue(binding.apply(surface, book, paged)); assertEquals(listOf(false), controller.calls)
        val scrolling = paged.copy(preferences = paged.preferences!!.copy(navigationMode = PdfNavigationMode.VERTICAL_SCROLL), revision = 2)
        assertTrue(binding.apply(surface, book, scrolling)); assertTrue(controller.enabled.value)
        assertTrue(binding.apply(surface, book, scrolling)); assertEquals(listOf(false, true), controller.calls)
        assertEquals(1, surface.crops.size); assertEquals(1, surface.fits.size); assertEquals(listOf(true), surface.spreads)
        controller.enabled.value = false // An actual surface drift must be reconciled, even with unchanged durable fields.
        assertTrue(binding.apply(surface, book, scrolling)); assertTrue(controller.enabled.value)
        assertEquals(listOf(false, true, true), controller.calls)
        assertTrue(binding.apply(surface, book, paged.copy(revision = 3))); assertFalse(controller.enabled.value)
        assertEquals(1, surface.crops.size); assertEquals(1, surface.fits.size); assertEquals(1, surface.spreads.size)
        assertEquals(0, surface.navigation); assertFalse(surface.observed)
    }
    @Test fun refusedOrUnacknowledgedNativeModeIsNotAppliedAndAnExplicitRetryDoesNotRepeatAcceptedFields() {
        for (acknowledges in listOf(false, true)) {
            val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
            val controller = surface.pdfScroll as ReaderTestPdfScrollController
            controller.accepts = !acknowledges; controller.acknowledges = acknowledges
            val preference = state(BookPdfViewPreferences(navigationMode = PdfNavigationMode.VERTICAL_SCROLL))
            assertFalse(binding.apply(surface, book, preference)); assertFalse(controller.enabled.value)
            assertEquals(listOf(true), controller.calls)
            controller.accepts = true; controller.acknowledges = true
            assertTrue(binding.apply(surface, book, preference)); assertTrue(controller.enabled.value)
            assertEquals(listOf(true, true), controller.calls)
            assertEquals(1, surface.crops.size); assertEquals(1, surface.fits.size); assertEquals(1, surface.spreads.size)
        }
    }
    @Test fun controllerReplacementDuringModeApplyRevokesTheAcknowledgementAndRetriesAgainstTheNewOwner() {
        val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
        val old = surface.pdfScroll as ReaderTestPdfScrollController; val replacement = ReaderTestPdfScrollController()
        old.afterApply = { surface.pdfScroll = replacement }
        val preference = state(BookPdfViewPreferences(navigationMode = PdfNavigationMode.VERTICAL_SCROLL))
        assertFalse(binding.apply(surface, book, preference)); assertTrue(old.enabled.value); assertFalse(replacement.enabled.value)
        assertTrue(binding.apply(surface, book, preference)); assertTrue(replacement.enabled.value)
        assertEquals(listOf(true), old.calls); assertEquals(listOf(true), replacement.calls)
        assertEquals(2, surface.crops.size); assertEquals(2, surface.fits.size); assertEquals(2, surface.spreads.size)
    }
    @Test fun nativeModeCancellationPropagatesAndOnlyItsUnacceptedCommandIsRetried() {
        val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
        val controller = surface.pdfScroll as ReaderTestPdfScrollController
        controller.failure = CancellationException("controlled mode cancellation")
        val preference = state(BookPdfViewPreferences(navigationMode = PdfNavigationMode.VERTICAL_SCROLL))
        assertTrue(runCatching { binding.apply(surface, book, preference) }.exceptionOrNull() is CancellationException)
        assertFalse(controller.enabled.value); controller.failure = null
        assertTrue(binding.apply(surface, book, preference)); assertTrue(controller.enabled.value)
        assertEquals(listOf(true, true), controller.calls)
        assertEquals(1, surface.crops.size); assertEquals(1, surface.fits.size); assertEquals(1, surface.spreads.size)
    }

    @Test fun `partial native failure retries only unaccepted commands without claiming whole view success`() {
        val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
        val preferences = state(BookPdfViewPreferences(8, 9, PdfFitPreference.WIDTH, true))
        surface.fitFailure = IOException("fit")
        assertTrue(runCatching { binding.apply(surface, book, preferences) }.exceptionOrNull() is IOException)
        assertEquals(listOf(PdfPageCrop(8, 9)), surface.crops)
        assertTrue(surface.fits.isEmpty()); assertTrue(surface.spreads.isEmpty())
        surface.fitFailure = null
        assertTrue(binding.apply(surface, book, preferences))
        assertEquals(1, surface.crops.size)
        assertEquals(listOf(PdfFitPreference.WIDTH), surface.fits); assertEquals(listOf(true), surface.spreads)
    }

    @Test fun `controller cancellation stays cancellation and unsuccessful command is retryable`() {
        val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
        surface.fitFailure = CancellationException("cancelled")
        assertTrue(runCatching { binding.apply(surface, book, state()) }.exceptionOrNull() is CancellationException)
        surface.fitFailure = null
        assertTrue(binding.apply(surface, book, state()))
        assertEquals(1, surface.crops.size); assertEquals(1, surface.fits.size)
    }

    @Test fun `lease invalidated by a reentrant native command blocks later commands and whole view acknowledgment`() {
        val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
        var current = true
        surface.afterCrop = { current = false }
        assertFalse(binding.apply(surface, book, state()) { current })
        assertEquals(1, surface.crops.size); assertTrue(surface.fits.isEmpty()); assertTrue(surface.spreads.isEmpty())
    }

    @Test fun `explicit same crop command resets once without repeating fit or spread`() {
        val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
        assertTrue(binding.apply(surface, book, state()))
        val repeated = state().copy(cropCommand = 1)
        assertTrue(binding.apply(surface, book, repeated)); assertTrue(binding.apply(surface, book, repeated))
        assertEquals(listOf(PdfPageCrop(), PdfPageCrop()), surface.crops)
        assertEquals(1, surface.fits.size); assertEquals(1, surface.spreads.size)
    }

    @Test fun `reentrant capability replacement rejects cached field success and later commands`() {
        val binding = ReaderPdfBookViewPreferencesBinding(); val surface = Surface()
        surface.afterCrop = { surface.pdfSpread = PdfSpreadController { error("obsolete controller") } }
        assertFalse(binding.apply(surface, book, state()))
        assertEquals(1, surface.crops.size); assertTrue(surface.fits.isEmpty()); assertTrue(surface.spreads.isEmpty())
    }
}

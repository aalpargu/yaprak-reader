package com.yaprak.reader.feature

import android.app.Application
import androidx.fragment.app.Fragment
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.settings.BookComicViewPreferences
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReaderComicBookViewPreferencesBindingTest {
    private val book = Book("comic", "Comic", null, BookFormat.CBZ, "comic.cbz", "comic.cbz", 1, "a".repeat(64), 1, 5)
    private fun state(enabled: Boolean = true) = ComicBookViewPreferencesState(ComicBookViewOwner(book.id, book.sha256, 1),
        BookComicViewPreferences(enabled), status = ComicBookViewStatus.APPLYING, revision = 1)
    private class Controller : ComicSpreadController {
        override val preferred = MutableStateFlow(false)
        val restored = mutableListOf<Boolean>(); var accepted = true; var afterRestore: () -> Unit = {}
        override fun apply(enabled: Boolean): Boolean = error("Saved values must use restore, not explicit page action")
        override fun restore(enabled: Boolean): Boolean {
            restored += enabled; if (accepted) preferred.value = enabled; afterRestore(); return accepted
        }
    }
    private class Surface(val controller: Controller = Controller()) : ReaderSurface {
        override val fragment = Fragment()
        override var comicSpread: ComicSpreadController? = controller
        var observations = 0; var navigation = 0
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) { observations++ }
        override fun forward(): Boolean { navigation++; return true }
        override fun backward(): Boolean { navigation++; return true }
    }
    @Test fun verifiedSavedTrueAndFalseUseOnlyNativeRestoreWithoutNavigationOrReadingEvidence() {
        val binding = ReaderComicBookViewPreferencesBinding(); val target = Surface()
        assertTrue(binding.apply(target, book, state())); assertTrue(target.controller.preferred.value)
        assertTrue(binding.apply(target, book, state(false))); assertFalse(target.controller.preferred.value)
        assertEquals(listOf(true, false), target.controller.restored); assertEquals(0, target.navigation); assertEquals(0, target.observations)
    }
    @Test fun pendingWrongBookHashFormatMissingCapabilityAndStaleOwnerIssueNoCommand() {
        val binding = ReaderComicBookViewPreferencesBinding(); val target = Surface()
        for (status in ComicBookViewStatus.entries.filter { it != ComicBookViewStatus.APPLYING })
            assertFalse(binding.apply(target, book, state().copy(status = status)))
        for (other in listOf(book.copy(id = "other"), book.copy(sha256 = "b".repeat(64)), book.copy(format = BookFormat.PDF)))
            assertFalse(binding.apply(target, other, state()))
        assertFalse(binding.apply(target, book, state()) { false }); assertTrue(target.controller.restored.isEmpty())
        target.comicSpread = null; assertFalse(binding.apply(target, book, state()))
    }
    @Test fun rejectedNativeCommandOrLeaseAndControllerChangedDuringRestoreCannotAcknowledge() {
        val binding = ReaderComicBookViewPreferencesBinding(); val target = Surface()
        target.controller.accepted = false; assertFalse(binding.apply(target, book, state())); assertFalse(target.controller.preferred.value)
        target.controller.accepted = true; var current = true; target.controller.afterRestore = { current = false }
        assertFalse(binding.apply(target, book, state()) { current })
        target.controller.afterRestore = { target.comicSpread = Controller() }
        assertFalse(binding.apply(target, book, state()))
        assertEquals(0, target.navigation); assertEquals(0, target.observations)
    }
}

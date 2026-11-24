package com.yaprak.reader.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** A snapshot of the real open source. Presentation ids never authorize a renderer command. */
internal class ReaderNavigationOwner(val session: ReaderSession, val surface: ReaderSurface) {
    private val book: Book = session.book
    private val bookmarkNavigator = surface.bookmarkNavigator

    fun matches(other: ReaderNavigationOwner?): Boolean = other != null &&
        session === other.session && surface === other.surface && book.id == other.book.id &&
        book.sha256 == other.book.sha256 && book.format == other.book.format && bookmarkNavigator === other.bookmarkNavigator
}

/** Deliberate jumps share one bounded history; percent resolution keeps its existing owner gates. */
internal class ReaderNavigationCoordinator(
    scope: CoroutineScope,
    private val currentOwner: () -> ReaderNavigationOwner?,
) {
    val positionJump = BookPositionJumpController(scope)
    val search = ReaderBookSearchController(scope, currentOwner)
    private val history = JumpHistory()
    private var historyOwner: ReaderNavigationOwner? = null
    var canReturn by mutableStateOf(false)
        private set

    private fun admit(owner: ReaderNavigationOwner): Boolean {
        if (!owner.matches(currentOwner())) return false
        if (!owner.matches(historyOwner)) {
            history.clear()
            historyOwner = owner
            canReturn = false
        }
        return true
    }

    /** Capture may call external renderer code: check the same owner and route gate again afterward. */
    fun navigate(jump: Boolean, canNavigate: () -> Boolean, request: () -> Boolean): Boolean {
        val owner = currentOwner() ?: return false
        if (!admit(owner) || !canNavigate()) return false
        val navigator = owner.surface.bookmarkNavigator
        val source = if (jump) try { navigator?.capture()?.location }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        else null
        if (!owner.matches(currentOwner()) || !canNavigate()) return false
        val accepted = request()
        // A synchronous callback can replace/fail the source while the renderer accepts the old target.
        if (!owner.matches(currentOwner())) return false
        if (jump) record(owner, source, accepted)
        return accepted
    }

    fun record(owner: ReaderNavigationOwner, source: ReadingLocation?, accepted: Boolean) {
        if (!admit(owner)) return
        history.record(source, accepted)
        canReturn = history.canReturn
    }

    fun returnToPrevious(): Boolean {
        val owner = currentOwner() ?: return false
        if (!admit(owner)) return false
        val navigator = owner.surface.bookmarkNavigator ?: return false
        val accepted = try {
            history.returnTo { location ->
                owner.matches(currentOwner()) && navigator.goTo(location) && owner.matches(currentOwner())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        canReturn = history.canReturn
        return accepted
    }

    fun clear() {
        history.clear()
        historyOwner = null
        canReturn = false
    }
}

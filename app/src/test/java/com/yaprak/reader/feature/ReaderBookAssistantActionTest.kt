package com.yaprak.reader.feature

import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.BookTextScope
import com.yaprak.reader.feature.reader.ReaderBookAssistantAction
import org.junit.Assert.*
import org.junit.Test

class ReaderBookAssistantActionTest {
    private val book = Book("owned-book", "Owned", null, BookFormat.DOCX, "source.docx", "owned.docx", 1, "a".repeat(64), 1)
    @Test fun explicitActionRoutesOnlyOriginalBookScopeOnceAndNeverRequiresASelectionOrSourceText() {
        for (format in BookFormat.entries) {
            val original = book.copy(format = format); val session = Any(); val surface = Any()
            val action = ReaderBookAssistantAction.create(original, session, surface, 7)
            if (format == BookFormat.CBZ) { assertNull(action); continue }
            val opened = mutableListOf<BookTextScope>()
            assertTrue(action!!.open(original.copy(lastReadAt = 99), session, surface, 7, true) { opened += it })
            assertFalse(action.open(original, session, surface, 7, true) { opened += it })
            assertEquals(listOf(BookTextScope(book.id, book.sha256)), opened)
        }
        assertNull(ReaderBookAssistantAction.create(book.copy(sha256 = "invalid"), Any(), Any(), 0))
        assertNull(ReaderBookAssistantAction.create(book.copy(id = ""), Any(), Any(), 0))
    }
    @Test fun oldLifecycleOwnerSurfaceBookAndBlockedReaderCannotLaunchAndReentrantClickIsConsumedBeforeOpening() {
        val session = Any(); val surface = Any()
        val action = ReaderBookAssistantAction.create(book, session, surface, 7)!!
        var launches = 0
        fun attempt(current: Book? = book, owner: Any? = session, view: Any? = surface, epoch: Long = 7, allowed: Boolean = true) =
            action.open(current, owner, view, epoch, allowed) { launches++ }
        assertFalse(attempt(allowed = false)); assertFalse(attempt(epoch = 8)); assertFalse(attempt(owner = Any()))
        assertFalse(attempt(view = Any())); assertFalse(attempt(current = null))
        for (changed in listOf(book.copy(id = "foreign"), book.copy(sha256 = "b".repeat(64)),
            book.copy(format = BookFormat.EPUB), book.copy(title = "Another rendered title"))) assertFalse(attempt(changed))
        assertEquals(0, launches)
        assertTrue(action.open(book, session, surface, 7, true) { launches++; assertFalse(attempt()) })
        assertEquals(1, launches)
    }
}

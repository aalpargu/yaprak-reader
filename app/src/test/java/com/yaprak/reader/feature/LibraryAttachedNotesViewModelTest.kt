package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.notes.*
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.feature.librarynotes.LibraryNoteFeedbackKind
import com.yaprak.reader.feature.librarynotes.LibraryNotesViewModel
import com.yaprak.reader.reader.SourceTextAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryAttachedNotesViewModelTest {
    private val ordinary = LibraryNote("same", "book", "a".repeat(64), "Kitap", null, BookFormat.EPUB, "Ordinary", 1)
    private val attached = ordinary.copy(text = "  Kaynaklı not\n🌿  ", source = LibraryNoteSource.EPUB_PASSAGE,
        sourceText = "Source quote", sourceAnchor = SourceTextAnchor("OPS/chapter.xhtml", 1, 13, "b".repeat(64)),
        sourceRevision = 7, highlightColor = EpubHighlightColor.GREEN)
    private class Repository(rows: List<LibraryNote>) : LibraryNotesRepository {
        val rows = MutableStateFlow(LibraryNotesResult(rows, rows.size))
        val requested = mutableListOf<LibraryNoteIdentity>()
        var returned: LibraryNote? = null
        override fun observe(query: String) = rows
        override suspend fun find(bookId: String, contentSha256: String, noteId: String) = error("Must resolve typed source")
        override suspend fun find(bookId: String, contentSha256: String, noteId: String, source: LibraryNoteSource): LibraryNote? {
            requested += LibraryNoteIdentity(source, noteId)
            return returned ?: rows.value.entries.singleOrNull {
                it.bookId == bookId && it.contentSha256 == contentSha256 && it.id == noteId && it.source == source
            }
        }
    }
    private suspend fun TestScope.model(block: suspend TestScope.(LibraryNotesViewModel, Repository) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); val repository = Repository(listOf(ordinary, attached))
        try {
            val vm = LibraryNotesViewModel(repository, SavedStateHandle()).also { store.put("notes", it) }
            vm.setActive(true); runCurrent(); block(vm, repository)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun `same id in two sources copies exact selected note and resolves each typed record`() = runTest {
        model { vm, repository ->
            val copies = mutableListOf<String>()
            vm.copy(attached, copies::add); runCurrent()
            vm.copy(ordinary, copies::add); runCurrent()
            assertEquals(listOf(attached.text, ordinary.text), copies)
            assertEquals(listOf(attached.identity, ordinary.identity), repository.requested)
            assertEquals(2, vm.state.value.entries.size)
        }
    }

    @Test fun `fresh source revision anchor or color blocks copy even when attached note text stays equal`() = runTest {
        model { vm, repository ->
            var copied = false
            val changed = listOf(attached.copy(sourceRevision = 8), attached.copy(highlightColor = EpubHighlightColor.PINK),
                attached.copy(sourceAnchor = (attached.sourceAnchor as SourceTextAnchor).copy(start = 2, end = 14)))
            for (record in changed) {
                repository.returned = record
                vm.copy(attached) { copied = true }; runCurrent()
                assertFalse(copied)
                assertEquals(LibraryNoteFeedbackKind.CHANGED, vm.state.value.feedback!!.kind)
            }
        }
    }

    @Test fun `removed attached result cannot use ordinary same-id record for copy or open`() = runTest {
        model { vm, repository ->
            repository.rows.value = LibraryNotesResult(listOf(ordinary), 1); runCurrent()
            var copied = false; var opened = false
            vm.copy(attached) { copied = true }; vm.open(attached) { opened = true }; runCurrent()
            assertFalse(copied); assertFalse(opened)
            assertTrue(repository.requested.isEmpty())
            assertEquals(listOf(ordinary), vm.state.value.entries)
        }
    }
}

package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.notes.BookNote
import com.yaprak.reader.domain.notes.BookNoteRepository
import com.yaprak.reader.feature.notes.BookNoteSort
import com.yaprak.reader.feature.notes.BookNotesViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookNotesViewModelTest {
    private val note = BookNote("note-1", "Bir düşünce", 1)

    private class Repository : BookNoteRepository {
        val entries = MutableStateFlow<List<BookNote>>(emptyList())
        var failRead = false
        var failWrite = false
        var addCalls = 0
        var addAtPageCalls = 0
        var lastPageIndex: Int? = null
        var observeCalls = 0
        var updateCalls = 0
        var removeCalls = 0
        var lastBookId: String? = null
        var lastHash: String? = null
        var lastText: String? = null
        var lastRemovedId: String? = null
        var lastUpdatedId: String? = null

        override fun observe(bookId: String, contentSha256: String) = flow {
            observeCalls++
            lastBookId = bookId
            lastHash = contentSha256
            if (failRead) throw IOException()
            emitAll(entries)
        }

        override suspend fun add(bookId: String, contentSha256: String, text: String) {
            addCalls++
            lastBookId = bookId
            lastHash = contentSha256
            lastText = text
            delay(100)
            if (failWrite) throw IOException()
            entries.value = entries.value + BookNote("new", text, 2)
        }

        override suspend fun addAtPdfPage(bookId: String, contentSha256: String, text: String, pageIndex: Int) {
            addAtPageCalls++
            lastBookId = bookId
            lastHash = contentSha256
            lastText = text
            lastPageIndex = pageIndex
            delay(100)
            if (failWrite) throw IOException()
            entries.value = entries.value + BookNote("page-note", text, 2, pageIndex)
        }

        override suspend fun remove(bookId: String, contentSha256: String, id: String) {
            removeCalls++
            lastBookId = bookId
            lastHash = contentSha256
            lastRemovedId = id
            delay(100)
            if (failWrite) throw IOException()
            entries.value = entries.value.filterNot { it.id == id }
        }

        override suspend fun update(bookId: String, contentSha256: String, id: String, text: String) {
            updateCalls++
            lastBookId = bookId
            lastHash = contentSha256
            lastUpdatedId = id
            lastText = text
            delay(100)
            if (failWrite) throw IOException()
            entries.value = entries.value.map { if (it.id == id) it.copy(text = text) else it }
        }
    }

    @Test fun `manual multiline draft validates UTF16 length and failed write stays retryable`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failWrite = true }
            val model = BookNotesViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.editDraft("  \n  ")
            assertFalse(model.state.value.canSave)
            model.add()
            assertEquals(0, repository.addCalls)
            model.editDraft("😀".repeat(1001)) // 2002 UTF-16 code units
            assertFalse(model.state.value.canSave)
            model.editDraft("  İlk satır\nİkinci satır  ")
            assertTrue(model.state.value.canSave)
            model.add()
            model.add()
            runCurrent()
            assertTrue(model.state.value.busy)
            assertEquals(1, repository.addCalls)
            model.clearDraft()
            assertEquals("  İlk satır\nİkinci satır  ", model.state.value.draft)
            advanceUntilIdle()
            assertEquals("  İlk satır\nİkinci satır  ", model.state.value.draft)
            assertTrue(model.state.value.entries.isEmpty())
            assertEquals("Not kaydedilemedi. Yeniden deneyebilirsin.", model.state.value.message)

            repository.failWrite = false
            model.add()
            advanceUntilIdle()
            assertEquals(2, repository.addCalls)
            assertEquals("book-a", repository.lastBookId)
            assertEquals("hash-a", repository.lastHash)
            assertEquals("İlk satır\nİkinci satır", repository.lastText)
            assertEquals(listOf("İlk satır\nİkinci satır"), model.state.value.entries.map { it.text })
            assertEquals("", model.state.value.draft)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `PDF page note captures displayed page only when save is pressed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val model = BookNotesViewModel("pdf-book", "pdf-hash", repository).also { owner.put("vm", it) }
            runCurrent()
            var renderedPage = 2
            var captureCalls = 0
            model.editDraft("  Bu sayfanın notu  ")
            assertEquals(0, captureCalls)
            model.addAtPdfPage { captureCalls++; renderedPage }
            renderedPage = 6
            runCurrent()
            assertEquals(1, captureCalls)
            assertEquals(1, repository.addAtPageCalls)
            assertEquals(2, repository.lastPageIndex)
            assertEquals("pdf-book", repository.lastBookId)
            assertEquals("pdf-hash", repository.lastHash)
            advanceUntilIdle()
            assertEquals("Bu sayfanın notu", repository.lastText)
            assertEquals(2, model.state.value.entries.single().pdfPageIndex)
            assertEquals(0, repository.addCalls)
            assertEquals("", model.state.value.draft)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `unavailable PDF page fails closed and retry takes a fresh page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failWrite = true }
            val model = BookNotesViewModel("pdf-book", "pdf-hash", repository).also { owner.put("vm", it) }
            runCurrent()
            model.editDraft("Sayfa notu")
            var page: Int? = null
            var captureCalls = 0
            val capture = { captureCalls++; page }
            model.addAtPdfPage(capture)
            assertEquals(1, captureCalls)
            assertEquals(0, repository.addAtPageCalls)
            assertEquals("Sayfa notu", model.state.value.draft)
            assertEquals("Görüntülenen PDF sayfası doğrulanamadı. Not kaydedilmedi.", model.state.value.message)
            page = -1
            model.addAtPdfPage(capture)
            assertEquals(0, repository.addAtPageCalls)
            page = 3
            model.addAtPdfPage(capture)
            runCurrent()
            assertEquals(1, repository.addAtPageCalls)
            advanceUntilIdle()
            assertEquals("Sayfa notu", model.state.value.draft)
            assertTrue(model.state.value.entries.isEmpty())
            repository.failWrite = false
            page = 5
            model.addAtPdfPage(capture)
            advanceUntilIdle()
            assertEquals(4, captureCalls)
            assertEquals(2, repository.addAtPageCalls)
            assertEquals(5, repository.lastPageIndex)
            assertEquals(5, model.state.value.entries.single().pdfPageIndex)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `PDF page capture exception and busy state never create a note`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val model = BookNotesViewModel("pdf-book", "pdf-hash", repository).also { owner.put("vm", it) }
            runCurrent()
            model.editDraft("Sayfa notu")
            model.addAtPdfPage { throw IllegalStateException("renderer stopped") }
            assertEquals(0, repository.addAtPageCalls)
            assertEquals("Sayfa notu", model.state.value.draft)
            model.addAtPdfPage { 1 }
            model.addAtPdfPage { error("must not capture during write") }
            runCurrent()
            assertEquals(1, repository.addAtPageCalls)
            advanceUntilIdle()
            assertEquals(1, model.state.value.entries.single().pdfPageIndex)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `selected text prefills only transient editable draft until explicit save`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val model = BookNotesViewModel("book-selection", "hash-selection", repository)
                .also { owner.put("vm", it) }
            runCurrent()
            model.prefillDraft("  Seçilen Türkçe satır\n😀  ")
            assertEquals("Seçilen Türkçe satır\n😀", model.state.value.draft)
            assertTrue(model.state.value.canSave)
            assertEquals(0, repository.addCalls)
            assertTrue(model.state.value.entries.isEmpty())
            model.editDraft("  Düzenlenmiş Türkçe not  ")
            assertEquals(0, repository.addCalls)
            model.add()
            advanceUntilIdle()
            assertEquals(1, repository.addCalls)
            assertEquals("book-selection", repository.lastBookId)
            assertEquals("hash-selection", repository.lastHash)
            assertEquals("Düzenlenmiş Türkçe not", repository.lastText)
            assertEquals("", model.state.value.draft)
            assertEquals(listOf("Düzenlenmiş Türkçe not"), model.state.value.entries.map { it.text })
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `selected text prefill rejects empty and overlong UTF16 without replacing draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository()
            val model = BookNotesViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.prefillDraft("  \n  ")
            assertEquals("", model.state.value.draft)
            model.prefillDraft("  " + "İ".repeat(1998) + "😀" + "  ")
            assertEquals(2000, model.state.value.draft.length)
            assertTrue(model.state.value.canSave)
            model.prefillDraft("😀".repeat(1001))
            assertEquals(2000, model.state.value.draft.length)
            model.prefillDraft(" ")
            assertEquals(2000, model.state.value.draft.length)
            assertEquals(0, repository.addCalls)
            model.clearSession()
            assertEquals("", model.state.value.draft)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `selected text prefill cannot overwrite busy or pending actions`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(note) }
            val model = BookNotesViewModel("book-b", "hash-b", repository).also { owner.put("vm", it) }
            runCurrent()
            model.editDraft("Elle yazılmış")
            model.requestDelete(note.id)
            model.prefillDraft("Silme onayında")
            assertEquals("Elle yazılmış", model.state.value.draft)
            model.cancelDelete()
            model.requestEdit(note.id)
            model.prefillDraft("Düzenleme açıkken")
            assertEquals("Elle yazılmış", model.state.value.draft)
            model.cancelEdit()
            model.add()
            assertTrue(model.state.value.busy)
            model.prefillDraft("Kaydetme sürerken")
            assertEquals("Elle yazılmış", model.state.value.draft)
            advanceUntilIdle()
            assertEquals(1, repository.addCalls)
            assertEquals("Elle yazılmış", repository.lastText)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `delete needs confirmation and failed removal preserves persistent entry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(note) }
            val model = BookNotesViewModel("book-b", "hash-b", repository).also { owner.put("vm", it) }
            runCurrent()
            model.requestDelete("missing")
            assertNull(model.state.value.pendingDelete)
            model.requestDelete(note.id)
            model.cancelDelete()
            model.delete()
            assertEquals(0, repository.removeCalls)

            repository.failWrite = true
            model.requestDelete(note.id)
            model.delete()
            model.delete()
            runCurrent()
            assertEquals(1, repository.removeCalls)
            assertTrue(model.state.value.busy)
            advanceUntilIdle()
            assertEquals(note, model.state.value.pendingDelete)
            assertEquals(listOf(note), model.state.value.entries)
            assertEquals("Not silinemedi. Yeniden deneyebilirsin.", model.state.value.message)
            repository.failWrite = false
            model.delete()
            advanceUntilIdle()
            assertEquals(2, repository.removeCalls)
            assertEquals("book-b", repository.lastBookId)
            assertEquals("hash-b", repository.lastHash)
            assertEquals(note.id, repository.lastRemovedId)
            assertTrue(model.state.value.entries.isEmpty())
            assertNull(model.state.value.pendingDelete)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `read failure blocks writes and reload retains unsaved draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { failRead = true }
            val model = BookNotesViewModel("book-a", "hash-a", repository).also { owner.put("vm", it) }
            runCurrent()
            model.editDraft("Taslak")
            model.add()
            assertTrue(model.state.value.loadFailed)
            assertEquals(0, repository.addCalls)
            repository.failRead = false
            model.reload()
            runCurrent()
            assertTrue(model.state.value.canSave)
            assertEquals("Taslak", model.state.value.draft)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `edit targets id validates trimmed UTF16 and commits only after update succeeds`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val sameText = note.copy(id = "note-2", createdAt = 3, pdfPageIndex = 4)
            val repository = Repository().apply { entries.value = listOf(note, sameText) }
            val model = BookNotesViewModel("book-c", "hash-c", repository).also { owner.put("vm", it) }
            runCurrent()
            model.requestEdit("missing")
            assertNull(model.state.value.pendingEdit)
            model.requestEdit(sameText.id)
            assertEquals(sameText, model.state.value.pendingEdit)
            assertEquals(sameText.text, model.state.value.editDraft)
            assertFalse(model.state.value.canUpdate)
            model.editSelectedDraft("  ${sameText.text}  ")
            assertFalse(model.state.value.canUpdate)
            model.editSelectedDraft("  ")
            assertFalse(model.state.value.canUpdate)
            model.editSelectedDraft("😀".repeat(1001))
            assertFalse(model.state.value.canUpdate)
            model.editSelectedDraft("  Yeni metin\nİkinci satır  ")
            assertTrue(model.state.value.canUpdate)
            assertFalse(model.state.value.canSave)
            model.requestDelete(note.id)
            model.add()
            assertNull(model.state.value.pendingDelete)
            assertEquals(0, repository.addCalls)
            repository.failWrite = true
            model.saveEdit()
            model.saveEdit()
            runCurrent()
            assertTrue(model.state.value.busy)
            assertEquals(1, repository.updateCalls)
            assertEquals(listOf(note, sameText), model.state.value.entries)
            model.cancelEdit()
            model.editSelectedDraft("Başka")
            assertEquals("  Yeni metin\nİkinci satır  ", model.state.value.editDraft)
            advanceUntilIdle()
            assertEquals(listOf(note, sameText), model.state.value.entries)
            assertEquals(sameText, model.state.value.pendingEdit)
            assertEquals("Not düzenlenemedi. Yeniden deneyebilirsin.", model.state.value.message)
            repository.failWrite = false
            model.saveEdit()
            advanceUntilIdle()
            assertEquals(2, repository.updateCalls)
            assertEquals("book-c", repository.lastBookId)
            assertEquals("hash-c", repository.lastHash)
            assertEquals(sameText.id, repository.lastUpdatedId)
            assertEquals("Yeni metin\nİkinci satır", repository.lastText)
            assertEquals(listOf(note, sameText.copy(text = "Yeni metin\nİkinci satır")), model.state.value.entries)
            assertNull(model.state.value.pendingEdit)
            assertEquals("", model.state.value.editDraft)
            assertEquals(0, repository.removeCalls)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `cancelling edit keeps saved note and separate add draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(note) }
            val model = BookNotesViewModel("book-d", "hash-d", repository).also { owner.put("vm", it) }
            runCurrent()
            model.editDraft("Yeni not taslağı")
            model.requestEdit(note.id)
            model.editDraft("Ana taslak değişmemeli")
            assertEquals("Yeni not taslağı", model.state.value.draft)
            model.editSelectedDraft("Düzeltilmiş metin")
            model.cancelEdit()
            assertEquals("Yeni not taslağı", model.state.value.draft)
            assertEquals(listOf(note), model.state.value.entries)
            assertEquals(0, repository.updateCalls)
            model.requestEdit(note.id)
            assertEquals(note.text, model.state.value.editDraft)
            model.cancelEdit()
            model.clearSession()
            assertEquals("", model.state.value.draft)
            assertEquals("", model.state.value.editDraft)
            assertNull(model.state.value.pendingEdit)
            assertNull(model.state.value.pendingDelete)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `search filters only saved notes without repository writes or changing add draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val second = BookNote("note-2", "Sessiz sabah", 2)
            val repository = Repository().apply { entries.value = listOf(note, second) }
            val model = BookNotesViewModel("book-search", "hash-search", repository)
                .also { owner.put("vm", it) }
            runCurrent()
            model.editDraft("Taslakta aranan sözcük")
            model.search("aranan")
            assertTrue(model.state.value.visibleEntries.isEmpty())
            model.search("sabah")
            assertEquals(listOf(second), model.state.value.visibleEntries)
            assertEquals(listOf(note, second), model.state.value.entries)
            assertEquals("Taslakta aranan sözcük", model.state.value.draft)
            assertEquals(0, repository.addCalls + repository.updateCalls + repository.removeCalls)
            model.clearSearch()
            assertEquals(listOf(note, second), model.state.value.visibleEntries)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `search remains through reload and recomputes as saved list changes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(note) }
            val model = BookNotesViewModel("book-search", "hash-search", repository)
                .also { owner.put("vm", it) }
            runCurrent()
            model.search("IŞIĞI")
            assertTrue(model.state.value.visibleEntries.isEmpty())
            model.reload()
            runCurrent()
            assertEquals("IŞIĞI", model.state.value.query)
            repository.entries.value = listOf(note, BookNote("note-2", "İstanbul ışığı", 2))
            runCurrent()
            assertEquals(listOf("note-2"), model.state.value.visibleEntries.map { it.id })
            repository.entries.value = listOf(note)
            runCurrent()
            assertTrue(model.state.value.visibleEntries.isEmpty())
            assertEquals(0, repository.addCalls + repository.updateCalls + repository.removeCalls)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `query is capped safely and cleared with dialog session`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = Repository().apply { entries.value = listOf(note) }
            val model = BookNotesViewModel("book-search", "hash-search", repository)
                .also { owner.put("vm", it) }
            runCurrent()
            model.search("x".repeat(198) + "😀y")
            assertEquals(200, model.state.value.query.length)
            assertEquals("x".repeat(198) + "😀", model.state.value.query)
            model.search("x".repeat(199) + "😀y")
            assertEquals(199, model.state.value.query.length)
            assertEquals("x".repeat(199), model.state.value.query)
            model.editDraft("Kaydedilmemiş")
            model.clearSession()
            assertEquals("", model.state.value.query)
            assertEquals("", model.state.value.draft)
            assertEquals(listOf(note), model.state.value.visibleEntries)
            assertEquals(0, repository.addCalls + repository.updateCalls + repository.removeCalls)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `sort composes with search and changes only visible saved notes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val first = BookNote("first", "Zeytin üstüne", 3)
            val second = BookNote("second", "Çınar üstüne", 2)
            val third = BookNote("third", "Elma altında", 1)
            val repository = Repository().apply { entries.value = listOf(first, second, third) }
            val model = BookNotesViewModel("book-sort", "hash-sort", repository)
                .also { owner.put("vm", it) }
            runCurrent()
            assertEquals(BookNoteSort.RECORD_ORDER, model.state.value.sort)
            assertEquals(listOf("first", "second", "third"), model.state.value.visibleEntries.map { it.id })
            model.editDraft("Üstüne taslak")
            model.search("üstüne")
            assertEquals(listOf("first", "second"), model.state.value.visibleEntries.map { it.id })
            model.setSort(BookNoteSort.TEXT_ASC)
            assertEquals(listOf("second", "first"), model.state.value.visibleEntries.map { it.id })
            assertEquals(listOf(first, second, third), model.state.value.entries)
            assertEquals("Üstüne taslak", model.state.value.draft)
            model.clearSearch()
            assertEquals(listOf("second", "third", "first"), model.state.value.visibleEntries.map { it.id })
            assertEquals(1, repository.observeCalls)
            assertEquals(0, repository.addCalls + repository.updateCalls + repository.removeCalls)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `sort stays selected through reload and resets on closing note session`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val first = BookNote("first", "Zeytin", 2)
            val second = BookNote("second", "Çınar", 1)
            val repository = Repository().apply { entries.value = listOf(first, second) }
            val model = BookNotesViewModel("book-sort", "hash-sort", repository)
                .also { owner.put("vm", it) }
            runCurrent()
            model.setSort(BookNoteSort.TEXT_ASC)
            model.search("n")
            model.reload()
            runCurrent()
            assertEquals(BookNoteSort.TEXT_ASC, model.state.value.sort)
            assertEquals("n", model.state.value.query)
            assertEquals(listOf("second", "first"), model.state.value.visibleEntries.map { it.id })
            repository.entries.value = listOf(first, second, BookNote("third", "Elma", 0))
            runCurrent()
            assertEquals(listOf("second", "first"), model.state.value.visibleEntries.map { it.id })
            model.clearSession()
            assertEquals(BookNoteSort.RECORD_ORDER, model.state.value.sort)
            assertEquals("", model.state.value.query)
            assertEquals(listOf("first", "second", "third"), model.state.value.visibleEntries.map { it.id })
            assertEquals(2, repository.observeCalls)
            assertEquals(0, repository.addCalls + repository.updateCalls + repository.removeCalls)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}

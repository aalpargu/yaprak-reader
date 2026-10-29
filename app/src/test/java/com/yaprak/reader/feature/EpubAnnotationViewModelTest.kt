package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.passages.*
import com.yaprak.reader.feature.passages.*
import com.yaprak.reader.reader.SourceTextAnchor
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpubAnnotationViewModelTest {
    @Test fun paragraphRangeSaveKeepsNativePreviewSeparatorsAndOneSharedColorNote() = checkModel { owner ->
        val range = com.yaprak.reader.reader.SourceRangeAnchor("chapter.xhtml", listOf(
            com.yaprak.reader.reader.SourceRangeSegment("0/0", 0, 4, "a".repeat(64), "Leaf"),
            com.yaprak.reader.reader.SourceRangeSegment("1/0", 0, 6, "b".repeat(64), "Across")))
        val preview = "Leaf\nAcross"
        val repository = Repository()
        val grouped = request.copy(text = preview, anchor = range)
        val model = EpubAnnotationViewModel(grouped, repository).also { owner.put("range", it) }
        model.editColor(EpubHighlightColor.BLUE); model.editNote("İki paragraf 🌿")
        model.save(); model.save(); advanceUntilIdle()
        val record = repository.records.value.single()
        assertEquals(range, record.anchor)
        assertEquals(preview, record.text)
        assertNotEquals(preview, range.segments.joinToString("") { it.selectedText })
        assertEquals(EpubHighlightColor.BLUE, record.highlightColor)
        assertEquals("İki paragraf 🌿", record.attachedNote)
        assertEquals(1, repository.creates.size)
        assertTrue(model.state.value.completed)
        val list = EpubPassagesViewModel("book", "hash", repository).also { owner.put("range-list", it) }
        runCurrent(); list.prefill(preview, range)
        assertTrue(list.state.value.alreadySaved)
        assertFalse(list.state.value.canSave)
    }

    private val anchor = SourceTextAnchor("chapter.xhtml", 0, 4, "a".repeat(64))
    private val request = EpubAnnotationRequest("selection-session", "book", "hash", "Leaf", anchor)
    private val saved = EpubPassage("passage", "Leaf", anchor, 1, EpubHighlightColor.YELLOW, " old note ", 3)

    private class Repository : EpubPassageRepository {
        val records = MutableStateFlow<List<EpubPassage>>(emptyList())
        val creates = mutableListOf<EpubPassage>()
        val updates = mutableListOf<EpubPassage>()
        val deletes = mutableListOf<EpubPassage>()
        var identity: Pair<String, String>? = null
        var fail = false
        var cancelled = false
        override fun observe(bookId: String, contentSha256: String) = records
        override suspend fun add(bookId: String, contentSha256: String, text: String, anchor: com.yaprak.reader.reader.EpubSourceAnchor) = error("Use annotation API")
        override suspend fun remove(bookId: String, contentSha256: String, id: String) = error("Use expected record API")
        override suspend fun createAnnotation(bookId: String, contentSha256: String, text: String,
            anchor: com.yaprak.reader.reader.EpubSourceAnchor, highlightColor: EpubHighlightColor?, attachedNote: String?): EpubPassage {
            identity = bookId to contentSha256
            val result = EpubPassage("new", text, anchor, 2, highlightColor, attachedNote)
            creates += result
            try { delay(100) } catch (failure: CancellationException) { cancelled = true; throw failure }
            if (fail) throw IOException("write")
            records.value = records.value + result
            return result
        }
        override suspend fun updateAnnotation(bookId: String, contentSha256: String, expected: EpubPassage,
            highlightColor: EpubHighlightColor?, attachedNote: String?): EpubPassage {
            identity = bookId to contentSha256
            updates += expected
            delay(100)
            if (fail) throw IOException("write")
            if (records.value.singleOrNull { it.id == expected.id } != expected) throw EpubPassageChangedException()
            val result = expected.copy(highlightColor = highlightColor, attachedNote = attachedNote, revision = expected.revision + 1)
            records.value = records.value.map { if (it.id == expected.id) result else it }
            return result
        }
        override suspend fun removeExpected(bookId: String, contentSha256: String, expected: EpubPassage) {
            deletes += expected
            delay(100)
            if (records.value.singleOrNull { it.id == expected.id } != expected) throw EpubPassageChangedException()
            records.value = records.value.filterNot { it.id == expected.id }
        }
    }

    private fun checkModel(block: suspend TestScope.(ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try { block(owner) } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `explicit save sends exact source identity color and Unicode note once`() = checkModel { owner ->
        val repository = Repository()
        val model = EpubAnnotationViewModel(request, repository).also { owner.put("create", it) }
        val note = "  Çığ\n😀\tNUL:\u0000  "
        model.editColor(EpubHighlightColor.PINK); model.editNote(note)
        assertTrue(repository.creates.isEmpty()); assertTrue(model.state.value.canSave)
        model.save(); model.save(); runCurrent()
        assertTrue(model.state.value.busy)
        model.editColor(null); model.editNote("replacement")
        advanceUntilIdle()
        assertEquals("book" to "hash", repository.identity)
        assertEquals(EpubPassage("new", "Leaf", anchor, 2, EpubHighlightColor.PINK, note), repository.creates.single())
        assertTrue(model.state.value.completed); assertFalse(model.state.value.canSave)
        model.save(); assertEquals(1, repository.creates.size)
    }

    @Test fun `note only and color only records are valid while empty annotation is rejected`() = checkModel { owner ->
        val repository = Repository()
        val model = EpubAnnotationViewModel(request, repository).also { owner.put("create", it) }
        model.editColor(null); assertFalse(model.state.value.canSave); model.save()
        model.editNote("  "); assertFalse(model.state.value.canSave); model.save()
        model.editNote("note only"); assertTrue(model.state.value.canSave); model.save(); advanceUntilIdle()
        assertNull(repository.creates.single().highlightColor)
        assertEquals("note only", repository.creates.single().attachedNote)
        val second = EpubAnnotationViewModel(request.copy(sessionId = "second", anchor = anchor.copy(start = 4, end = 8)), repository)
            .also { owner.put("second", it) }
        second.save(); advanceUntilIdle()
        assertNull(repository.creates.last().attachedNote)
        assertEquals(EpubHighlightColor.YELLOW, repository.creates.last().highlightColor)
    }

    @Test fun `invalid note boundaries and lone surrogate prevent writes without trimming draft`() = checkModel { owner ->
        val repository = Repository()
        val model = EpubAnnotationViewModel(request, repository).also { owner.put("create", it) }
        listOf("\n\t ", "x".repeat(2001), "\uD800", "x\uDC00").forEach { invalid ->
            model.editNote(invalid); assertEquals(invalid, model.state.value.note)
            assertFalse(model.state.value.canSave); model.save()
        }
        assertTrue(repository.creates.isEmpty())
        model.editNote("😀".repeat(1000)); assertTrue(model.state.value.canSave)
        model.save(); advanceUntilIdle(); assertEquals(2000, repository.creates.single().attachedNote!!.length)
    }

    @Test fun `failed save preserves exact draft and retries the admitted fields`() = checkModel { owner ->
        val repository = Repository().apply { fail = true }
        val model = EpubAnnotationViewModel(request, repository).also { owner.put("create", it) }
        model.editColor(EpubHighlightColor.BLUE); model.editNote(" note\n ")
        model.save(); advanceUntilIdle()
        assertTrue(model.state.value.failed); assertFalse(model.state.value.completed)
        assertEquals(" note\n ", model.state.value.note); assertTrue(model.state.value.canSave)
        repository.fail = false; model.save(); advanceUntilIdle()
        assertTrue(model.state.value.completed); assertFalse(model.state.value.failed)
        assertEquals(2, repository.creates.size)
    }

    @Test fun `disposing after admitted save lets that write settle but does not dismiss replacement session`() = checkModel { owner ->
        val repository = Repository()
        val old = EpubAnnotationViewModel(request, repository).also { owner.put("old", it) }
        old.save(); runCurrent(); old.close()
        val current = EpubAnnotationViewModel(request.copy(sessionId = "replacement"), repository).also { owner.put("new", it) }
        current.editNote("new draft"); advanceUntilIdle()
        assertTrue(old.state.value.closed); assertFalse(old.state.value.completed)
        assertFalse(current.state.value.completed); assertEquals("new draft", current.state.value.note)
        old.editNote("old callback"); old.save(); assertEquals(1, repository.creates.size)
    }

    @Test fun `owner cancellation cancels active write and propagates without a false save error`() = checkModel { owner ->
        val repository = Repository()
        val model = EpubAnnotationViewModel(request, repository).also { owner.put("create", it) }
        model.save(); runCurrent(); owner.clear(); runCurrent()
        assertTrue(repository.cancelled); assertTrue(repository.records.value.isEmpty())
        assertFalse(model.state.value.failed); assertFalse(model.state.value.completed); assertFalse(model.state.value.busy)
    }

    @Test fun `editor independently clears color and note while keeping immutable source and revision`() = checkModel { owner ->
        val repository = Repository().apply { records.value = listOf(saved) }
        val model = EpubPassagesViewModel("book", "hash", repository).also { owner.put("list", it) }
        runCurrent(); model.requestEdit(saved); model.editColor(null); model.update(); advanceUntilIdle()
        val withoutColor = repository.records.value.single()
        assertEquals(saved.copy(highlightColor = null, revision = 4), withoutColor)
        model.requestEdit(withoutColor); model.editNote(""); model.update(); advanceUntilIdle()
        assertEquals(saved.copy(highlightColor = null, attachedNote = null, revision = 5), repository.records.value.single())
        assertEquals(listOf(saved, withoutColor), repository.updates)
        assertNull(model.state.value.pendingEdit)
    }

    @Test fun `same value newer revision invalidates editor and delete without overwriting newer record`() = checkModel { owner ->
        val repository = Repository().apply { records.value = listOf(saved) }
        val model = EpubPassagesViewModel("book", "hash", repository).also { owner.put("list", it) }
        runCurrent(); model.requestEdit(saved); model.editNote("new draft")
        repository.records.value = listOf(saved.copy(revision = 4)); runCurrent()
        assertTrue(model.state.value.editChanged); assertFalse(model.state.value.canUpdate)
        model.editNote("late edit"); model.update(); assertTrue(repository.updates.isEmpty())
        assertEquals("new draft", model.state.value.editNote)
        model.cancelEdit(); model.requestDelete(saved.id)
        repository.records.value = listOf(saved.copy(revision = 5)); runCurrent()
        assertTrue(model.state.value.deleteChanged); model.delete(); assertTrue(repository.deletes.isEmpty())
    }

    @Test fun `CAS rejection after write admission retains editor draft and never substitutes newer expected record`() = checkModel { owner ->
        val repository = Repository().apply { records.value = listOf(saved) }
        val model = EpubPassagesViewModel("book", "hash", repository).also { owner.put("list", it) }
        runCurrent(); model.requestEdit(saved); model.editNote("draft"); model.update(); runCurrent()
        val newer = saved.copy(attachedNote = "external", revision = 4)
        repository.records.value = listOf(newer); advanceUntilIdle()
        assertEquals(saved, repository.updates.single()); assertEquals(newer, repository.records.value.single())
        assertEquals(saved, model.state.value.pendingEdit); assertEquals("draft", model.state.value.editNote)
        assertTrue(model.state.value.editChanged); assertNotNull(model.state.value.message)
    }

    @Test fun `replaced list session discards pending write feedback and ignores old dispose token`() = checkModel { owner ->
        val repository = Repository().apply { records.value = listOf(saved); fail = true }
        val model = EpubPassagesViewModel("book", "hash", repository).also { owner.put("list", it) }
        runCurrent(); model.startSession("old"); model.requestEdit(saved); model.editNote("draft"); model.update(); runCurrent()
        model.startSession("new"); model.clearSession("old"); advanceUntilIdle()
        assertEquals("new", model.state.value.sessionId)
        assertNull(model.state.value.pendingEdit); assertEquals("", model.state.value.editNote)
        assertNull(model.state.value.message); assertFalse(model.state.value.busy)
    }

    @Test fun `saved passage search combines Turkish folded quote and attached note without mutation`() {
        val annotated = saved.copy(text = "Dependency Injection", attachedNote = "Çığ ışık İstanbul")
        assertEquals(listOf(annotated), searchEpubPassages(listOf(annotated, saved), "dependency IŞIK"))
        assertTrue(searchEpubPassages(listOf(annotated), "dependency missing").isEmpty())
        assertEquals("Çığ ışık İstanbul", annotated.attachedNote)
    }
}

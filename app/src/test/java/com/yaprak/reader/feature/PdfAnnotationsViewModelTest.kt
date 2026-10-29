package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.feature.pdfannotations.*
import com.yaprak.reader.reader.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfAnnotationsViewModelTest {
    private fun request(expected: PdfAnnotation?=null)=PdfAnnotationRequest("book","a".repeat(64),PDF_TEST_TEXT,PDF_TEST_ANCHOR,expected)
    @Test fun createUsesOneExplicitSaveAndRetainsExactTextColorNoteAndNativeGeometry() = checkModel { owner ->
        val repo=FakePdfAnnotationsRepository(); val model=PdfAnnotationEditorViewModel(request(),repo).also { owner.put("create",it) }
        runCurrent(); assertEquals(0,repo.creates)
        model.editColor(PdfHighlightColor.BLUE); model.editNote("  bağlı 🌿\nnot  "); model.save { true }; model.save { true }; advanceUntilIdle()
        val record=repo.records.value.single(); assertEquals(PDF_TEST_TEXT,record.text); assertEquals(PDF_TEST_ANCHOR,record.anchor)
        assertEquals("  bağlı 🌿\nnot  ",record.attachedNote); assertEquals(PdfHighlightColor.BLUE,record.highlightColor)
        assertEquals(1,repo.creates); assertTrue(model.state.value.completed)
    }
    @Test fun cancellingTheRequestWhileNativeProofIsUninterruptiblePreventsLateWriteAndCompletion() = checkModel { owner ->
        val repo=FakePdfAnnotationsRepository(); val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        repo.beforeCreate={ withContext(NonCancellable) { entered.complete(Unit); release.await() } }
        val request=request(); val model=PdfAnnotationEditorViewModel(request,repo).also { owner.put("cancel",it) }
        model.save { true }; runCurrent(); assertTrue(entered.isCompleted); request.close(); release.complete(Unit); advanceUntilIdle()
        assertTrue(repo.records.value.isEmpty()); assertFalse(model.state.value.completed); model.save { true }; advanceUntilIdle(); assertEquals(1,repo.creates)
    }
    @Test fun invalidDraftsAndLostActivityAdmissionNeverReachTheRepository() = checkModel { owner ->
        val repo=FakePdfAnnotationsRepository(); val model=PdfAnnotationEditorViewModel(request(),repo).also { owner.put("invalid",it) }
        for(note in listOf(" ","x".repeat(2001),"bad\u0000text","bad\uD800")) { model.editNote(note); model.save { true }; advanceUntilIdle(); assertFalse(model.state.value.canSave) }
        model.editNote(""); model.editColor(null); model.save { true }; advanceUntilIdle(); assertEquals(0,repo.creates)
        model.editColor(PdfHighlightColor.GREEN); model.save { false }; advanceUntilIdle(); assertEquals(0,repo.creates)
    }
    @Test fun sourceLimitAndDuplicateFailuresAreVisibleWithoutSuccessfulCompletion() = checkModel { owner ->
        for ((index,failure) in listOf(PdfAnnotationSourceException(),PdfAnnotationLimitException(),PdfAnnotationChangedException()).withIndex()) {
            val repo=FakePdfAnnotationsRepository(); repo.beforeCreate={ throw failure }
            val model=PdfAnnotationEditorViewModel(request(),repo).also { owner.put("failure$index",it) }
            model.save { true }; advanceUntilIdle()
            assertEquals(listOf(PdfAnnotationError.SOURCE,PdfAnnotationError.LIMIT,PdfAnnotationError.CHANGED)[index],model.state.value.error)
            assertFalse(model.state.value.completed); assertFalse(model.state.value.busy); assertTrue(repo.records.value.isEmpty())
        }
    }
    @Test fun anExistingNoteOnlyRecordKeepsNoColorAndExternalAbaInvalidatesItsEditor() = checkModel { owner ->
        val saved=pdfTestRecord().copy(highlightColor=null,attachedNote="note")
        val repo=FakePdfAnnotationsRepository(listOf(saved)); val model=PdfAnnotationEditorViewModel(request(saved),repo).also { owner.put("edit",it) }
        runCurrent(); assertNull(model.state.value.color); assertEquals("note",model.state.value.note)
        repo.records.value=listOf(saved.copy(revision=2)); runCurrent(); assertEquals(PdfAnnotationError.CHANGED,model.state.value.error)
        model.editNote("overwrite"); model.save { true }; advanceUntilIdle(); assertEquals("note",repo.records.value.single().attachedNote); assertEquals(0,repo.updates)
    }
    @Test fun editingARecordCompletesAfterItsOwnRepositoryInvalidationAndPreservesSource() = checkModel { owner ->
        val saved=pdfTestRecord(); val repo=FakePdfAnnotationsRepository(listOf(saved))
        val model=PdfAnnotationEditorViewModel(request(saved),repo).also { owner.put("edit",it) }; runCurrent()
        model.editColor(null); model.editNote(" note only "); model.save { true }; advanceUntilIdle()
        assertTrue(model.state.value.completed); assertNull(model.state.value.error); assertEquals(saved.copy(highlightColor=null,attachedNote=" note only ",revision=1),repo.records.value.single())
    }
    @Test fun listSearchAndCallbacksCannotRetargetAnEditedRecordOrHiddenResult() = checkModel { owner ->
        val saved=pdfTestRecord(); val repo=FakePdfAnnotationsRepository(listOf(saved)); val window=PdfAnnotationsWindow("book","a".repeat(64),true)
        val model=PdfAnnotationsViewModel(window,repo).also { owner.put("list",it) }; runCurrent()
        model.search("İĞNE 🌿"); assertEquals(listOf(saved),model.state.value.visibleEntries)
        model.search("absent"); model.edit(saved); model.askDelete(saved); assertNull(model.state.value.editor); assertNull(model.state.value.deleteCandidate)
        model.search(""); repo.records.value=listOf(saved.copy(revision=2)); runCurrent(); model.edit(saved); model.askDelete(saved); assertNull(model.state.value.editor); assertNull(model.state.value.deleteCandidate)
        val changed=repo.records.value.single(); model.edit(changed); assertEquals(changed,model.state.value.editor!!.expected)
        val editor=model.state.value.editor!!; model.closeEditor(editor); assertFalse(editor.lifetime.isActive)
    }
    @Test fun deleteRequiresExplicitConfirmationAndAClosedWindowCannotAdmitQueuedNavigation() = checkModel { owner ->
        val saved=pdfTestRecord(); val repo=FakePdfAnnotationsRepository(listOf(saved)); val window=PdfAnnotationsWindow("book","a".repeat(64),false)
        val model=PdfAnnotationsViewModel(window,repo).also { owner.put("list",it) }; runCurrent()
        model.delete(saved) { true }; runCurrent(); assertEquals(0,repo.removes)
        model.edit(saved); assertNull(model.state.value.editor)
        model.askDelete(saved); model.delete(saved) { false }; runCurrent(); assertEquals(0,repo.removes)
        model.delete(saved) { true }; advanceUntilIdle(); assertEquals(1,repo.removes); assertTrue(repo.records.value.isEmpty())
        repo.records.value=listOf(saved); runCurrent(); var navigations=0
        model.openPage(saved,{ true }) { navigations++; true }; window.close(); advanceUntilIdle(); assertEquals(0,navigations)
    }
    @Test fun modernOpeningFreshlyProvesTheExactAnnotationAndPassesItsOriginalAnchor() = checkModel { owner ->
        val saved = pdfTestRecord(); val repo = FakePdfAnnotationsRepository(listOf(saved))
        val window = PdfAnnotationsWindow("book", "a".repeat(64), true)
        val model = PdfAnnotationsViewModel(window, repo).also { owner.put("point-open", it) }
        runCurrent(); val opened = mutableListOf<Any>()
        repo.beforeProof = { assertEquals(listOf(saved), it); assertTrue(opened.isEmpty()) }
        model.openPage(saved, { true }) { opened += it; true }; advanceUntilIdle()
        assertEquals(1, repo.proofs); assertEquals(listOf<Any>(saved.anchor), opened)
        assertNull(model.state.value.error); assertFalse(model.state.value.busy)
    }

    @Test fun modernOpeningCannotNavigateAfterFreshOriginalProofFails() = checkModel { owner ->
        val saved = pdfTestRecord(); val repo = FakePdfAnnotationsRepository(listOf(saved))
        val model = PdfAnnotationsViewModel(PdfAnnotationsWindow("book", "a".repeat(64), true), repo).also { owner.put("point-fail", it) }
        runCurrent(); var opened = 0; repo.beforeProof = { throw PdfAnnotationSourceException() }
        model.openPage(saved, { true }) { opened++; true }; advanceUntilIdle()
        assertEquals(1, repo.proofs); assertEquals(0, opened)
        assertEquals(PdfAnnotationError.SOURCE, model.state.value.error); assertFalse(model.state.value.busy)
    }

    @Test fun modernOpeningRechecksTheCurrentRowAfterNativeProofSuspends() = checkModel { owner ->
        val saved = pdfTestRecord(); val repo = FakePdfAnnotationsRepository(listOf(saved))
        val model = PdfAnnotationsViewModel(PdfAnnotationsWindow("book", "a".repeat(64), true), repo).also { owner.put("point-changed", it) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var opened = 0
        repo.beforeProof = { entered.complete(Unit); release.await() }
        try {
            runCurrent(); model.openPage(saved, { true }) { opened++; true }; runCurrent()
            assertTrue(entered.isCompleted); assertEquals(0, opened)
            repo.records.value = listOf(saved.copy(revision = saved.revision + 2)); runCurrent()
            release.complete(Unit); advanceUntilIdle()
            assertEquals(0, opened); assertEquals(PdfAnnotationError.CHANGED, model.state.value.error)
        } finally { release.complete(Unit) }
    }

    @Test fun modernOpeningCannotCommitALateUninterruptibleProofAfterWindowCloseOrAdmissionLoss() = checkModel { owner ->
        for (close in listOf(true, false)) {
            val saved = pdfTestRecord(); val repo = FakePdfAnnotationsRepository(listOf(saved))
            val window = PdfAnnotationsWindow("book", "a".repeat(64), true)
            val model = PdfAnnotationsViewModel(window, repo).also { owner.put("point-cancel-$close", it) }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var allowed = true; var opened = 0
            repo.beforeProof = { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            try {
                runCurrent(); model.openPage(saved, { allowed }) { opened++; true }; runCurrent()
                assertTrue(entered.isCompleted); assertEquals(0, opened)
                if (close) window.close() else allowed = false
                release.complete(Unit); advanceUntilIdle()
                assertEquals(0, opened); assertNull(model.state.value.error); assertFalse(model.state.value.busy)
            } finally { release.complete(Unit); window.close() }
        }
    }

    @Test fun legacyOpeningKeepsPageAccessWithoutAttemptingUnsupportedNativeProof() = checkModel { owner ->
        val saved = pdfTestRecord(); val repo = FakePdfAnnotationsRepository(listOf(saved))
        val model = PdfAnnotationsViewModel(PdfAnnotationsWindow("book", "a".repeat(64), false), repo).also { owner.put("point-legacy", it) }
        runCurrent(); var page = -1
        model.openPage(saved, { true }) { target ->
            val value: Any = target
            page = (value as? PdfSourceAnchor)?.pageIndex ?: (value as Int)
            true
        }; advanceUntilIdle()
        assertEquals(saved.anchor.pageIndex, page); assertEquals(0, repo.proofs); assertNull(model.state.value.error)
    }

    private fun checkModel(block: suspend TestScope.(ViewModelStore)->Unit)=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val owner=ViewModelStore()
        try { block(owner) } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}

internal val PDF_TEST_TEXT="  İğne 🌿\nexact text  "
internal val PDF_TEST_ANCHOR=PdfSourceAnchor(3,300,600,PdfPagePoint(60,100),PdfPagePoint(180,100),listOf(PdfTextBounds(60.0,90.0,180.0,110.0)))
internal fun pdfTestRecord()=PdfAnnotation(UUID.randomUUID().toString(),PDF_TEST_TEXT,PDF_TEST_ANCHOR,1,PdfHighlightColor.YELLOW,"note")
internal class FakePdfAnnotationsRepository(initial: List<PdfAnnotation> = emptyList()): PdfAnnotationRepository {
    val records=MutableStateFlow(initial); var creates=0; var updates=0; var removes=0; var proofs=0
    var beforeCreate: suspend ()->Unit={}; var beforeProof: suspend (List<PdfAnnotation>)->Unit={}
    override fun observe(bookId: String,contentSha256: String)=records
    override suspend fun create(bookId: String,contentSha256: String,text: String,anchor: PdfSourceAnchor,highlightColor: PdfHighlightColor?,attachedNote: String?): PdfAnnotation {
        creates++; beforeCreate(); currentCoroutineContext().ensureActive()
        return PdfAnnotation(UUID.randomUUID().toString(),text,anchor,1,highlightColor,attachedNote).also { records.value=records.value+it }
    }
    override suspend fun update(bookId: String,contentSha256: String,expected: PdfAnnotation,highlightColor: PdfHighlightColor?,attachedNote: String?): PdfAnnotation {
        if(records.value.singleOrNull { it.id==expected.id }!=expected) throw PdfAnnotationChangedException()
        updates++; return expected.copy(highlightColor=highlightColor,attachedNote=attachedNote,revision=expected.revision+1).also { next -> records.value=records.value.map { if(it.id==next.id) next else it } }
    }
    override suspend fun remove(bookId: String,contentSha256: String,expected: PdfAnnotation) { if(records.value.singleOrNull { it.id==expected.id }!=expected) throw PdfAnnotationChangedException(); removes++; records.value=records.value.filterNot { it.id==expected.id } }
    override suspend fun verifyExisting(bookId: String,contentSha256: String,expected: List<PdfAnnotation>) { proofs++; beforeProof(expected) }
}

package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.assistant.*
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.fixtures.PdfSelectedAssistantFixtures as P
import com.yaprak.reader.fixtures.SelectedAssistantFixtures as E
import com.yaprak.reader.fixtures.AssistantFixtures as A
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfSelectionAssistantTest {
    @Test fun nativePdfCannotBorrowTransientEpubDomRangeEvenWhenItsNativeAnchorAndPageAreValid() {
        val request = P.request()
        val preview = SelectedTextPreview.Ready(request.preview, sessionId = request.selectionId,
            pdfPageIndex = 2, sourceSelectionId = request.sourceSelectionId, pdfSourceAnchor = P.anchor,
            rangeCandidate = com.yaprak.reader.reader.SelectionRangeCandidate("OPS/first.xhtml", "0/0", 0,
                request.preview.length, E.hash(request.preview)))
        assertNull(selectedAssistantRequest(P.book, preview))
        assertEquals(request, selectedAssistantRequest(P.book, preview.copy(rangeCandidate = null)))
    }
    @Test fun onlyCurrentNativePdfSelectionWithActualPageCanCreateReaderAssistantRequest() {
        val request = P.request(); val preview = SelectedTextPreview.Ready(request.preview, sessionId = request.selectionId,
            pdfPageIndex = 2, sourceSelectionId = request.sourceSelectionId, pdfSourceAnchor = P.anchor)
        assertEquals(request, selectedAssistantRequest(P.book, preview))
        for (invalid in listOf(preview.copy(pdfOcr = true), preview.copy(sourceSelectionId = null),
            preview.copy(sourceSelectionId = "old"), preview.copy(pdfSourceAnchor = null), preview.copy(pdfPageIndex = 1),
            preview.copy(sourceAnchor = E.request().anchor), preview.copy(text = "x".repeat(4097))))
            assertNull(selectedAssistantRequest(P.book, invalid))
        for (book in listOf(P.book.copy(pageCount = null), P.book.copy(pageCount = 2),
            P.book.copy(format = BookFormat.TXT), P.book.copy(format = BookFormat.CBZ), E.book))
            assertNull(selectedAssistantRequest(book, preview))
    }
    @Test fun nativePdfPreviewAndDraftStayLocalAndExplicitSendReprovesBeforeNewRequestBoundAnswer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        var proofs = 0; val sent = mutableListOf<SelectedTextQuestionRequest>(); val request = P.request()
        val model = SelectionAssistantViewModel(SelectedTextSourceProvider { proofs++; P.sources(it) },
            SelectedTextAnswerService { current, _ -> sent += current; E.answer(current) }); store.put("pdf", model)
        try {
            model.open(request, "Açıkla") { true }; runCurrent(); val preview = model.state.value!!.sources!!
            assertEquals(1, proofs); assertTrue(sent.isEmpty())
            model.editQuestion(model.state.value!!, "Daha basit anlat")
            model.editEndpoint(model.state.value!!, A.connection().endpoint.toString()); model.editToken(model.state.value!!, A.connection().accessToken)
            val ready = model.state.value!!; model.send(ready) { true }; model.send(ready) { true }; runCurrent()
            assertEquals(2, proofs); assertEquals(1, sent.size); assertNotEquals(preview.requestId, sent.single().sources.requestId)
            assertEquals(P.anchor, sent.single().sources.request.pdfSourceAnchor); assertNotNull(model.state.value!!.answer)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun changedNativeOwnerDuringFreshProofCannotSendOrPublishTheOldPanel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        var current = true; var proofs = 0; var sends = 0; val release = CompletableDeferred<Unit>()
        val model = SelectionAssistantViewModel(SelectedTextSourceProvider {
            if (++proofs == 2) withContext(NonCancellable) { release.await() }; P.sources(it)
        }, SelectedTextAnswerService { request, _ -> sends++; E.answer(request) }); store.put("pdf", model)
        try {
            model.open(P.request(), "Açıkla") { current }; runCurrent()
            model.editEndpoint(model.state.value!!, A.connection().endpoint.toString()); model.editToken(model.state.value!!, A.connection().accessToken)
            model.send(model.state.value!!) { current }; runCurrent(); current = false; release.complete(Unit); runCurrent()
            assertEquals(0, sends); assertNull(model.state.value)
        } finally { release.complete(Unit); store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}

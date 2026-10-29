package com.yaprak.reader.formats

import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.feature.FakePdfAnnotationsRepository
import com.yaprak.reader.feature.pdfTestRecord
import com.yaprak.reader.formats.pdf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfAnnotationPaintControllerTest {
    private val book=Book("book","PDF",null,BookFormat.PDF,"source.pdf","source.pdf",10,"a".repeat(64),1,pageCount=12)
    @Test fun storedRowsCannotPaintUntilFreshProofFinishesAndOnlyVisibleColoredPagesAreVerified()=runTest {
        val saved=pdfTestRecord(); val repo=FakePdfAnnotationsRepository(listOf(saved,saved.copy(id="right",anchor=otherPage(saved,4)),saved.copy(id="note",highlightColor=null)))
        val release=CompletableDeferred<Unit>(); var verified: List<PdfAnnotation>?=null
        repo.beforeProof={ verified=it; release.await() }
        val painter=PdfAnnotationPaintController(book,repo,true) { true }
        try { painter.start(backgroundScope,flowOf(listOf(3,4))); runCurrent(); assertEquals(PdfAnnotationPaintState.Loading,painter.state.value)
            assertEquals(listOf(saved,repo.records.value[1]),verified); release.complete(Unit); runCurrent()
            assertEquals(verified,(painter.state.value as PdfAnnotationPaintState.Ready).annotations)
        } finally { painter.stop() }
    }
    @Test fun aPausedOrReplacedOwnerDropsAnUninterruptibleOldProof()=runTest {
        val repo=FakePdfAnnotationsRepository(listOf(pdfTestRecord())); val release=CompletableDeferred<Unit>()
        repo.beforeProof={ withContext(NonCancellable) { release.await() } }
        var active=true; val painter=PdfAnnotationPaintController(book,repo,true) { active }
        painter.start(backgroundScope,flowOf(listOf(3))); runCurrent(); active=false; painter.stop(); release.complete(Unit); runCurrent()
        assertEquals(PdfAnnotationPaintState.Loading,painter.state.value)
    }
    @Test fun sourceFailureNeverPublishesGeometryAndRetryCannotRetargetANewerFailedRequest()=runTest {
        val repo=FakePdfAnnotationsRepository(listOf(pdfTestRecord())); repo.beforeProof={ throw PdfAnnotationSourceException() }
        val painter=PdfAnnotationPaintController(book,repo,true) { true }
        try { painter.start(backgroundScope,flowOf(listOf(3))); runCurrent(); val first=painter.state.value
            assertTrue(first is PdfAnnotationPaintState.Failed); painter.retry(first); runCurrent(); val second=painter.state.value
            assertNotSame(first,second); assertEquals(2,repo.proofs); painter.retry(first); runCurrent(); assertEquals(2,repo.proofs)
            repo.beforeProof={}; painter.retry(second); runCurrent(); assertTrue(painter.state.value is PdfAnnotationPaintState.Ready)
        } finally { painter.stop() }
    }
    @Test fun unsupportedDecoderDoesNotPaintStoredBoundsOrPretendToVerifyThem()=runTest {
        val repo=FakePdfAnnotationsRepository(listOf(pdfTestRecord())); val painter=PdfAnnotationPaintController(book,repo,false) { true }
        try { painter.start(backgroundScope,flowOf(listOf(3))); runCurrent(); assertEquals(PdfAnnotationPaintState.Unsupported,painter.state.value); assertEquals(0,repo.proofs) }
        finally { painter.stop() }
    }
    @Test fun pageAndRecordChangesRemoveOldPaintAndPerformANewSourceProof()=runTest {
        val saved=pdfTestRecord(); val repo=FakePdfAnnotationsRepository(listOf(saved)); val pages=MutableStateFlow(listOf(3))
        val painter=PdfAnnotationPaintController(book,repo,true) { true }
        try { painter.start(backgroundScope,pages); runCurrent(); assertEquals(1,repo.proofs)
            pages.value=listOf(4); runCurrent(); assertTrue((painter.state.value as PdfAnnotationPaintState.Ready).annotations.isEmpty()); assertEquals(1,repo.proofs)
            repo.records.value=listOf(saved.copy(anchor=otherPage(saved,4),revision=1)); runCurrent(); assertEquals(2,repo.proofs)
            assertEquals(1L,(painter.state.value as PdfAnnotationPaintState.Ready).annotations.single().revision)
        } finally { painter.stop() }
    }
    @Test fun excessiveNativeBoundsCannotCreatePartialPaintSuccessOrAnUnboundedNativeBatch()=runTest {
        val saved=pdfTestRecord(); val original=saved.anchor
        val heavy=com.yaprak.reader.reader.PdfSourceAnchor(3,original.pageWidth,original.pageHeight,original.start,original.stop,List(256) { original.bounds.single() })
        val repo=FakePdfAnnotationsRepository(List(33) { saved.copy(id="record$it",anchor=heavy) })
        val painter=PdfAnnotationPaintController(book,repo,true) { true }
        try { painter.start(backgroundScope,flowOf(listOf(3))); runCurrent(); assertTrue(painter.state.value is PdfAnnotationPaintState.Failed); assertEquals(0,repo.proofs) }
        finally { painter.stop() }
    }
    private fun otherPage(record: PdfAnnotation,page: Int)=record.anchor.let { com.yaprak.reader.reader.PdfSourceAnchor(page,it.pageWidth,it.pageHeight,it.start,it.stop,it.bounds) }
}

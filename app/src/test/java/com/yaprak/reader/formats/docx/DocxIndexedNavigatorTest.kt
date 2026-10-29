package com.yaprak.reader.formats.docx

import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.reader.BookPositionNavigator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Native positions are controlled here. This proves ownership/gates, not Android navigation. */
class DocxIndexedNavigatorTest {
    private val book=Book("owned","Owned",null,BookFormat.DOCX,"owned.docx","owned.docx",100,"a".repeat(64),1)
    private val target=LibraryTextTarget(book.id,book.sha256,DOCX_INDEX_HREF,"b".repeat(64),sourceFormat=BookFormat.DOCX)
    private class Positions(var result: ReadingLocation?=ReadingLocation("opaque native",DOCX_INDEX_HREF)): BookPositionNavigator {
        var resolutions=0;var opened: ReadingLocation?=null
        override suspend fun resolvePercent(percent: Int): ReadingLocation? { assertEquals(0,percent);resolutions++;return result }
        override fun goTo(location: ReadingLocation): Boolean { opened=location;return true }
    }
    @Test fun verificationUsesTheNativeFirstPositionAndTheProofIsConsumedExactlyOnce()=runBlocking {
        val positions=Positions();var checks=0
        val navigator=DocxIndexedNavigator(book,{true},{assertEquals(target,it);checks++;true},positions)
        assertEquals(DOCX_INDEX_HREF,navigator.verifiedChapterHref(target));assertEquals(1,checks)
        assertTrue(navigator.goToChapter(DOCX_INDEX_HREF));assertSame(positions.result,positions.opened)
        assertFalse(navigator.goToChapter(DOCX_INDEX_HREF))
    }
    @Test fun absentFailedOrWrongDestinationProofNeverNavigates()=runBlocking {
        val positions=Positions();val navigator=DocxIndexedNavigator(book,{true},{false},positions)
        assertFalse(navigator.goToChapter(DOCX_INDEX_HREF));assertNull(navigator.verifiedChapterHref(target));assertNull(positions.opened)
        val valid=DocxIndexedNavigator(book,{true},{true},positions)
        valid.verifiedChapterHref(target);assertFalse(valid.goToChapter("../document.xhtml"));assertFalse(valid.goToChapter(DOCX_INDEX_HREF))
    }
    @Test fun foreignIdentityFormatAddressVersionAndPageAreRejectedBeforeNativeResolution()=runBlocking {
        val positions=Positions();val navigator=DocxIndexedNavigator(book,{true},{error("No source check")},positions)
        for(invalid in listOf(target.copy(bookId="other"),target.copy(contentSha256="c".repeat(64)),target.copy(sourceFormat=BookFormat.EPUB),
            target.copy(resourceHref=DOCX_INDEX_HREF+"#fragment"),target.copy(extractionVersion=99),target.copy(pdfPageIndex=0)))
            assertNull(navigator.verifiedChapterHref(invalid))
        assertEquals(0,positions.resolutions)
    }
    @Test fun wrongOrUnavailableNativeResourceCannotGrantASourceProof()=runBlocking {
        val positions=Positions(ReadingLocation("native","Other.xhtml"))
        val navigator=DocxIndexedNavigator(book,{true},{error("Wrong native resource")},positions)
        assertNull(navigator.verifiedChapterHref(target));positions.result=null;assertNull(navigator.verifiedChapterHref(target))
    }
    @Test fun invalidationWhileSourceCheckIsPendingRejectsItsLateResult()=runBlocking {
        val entered=CompletableDeferred<Unit>();val answer=CompletableDeferred<Boolean>()
        val navigator=DocxIndexedNavigator(book,{true},{entered.complete(Unit);answer.await()},Positions())
        val pending=async { navigator.verifiedChapterHref(target) };entered.await();navigator.invalidate();answer.complete(true)
        assertNull(pending.await());assertFalse(navigator.goToChapter(DOCX_INDEX_HREF))
    }
    @Test fun pausedOrReplacedSurfaceRejectsBothVerificationAndAcceptedNavigation()=runBlocking {
        var current=true;val positions=Positions();val navigator=DocxIndexedNavigator(book,{current},{true},positions)
        navigator.verifiedChapterHref(target);current=false
        assertFalse(navigator.goToChapter(DOCX_INDEX_HREF));assertNull(navigator.verifiedChapterHref(target));assertNull(positions.opened)
    }
    @Test fun cancelledOwnerCannotConsumeAStoredNavigationProof()=runBlocking {
        val positions=Positions();val navigator=DocxIndexedNavigator(book,{true},{true},positions)
        val child=launch(start=CoroutineStart.UNDISPATCHED) { navigator.verifiedChapterHref(target);awaitCancellation() }
        child.cancelAndJoin();assertFalse(navigator.goToChapter(DOCX_INDEX_HREF));assertNull(positions.opened)
    }
    @Test fun sourceCancellationPropagatesAndRevokesTheOldProof()=runBlocking {
        val cancelled=CancellationException("owned");val navigator=DocxIndexedNavigator(book,{true},{throw cancelled},Positions())
        assertSame(cancelled,runCatching { navigator.verifiedChapterHref(target) }.exceptionOrNull())
        assertFalse(navigator.goToChapter(DOCX_INDEX_HREF))
    }
}

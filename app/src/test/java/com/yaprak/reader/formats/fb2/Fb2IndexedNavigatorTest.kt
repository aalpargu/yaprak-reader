package com.yaprak.reader.formats.fb2

import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.reader.ContentsNavigator
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Fb2IndexedNavigatorTest {
    private val book = Book("book", "FB2", null, BookFormat.FB2, "book.fb2", "book.fb2", 1, "a".repeat(64), 1)
    private val target = LibraryTextTarget(book.id, book.sha256, "Text/b1-p2.xhtml", "b".repeat(64), sourceFormat = BookFormat.FB2)
    private class Contents : ContentsNavigator {
        override var contents = BookContents(listOf(ContentsEntry("0", "First", 0, true), ContentsEntry("1", "Nested", 1, true), ContentsEntry("2", "Deeper", 2, true), ContentsEntry("3", "Last", 0, true)))
        var mapped: String? = "3"
        override fun entryIdForResource(href: String) = mapped.takeIf { href == "Text/b1-p2.xhtml" }
        val calls = mutableListOf<String>(); var accept = true
        override fun goTo(entryId: String): Boolean { calls += entryId; return accept }
    }
    @Test fun onlyVerifiedTypedOriginalPartCanOpenRealContentsEntryOnceWithoutPassingAnArbitraryHref() = runTest {
        val contents = Contents(); val nav = Fb2IndexedNavigator(book, { true }, { true }, contents)
        assertFalse(nav.goToChapter(target.resourceHref)); assertEquals(target.resourceHref, nav.verifiedChapterHref(target))
        assertTrue(nav.goToChapter(target.resourceHref)); assertEquals(listOf("3"), contents.calls)
        assertFalse(nav.goToChapter(target.resourceHref)); assertEquals(1, contents.calls.size)
        assertEquals(target.resourceHref, nav.verifiedChapterHref(target)); assertFalse(nav.goToChapter("Text/b1-p1.xhtml"))
        assertFalse(nav.goToChapter(target.resourceHref)); assertEquals(1, contents.calls.size)
    }
    @Test fun badTypedIdentityMissingNativeEntryAndUnusableNativeEntryCannotRequestSourceVerification() = runTest {
        val contents = Contents(); var proofs = 0
        val nav = Fb2IndexedNavigator(book, { true }, { proofs++; true }, contents)
        for (bad in listOf(target.copy(bookId = "other"), target.copy(contentSha256 = "c".repeat(64)),
            target.copy(sourceFormat = BookFormat.EPUB), target.copy(pdfPageIndex = 0),
            target.copy(resourceHref = "Text/b0-p1.xhtml"), target.copy(resourceHref = "Text/b1-p3.xhtml"))) assertNull(nav.verifiedChapterHref(bad))
        contents.contents = BookContents(listOf(ContentsEntry("3", "Chapter two", 0, false)))
        assertNull(nav.verifiedChapterHref(target)); assertEquals(0, proofs); assertTrue(contents.calls.isEmpty())
    }
    @Test fun pausedClosedOrReplacedSurfaceAndFailedProofInvalidateEveryPreviousClaim() = runTest {
        var current = true; var verified = true; val contents = Contents()
        val nav = Fb2IndexedNavigator(book, { current }, { verified }, contents)
        nav.verifiedChapterHref(target); current = false; assertFalse(nav.goToChapter(target.resourceHref))
        current = true; nav.verifiedChapterHref(target); nav.invalidate(); assertFalse(nav.goToChapter(target.resourceHref))
        nav.verifiedChapterHref(target); verified = false; assertNull(nav.verifiedChapterHref(target))
        assertFalse(nav.goToChapter(target.resourceHref)); assertTrue(contents.calls.isEmpty())
    }
    @Test fun lateOldVerificationCannotReplaceNewProofAndResumedSurfaceCannotReuseAPrePauseProof() = runTest {
        val old = CompletableDeferred<Boolean>(); val latest = CompletableDeferred<Boolean>(); var count = 0
        val contents = Contents(); val nav = Fb2IndexedNavigator(book, { true }, { if (count++ == 0) old.await() else latest.await() }, contents)
        val first = async { nav.verifiedChapterHref(target) }; runCurrent()
        val second = async { assertEquals(target.resourceHref, nav.verifiedChapterHref(target)); assertTrue(nav.goToChapter(target.resourceHref)) }
        runCurrent(); latest.complete(true); second.await(); old.complete(true); assertNull(first.await())
        assertEquals(listOf("3"), contents.calls)
        val pause = CompletableDeferred<Boolean>(); val paused = Fb2IndexedNavigator(book, { true }, { pause.await() }, contents)
        val pending = async { paused.verifiedChapterHref(target) }; runCurrent(); paused.invalidate(); pause.complete(true)
        assertNull(pending.await()); assertFalse(paused.goToChapter(target.resourceHref)); assertEquals(1, contents.calls.size)
    }
    @Test fun completedOrCanceledCallerCannotTransferItsProofAndNativeRejectionIsSingleUse() = runTest {
        val contents = Contents(); val nav = Fb2IndexedNavigator(book, { true }, { true }, contents)
        async { assertEquals(target.resourceHref, nav.verifiedChapterHref(target)) }.await()
        assertFalse(nav.goToChapter(target.resourceHref))
        val entered = CompletableDeferred<Unit>()
        val owner = launch { nav.verifiedChapterHref(target); entered.complete(Unit); awaitCancellation() }
        entered.await(); owner.cancelAndJoin(); assertFalse(nav.goToChapter(target.resourceHref))
        contents.accept = false; nav.verifiedChapterHref(target); assertFalse(nav.goToChapter(target.resourceHref))
        assertFalse(nav.goToChapter(target.resourceHref)); assertEquals(listOf("3"), contents.calls)
    }
    @Test fun cancellationDuringSourceProofPropagatesAndCannotLeaveNavigationPermission() = runTest {
        val contents = Contents(); val entered = CompletableDeferred<Unit>()
        val nav = Fb2IndexedNavigator(book, { true }, { entered.complete(Unit); awaitCancellation() }, contents)
        val owner = launch { nav.verifiedChapterHref(target) }; entered.await(); owner.cancelAndJoin()
        assertTrue(owner.isCancelled); assertFalse(nav.goToChapter(target.resourceHref)); assertTrue(contents.calls.isEmpty())
    }
    @Test fun changedMappingAndTruncatedNativeContentsCannotReuseAnEarlierProof() = runTest {
        val contents = Contents(); val nav = Fb2IndexedNavigator(book, { true }, { true }, contents)
        assertEquals(target.resourceHref, nav.verifiedChapterHref(target)); contents.mapped = null
        assertFalse(nav.goToChapter(target.resourceHref)); assertTrue(contents.calls.isEmpty())
        contents.mapped = "3"; contents.contents = contents.contents.copy(truncated = true)
        assertNull(nav.verifiedChapterHref(target)); assertTrue(contents.calls.isEmpty())
    }
}

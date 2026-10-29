package com.yaprak.reader.feature

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.feature.reader.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IndexedChapterLaunchTest {
    private val target = LibraryTextTarget("book", "a".repeat(64), "OPS/chapter.xhtml", "b".repeat(64))
    private fun launch() = ReaderActivity.intent(RuntimeEnvironment.getApplication(), target)

    @Test fun `TXT launch and saved receipt preserve original part type without a PDF page or book excerpts`() {
        val txt = target.copy(sourceFormat = com.yaprak.reader.domain.library.BookFormat.TXT, resourceHref = "Text/part0064.xhtml")
        val intent = ReaderActivity.intent(RuntimeEnvironment.getApplication(), txt)
        val request = IndexedChapterLaunch.read(intent)!!
        assertEquals(txt, request.target); assertEquals(8, intent.extras!!.keySet().size)
        assertEquals("TXT", intent.getStringExtra("indexedChapterFormat")); assertFalse(intent.hasExtra("indexedChapterPdfPage"))
        val bundle = Bundle(); IndexedChapterLaunch.save(bundle, IndexedChapterState(request, IndexedChapterPhase.ACCEPTED))
        assertEquals(request, IndexedChapterLaunch.restore(parcel(bundle), request)!!.request)
        assertNull(IndexedChapterLaunch.restore(bundle, request.copy(target = txt.copy(resourceHref = "Text/part0001.xhtml"))))
        val legacy = android.content.Intent(intent).apply { removeExtra("indexedChapterFormat") }
        assertEquals(com.yaprak.reader.domain.library.BookFormat.EPUB, IndexedChapterLaunch.read(legacy)!!.target.sourceFormat)
        IndexedChapterLaunch.save(bundle, IndexedChapterState(request.copy(target = target), IndexedChapterPhase.ACCEPTED))
        assertFalse(bundle.containsKey("readerIndexedChapterFormat")); assertNull(IndexedChapterLaunch.restore(bundle, request))
    }
    @Test fun `TXT part targets refuse forged addresses wrong types pages and versions at the Android boundary`() {
        val txt = target.copy(sourceFormat = com.yaprak.reader.domain.library.BookFormat.TXT, resourceHref = "Text/part0001.xhtml")
        for (href in listOf("Text/part0000.xhtml", "Text/part0065.xhtml", "Text/part1.xhtml", "text/part0001.xhtml",
            "Text/%70art0001.xhtml", "Text/part0001.xhtml#p", "Text/part0001.xhtml?query", "../Text/part0001.xhtml")) assertFalse(txt.copy(resourceHref = href).isValid)
        for (mutate in listOf<(android.content.Intent) -> Unit>(
            { it.putExtra("indexedChapterFormat", "PDF") }, { it.putExtra("indexedChapterPdfPage", 0) },
            { it.putExtra("indexedChapterFormat", 1) }, { it.putExtra("indexedChapterVersion", 2) },
            { it.putExtra("indexedChapterHref", "Text/part0065.xhtml") })) {
            val changed = ReaderActivity.intent(RuntimeEnvironment.getApplication(), txt); mutate(changed)
            assertNull(IndexedChapterLaunch.read(changed))
        }
    }

    @Test fun `PDF page launch and accepted receipt preserve typed page without storing excerpts`() {
        val pdf = target.copy(resourceHref = "", sourceFormat = com.yaprak.reader.domain.library.BookFormat.PDF, pdfPageIndex = 2)
        val intent = ReaderActivity.intent(RuntimeEnvironment.getApplication(), pdf)
        val request = IndexedChapterLaunch.read(intent)!!
        assertEquals(pdf, request.target); assertEquals(9, intent.extras!!.keySet().size)
        val bundle = Bundle(); IndexedChapterLaunch.save(bundle, IndexedChapterState(request, IndexedChapterPhase.ACCEPTED))
        assertEquals(request, IndexedChapterLaunch.restore(parcel(bundle), request)!!.request)
        for (mutate in listOf<(android.content.Intent) -> Unit>(
            { it.putExtra("indexedChapterPdfPage", "2") }, { it.putExtra("indexedChapterPdfPage", -1) },
            { it.putExtra("indexedChapterFormat", "OTHER") }, { it.putExtra("indexedChapterFormat", "EPUB") },
            { it.removeExtra("indexedChapterPdfPage") }, { it.removeExtra("indexedChapterFormat") },
            { it.putExtra("indexedChapterHref", "chapter.xhtml") })) {
            val changed = android.content.Intent(intent); mutate(changed); assertNull(IndexedChapterLaunch.read(changed))
        }
    }

    @Test fun `PDF accepted saved receipt cannot suppress a different page or cross into old EPUB route`() {
        val pdf = target.copy(resourceHref = "", sourceFormat = com.yaprak.reader.domain.library.BookFormat.PDF, pdfPageIndex = 2)
        val request = IndexedChapterLaunch.read(ReaderActivity.intent(RuntimeEnvironment.getApplication(), pdf))!!
        val bundle = Bundle(); IndexedChapterLaunch.save(bundle, IndexedChapterState(request, IndexedChapterPhase.ACCEPTED))
        assertNull(IndexedChapterLaunch.restore(bundle, request.copy(target = pdf.copy(pdfPageIndex = 1))))
        IndexedChapterLaunch.save(bundle, IndexedChapterState(request.copy(target = target), IndexedChapterPhase.ACCEPTED))
        assertFalse(bundle.containsKey("readerIndexedChapterPdfPage")); assertFalse(bundle.containsKey("readerIndexedChapterFormat"))
        assertNull(IndexedChapterLaunch.restore(bundle, request))
    }
    private fun parcel(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try { parcel.writeBundle(bundle); parcel.setDataPosition(0); return parcel.readBundle(javaClass.classLoader)!! }
        finally { parcel.recycle() }
    }

    @Test fun `target intent preserves old book id route and generates distinct bounded launch ids`() {
        val first = launch(); val second = launch()
        val firstRequest = IndexedChapterLaunch.read(first)!!
        assertEquals(target, firstRequest.target); assertNotEquals(firstRequest.id, IndexedChapterLaunch.read(second)!!.id)
        assertEquals(target.bookId, first.getStringExtra("bookId"))
        assertNull(IndexedChapterLaunch.read(ReaderActivity.intent(RuntimeEnvironment.getApplication(), target.bookId)))
        assertEquals(7, first.extras!!.keySet().size)
    }

    @Test fun `intent rejects wrong types altered book unsafe href version hash and nonce`() {
        val mutations: List<(android.content.Intent) -> Unit> = listOf(
            { it.putExtra("bookId", "other") }, { it.putExtra("indexedChapterVersion", "1") },
            { it.putExtra("indexedChapterVersion", 2) }, { it.putExtra("indexedChapterHash", "bad") },
            { it.putExtra("indexedChapterResourceHash", "b".repeat(64).uppercase()) },
            { it.putExtra("indexedChapterHref", "chapter.xhtml#paragraph") },
            { it.putExtra("indexedChapterHref", "../chapter.xhtml") },
            { it.putExtra("indexedChapterNonce", "0-0-0-0-0") },
            { it.putExtra("indexedChapterBook", 12) })
        mutations.forEach { mutate -> val intent = launch(); mutate(intent); assertNull(IndexedChapterLaunch.read(intent)) }
    }

    @Test fun `accepted and failed receipts survive parcel and loading recreation without another claim`() {
        val request = IndexedChapterLaunch.read(launch())!!
        for (phase in listOf(IndexedChapterPhase.ACCEPTED, IndexedChapterPhase.FAILED)) {
            val state = IndexedChapterState(request, phase,
                if (phase == IndexedChapterPhase.FAILED) IndexedChapterFailure.NAVIGATION else null)
            val bundle = Bundle(); IndexedChapterLaunch.save(bundle, state)
            val restored = IndexedChapterLaunch.restore(parcel(bundle), request)!!
            assertEquals(state, restored)
            val open = IndexedChapterOpen(); open.initialize(request, restored); assertNull(open.begin(request))
        }
    }

    @Test fun `unfinished verification saves pending rather than a dead activity attempt`() {
        val request = IndexedChapterLaunch.read(launch())!!; val open = IndexedChapterOpen(); open.initialize(request, null)
        open.begin(request)
        val bundle = Bundle(); IndexedChapterLaunch.save(bundle, open.state.value)
        val restored = IndexedChapterLaunch.restore(parcel(bundle), request)!!
        assertEquals(IndexedChapterPhase.PENDING, restored.phase); assertNull(restored.attemptId)
        val next = IndexedChapterOpen(); next.initialize(request, restored); assertNotNull(next.begin(request))
    }

    @Test fun `saved receipt must match entire target and unique launch including hash and version`() {
        val request = IndexedChapterLaunch.read(launch())!!; val bundle = Bundle()
        IndexedChapterLaunch.save(bundle, IndexedChapterState(request, IndexedChapterPhase.ACCEPTED))
        assertNull(IndexedChapterLaunch.restore(bundle, IndexedChapterLaunch.read(launch())))
        for (other in listOf(request.copy(target = target.copy(bookId = "other")),
            request.copy(target = target.copy(contentSha256 = "c".repeat(64))),
            request.copy(target = target.copy(resourceSha256 = "c".repeat(64))),
            request.copy(target = target.copy(resourceHref = "other.xhtml"))))
            assertNull(IndexedChapterLaunch.restore(bundle, other))
        bundle.putInt("readerIndexedChapterVersion", 2); assertNull(IndexedChapterLaunch.restore(bundle, request))
    }

    @Test fun `malformed receipt status or fields cannot suppress a legitimate new verification`() {
        val request = IndexedChapterLaunch.read(launch())!!
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString("readerIndexedChapterPhase", "VERIFYING") },
            { it.putInt("readerIndexedChapterPhase", 2) },
            { it.putString("readerIndexedChapterPhase", "UNKNOWN") },
            { it.putString("readerIndexedChapterFailure", "SOURCE") },
            { it.putString("readerIndexedChapterNonce", "bad") },
            { it.putString("readerIndexedChapterVersion", "1") })
        mutations.forEach { mutate ->
            val bundle = Bundle(); IndexedChapterLaunch.save(bundle, IndexedChapterState(request, IndexedChapterPhase.ACCEPTED))
            mutate(bundle); assertNull(IndexedChapterLaunch.restore(parcel(bundle), request))
        }
        val failed = Bundle(); IndexedChapterLaunch.save(failed,
            IndexedChapterState(request, IndexedChapterPhase.FAILED, IndexedChapterFailure.SOURCE))
        failed.remove("readerIndexedChapterFailure"); assertNull(IndexedChapterLaunch.restore(failed, request))
    }

    @Test fun `saving absent launch clears only own keys and never stores text offsets or snippets`() {
        val request = IndexedChapterLaunch.read(launch())!!; val bundle = Bundle(); bundle.putString("unrelated", "keep")
        IndexedChapterLaunch.save(bundle, IndexedChapterState(request, IndexedChapterPhase.FAILED, IndexedChapterFailure.SOURCE))
        assertFalse(bundle.keySet().any { it.contains("text", ignoreCase = true) || it.contains("offset", ignoreCase = true) })
        IndexedChapterLaunch.save(bundle, IndexedChapterState())
        assertEquals(setOf("unrelated"), bundle.keySet()); assertEquals("keep", bundle.getString("unrelated"))
    }
}

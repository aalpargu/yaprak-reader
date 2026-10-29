package com.yaprak.reader.formats.pdf

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BoundedPdfOutlineTest {
    private class Fixture(val count: Int = 3) {
        val pages = List(count) { COSDictionary().apply { setItem(COSName.TYPE, COSName.PAGE) } }
        val outline = COSDictionary()
        val catalog = COSDictionary().apply {
            setItem(COSName.PAGES, COSDictionary().apply {
                setItem(COSName.TYPE, COSName.PAGES)
                setItem(COSName.KIDS, COSArray().apply { pages.forEach(::add) })
            })
            setItem(COSName.OUTLINES, outline)
        }
        fun destination(page: Int) = COSArray().apply { add(pages[page]); add(COSName.getPDFName("Fit")) }
        fun entry(title: String, page: Int? = null) = COSDictionary().apply {
            setItem(COSName.TITLE, COSString(title))
            page?.let { setItem(COSName.DEST, destination(it)) }
        }
        fun read() = BoundedPdfOutline().read(catalog, count)
    }

    @Test fun actualPdfBytesResolveToTheSecondRealPage() {
        val path = File(checkNotNull(javaClass.classLoader!!.getResource("pdf-outline182.pdf")).toURI())
        PDDocument.load(path).use { doc ->
            val result = BoundedPdfOutline().read(doc.documentCatalog.cosObject, doc.numberOfPages)
            assertEquals(listOf(PdfOutlineRow("0", "Chapter Two", 0, 1)), result.rows)
            assertFalse(result.truncated)
        }
    }

    @Test fun nestedAndSiblingEntriesRetainOrderAndActualPageIdentity() {
        val f = Fixture(); val first = f.entry("First", 1); val child = f.entry("Child", 2); val second = f.entry("Second", 0)
        f.outline.setItem(COSName.FIRST, first); first.setItem(COSName.FIRST, child); first.setItem(COSName.NEXT, second)
        val rows = f.read().rows
        assertEquals(listOf("First", "Child", "Second"), rows.map { it.title })
        assertEquals(listOf(0, 1, 0), rows.map { it.depth })
        assertEquals(listOf(1, 2, 0), rows.map { it.pageIndex })
    }

    @Test fun absentOutlineAndEmptyRootProduceAnHonestEmptyList() {
        val f = Fixture(); assertTrue(f.read().rows.isEmpty())
        f.catalog.removeItem(COSName.OUTLINES); assertTrue(f.read().rows.isEmpty())
    }

    @Test fun malformedExistingOutlineIsNotReportedAsAnEmptyBook() {
        val f = Fixture(); f.catalog.setItem(COSName.OUTLINES, COSString("invalid"))
        assertEquals(PdfOutlineProblem.MALFORMED,
            assertThrows(PdfOutlineParseException::class.java) { f.read() }.problem)
    }

    @Test fun duplicateNamedDestinationsNeverChooseAnArbitraryPage() {
        val f = Fixture(); val entry = f.entry("Named"); f.outline.setItem(COSName.FIRST, entry)
        entry.setItem(COSName.DEST, COSString("same"))
        f.catalog.setItem(COSName.NAMES, COSDictionary().apply {
            setItem(COSName.DESTS, COSDictionary().apply {
                setItem(COSName.NAMES, COSArray().apply {
                    add(COSString("same")); add(f.destination(0)); add(COSString("same")); add(f.destination(2))
                })
            })
        })
        assertEquals(PdfOutlineProblem.MALFORMED,
            assertThrows(PdfOutlineParseException::class.java) { f.read() }.problem)
    }

    @Test fun goToActionResolvesOnlyAnActualLocalPage() {
        val f = Fixture(); val entry = f.entry("Local")
        f.outline.setItem(COSName.FIRST, entry)
        entry.setItem(COSName.A, COSDictionary().apply {
            setItem(COSName.S, COSName.getPDFName("GoTo")); setItem(COSName.D, f.destination(2))
        })
        assertEquals(2, f.read().rows.single().pageIndex)
    }

    @Test fun externalLaunchRemoteAndJavaScriptActionsRemainNonNavigable() {
        for (type in listOf("URI", "Launch", "GoToR", "JavaScript")) {
            val f = Fixture(); val entry = f.entry(type); f.outline.setItem(COSName.FIRST, entry)
            entry.setItem(COSName.A, COSDictionary().apply {
                setItem(COSName.S, COSName.getPDFName(type)); setItem(COSName.D, f.destination(1))
            })
            assertNull(f.read().rows.single().pageIndex)
        }
    }

    @Test fun competingDestAndActionDoNotPickOneArbitrarily() {
        val f = Fixture(); val entry = f.entry("Competing", 1); f.outline.setItem(COSName.FIRST, entry)
        entry.setItem(COSName.A, COSDictionary().apply {
            setItem(COSName.S, COSName.getPDFName("GoTo")); setItem(COSName.D, f.destination(2))
        })
        assertNull(f.read().rows.single().pageIndex)
    }

    @Test fun aDetachedPageDictionaryAndNumericRemotePageCannotClaimLocalTargets() {
        for (target in listOf(COSDictionary().apply { setItem(COSName.TYPE, COSName.PAGE) }, COSInteger.ONE)) {
            val f = Fixture(); val entry = f.entry("Unverified"); f.outline.setItem(COSName.FIRST, entry)
            entry.setItem(COSName.DEST, COSArray().apply { add(target); add(COSName.getPDFName("Fit")) })
            assertNull(f.read().rows.single().pageIndex)
        }
    }

    @Test fun nameTreeAndLegacyDictionaryResolveRealNamedDestinations() {
        val f = Fixture(); val entry = f.entry("Named"); f.outline.setItem(COSName.FIRST, entry)
        entry.setItem(COSName.DEST, COSString("later"))
        f.catalog.setItem(COSName.NAMES, COSDictionary().apply {
            setItem(COSName.DESTS, COSDictionary().apply {
                setItem(COSName.KIDS, COSArray().apply { add(COSDictionary().apply {
                    setItem(COSName.NAMES, COSArray().apply { add(COSString("later")); add(f.destination(2)) })
                }) })
            })
        })
        assertEquals(2, f.read().rows.single().pageIndex)
        f.catalog.removeItem(COSName.NAMES)
        f.catalog.setItem(COSName.DESTS, COSDictionary().apply {
            setItem(COSName.getPDFName("later"), COSDictionary().apply { setItem(COSName.D, f.destination(1)) })
        })
        assertEquals(1, f.read().rows.single().pageIndex)
    }

    @Test fun cyclicNamedDestinationsAndNameTreesDoNotLoop() {
        val f = Fixture(); val entry = f.entry("Cycle"); f.outline.setItem(COSName.FIRST, entry)
        entry.setItem(COSName.DEST, COSString("loop"))
        f.catalog.setItem(COSName.DESTS, COSDictionary().apply { setItem(COSName.getPDFName("loop"), COSString("loop")) })
        assertNull(f.read().rows.single().pageIndex)
        f.catalog.removeItem(COSName.DESTS)
        val tree = COSDictionary(); tree.setItem(COSName.KIDS, COSArray().apply { add(tree) })
        f.catalog.setItem(COSName.NAMES, COSDictionary().apply { setItem(COSName.DESTS, tree) })
        assertNull(f.read().rows.single().pageIndex)
    }

    @Test fun siblingAndChildCyclesFailInsteadOfRepeatingTitles() {
        for (field in listOf(COSName.NEXT, COSName.FIRST)) {
            val f = Fixture(); val entry = f.entry("Loop", 0); f.outline.setItem(COSName.FIRST, entry); entry.setItem(field, entry)
            assertEquals(PdfOutlineProblem.MALFORMED,
                assertThrows(PdfOutlineParseException::class.java) { f.read() }.problem)
        }
    }

    @Test fun pageCountMismatchAndRepeatedPageObjectsFail() {
        val f = Fixture(); f.outline.setItem(COSName.FIRST, f.entry("Entry", 0))
        assertEquals(PdfOutlineProblem.MALFORMED,
            assertThrows(PdfOutlineParseException::class.java) { BoundedPdfOutline().read(f.catalog, 4) }.problem)
        val root = f.catalog.getDictionaryObject(COSName.PAGES) as COSDictionary
        root.setItem(COSName.KIDS, COSArray().apply { repeat(3) { add(f.pages[0]) } })
        assertEquals(PdfOutlineProblem.MALFORMED, assertThrows(PdfOutlineParseException::class.java) { f.read() }.problem)
    }

    @Test fun deeplyNestedOutlinesHaveABoundedFailure() {
        val f = Fixture(); val first = f.entry("Root", 0); f.outline.setItem(COSName.FIRST, first)
        var previous = first
        repeat(66) { val next = f.entry("Deep", 0); previous.setItem(COSName.FIRST, next); previous = next }
        assertEquals(PdfOutlineProblem.LIMIT, assertThrows(PdfOutlineParseException::class.java) { f.read() }.problem)
    }

    @Test fun aLongOutlineReturnsOnlyTheExplicitlyTruncatedPrefix() {
        val f = Fixture(); val first = f.entry("0", 0); f.outline.setItem(COSName.FIRST, first)
        var previous = first
        repeat(5000) { val next = f.entry("${it+1}", 1); previous.setItem(COSName.NEXT, next); previous = next }
        val result = f.read(); assertEquals(5000, result.rows.size); assertTrue(result.truncated)
        assertThrows(UnsupportedOperationException::class.java) { (result.rows as MutableList).clear() }
    }

    @Test fun cancellationPropagatesAsTheExactOriginalException() {
        val cancelled = CancellationException("retired owner")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            BoundedPdfOutline { throw cancelled }.read(COSDictionary(), 1)
        })
    }
}

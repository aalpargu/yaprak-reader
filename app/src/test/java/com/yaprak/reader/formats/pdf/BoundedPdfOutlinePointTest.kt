package com.yaprak.reader.formats.pdf

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Distinct PDF coordinate systems and malformed geometry, independent of display/crop preferences. */
class BoundedPdfOutlinePointTest {
    private class Fixture {
        val page = COSDictionary().apply { setItem(COSName.TYPE, COSName.PAGE) }
        val root = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.PAGES)
            setItem(COSName.KIDS, COSArray().apply { add(page) })
        }
        val entry = COSDictionary().apply { setItem(COSName.TITLE, COSString("Point")) }
        val catalog = COSDictionary().apply {
            setItem(COSName.PAGES, root)
            setItem(COSName.OUTLINES, COSDictionary().apply { setItem(COSName.FIRST, entry) })
        }
        init {
            page.setItem(COSName.PARENT, root)
            root.setItem(COSName.MEDIA_BOX, numbers(10, 20, 230, 1020))
            root.setItem(COSName.CROP_BOX, numbers(20, 120, 220, 920))
        }
        fun destination(mode: String, vararg values: COSBase) = COSArray().apply {
            add(page); add(COSName.getPDFName(mode)); values.forEach(::add)
        }
        fun xyz(left: COSBase = COSInteger.get(100), top: COSBase = COSInteger.get(600), zoom: COSBase = COSNull.NULL) {
            entry.setItem(COSName.DEST, destination("XYZ", left, top, zoom))
        }
        fun row() = BoundedPdfOutline().read(catalog, 1).rows.single()
    }

    @Test fun inheritedNonzeroMediaAndCropBoxesUseTopOriginNativePaperCoordinates() {
        val f = Fixture(); f.xyz()
        assertEquals(0, f.row().pageIndex); assertEquals(400_000, f.row().anchorY)
    }

    @Test fun fitHAndFitBHCarryTheirVerticalPointWithoutApplyingZoom() {
        for (mode in listOf("FitH", "FitBH")) {
            val f = Fixture(); f.entry.setItem(COSName.DEST, f.destination(mode, COSInteger.get(440)))
            assertEquals(600_000, f.row().anchorY)
        }
    }

    @Test fun allQuarterTurnRotationsUseTheRelevantOriginalCoordinate() {
        for ((degrees, expected) in listOf(0 to 400_000, 90 to 400_000, 180 to 600_000, 270 to 600_000, -90 to 600_000)) {
            val f = Fixture(); f.root.setInt(COSName.ROTATE, degrees); f.xyz()
            assertEquals("Rotation $degrees", expected, f.row().anchorY)
        }
    }

    @Test fun nullRelevantCoordinateDoesNotBecomeAnInventedZeroPoint() {
        for (degrees in listOf(0, 90, 180, 270)) {
            val f = Fixture(); f.root.setInt(COSName.ROTATE, degrees)
            if (degrees % 180 == 0) f.xyz(top = COSNull.NULL) else f.xyz(left = COSNull.NULL)
            assertEquals(0, f.row().pageIndex); assertNull(f.row().anchorY)
        }
        val f = Fixture(); f.root.setInt(COSName.ROTATE, 90)
        f.entry.setItem(COSName.DEST, f.destination("FitH", COSInteger.get(440)))
        assertNull(f.row().anchorY)
    }

    @Test fun zeroNullAndPositiveZoomDoNotChangeTheAdmittedVerticalPoint() {
        for (zoom in listOf(COSNull.NULL, COSInteger.ZERO, COSFloat(2.5f))) {
            val f = Fixture(); f.xyz(zoom = zoom); assertEquals(400_000, f.row().anchorY)
        }
    }

    @Test fun malformedParametersRetainOnlyTheVerifiedPageTarget() {
        val f = Fixture()
        for (bad in listOf(COSString("600"), COSName.getPDFName("other"), COSArray())) {
            f.xyz(top = bad); assertEquals(0, f.row().pageIndex); assertNull(f.row().anchorY)
        }
        f.xyz(zoom = COSInteger.get(-1)); assertNull(f.row().anchorY)
        f.entry.setItem(COSName.DEST, f.destination("XYZ", COSInteger.ZERO, COSInteger.get(600)))
        assertNull(f.row().anchorY)
        f.entry.setItem(COSName.DEST, f.destination("FitH", COSInteger.get(600), COSInteger.ZERO))
        assertNull(f.row().anchorY)
    }

    @Test fun cropIntersectionAndReversedBoxCornersMatchTheVisiblePage() {
        val f = Fixture()
        f.root.setItem(COSName.MEDIA_BOX, numbers(230, 1020, 10, 20))
        f.root.setItem(COSName.CROP_BOX, numbers(300, 920, 20, -100))
        f.xyz(top = COSInteger.get(560))
        // Intersection is x20..230/y20..920, rather than the unbounded crop rectangle.
        assertEquals(400_000, f.row().anchorY)
    }

    @Test fun explicitFiniteCoordinatesOutsideTheVisibleBoxClampToItsEdges() {
        val f = Fixture(); f.xyz(top = COSInteger.get(2000)); assertEquals(0, f.row().anchorY)
        f.xyz(top = COSInteger.get(-2000)); assertEquals(1_000_000, f.row().anchorY)
    }

    @Test fun nonFiniteGeometryAndPointValuesNeverProduceAnAnchor() {
        val box = listOf(0.0, 0.0, 200.0, 1000.0)
        val space = checkNotNull(PdfOutlinePageSpace.intersection(box, box, 0))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(space.anchorY(0.0, invalid))
            assertNull(PdfOutlinePageSpace.intersection(listOf(0.0, 0.0, 200.0, invalid), box, 0))
        }
    }

    @Test fun anUnrelatedParentCannotSupplyOrSuppressInheritedGeometry() {
        val f = Fixture(); f.xyz()
        f.page.setItem(COSName.MEDIA_BOX, numbers(0, 0, 200, 1000))
        f.page.setItem(COSName.PARENT, COSDictionary().apply { setInt(COSName.ROTATE, 180) })
        assertEquals(0, f.row().pageIndex); assertNull(f.row().anchorY)
    }

    @Test fun missingMalformedDisjointAndUnsupportedGeometryNeverGuessesAPoint() {
        val variants: List<(Fixture) -> Unit> = listOf(
            { it.root.removeItem(COSName.MEDIA_BOX) },
            { it.root.setItem(COSName.CROP_BOX, COSString("invalid")) },
            { it.root.setItem(COSName.CROP_BOX, numbers(500, 600, 700, 800)) },
            { it.root.setItem(COSName.CROP_BOX, numbers(20, 120, 20, 920)) },
            { it.root.setInt(COSName.ROTATE, 45) },
            { it.root.setItem(COSName.ROTATE, COSInteger.get(2_147_483_700L)) },
            { it.root.setItem(COSName.ROTATE, COSInteger.get(-2_147_483_790L)) },
            { it.root.setItem(COSName.ROTATE, COSFloat(90f)) },
        )
        for (variant in variants) {
            val f = Fixture(); f.xyz(); variant(f); assertEquals(0, f.row().pageIndex); assertNull(f.row().anchorY)
        }
    }

    @Test fun namedAndDictionaryWrappedGoToDestinationsKeepTheSamePoint() {
        val f = Fixture()
        f.catalog.setItem(COSName.DESTS, COSDictionary().apply {
            setItem(COSName.getPDFName("named"), COSDictionary().apply {
                setItem(COSName.D, f.destination("XYZ", COSInteger.get(100), COSInteger.get(600), COSInteger.ZERO))
            })
        })
        f.entry.setItem(COSName.A, COSDictionary().apply {
            setItem(COSName.S, COSName.getPDFName("GoTo")); setItem(COSName.D, COSString("named"))
        })
        assertEquals(400_000, f.row().anchorY)
    }

    @Test fun actualPdfBytesPreservePointTargetsWhileExternalActionsStayInert() {
        val file = File(checkNotNull(javaClass.classLoader!!.getResource("pdf-outline184.pdf")).toURI())
        PDDocument.load(file).use { document ->
            val rows = BoundedPdfOutline().read(document.documentCatalog.cosObject, 3).rows
            assertEquals(listOf(1, 1, null), rows.map { it.pageIndex })
            assertEquals(listOf(400_000, 600_000, null), rows.map { it.anchorY })
        }
    }

    private companion object {
        fun numbers(vararg values: Int) = COSArray().apply { values.forEach { add(COSInteger.get(it.toLong())) } }
    }
}

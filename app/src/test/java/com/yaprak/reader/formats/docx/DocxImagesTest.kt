package com.yaprak.reader.formats.docx

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.formats.epub.EpubBookSearch
import com.yaprak.reader.formats.epub.EpubPublicationSource
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocxImagesTest {
    @get:Rule val temp = TemporaryFolder()
    private val raster: DocxRasterValidator = { _, _ -> DocxRaster(6, 4) }
    private suspend fun decode(body: String) = StrictDocx.decode(DocxPictureFixtures.bytes(body), raster)
    private suspend fun refused(reason: DocxFailure = DocxFailure.UNSUPPORTED, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected DOCX refusal, got "+failure, failure is DocxException)
        assertEquals(reason, (failure as DocxException).reason)
    }

    @Test fun repeatedPictureUsesActualPartOnceAndEscapesAltWithoutChangingReadingOrder() = runBlocking {
        val picture = DocxPictureFixtures.picture(alt = "&lt;script&gt; &amp; &quot;x&quot;")
        var calls = 0
        val document = StrictDocx.decode(DocxPictureFixtures.bytes("<w:p><w:r><w:t>Before</w:t>"+picture+"<w:t>Between</w:t>"+picture+"<w:t>After</w:t></w:r></w:p>")) { bytes, extension ->
            calls++; assertEquals("png", extension); assertArrayEquals(DocxPictureFixtures.png(), bytes); DocxRaster(6,4)
        }
        assertEquals(1, calls); assertEquals(1, document.images.size)
        assertEquals("word/media/owned.png", document.images.single().part)
        assertArrayEquals(DocxPictureFixtures.png(), document.images.single().bytes)
        assertTrue(document.body.startsWith("<p>Before<img")); assertTrue(document.body.endsWith("After</p>"))
        assertEquals(2, Regex("src=\"../Images/image1.png\"").findAll(document.body).count())
        assertTrue(document.body.contains("alt=\"&lt;script&gt; &amp; &quot;x&quot;\""))
        assertTrue(document.body.contains("width:8.0000px;max-width:100%;height:auto"))
    }

    @Test fun imageOnlyStoryIsReadableButDeletedAndPropertyDrawingsAreNotPublished() = runBlocking {
        val picture = DocxPictureFixtures.picture()
        assertEquals(1, decode("<w:p><w:r>"+picture+"</w:r></w:p>").images.size)
        var calls = 0
        val document = StrictDocx.decode(DocxPictureFixtures.bytes("<w:p><w:pPr>"+picture+"</w:pPr><w:del><w:r>"+picture+"</w:r></w:del><w:r><w:t>Retained</w:t></w:r></w:p>")) { _, _ -> calls++; error("Deleted resource must not decode") }
        assertEquals(0, calls); assertTrue(document.images.isEmpty()); assertEquals("<p>Retained</p>", document.body)
    }

    @Test fun tableContentControlAndFinalInsertedPictureKeepTheImage() = runBlocking {
        val body = "<w:sdt><w:sdtContent><w:tbl><w:tr><w:tc><w:p><w:ins><w:r>"+DocxPictureFixtures.picture()+"</w:r></w:ins></w:p></w:tc></w:tr></w:tbl></w:sdtContent></w:sdt>"
        val document = decode(body)
        assertEquals(1, document.images.size); assertTrue(document.body.contains("<td><p><img"))
    }

    @Test fun strictNamespacesAndActualAlternateMainPartResolveTheOwningRelationship() = runBlocking {
        val body = "<w:p><w:r>"+DocxPictureFixtures.picture(strict = true)+"</w:r></w:p>"
        val parts = DocxPictureFixtures.parts(body, strict = true, main = "custom/main.xml", target = "../assets/actual.png", images = mapOf("assets/actual.png" to DocxPictureFixtures.png()))
        val document = StrictDocx.decode(DocxFixtures.zipBytes(parts), raster)
        assertEquals("assets/actual.png", document.images.single().part)
    }

    @Test fun missingWrongRoleRemoteAndLinkedPicturesCannotReachTheRasterValidator() = runBlocking {
        val body = "<w:p><w:r>"+DocxPictureFixtures.picture()+"</w:r></w:p>"
        for (relation in listOf(DocxFixtures.relationship("image","hyperlink","media/owned.png"), DocxFixtures.relationship("image","image","https://example.invalid/image.png", external = true))) {
            refused { StrictDocx.decode(DocxFixtures.zipBytes(DocxPictureFixtures.parts(body, relation = relation))) { _, _ -> error("No decode") } }
        }
        refused(DocxFailure.INVALID) { StrictDocx.decode(DocxFixtures.zipBytes(DocxPictureFixtures.parts(body, relation = "")), raster) }
        refused { decode(body.replace("r:embed='image'", "r:link='image'")) }
    }

    @Test fun floatingChartsForeignNamespacesAndExtraVisibleDrawingChildrenAreRefused() = runBlocking {
        val picture = DocxPictureFixtures.picture()
        val variants = listOf(picture.replace("wp:inline", "wp:anchor"),
            picture.replace("graphicData uri='http://schemas.openxmlformats.org/drawingml/2006/picture'", "graphicData uri='urn:chart'"),
            picture.replace("<a:graphic>", "<a:graphic><a:unknown/>"),
            picture.replace("<pic:pic>", "<pic:pic><pic:unknown/>"),
            picture.replace("xmlns:pic='http://schemas.openxmlformats.org/drawingml/2006/picture'", "xmlns:pic='urn:wrong'"))
        for (variant in variants) refused { decode("<w:p><w:r>"+variant+"</w:r></w:p>") }
    }

    @Test fun cropEffectsRotationOffsetHiddenAndNonRectangularGeometryAreNotSilentlyLost() = runBlocking {
        val picture = DocxPictureFixtures.picture()
        val variants = listOf(picture.replace("<a:stretch>", "<a:srcRect l='10'/><a:stretch>"),
            picture.replace("<a:blip r:embed='image'/>", "<a:blip r:embed='image'><a:grayscl/></a:blip>"),
            picture.replace("<a:xfrm>", "<a:xfrm rot='60000'>"), picture.replace("<a:xfrm>", "<a:xfrm flipH='1'>"),
            picture.replace("x='0'", "x='1'"), picture.replace("prst='rect'", "prst='ellipse'"),
            picture.replace("name='Picture'", "name='Picture' hidden='1'"),
            picture.replace("<a:fillRect/>", "<a:fillRect r='1'/>"),
            picture.replace("<wp:inline>", "<wp:inline distT='1'>"))
        for (variant in variants) refused { decode("<w:p><w:r>"+variant+"</w:r></w:p>") }
    }

    @Test fun mismatchedTransformAndDeliberateNonuniformStretchAreRefused() = runBlocking {
        val picture = DocxPictureFixtures.picture()
        refused { decode("<w:p><w:r>"+picture.replace("<a:ext cx='76200'", "<a:ext cx='90000'")+"</w:r></w:p>") }
        refused { StrictDocx.decode(DocxPictureFixtures.bytes()) { _, _ -> DocxRaster(4,4) } }
        refused(DocxFailure.INVALID) { decode("<w:p><w:r>"+picture.replace("<a:off x='0' y='0'/>","<a:off x='0' y='0'/><a:off x='0' y='0'/>")+"</w:r></w:p>") }
        refused { decode("<w:p><w:r>"+picture.replace("<pic:spPr>","<pic:spPr bwMode='gray'>")+"</w:r></w:p>") }
    }

    @Test fun onlyKnownInertBlipMetadataExtensionsAreAccepted() = runBlocking {
        val original = DocxPictureFixtures.picture()
        val extension = "<a:extLst><a:ext uri='{28A0092B-C50C-407E-A947-70E740481C1C}'><a14:useLocalDpi xmlns:a14='http://schemas.microsoft.com/office/drawing/2010/main' val='0'/></a:ext></a:extLst>"
        val picture = original.replace("<a:blip r:embed='image'/>", "<a:blip r:embed='image' cstate='print'>"+extension+"</a:blip>")
        assertEquals(1, decode("<w:p><w:r>"+picture+"</w:r></w:p>").images.size)
        refused { decode("<w:p><w:r>"+picture.replace("28A0092B", "00000000")+"</w:r></w:p>") }
        refused { decode("<w:p><w:r>"+picture.replace("a14:useLocalDpi", "a14:effect")+"</w:r></w:p>") }
    }

    @Test fun unsupportedMimeAndMalformedNumericDimensionsFailExplicitly() = runBlocking {
        refused { StrictDocx.decode(DocxFixtures.zipBytes(DocxPictureFixtures.parts(mime = "image/svg+xml")), raster) }
        refused(DocxFailure.INVALID) { decode("<w:p><w:r>"+DocxPictureFixtures.picture().replace("cx='76200'", "cx='bad'")+"</w:r></w:p>") }
        refused(DocxFailure.INVALID) { decode("<w:p><w:r>"+DocxPictureFixtures.picture().replace("<a:xfrm>", "<a:xfrm rot='bad'>")+"</w:r></w:p>") }
    }

    @Test fun individualAndCombinedSourceBudgetsFailBeforeExcessDecode() = runBlocking {
        var calls = 0
        val validator: DocxRasterValidator = { _, _ -> calls++; DocxRaster(6,4) }
        refused(DocxFailure.LIMIT) { StrictDocx.decode(DocxFixtures.zipBytes(DocxPictureFixtures.parts(images = mapOf("word/media/owned.png" to ByteArray(4*1024*1024+1)))), validator) }
        assertEquals(0, calls)
        val images = linkedMapOf("word/media/a.png" to ByteArray(4*1024*1024), "word/media/b.png" to ByteArray(4*1024*1024), "word/media/c.png" to byteArrayOf(1))
        val relations = images.keys.mapIndexed { index, path -> DocxFixtures.relationship("i"+index,"image",path.removePrefix("word/")) }.joinToString("")
        val body = "<w:p><w:r>"+(0..2).joinToString("") { DocxPictureFixtures.picture("i"+it) }+"</w:r></w:p>"
        refused(DocxFailure.LIMIT) { StrictDocx.decode(DocxFixtures.zipBytes(DocxPictureFixtures.parts(body, images = images, relation = relations)), validator) }
        assertEquals(2, calls)
    }

    @Test fun sourcePixelAndOrientationBoundsRejectUnsupportedRasterMetadata() = runBlocking {
        refused(DocxFailure.LIMIT) { StrictDocx.decode(DocxPictureFixtures.bytes()) { _, _ -> DocxRaster(2001,2000) } }
        refused { StrictDocx.decode(DocxPictureFixtures.bytes()) { _, _ -> DocxRaster(6,4,6) } }
        refused(DocxFailure.INVALID) { StrictDocx.decode(DocxPictureFixtures.bytes()) { _, _ -> DocxRaster(0,4) } }
    }

    @Test fun uniquePartLimitDoesNotDecodeThe129thResource() = runBlocking {
        val images = (0..128).associate { "word/media/i"+it+".png" to DocxPictureFixtures.png() }
        val relations = images.keys.mapIndexed { index, path -> DocxFixtures.relationship("i"+index,"image",path.removePrefix("word/")) }.joinToString("")
        val body = "<w:p><w:r>"+(0..128).joinToString("") { DocxPictureFixtures.picture("i"+it) }+"</w:r></w:p>"
        var calls = 0
        refused(DocxFailure.LIMIT) { StrictDocx.decode(DocxFixtures.zipBytes(DocxPictureFixtures.parts(body, images = images, relation = relations))) { _, _ -> calls++; DocxRaster(6,4) } }
        assertEquals(128, calls)
    }

    @Test fun cancellationAndTypedRasterFailurePropagateWithoutReturningPartialContent() = runBlocking {
        val cancellation = CancellationException("owned")
        assertSame(cancellation, runCatching { StrictDocx.decode(DocxPictureFixtures.bytes()) { _, _ -> throw cancellation } }.exceptionOrNull())
        refused(DocxFailure.INVALID) { StrictDocx.decode(DocxPictureFixtures.bytes()) { _, _ -> docxFail() } }
        // This production validation fails at complete-container integrity, before Android raster decoding.
        refused(DocxFailure.INVALID) { validateDocxRaster(DocxPictureFixtures.png().copyOf(24), "png") }
    }

    @Test fun independentProducerPackageHasExactBinaryManifestAndRealReadiumTextSearch() = runBlocking {
        val original = DocxPictureFixtures.original()
        val document = StrictDocx.decode(original, raster)
        assertEquals("Owned image DOCX", document.metadata.title); assertEquals(1, document.images.size)
        val first = temp.newFile("images.epub"); val second = temp.newFile("images-copy.epub")
        DocxEpub.write(first, "a".repeat(64), document); DocxEpub.write(second, "a".repeat(64), document)
        assertArrayEquals(first.readBytes(), second.readBytes())
        ZipFile(first).use { zip ->
            assertArrayEquals(DocxPictureFixtures.png(), zip.getInputStream(zip.getEntry("Images/image1.png")).readBytes())
            val manifest = zip.getInputStream(zip.getEntry("package.opf")).bufferedReader().readText()
            assertTrue(manifest.contains("href=\"Images/image1.png\" media-type=\"image/png\""))
        }
        val context = ApplicationProvider.getApplicationContext<Application>()
        EpubPublicationSource(context.contentResolver).open(first).use { opened ->
            assertEquals("Text/document.xhtml", EpubBookSearch(opened.publication).find("After picture unique tail").hits.single().location.resourceHref)
            assertEquals("Picture heading", opened.publication.tableOfContents.single().title)
        }
        val owned = File("build/reports/fixtures/docx-images-0.162.0.epub")
        requireNotNull(owned.parentFile).mkdirs(); first.copyTo(owned, overwrite = true); Unit
    }

    @Test fun invalidGeneratedResourceCannotTruncateAnExistingOutput() = runBlocking {
        val document = decode("<w:p><w:r>"+DocxPictureFixtures.picture()+"</w:r></w:p>")
        val output = temp.newFile().apply { writeText("existing") }
        val invalid = document.copy(images = listOf(document.images.single().copy(href = "../outside.png")))
        refused(DocxFailure.INVALID) { DocxEpub.write(output, "a".repeat(64), invalid) }
        assertEquals("existing", output.readText())
    }
}

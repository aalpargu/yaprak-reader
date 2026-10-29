package com.yaprak.reader.formats.fb2

import android.app.Application
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrictFb2Test {
    private fun xml(body: String = "<section id='chapter'><title><p>Bölüm 🌿</p></title><p>İğne &amp; kitap &#x1F33F;</p></section>",
        extra: String = "", cover: String = "") =
        "<FictionBook xmlns='$FB2_NAMESPACE' xmlns:l='$FB2_LINK_NAMESPACE'><description><title-info>" +
        "<author><first-name>Ada</first-name><middle-name>M.</middle-name><last-name>Yazar</last-name></author>" +
        "<book-title>  Özgün 🌿 Kitap  </book-title><lang>tr</lang>$cover</title-info></description><body>$body</body>$extra</FictionBook>"
    private suspend fun decode(value: String) = StrictFb2.decode(value.toByteArray(Charsets.UTF_8))
    private suspend fun refused(reason: Fb2Failure = Fb2Failure.INVALID, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected bounded FB2 refusal, got ${failure?.javaClass?.simpleName}", failure is Fb2Exception)
        assertEquals(reason, (failure as Fb2Exception).reason)
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    @Test fun blankBooksAndContentInsideEmptyElementsAreRefusedInsteadOfLosingText() = runBlocking {
        for (body in listOf("<section><p>   </p></section>", "<section><p>ok</p><empty-line>lost text</empty-line></section>")) {
            refused { decode(xml(body)) }
        }
    }

    @Test fun originalUnicodeMetadataInlineStylesCdataAndPredefinedEntitiesRemainLogicalXmlText() = runBlocking {
        val book = decode(xml("<section><p id='p1'>  İğne <strong>kalın</strong><emphasis>eğik</emphasis><![CDATA[ <işaret> ]]>&amp; &#x1F33F;  </p></section>"))
        assertEquals("Özgün 🌿 Kitap", book.title); assertEquals("Ada M. Yazar", book.authors); assertEquals("tr", book.language)
        assertEquals("  İğne kalıneğik <işaret> & 🌿  ", book.bodies.single().elements.single().elements.single().text())
        assertEquals(listOf("strong", "emphasis"), book.bodies.single().elements.single().elements.single().elements.map { it.name })
    }
    @Test fun utf8BomUtf16BothOrdersAndExplicitCyrillicCodepageDecodeWithoutGuessing() = runBlocking {
        val plain = xml()
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bom = if (charset == Charsets.UTF_16LE) byteArrayOf(-1, -2) else byteArrayOf(-2, -1)
            val actual = StrictFb2.decode(bom + ("<?xml version='1.0' encoding='UTF-16'?>" + plain).toByteArray(charset))
            assertEquals("Özgün 🌿 Kitap", actual.title)
        }
        assertEquals("Özgün 🌿 Kitap", StrictFb2.decode(byteArrayOf(-17, -69, -65) + plain.toByteArray()).title)
        val cyrillic = xml().replace("Özgün 🌿 Kitap", "Книга").replace("İğne &amp; kitap &#x1F33F;", "Привет").replace("Bölüm 🌿", "Глава")
        val actual = StrictFb2.decode(("<?xml version='1.0' encoding='windows-1251'?>" + cyrillic).toByteArray(Charset.forName("windows-1251")))
        assertEquals("Книга", actual.title); assertTrue(actual.bodies.single().text().contains("Привет"))
    }
    @Test fun malformedBytesUnknownEncodingAndBomDeclarationMismatchAreRefused() = runBlocking {
        refused { StrictFb2.decode(xml().toByteArray() + byteArrayOf(-61, 40)) }
        refused(Fb2Failure.UNSUPPORTED) { decode("<?xml version='1.0' encoding='UTF-7'?>" + xml()) }
        refused { StrictFb2.decode(byteArrayOf(-17, -69, -65) + ("<?xml version='1.0' encoding='windows-1251'?>" + xml()).toByteArray()) }
    }
    @Test fun dtdAndExternalOrCustomEntitiesNeverBecomeBookContent() = runBlocking {
        refused { decode("<!DOCTYPE FictionBook [<!ENTITY hidden 'injected'>]>" + xml("<section><p>&hidden;</p></section>")) }
        refused { decode("<!DOCTYPE FictionBook SYSTEM 'urn:yaprak:no-external-fetch'>" + xml()) }
        refused { decode(xml("<section><p>&unresolved;</p></section>")) }
        assertTrue(decode(xml("<section><p><![CDATA[<!DOCTYPE literal>]]></p></section>")).bodies.single().text().contains("<!DOCTYPE literal>"))
    }
    @Test fun foreignNamespacesUnknownBodyTagsAndNonFb2RootsAreRefused() = runBlocking {
        refused(Fb2Failure.UNSUPPORTED) { decode(xml().replace("<section id='chapter'>", "<section xmlns='urn:foreign' id='chapter'>")) }
        refused(Fb2Failure.UNSUPPORTED) { decode(xml("<section><script>bad</script></section>")) }
        refused { decode(xml().replace("FictionBook", "OtherBook")) }
        refused(Fb2Failure.UNSUPPORTED) { decode(xml().replace("xmlns='$FB2_NAMESPACE'", "")) }
    }
    @Test fun byteDepthNodeBudgetsAndCallerCancellationRefuseWholeDocuments() = runBlocking {
        refused(Fb2Failure.LIMIT) { StrictFb2.decode(ByteArray(MAX_FB2_BYTES + 1)) }
        refused(Fb2Failure.LIMIT) { decode(xml("<section>".repeat(49) + "<p>deep</p>" + "</section>".repeat(49))) }
        refused(Fb2Failure.LIMIT) { decode(xml("<section><p>" + "&amp;".repeat(200_001) + "</p></section>")) }
        var checks = 0
        assertTrue(runCatching { StrictFb2.decode(xml().toByteArray()) { if (++checks == 3) throw CancellationException("test") } }.exceptionOrNull() is CancellationException)
    }
    @Test fun originalEmbeddedPngCoverAndImageBytesRemainExact() = runBlocking {
        val bytes = png(); val data = Base64.getEncoder().encodeToString(bytes)
        val book = decode(xml("<section><image l:href='#cover'/></section>",
            "<binary id='cover' content-type='image/png'>$data</binary>", "<coverpage><image l:href='#cover'/></coverpage>"))
        assertEquals("cover", book.coverId); assertEquals("image/png", book.binaries.single().mimeType)
        assertArrayEquals(bytes, book.binaries.single().bytes)
    }
    @Test fun duplicateIdsUnresolvedImagesAndUnsafeSchemesAreRefused() = runBlocking {
        refused { decode(xml("<section id='same'><p id='same'>x</p></section>")) }
        refused { decode(xml("<section><image l:href='#missing'/></section>")) }
        refused(Fb2Failure.UNSUPPORTED) { decode(xml("<section><p><a l:href='javascript:alert(1)'>x</a></p></section>")) }
        refused { decode(xml("<section><image l:href='https://example.invalid/image.png'/></section>")) }
    }
    @Test fun mainAndNotesBodiesKeepInternalReferencesAndSourceOrder() = runBlocking {
        val book = decode(xml("<section><p>Text<a l:href='#note1' type='note'>1</a></p></section>",
            "<body name='notes'><section id='note1'><p>Özgün dipnot</p></section></body>"))
        assertEquals(2, book.bodies.size); assertTrue(book.bodies[0].text().contains("Text1"))
        assertEquals("notes", book.bodies[1].attribute("name")); assertTrue(book.bodies[1].text().contains("Özgün dipnot"))
    }
    @Test fun percentEncodedUnicodeInternalTargetsResolveToTheOriginalId() = runBlocking {
        val book = decode(xml("<section><p><a l:href='#not%F0%9F%8C%BF'>1</a></p></section>",
            "<body name='notes'><section id='not🌿'><p>Dipnot</p></section></body>"))
        assertEquals("not🌿", book.bodies[1].elements.single().attribute("id"))
    }
    @Test fun numericInvalidXmlCodepointsAndMalformedEncodedFragmentsCannotInventCharacters() = runBlocking {
        for (entity in listOf("&#0;", "&#xD800;", "&#xFFFF;", "&#x110000;"))
            refused { decode(xml("<section><p>$entity</p></section>")) }
        assertNull(fb2InternalId("#bad%FF")); assertNull(fb2InternalId("#bad%"))
        assertEquals("a+b", fb2InternalId("#a+b")); assertEquals("not🌿", fb2InternalId("#not%F0%9F%8C%BF"))
    }
    @Test fun malformedOrMislabeledEmbeddedImageCannotPassAsADrawableResource() = runBlocking {
        refused { decode(xml(extra = "<binary id='bad' content-type='image/png'>AQIDBA==</binary>")) }
        refused { decode(xml(extra = "<binary id='bad' content-type='image/jpeg'>${Base64.getEncoder().encodeToString(png())}</binary>")) }
    }
}

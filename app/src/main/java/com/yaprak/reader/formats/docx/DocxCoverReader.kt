package com.yaprak.reader.formats.docx

import android.graphics.Bitmap
import com.yaprak.reader.formats.CustomCoverBitmapDecoder
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only an actual package thumbnail relationship; never infer a cover from the first body image. */
internal class DocxCoverReader(private val validate: DocxRasterValidator = ::validateDocxRaster) {
    suspend fun thumbnail(file: File): DocxBinary? {
        val caller=currentCoroutineContext(); caller.ensureActive()
        val opc=DocxOpc.read(DocxPackages.read(file))
        val links=opc.relationships().values.filter { it.type==DOCX_PACKAGE_REL_NS+"/metadata/thumbnail" }
        if(links.size>1) docxFail()
        val link=links.singleOrNull() ?: return null
        if(link.external) docxFail(DocxFailure.UNSUPPORTED)
        val part=opc.resolve("",link.target)
        val extension=when(opc.type(part)) { "image/png"->"png";"image/jpeg"->"jpg";"image/webp"->"webp";else->return null }
        val bytes=opc.source.required(part)
        if(bytes.size>4*1024*1024) docxFail(DocxFailure.LIMIT)
        val info=validate(docxThumbnailRasterBytes(bytes,extension),extension)
        caller.ensureActive()
        if(info.width<=0 || info.height<=0) docxFail()
        if(info.width.toLong()*info.height>4_000_000L) docxFail(DocxFailure.LIMIT)
        return DocxBinary(part,"",opc.type(part),bytes)
    }

    suspend fun read(file: File): Bitmap? {
        val data=thumbnail(file) ?: return null
        val caller=currentCoroutineContext(); caller.ensureActive()
        val bytes=docxThumbnailRasterBytes(data.bytes,if(data.mime=="image/jpeg") "jpg" else "")
        val bitmap=CustomCoverBitmapDecoder.decode(bytes)
        try { caller.ensureActive() }
        catch(failure: Throwable) { bitmap?.recycle(); throw failure }
        return bitmap
    }
}

/** Some OPC producers pad their declared JPEG thumbnail with exactly one NUL after EOI.
 * Normalize only that decode input; the package and returned declared-part bytes stay exact.
 * Full JPEG/container validation still follows. Body pictures and CBZ admission are unchanged.
 */
internal fun docxThumbnailRasterBytes(bytes: ByteArray, extension: String): ByteArray {
    val paddedJpeg = extension in setOf("jpg", "jpeg") && bytes.size >= 7 &&
        bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() &&
        bytes[bytes.size - 3] == (-1).toByte() && bytes[bytes.size - 2] == (-39).toByte() &&
        bytes.last() == 0.toByte()
    return if (paddedJpeg) bytes.copyOf(bytes.size - 1) else bytes
}

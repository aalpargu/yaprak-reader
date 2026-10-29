package com.yaprak.reader.formats.docx

import com.yaprak.reader.formats.cbz.CbzException
import com.yaprak.reader.formats.cbz.CbzFailure
import com.yaprak.reader.formats.cbz.CbzImages
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class DocxBinary(val part: String, val href: String, val mime: String, val bytes: ByteArray)
internal data class DocxRaster(val width: Int, val height: Int, val orientation: Int = 1)
internal typealias DocxRasterValidator = suspend (ByteArray, String) -> DocxRaster

/** Production always validates the complete static container and an actual bounded Android decode. */
internal suspend fun validateDocxRaster(bytes: ByteArray, extension: String): DocxRaster {
    val image = try { CbzImages.validate(bytes, extension) }
        catch (failure: CbzException) { docxFail(when (failure.reason) {
            CbzFailure.LIMIT -> DocxFailure.LIMIT
            CbzFailure.UNSUPPORTED -> DocxFailure.UNSUPPORTED
            else -> DocxFailure.INVALID
        }) }
    if (image.width.toLong() * image.height > 4_000_000L) docxFail(DocxFailure.LIMIT)
    val orientation = ByteArrayInputStream(bytes).use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }
    currentCoroutineContext().ensureActive()
    return DocxRaster(image.width, image.height, orientation)
}

internal data class DocxImageOccurrence(val image: DocxBinary, val alt: String, val widthCss: String)
internal class DocxImageSet(val binaries: List<DocxBinary>, private val occurrences: IdentityHashMap<DocxXmlElement, DocxImageOccurrence>) {
    fun occurrence(drawing: DocxXmlElement): DocxImageOccurrence = occurrences[drawing] ?: docxFail()
}

/** Supported inline bitmap pictures only; no arbitrary descendant harvesting or remote fetching. */
internal object DocxImages {
    suspend fun read(opc: DocxOpc, body: DocxXmlElement, validate: DocxRasterValidator = ::validateDocxRaster): DocxImageSet {
        val caller=currentCoroutineContext(); caller.ensureActive()
        val strict=opc.wordNamespace==DOCX_STRICT_WORD_NS
        val wp=if(strict) "http://purl.oclc.org/ooxml/drawingml/wordprocessingDrawing" else "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
        val a=if(strict) "http://purl.oclc.org/ooxml/drawingml/main" else "http://schemas.openxmlformats.org/drawingml/2006/main"
        val pic=if(strict) "http://purl.oclc.org/ooxml/drawingml/picture" else "http://schemas.openxmlformats.org/drawingml/2006/picture"
        val binaries=LinkedHashMap<String,DocxBinary>()
        val dimensions=HashMap<String,DocxRaster>()
        val occurrences=IdentityHashMap<DocxXmlElement,DocxImageOccurrence>()
        fun only(node: DocxXmlElement, allowed: Set<DocxName>) {
            node.requireElementContent()
            if(node.elements.any { it.name !in allowed }) docxFail(DocxFailure.UNSUPPORTED)
        }
        fun one(node: DocxXmlElement, local: String, namespace: String): DocxXmlElement = node.single(local,namespace) ?: docxFail()
        fun empty(node: DocxXmlElement) { node.requireElementContent(); if(node.elements.isNotEmpty()) docxFail(DocxFailure.UNSUPPORTED) }
        fun zero(node: DocxXmlElement,key: String) {
            node.attribute(key)?.let { raw ->
                val number=raw.toLongOrNull() ?: docxFail()
                if(number!=0L) docxFail(DocxFailure.UNSUPPORTED)
            }
        }
        suspend fun image(drawing: DocxXmlElement) {
            caller.ensureActive()
            only(drawing,setOf(DocxName(wp,"inline")))
            val inline=drawing.single("inline",wp) ?: docxFail(DocxFailure.UNSUPPORTED)
            listOf("distT","distB","distL","distR").forEach { zero(inline,it) }
            only(inline,setOf("extent","effectExtent","docPr","cNvGraphicFramePr").map { DocxName(wp,it) }.toSet()+DocxName(a,"graphic"))
            val extent=one(inline,"extent",wp); empty(extent)
            val cx=extent.attribute("cx")?.toLongOrNull()?.takeIf { it in 1..1_000_000_000L } ?: docxFail()
            val cy=extent.attribute("cy")?.toLongOrNull()?.takeIf { it in 1..1_000_000_000L } ?: docxFail()
            inline.single("effectExtent",wp)?.let { effect -> empty(effect); listOf("l","t","r","b").forEach { zero(effect,it) } }
            val properties=one(inline,"docPr",wp); empty(properties)
            if(properties.attribute("hidden")?.let(::docxOnOff)==true) docxFail(DocxFailure.UNSUPPORTED)
            inline.single("cNvGraphicFramePr",wp)?.let { frame ->
                only(frame,setOf(DocxName(a,"graphicFrameLocks"))); frame.single("graphicFrameLocks",a)?.let(::empty)
            }
            val graphic=one(inline,"graphic",a); only(graphic,setOf(DocxName(a,"graphicData")))
            val data=one(graphic,"graphicData",a)
            if(data.attribute("uri")!=pic) docxFail(DocxFailure.UNSUPPORTED)
            only(data,setOf(DocxName(pic,"pic")))
            val picture=one(data,"pic",pic)
            only(picture,setOf("nvPicPr","blipFill","spPr").map { DocxName(pic,it) }.toSet())
            val nonVisual=one(picture,"nvPicPr",pic)
            only(nonVisual,setOf(DocxName(pic,"cNvPr"),DocxName(pic,"cNvPicPr")))
            val pictureProperties=one(nonVisual,"cNvPr",pic); empty(pictureProperties)
            if(pictureProperties.attribute("hidden")?.let(::docxOnOff)==true) docxFail(DocxFailure.UNSUPPORTED)
            one(nonVisual,"cNvPicPr",pic).let { node ->
                only(node,setOf(DocxName(a,"picLocks"))); node.single("picLocks",a)?.let(::empty)
            }
            val fill=one(picture,"blipFill",pic)
            only(fill,setOf("blip","stretch").map { DocxName(a,it) }.toSet())
            val blip=one(fill,"blip",a)
            // Color/effect transforms and unknown extensions are not silently discarded.
            only(blip,setOf(DocxName(a,"extLst")))
            // Microsoft picture metadata only; other extensions/effects need explicit rendering support.
            blip.single("extLst",a)?.let { extensions ->
                only(extensions,setOf(DocxName(a,"ext")))
                if(extensions.elements.size!=1) docxFail(DocxFailure.UNSUPPORTED)
                val extension=one(extensions,"ext",a)
                if(!extension.attribute("uri").equals("{28A0092B-C50C-407E-A947-70E740481C1C}",true)) docxFail(DocxFailure.UNSUPPORTED)
                val a14="http://schemas.microsoft.com/office/drawing/2010/main"
                only(extension,setOf(DocxName(a14,"useLocalDpi")))
                extension.single("useLocalDpi",a14)?.let { dpi -> empty(dpi); docxOnOff(dpi.attribute("val") ?: docxFail()) }
            }
            if(blip.attributes.keys.any { it !in setOf(DocxName(opc.relationshipNamespace,"embed"),DocxName(opc.relationshipNamespace,"link"),DocxName("","cstate")) })
                docxFail(DocxFailure.UNSUPPORTED)
            if(blip.attribute("link",opc.relationshipNamespace)!=null) docxFail(DocxFailure.UNSUPPORTED)
            val relation=opc.relationship(opc.mainPart,blip.attribute("embed",opc.relationshipNamespace) ?: docxFail())
            if(relation.external || relation.type!=opc.relationshipNamespace+"/image") docxFail(DocxFailure.UNSUPPORTED)
            val stretch=one(fill,"stretch",a); only(stretch,setOf(DocxName(a,"fillRect")))
            val rect=one(stretch,"fillRect",a); empty(rect)
            listOf("l","t","r","b").forEach { zero(rect,it) }
            val shape=one(picture,"spPr",pic)
            if(shape.attribute("bwMode")?.let { it!="auto" }==true) docxFail(DocxFailure.UNSUPPORTED)
            only(shape,setOf("xfrm","prstGeom").map { DocxName(a,it) }.toSet())
            shape.single("xfrm",a)?.let { transform ->
                zero(transform,"rot")
                if(listOf("flipH","flipV").any { transform.attribute(it)?.let(::docxOnOff)==true }) docxFail(DocxFailure.UNSUPPORTED)
                only(transform,setOf(DocxName(a,"off"),DocxName(a,"ext")))
                transform.single("off",a); transform.single("ext",a)
                transform.elements.forEach { element ->
                    empty(element)
                    if(element.name.local=="off") {
                        for(key in listOf("x","y")) {
                            val offset=element.attribute(key)?.toLongOrNull() ?: docxFail()
                            if(offset!=0L) docxFail(DocxFailure.UNSUPPORTED)
                        }
                    } else {
                        if(element.attribute("cx")?.toLongOrNull()!=cx || element.attribute("cy")?.toLongOrNull()!=cy)
                            docxFail(DocxFailure.UNSUPPORTED)
                    }
                }
            }
            val geometry=one(shape,"prstGeom",a)
            if(geometry.attribute("prst")!="rect") docxFail(DocxFailure.UNSUPPORTED)
            only(geometry,setOf(DocxName(a,"avLst"))); geometry.single("avLst",a)?.let(::empty)
            val part=opc.resolve(opc.mainPart,relation.target)
            val binary=binaries[part] ?: run {
                val mime=opc.type(part)
                val extension=when(mime) { "image/png"->"png";"image/jpeg"->"jpg";"image/webp"->"webp";else->docxFail(DocxFailure.UNSUPPORTED) }
                val bytes=opc.source.required(part)
                if(bytes.size>4*1024*1024) docxFail(DocxFailure.LIMIT)
                if(binaries.size>=128 || binaries.values.sumOf { it.bytes.size.toLong() }+bytes.size>8L*1024*1024) docxFail(DocxFailure.LIMIT)
                val decoded=validate(bytes,extension)
                caller.ensureActive()
                if(decoded.width<=0 || decoded.height<=0) docxFail()
                if(decoded.width.toLong()*decoded.height>4_000_000L) docxFail(DocxFailure.LIMIT)
                if(decoded.orientation !in setOf(0,1)) docxFail(DocxFailure.UNSUPPORTED)
                dimensions[part]=decoded
                DocxBinary(part,"Images/image"+(binaries.size+1)+"."+extension,mime,bytes).also { binaries[part]=it }
            }
            val alt=(properties.attribute("descr") ?: properties.attribute("title") ?: properties.attribute("name")).orEmpty()
            val raster=dimensions.getValue(part)
            val left=cx*raster.height; val right=cy*raster.width
            // Preserve natural proportions. Deliberate nonuniform Word stretching is unsupported.
            if(abs(left-right)>maxOf(left,right)/1000L+raster.width+raster.height) docxFail(DocxFailure.UNSUPPORTED)
            occurrences[drawing]=DocxImageOccurrence(binary,alt,String.format(Locale.ROOT,"%.4fpx",cx/9525.0))
            if(occurrences.size>4096) docxFail(DocxFailure.LIMIT)
        }
        suspend fun visit(node: DocxXmlElement) {
            caller.ensureActive()
            if(node.name.namespace==opc.wordNamespace && node.name.local in setOf("del","moveFrom","pPr","rPr","tblPr","trPr","tcPr","tblGrid","sdtPr","sdtEndPr","sectPr","instrText","fldChar","lastRenderedPageBreak","annotationRef","proofErr","bookmarkStart","bookmarkEnd","commentRangeStart","commentRangeEnd")) return
            if(node.name==DocxName(opc.wordNamespace,"drawing")) { image(node); return }
            for(child in node.elements) visit(child)
        }
        visit(body); caller.ensureActive()
        return DocxImageSet(binaries.values.toList(),occurrences)
    }
}

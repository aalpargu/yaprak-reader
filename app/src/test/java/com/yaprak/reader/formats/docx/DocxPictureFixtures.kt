package com.yaprak.reader.formats.docx

import java.util.zip.ZipInputStream

/** Owned image packages. Host raster metadata is explicitly controlled; actual decode has device tests. */
internal object DocxPictureFixtures {
    fun original(): ByteArray = requireNotNull(javaClass.getResourceAsStream("/docx/python-docx-image.docx")).use { it.readBytes() }
    fun png(): ByteArray = ZipInputStream(original().inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: error("Owned PNG missing")
            if (entry.name == "word/media/image1.png") return@use zip.readBytes()
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    fun picture(id: String = "image", strict: Boolean = false, alt: String = "Owned &amp; 🌿"): String {
        val base = if (strict) "http://purl.oclc.org/ooxml/drawingml/" else "http://schemas.openxmlformats.org/drawingml/2006/"
        val rel = if (strict) DOCX_STRICT_REL_NS else DOCX_REL_NS
        return "<w:drawing xmlns:wp='"+base+"wordprocessingDrawing' xmlns:a='"+base+"main' xmlns:pic='"+base+"picture' xmlns:r='"+rel+"'>"+
            "<wp:inline><wp:extent cx='76200' cy='50800'/><wp:docPr id='1' name='Picture' descr='"+alt+"'/>"+
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect='1'/></wp:cNvGraphicFramePr><a:graphic><a:graphicData uri='"+base+"picture'>"+
            "<pic:pic><pic:nvPicPr><pic:cNvPr id='0' name='owned.png'/><pic:cNvPicPr/></pic:nvPicPr>"+
            "<pic:blipFill><a:blip r:embed='"+id+"'/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"+
            "<pic:spPr><a:xfrm><a:off x='0' y='0'/><a:ext cx='76200' cy='50800'/></a:xfrm><a:prstGeom prst='rect'><a:avLst/></a:prstGeom></pic:spPr>"+
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing>"
    }
    fun parts(body: String = "<w:p><w:r>"+picture()+"</w:r></w:p>", strict: Boolean = false,
        main: String = "word/document.xml", target: String = "media/owned.png",
        images: Map<String, ByteArray> = mapOf("word/media/owned.png" to png()),
        relation: String = DocxFixtures.relationship("image", "image", target, strict = strict),
        rootExtra: String = "", mime: String = "image/png"): LinkedHashMap<String, ByteArray> {
        val parts = DocxFixtures.parts(main, DocxFixtures.word(body, strict), strict).mapValuesTo(linkedMapOf()) { it.value.toByteArray() }
        parts["[Content_Types].xml"] = DocxFixtures.types(main, "<Default Extension='png' ContentType='"+mime+"'/>").toByteArray()
        parts["_rels/.rels"] = DocxFixtures.relationships(DocxFixtures.relationship("document", "officeDocument", main, strict = strict)+rootExtra).toByteArray()
        val parent = main.substringBeforeLast('/', "")
        val name = main.substringAfterLast('/')
        parts[(if (parent.isEmpty()) "" else parent+"/")+"_rels/"+name+".rels"] = DocxFixtures.relationships(relation).toByteArray()
        parts.putAll(images)
        return parts
    }
    fun bytes(body: String = "<w:p><w:r>"+picture()+"</w:r></w:p>"): ByteArray = DocxFixtures.zipBytes(parts(body))
    fun thumbnail(id: String = "cover", target: String = "word/media/owned.png", external: Boolean = false) =
        "<Relationship Id='"+id+"' Type='"+DOCX_PACKAGE_REL_NS+"/metadata/thumbnail' Target='"+target+"'"+
            (if (external) " TargetMode='External'" else "")+"/>"
}

package com.yaprak.reader.formats.docx

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Owned minimal OPC archives, never user library files. Shared by parser/reader acceptance tests. */
internal object DocxFixtures {
    fun word(body: String = "<w:p><w:r><w:t>Özgün 🌿 belge</w:t></w:r></w:p>", strict: Boolean = false): String =
        "<w:document xmlns:w='${if (strict) DOCX_STRICT_WORD_NS else DOCX_WORD_NS}' xmlns:r='${if (strict) DOCX_STRICT_REL_NS else DOCX_REL_NS}'><w:body>$body</w:body></w:document>"
    fun relationship(id: String, role: String, target: String, external: Boolean = false, strict: Boolean = false) =
        "<Relationship Id='$id' Type='${if (strict) DOCX_STRICT_REL_NS else DOCX_REL_NS}/$role' Target='$target'${if (external) " TargetMode='External'" else ""}/>"
    fun relationships(items: String) = "<Relationships xmlns='$DOCX_PACKAGE_REL_NS'>$items</Relationships>"
    fun types(mainPart: String = "word/document.xml", extra: String = "") =
        "<Types xmlns='$DOCX_TYPES_NS'><Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Default Extension='xml' ContentType='application/xml'/><Override PartName='/$mainPart' ContentType='$DOCX_MAIN_TYPE'/>$extra</Types>"
    fun parts(mainPart: String = "word/document.xml", body: String = word(), strict: Boolean = false): LinkedHashMap<String, String> = linkedMapOf(
        "[Content_Types].xml" to types(mainPart), "_rels/.rels" to relationships(relationship("document", "officeDocument", mainPart, strict = strict)),
        mainPart to body)
    fun zip(parts: Map<String, String>): ByteArray = zipBytes(parts.mapValues { it.value.toByteArray() })
    fun zipBytes(parts: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> parts.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
        } }
        return bytes.toByteArray()
    }
}

package com.yaprak.reader.formats.docx

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal object DocxIndexFixtures {
    fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun p(text: String)="<w:p><w:r><w:t xml:space='preserve'>"+text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")+"</w:t></w:r></w:p>"
    fun bytes(body: String=p("dependency injection 🌿"))=DocxFixtures.zip(DocxFixtures.parts(body=DocxFixtures.word(body)))
    fun withNote(main: String="Original body",note: String="Actual note tail"): ByteArray {
        val parts=DocxFixtures.parts(body=DocxFixtures.word("<w:p><w:r><w:t>"+main+"</w:t><w:footnoteReference w:id='2'/></w:r></w:p>"))
        parts["[Content_Types].xml"]=DocxFixtures.types(extra="<Override PartName='/word/footnotes.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml'/>")
        parts["word/_rels/document.xml.rels"]=DocxFixtures.relationships(DocxFixtures.relationship("notes","footnotes","footnotes.xml"))
        parts["word/footnotes.xml"]="<w:footnotes xmlns:w='"+DOCX_WORD_NS+"'><w:footnote w:id='2'><w:p><w:r><w:footnoteRef/><w:t>"+note+"</w:t></w:r></w:p></w:footnote></w:footnotes>"
        return DocxFixtures.zip(parts)
    }
    fun book(folder: File,bytes: ByteArray=bytes()): Pair<Book,File> {
        val id=UUID.randomUUID().toString()
        val file=File(folder,id+".docx").apply { writeBytes(bytes) }
        return Book(id,"Owned",null,BookFormat.DOCX,"owned.docx",file.name,file.length(),hash(bytes),1) to file
    }
}

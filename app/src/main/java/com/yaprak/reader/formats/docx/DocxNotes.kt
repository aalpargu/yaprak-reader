package com.yaprak.reader.formats.docx

import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class DocxNoteKind(val role: String, val element: String, val prefix: String, val label: String) {
    FOOTNOTE("footnotes", "footnote", "fn", "Dipnot"),
    ENDNOTE("endnotes", "endnote", "en", "Sonnot"),
}
internal data class DocxNoteReference(val target: String, val anchor: String, val label: String)
internal data class DocxNote(val kind: DocxNoteKind, val sourceId: Int, val owner: String,
    val body: DocxXmlElement, val target: String, val label: String, val backlinks: List<String>)
internal class DocxNotesPlan(val notes: List<DocxNote>,
    private val references: IdentityHashMap<DocxXmlElement,DocxNoteReference>) {
    fun reference(node: DocxXmlElement): DocxNoteReference = references[node] ?: docxFail()
}

/** Actual referenced note parts only. Printed Word numbering/page placement is not emulated. */
internal object DocxNotes {
    suspend fun read(opc: DocxOpc, mainBody: DocxXmlElement): DocxNotesPlan {
        val caller=currentCoroutineContext(); caller.ensureActive()
        val ns=opc.wordNamespace
        data class Key(val kind: DocxNoteKind, val id: Int)
        data class Pending(val target: String, val label: String, val returns: MutableList<String>)
        val pending=LinkedHashMap<Key,Pending>()
        val references=IdentityHashMap<DocxXmlElement,DocxNoteReference>()
        val counts=HashMap<DocxNoteKind,Int>()
        val containers=setOf("body","p","r","tbl","tr","tc","ins","moveTo","fldSimple","sdt","sdtContent","hyperlink")
        fun id(node: DocxXmlElement): Int {
            val value=node.attribute("id",ns) ?: docxFail()
            if(!value.matches(Regex("-?[0-9]{1,10}"))) docxFail()
            return value.toIntOrNull() ?: docxFail()
        }
        fun ignored(node: DocxXmlElement) = node.name.namespace==ns && node.name.local in
            setOf("del","moveFrom","pPr","rPr","tblPr","trPr","tcPr","tblGrid","sdtPr","sdtEndPr","sectPr","drawing")
        fun kind(node: DocxXmlElement): DocxNoteKind? = if(node.name.namespace!=ns) null else when(node.name.local) {
            "footnoteReference" -> DocxNoteKind.FOOTNOTE
            "endnoteReference" -> DocxNoteKind.ENDNOTE
            else -> null
        }
        fun scan(node: DocxXmlElement) {
            caller.ensureActive()
            if(ignored(node)) return
            val kind=kind(node)
            if(kind!=null) {
                node.requireElementContent(); if(node.elements.isNotEmpty()) docxFail()
                if(node.attributes.keys.any { it.namespace!=ns || it.local !in setOf("id","customMarkFollows") }) docxFail(DocxFailure.UNSUPPORTED)
                if(node.attribute("customMarkFollows",ns)?.let(::docxOnOff)==true) docxFail(DocxFailure.UNSUPPORTED)
                val id=id(node); if(id<0) docxFail()
                val key=Key(kind,id)
                val note=pending.getOrPut(key) {
                    if(pending.size>=1024) docxFail(DocxFailure.LIMIT)
                    val number=(counts[kind] ?: 0)+1; counts[kind]=number
                    Pending(kind.prefix+number,kind.label+" "+number,ArrayList())
                }
                if(references.size>=4096) docxFail(DocxFailure.LIMIT)
                val anchor="nr"+(references.size+1)
                note.returns+=anchor
                references[node]=DocxNoteReference(note.target,anchor,note.label)
                return
            }
            if(node.name.namespace==ns && node.name.local in containers) node.elements.forEach(::scan)
        }
        scan(mainBody)
        val definitions=HashMap<Key,Pair<String,DocxXmlElement>>()
        for(kind in counts.keys) {
            caller.ensureActive()
            val owner=opc.officePart(kind.role) ?: docxFail()
            if(opc.type(owner)!="application/vnd.openxmlformats-officedocument.wordprocessingml."+kind.role+"+xml")
                docxFail(DocxFailure.UNSUPPORTED)
            val root=opc.xml(owner)
            if(root.name!=DocxName(ns,kind.role)) docxFail()
            root.requireElementContent()
            val seen=HashSet<Int>()
            for(node in root.elements) {
                caller.ensureActive()
                if(node.name!=DocxName(ns,kind.element)) docxFail()
                val id=id(node); if(!seen.add(id)) docxFail()
                if(seen.size>4096) docxFail(DocxFailure.LIMIT)
                when(node.attribute("type",ns) ?: "normal") {
                    "normal" -> {
                        if(id<0) docxFail()
                        definitions[Key(kind,id)]=owner to node
                    }
                    "separator", "continuationSeparator", "continuationNotice" -> Unit
                    else -> docxFail(DocxFailure.UNSUPPORTED)
                }
            }
        }
        val notes=ArrayList<DocxNote>()
        for((key,pending) in pending) {
            caller.ensureActive()
            val (owner,body)=definitions[key] ?: docxFail()
            fun noNested(node: DocxXmlElement) {
                caller.ensureActive()
                if(ignored(node)) return
                if(kind(node)!=null) docxFail(DocxFailure.UNSUPPORTED)
                if(node===body || node.name.namespace==ns && node.name.local in containers) node.elements.forEach(::noNested)
            }
            noNested(body)
            notes+=DocxNote(key.kind,key.id,owner,body,pending.target,pending.label,pending.returns.toList())
        }
        caller.ensureActive()
        return DocxNotesPlan(notes,references)
    }
}

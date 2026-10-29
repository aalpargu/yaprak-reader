package com.yaprak.reader.formats.docx

import java.security.MessageDigest

internal const val DOCX_INDEX_HREF = "Text/document.xhtml"
internal const val DOCX_INDEX_TITLE = "Belge metni"
internal data class DocxTextPart(val text: String, val sha256: String)

/** Only actual final-view source run text. Separators are extraction delimiters, never Word/DOM offsets. */
internal class DocxSourceTextWriter(private val active: () -> Unit) {
    private val result=StringBuilder()
    private var line=false
    private var space=false
    fun boundary() { line=true; active() }
    fun gap() { space=true; active() }
    fun append(value: String) {
        value.forEachIndexed { index,char ->
            if(index%4096==0) active()
            when {
                char=='\n' || char=='\r' -> line=true
                char.isWhitespace() -> space=true
                else -> {
                    if(result.isNotEmpty()) {
                        if(line) put('\n') else if(space) put(' ')
                    }
                    put(char); line=false; space=false
                }
            }
        }
        active()
    }
    private fun put(char: Char) {
        if(result.length>=MAX_DOCX_ENTRY_BYTES) docxFail(DocxFailure.LIMIT)
        result.append(char)
    }
    fun value(): String { active(); return result.toString() }
}

internal fun docxOriginalText(document: DocxDocument): DocxTextPart {
    val text=document.sourceText
    val hash=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return DocxTextPart(text,hash)
}

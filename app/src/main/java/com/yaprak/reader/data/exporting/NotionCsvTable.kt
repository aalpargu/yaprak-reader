package com.yaprak.reader.data.exporting

/** CSV transport shared by actual book-study and whole-library note tables. No account or upload. */
internal class NotionCsvTable {
    private val output = BoundedStudyText(MAX_NOTION_STUDY_CSV_BYTES)
    fun studyHeader() = row("Ad", "Kitap", "Yazar", "Biçim", "Kayıt türü", "Metin", "Anlam", "Bağlı not", "Kaynak", "PDF sayfası", "Vurgu rengi")
    fun row(vararg fields: String) {
        fields.forEach { validStudyRecord('\u0000' !in it) }
        fields.forEachIndexed { index, field ->
            if (index != 0) output.append(",")
            output.append("\"", field.replace("\"", "\"\""), "\"")
        }
        output.append("\r\n")
    }
    fun bytes() = output.bytes()
}

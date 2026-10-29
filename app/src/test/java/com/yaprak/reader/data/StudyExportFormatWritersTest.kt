package com.yaprak.reader.data

import com.yaprak.reader.data.exporting.BoundedStudyText
import com.yaprak.reader.data.exporting.StudyExportException
import com.yaprak.reader.data.exporting.studyExportTextWriter
import com.yaprak.reader.domain.library.MAX_STUDY_EXPORT_BYTES
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.passages.EpubHighlightColor
import org.junit.Assert.*
import org.junit.Test

class StudyExportFormatWritersTest {
    @Test fun `attached Markdown note hostile content stays in independent literal source blocks`() {
        val note = "  # Not başlığı\n``````\n<img src=\"https://example.test/private\">\n🌿 e\u0301\n  "
        val quote = "Kaynak metin 🌿"
        val writer = studyExportTextWriter(StudyExportFormat.MARKDOWN, "Kitap", null, "EPUB")
        writer.endNotes(0); writer.endWords(0)
        writer.passage(1, quote, "OPS/chapter.xhtml", EpubHighlightColor.BLUE, note)
        writer.endPassages(1)
        val blocks = literalMarkdownBlocks(writer.bytes().toString(Charsets.UTF_8))
        assertEquals(listOf("Kitap", "Bilinmiyor", "OPS/chapter.xhtml", quote, note), blocks.first)
        assertTrue(blocks.second.contains("Vurgu rengi: Mavi"))
        assertTrue(blocks.second.contains("Bağlı not:"))
        assertFalse(blocks.second.contains("https://example.test/private"))
        assertFalse(blocks.second.contains("# Not başlığı"))
    }

    @Test fun `all four colors and note-only TXT preserve exact attached whitespace`() {
        for (color in EpubHighlightColor.entries + listOf(null)) {
            val note = "  çok satır\n🌿  "
            val writer = studyExportTextWriter(StudyExportFormat.TEXT, "Kitap", null, "EPUB")
            writer.endNotes(0); writer.endWords(0)
            writer.passage(1, "Alıntı", "OPS/chapter.xhtml", color, note); writer.endPassages(1)
            val output = writer.bytes().toString(Charsets.UTF_8)
            assertTrue(output.endsWith("Bağlı not:\n$note\n\n"))
            assertTrue(output.contains("Kaynak: OPS/chapter.xhtml\n"))
            assertEquals(color != null, output.contains("Vurgu rengi:"))
        }
    }

    @Test fun `attached note fences and color labels count toward export byte budget`() {
        val writer = studyExportTextWriter(StudyExportFormat.MARKDOWN, "Kitap", null, "EPUB")
        writer.endNotes(0); writer.endWords(0)
        val failure = runCatching {
            repeat(1000) { writer.passage(it + 1, "雪".repeat(4096), "OPS/chapter.xhtml",
                EpubHighlightColor.GREEN, "`".repeat(1998) + "🌿") }
        }.exceptionOrNull()
        assertTrue(failure is StudyExportException)
        assertEquals(StudyExportFailure.TOO_LARGE, (failure as StudyExportException).reason)
        assertTrue(writer.bytes().size <= MAX_STUDY_EXPORT_BYTES)
    }

    @Test fun `TXT byte golden retains every previous heading separator and saved character`() {
        val writer = studyExportTextWriter(StudyExportFormat.TEXT, "İstanbul 🌿", "Çağrı Şen", "EPUB")
        writer.note(1, " yeni not\nçığ 🌿\n ", null)
        writer.endNotes(1)
        writer.word(1, "snow", "kar ❄")
        writer.endWords(1)
        writer.passage(1, "  yeni pasaj\nİğne 🌿  ", "OPS/source.xhtml")
        writer.endPassages(1)
        val expected = "Yaprak — Kitap çalışma kayıtları\n" +
            "Başlık: İstanbul 🌿\nYazar: Çağrı Şen\nBiçim: EPUB\n\n" +
            "Notlar\n------\n[1]\n yeni not\nçığ 🌿\n \n\n" +
            "Kelimeler\n---------\n[1]\nİngilizce: snow\nTürkçe: kar ❄\n\n" +
            "Kayıtlı EPUB pasajları\n---------------------\n[1]\n  yeni pasaj\nİğne 🌿  \n\n"
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), writer.bytes())
    }

    @Test fun `TXT empty section and PDF page golden remain byte compatible`() {
        val writer = studyExportTextWriter(StudyExportFormat.TEXT, "PDF", null, "PDF")
        writer.note(1, "Sayfa\nnotu", 4)
        writer.endNotes(1)
        writer.endWords(0)
        writer.endPassages(0)
        val expected = "Yaprak — Kitap çalışma kayıtları\nBaşlık: PDF\nYazar: Bilinmiyor\nBiçim: PDF\n\n" +
            "Notlar\n------\n[1] — PDF sayfası: 5\nSayfa\nnotu\n\n" +
            "Kelimeler\n---------\nKayıt yok.\n\nKayıtlı EPUB pasajları\n---------------------\nKayıt yok.\n"
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), writer.bytes())
    }

    @Test fun `Markdown hostile metadata note words and href remain literal blocks`() {
        val title = "# Başlık\n<img src=\"https://example.test/title\">\n```\n🌿"
        val author = "![yazar](https://example.test/a)\n<script>alert(1)</script>"
        val note = " Notlar\n------\n## Kelimeler\n``````\n[link](https://example.test/n)\n "+
            "<img src=\"https://example.test/i\">\n漢字\n"
        val front = "#word\t\"雪\""
        val back = "<b>çığ</b> `````"
        val href = "OPS/a`b.xhtml"
        val passage = "~~~\n``````````\n![image](https://example.test/p)\n "+
            "İğne 🌿\n"
        val writer = studyExportTextWriter(StudyExportFormat.MARKDOWN, title, author, "EPUB")
        writer.note(1, note, null)
        writer.endNotes(1)
        writer.word(1, front, back)
        writer.endWords(1)
        writer.passage(1, passage, href)
        writer.endPassages(1)
        val markdown = writer.bytes().toString(Charsets.UTF_8)
        val parsed = literalMarkdownBlocks(markdown)
        assertEquals(listOf(title, author, note, front, back, href, passage), parsed.first)
        assertFalse(parsed.second.contains("https://example.test"))
        assertFalse(parsed.second.contains("<img"))
        assertFalse(parsed.second.contains("<script"))
        assertTrue(parsed.second.contains("## Notlar"))
        assertTrue(parsed.second.contains("## Kelimeler"))
        assertTrue(parsed.second.contains("## Kayıtlı EPUB pasajları"))
    }

    @Test fun `Markdown PDF attribution uses one based page outside literal note`() {
        val writer = studyExportTextWriter(StudyExportFormat.MARKDOWN, "PDF", null, "PDF")
        writer.note(1, "PDF sayfası: 99\n## title", 4)
        writer.endNotes(1)
        writer.endWords(0)
        writer.endPassages(0)
        val parsed = literalMarkdownBlocks(writer.bytes().toString(Charsets.UTF_8))
        assertEquals(listOf("PDF", "Bilinmiyor", "PDF sayfası: 99\n## title"), parsed.first)
        assertTrue(parsed.second.contains("PDF sayfası: 5"))
        assertFalse(parsed.second.contains("PDF sayfası: 99"))
    }

    @Test fun `Anki quoted TSV roundtrips tabs quotes leading header HTML and Unicode as two fields`() {
        val pairs = listOf(
            listOf("#separator:Comma", "#html:true"),
            listOf("a\tb", "çığ\t雪 🌿"),
            listOf("\"leaf\"", "\"yaprak\""),
            listOf("<img src=\"https://example.test\">", "<b>kar</b> & snow"),
            listOf("  space  ", " leading\ttrailing "),
        )
        val writer = studyExportTextWriter(StudyExportFormat.ANKI_TSV, "unused", "unused", "EPUB")
        pairs.forEachIndexed { index, pair -> writer.word(index + 1, pair[0], pair[1]) }
        val tsv = writer.bytes().toString(Charsets.UTF_8)
        assertTrue(tsv.startsWith("#separator:Tab\n#html:false\n#columns:Front\tBack\n"))
        assertEquals(pairs, parseQuotedTsv(tsv.lineSequence().drop(3).joinToString("\n")))
        assertFalse(tsv.contains("#guid column"))
    }

    @Test fun `UTF8 byte bound includes every appended multibyte header and separator`() {
        val text = BoundedStudyText()
        text.append("雪")
        text.append("x".repeat(MAX_STUDY_EXPORT_BYTES - 3))
        assertEquals(MAX_STUDY_EXPORT_BYTES, text.bytes().size)
        val failure = runCatching { text.append("\n") }.exceptionOrNull()
        assertTrue(failure is StudyExportException)
        assertEquals(StudyExportFailure.TOO_LARGE, (failure as StudyExportException).reason)
        assertEquals(MAX_STUDY_EXPORT_BYTES, text.bytes().size)
    }

    @Test fun `Markdown repeated long fences count expansion against byte bound`() {
        val writer = studyExportTextWriter(StudyExportFormat.MARKDOWN, "title", "author", "EPUB")
        val value = "`".repeat(2000)
        val error = runCatching { repeat(2000) { writer.note(it + 1, value, null) } }.exceptionOrNull()
        assertTrue(error is StudyExportException)
        assertEquals(StudyExportFailure.TOO_LARGE, (error as StudyExportException).reason)
    }

    @Test fun `Anki quote escaping and headers count against byte bound`() {
        val writer = studyExportTextWriter(StudyExportFormat.ANKI_TSV, "title", "author", "EPUB")
        val error = runCatching {
            repeat(20_000) { writer.word(it + 1, "\"".repeat(100), "\"".repeat(200)) }
        }.exceptionOrNull()
        assertTrue(error is StudyExportException)
        assertEquals(StudyExportFailure.TOO_LARGE, (error as StudyExportException).reason)
    }

    // Independent reader: a CommonMark backtick fence closes only on a pure fence at least as long.
    private fun literalMarkdownBlocks(markdown: String): Pair<List<String>, String> {
        val blocks = mutableListOf<String>()
        val outside = StringBuilder()
        val content = StringBuilder()
        var openingLength = 0
        for (line in markdown.split('\n')) {
            val trimmed = line.trimEnd(' ', '\t')
            val pureFence = trimmed.length >= 3 && trimmed.all { it == '`' }
            if (openingLength == 0) {
                if (pureFence) openingLength = trimmed.length
                else outside.append(line).append('\n')
            } else if (pureFence && trimmed.length >= openingLength) {
                blocks.add(content.toString().removeSuffix("\n"))
                content.clear()
                openingLength = 0
            } else content.append(line).append('\n')
        }
        assertEquals("Every literal fence must close", 0, openingLength)
        return blocks to outside.toString()
    }

    // Independent quoted-delimited parser supports multiline fields and doubled embedded quotes.
    private fun parseQuotedTsv(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var offset = 0
        while (offset < input.length) {
            val fields = mutableListOf<String>()
            while (true) {
                assertEquals('"', input[offset++])
                val field = StringBuilder()
                var closed = false
                while (offset < input.length) {
                    val character = input[offset++]
                    if (character != '"') field.append(character)
                    else if (offset < input.length && input[offset] == '"') {
                        field.append('"')
                        offset++
                    } else { closed = true; break }
                }
                assertTrue("Quoted field closes", closed)
                fields.add(field.toString())
                if (offset == input.length) { rows.add(fields); break }
                when (input[offset++]) {
                    '\t' -> continue
                    '\n' -> { rows.add(fields); break }
                    else -> fail("Unexpected character after quoted field")
                }
            }
        }
        return rows
    }
}

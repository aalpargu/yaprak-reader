package com.yaprak.reader.domain.pdfannotations

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.yaprak.reader.reader.PdfPagePoint
import com.yaprak.reader.reader.PdfSourceAnchor
import com.yaprak.reader.reader.PdfTextBounds
import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest

/** Bounded canonical storage format. It never substitutes for fresh native source proof. */
object PdfAnchorCodec {
    const val MAX_JSON_UNITS = 65536
    const val MAX_JSON_BYTES = 65536
    fun encode(anchor: PdfSourceAnchor): String {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginObject().name("version").value(PdfSourceAnchor.VERSION)
                .name("pageIndex").value(anchor.pageIndex).name("pageWidth").value(anchor.pageWidth)
                .name("pageHeight").value(anchor.pageHeight)
            fun point(name: String, point: PdfPagePoint) {
                writer.name(name).beginArray().value(point.x).value(point.y).endArray()
            }
            point("start", anchor.start); point("stop", anchor.stop)
            writer.name("bounds").beginArray()
            anchor.bounds.forEach { writer.beginArray().value(it.left).value(it.top).value(it.right).value(it.bottom).endArray() }
            writer.endArray().endObject()
        }
        return output.toString().also(::bounded)
    }
    fun decode(json: String): PdfSourceAnchor {
        bounded(json)
        val anchor = try {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false; reader.beginObject()
                val fields = HashSet<String>()
                var version: Int? = null; var page: Int? = null; var width: Int? = null; var height: Int? = null
                var start: PdfPagePoint? = null; var stop: PdfPagePoint? = null; var bounds: List<PdfTextBounds>? = null
                while (reader.hasNext()) {
                    val field = reader.nextName(); require(fields.add(field))
                    when (field) {
                        "version" -> version = reader.integer()
                        "pageIndex" -> page = reader.integer()
                        "pageWidth" -> width = reader.integer()
                        "pageHeight" -> height = reader.integer()
                        "start", "stop" -> {
                            reader.beginArray(); val point = PdfPagePoint(reader.integer(), reader.integer()); reader.endArray()
                            if (field == "start") start = point else stop = point
                        }
                        "bounds" -> {
                            reader.beginArray(); val rectangles = ArrayList<PdfTextBounds>()
                            while (reader.hasNext()) {
                                require(rectangles.size < PdfSourceAnchor.MAX_BOUNDS)
                                reader.beginArray()
                                rectangles += PdfTextBounds(reader.number(), reader.number(), reader.number(), reader.number())
                                reader.endArray()
                            }
                            reader.endArray(); bounds = rectangles
                        }
                        else -> error("Unknown PDF anchor field")
                    }
                }
                reader.endObject()
                require(fields == setOf("version", "pageIndex", "pageWidth", "pageHeight", "start", "stop", "bounds") &&
                    version == PdfSourceAnchor.VERSION && reader.peek() == JsonToken.END_DOCUMENT)
                PdfSourceAnchor(requireNotNull(page), requireNotNull(width), requireNotNull(height),
                    requireNotNull(start), requireNotNull(stop), requireNotNull(bounds))
            }
        } catch (failure: Exception) { throw IllegalArgumentException("Invalid PDF anchor payload", failure) }
        require(encode(anchor) == json) { "Noncanonical PDF anchor payload" }
        return anchor
    }
    fun key(anchor: PdfSourceAnchor): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(anchor).toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    /** A different finger landing point on the same native text/rectangles is the same saved selection. */
    fun selectionKey(anchor: PdfSourceAnchor, text: String): String {
        validatePdfAnnotationText(text, MAX_PDF_ANNOTATION_TEXT)
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginObject().name("version").value(PdfSourceAnchor.VERSION)
                .name("pageIndex").value(anchor.pageIndex).name("pageWidth").value(anchor.pageWidth)
                .name("pageHeight").value(anchor.pageHeight).name("textSha256").value(hash(text))
                .name("bounds").beginArray()
            anchor.bounds.forEach { writer.beginArray().value(it.left).value(it.top).value(it.right).value(it.bottom).endArray() }
            writer.endArray().endObject()
        }
        return hash(output.toString())
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    fun verify(json: String, anchorKey: String): PdfSourceAnchor {
        require(Regex("[0-9a-f]{64}").matches(anchorKey))
        return decode(json).also { require(key(it) == anchorKey) }
    }
    private fun bounded(json: String) {
        require(json.length in 1..MAX_JSON_UNITS && json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES)
        // Canonical payload contains no free text. Reject invalid UTF-16 instead of hashing replacement bytes.
        require(json.none { it.isSurrogate() || it == '\u0000' })
    }
    private fun JsonReader.integer(): Int {
        require(peek() == JsonToken.NUMBER); val raw = nextString()
        require(Regex("0|[1-9][0-9]*").matches(raw)); return raw.toIntOrNull() ?: error("PDF anchor integer overflow")
    }
    private fun JsonReader.number(): Double {
        require(peek() == JsonToken.NUMBER)
        return nextString().toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("Invalid PDF rectangle number")
    }
}

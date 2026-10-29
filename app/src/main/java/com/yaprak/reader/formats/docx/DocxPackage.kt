package com.yaprak.reader.formats.docx

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val MAX_DOCX_PACKAGE_BYTES = 16 * 1024 * 1024
internal const val MAX_DOCX_ENTRY_BYTES = 8 * 1024 * 1024
internal const val MAX_DOCX_EXPANDED_BYTES = 32 * 1024 * 1024
internal const val MAX_DOCX_ENTRIES = 256
internal enum class DocxFailure { INVALID, LIMIT, UNSUPPORTED }
internal class DocxException(val reason: DocxFailure) : Exception(reason.name)
internal fun docxFail(reason: DocxFailure = DocxFailure.INVALID): Nothing = throw DocxException(reason)

/** Complete verified OPC ZIP items; nothing is extracted into a filesystem path. */
internal class DocxPackage internal constructor(parts: Map<String, ByteArray>) {
    val parts: Map<String, ByteArray> = Collections.unmodifiableMap(LinkedHashMap(parts))
    private val names = parts.keys.associateBy(::docxPartKey)
    fun partName(name: String): String? = names[docxPartKey(name)]
    fun required(name: String): ByteArray = parts[partName(name)] ?: docxFail()
}

/** Bounded ZIP32, including local/central correspondence and actual decompression CRC/size checks. */
internal object DocxPackages {
    suspend fun read(file: File): DocxPackage {
        val caller = currentCoroutineContext(); caller.ensureActive()
        if (file.length() > MAX_DOCX_PACKAGE_BYTES) docxFail(DocxFailure.LIMIT)
        val output = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                caller.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > MAX_DOCX_PACKAGE_BYTES - output.size()) docxFail(DocxFailure.LIMIT)
                output.write(buffer, 0, count)
            }
        }
        caller.ensureActive()
        return decode(output.toByteArray())
    }

    suspend fun decode(bytes: ByteArray): DocxPackage {
        val caller = currentCoroutineContext(); caller.ensureActive()
        if (bytes.size > MAX_DOCX_PACKAGE_BYTES) docxFail(DocxFailure.LIMIT)
        try {
            val zip = Directory(bytes) { caller.ensureActive() }
            val records = zip.records()
            val byName = records.associateBy { it.rawName }
            val seen = HashSet<String>()
            val parts = LinkedHashMap<String, ByteArray>()
            var total = 0
            ZipInputStream(bytes.inputStream(), Charsets.UTF_8).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    caller.ensureActive()
                    val local = input.nextEntry ?: break
                    val expected = byName[local.name] ?: docxFail()
                    if (!seen.add(local.name) || local.method != expected.method) docxFail()
                    val output = ByteArrayOutputStream(minOf(expected.size, 64 * 1024))
                    while (true) {
                        caller.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > MAX_DOCX_ENTRY_BYTES - output.size() ||
                            count > MAX_DOCX_EXPANDED_BYTES - total) docxFail(DocxFailure.LIMIT)
                        output.write(buffer, 0, count); total += count
                    }
                    // ZipInputStream validates the real local data/descriptor CRC at EOF.
                    if (output.size() != expected.size || local.size != expected.size.toLong() ||
                        local.compressedSize != expected.compressed.toLong() || local.crc != expected.crc) docxFail()
                    if (local.isDirectory) { if (output.size() != 0) docxFail() }
                    else if (parts.put(expected.partName, output.toByteArray()) != null) docxFail()
                    input.closeEntry()
                }
            }
            if (seen != byName.keys || "[Content_Types].xml" !in parts || "_rels/.rels" !in parts) docxFail()
            caller.ensureActive()
            return DocxPackage(parts)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DocxException) { throw failure }
        catch (_: Exception) { docxFail() }
    }

    private data class Record(val rawName: String, val partName: String, val version: Int, val flags: Int, val method: Int,
        val crc: Long, val compressed: Int, val size: Int, val local: Int, val nameBytes: ByteArray)

    private class Directory(private val bytes: ByteArray, private val active: () -> Unit) {
        private fun range(offset: Int, count: Int) {
            if (offset < 0 || count < 0 || offset.toLong() + count > bytes.size) docxFail()
        }
        private fun u16(offset: Int): Int {
            range(offset, 2)
            return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        }
        private fun u32(offset: Int): Long {
            range(offset, 4)
            return (0..3).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 255) shl (8 * i)) }
        }
        private fun small(offset: Int): Int {
            val value = u32(offset)
            if (value > bytes.size) docxFail()
            return value.toInt()
        }
        private fun extras(offset: Int, length: Int) {
            range(offset, length)
            var cursor = offset
            while (cursor < offset + length) {
                active()
                if (offset + length - cursor < 4) docxFail()
                val type = u16(cursor); val size = u16(cursor + 2)
                if (size > offset + length - cursor - 4) docxFail()
                if (type == 1) docxFail(DocxFailure.UNSUPPORTED) // ZIP64
                cursor += 4 + size
            }
        }
        fun records(): List<Record> {
            active()
            if (bytes.size < 22 || u32(0) != 0x04034b50L) docxFail()
            val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65_557)).firstOrNull {
                u32(it) == 0x06054b50L && it.toLong() + 22 + u16(it + 20) == bytes.size.toLong()
            } ?: docxFail()
            if (u16(end + 4) != 0 || u16(end + 6) != 0 || u16(end + 8) != u16(end + 10)) docxFail()
            val count = u16(end + 10)
            if (count == 65_535) docxFail(DocxFailure.UNSUPPORTED)
            if (count !in 1..MAX_DOCX_ENTRIES) docxFail(DocxFailure.LIMIT)
            val centralSize = small(end + 12); val central = small(end + 16)
            if (centralSize > 2 * 1024 * 1024) docxFail(DocxFailure.LIMIT)
            if (central.toLong() + centralSize != end.toLong()) docxFail()
            val result = ArrayList<Record>(count)
            val names = HashSet<String>(); val canonical = HashSet<String>()
            var cursor = central; var expanded = 0L
            repeat(count) {
                active(); range(cursor, 46)
                if (u32(cursor) != 0x02014b50L || u16(cursor + 34) != 0) docxFail()
                val version = u16(cursor + 6); val flags = u16(cursor + 8); val method = u16(cursor + 10)
                if (version > 20 || flags and (0x080e.inv() and 0xffff) != 0 || method !in setOf(0, 8))
                    docxFail(DocxFailure.UNSUPPORTED)
                if (method == 0 && flags and 6 != 0) docxFail()
                val compressed = small(cursor + 20)
                val sizeLong = u32(cursor + 24)
                if (sizeLong > MAX_DOCX_ENTRY_BYTES) docxFail(DocxFailure.LIMIT)
                expanded += sizeLong
                if (expanded > MAX_DOCX_EXPANDED_BYTES) docxFail(DocxFailure.LIMIT)
                val size = sizeLong.toInt(); val nameSize = u16(cursor + 28)
                val extra = u16(cursor + 30); val comment = u16(cursor + 32)
                if (nameSize !in 1..1024 || cursor.toLong() + 46 + nameSize + extra + comment > end) docxFail()
                val nameBytes = bytes.copyOfRange(cursor + 46, cursor + 46 + nameSize)
                if (flags and 0x0800 == 0 && nameBytes.any { it.toInt() and 0x80 != 0 }) docxFail(DocxFailure.UNSUPPORTED)
                val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes)).toString()
                val part = canonicalDocxPart(name)
                if (!names.add(name) || !canonical.add(docxPartKey(part))) docxFail()
                if (u32(cursor + 38).ushr(16) and 0xf000L == 0xa000L) docxFail(DocxFailure.UNSUPPORTED)
                if (name.endsWith('/') && (size != 0 || u32(cursor + 16) != 0L)) docxFail()
                extras(cursor + 46 + nameSize, extra)
                result += Record(name, part, version, flags, method, u32(cursor + 16), compressed, size,
                    small(cursor + 42), nameBytes)
                cursor += 46 + nameSize + extra + comment
            }
            if (cursor != end) docxFail()
            var next = 0
            for (record in result.sortedBy { it.local }) {
                active()
                val local = record.local
                if (local != next || u32(local) != 0x04034b50L || u16(local + 4) != record.version ||
                    u16(local + 6) != record.flags || u16(local + 8) != record.method) docxFail()
                val nameSize = u16(local + 26); val extra = u16(local + 28)
                range(local + 30, nameSize + extra)
                if (!bytes.copyOfRange(local + 30, local + 30 + nameSize).contentEquals(record.nameBytes)) docxFail()
                extras(local + 30 + nameSize, extra)
                val descriptor = record.flags and 8 != 0
                if (u32(local + 14) !in (if (descriptor) setOf(0L, record.crc) else setOf(record.crc)) ||
                    u32(local + 18) !in (if (descriptor) setOf(0L, record.compressed.toLong()) else setOf(record.compressed.toLong())) ||
                    u32(local + 22) !in (if (descriptor) setOf(0L, record.size.toLong()) else setOf(record.size.toLong()))) docxFail()
                val data = local.toLong() + 30 + nameSize + extra
                val dataEnd = data + record.compressed
                if (dataEnd > central || dataEnd > Int.MAX_VALUE) docxFail()
                next = dataEnd.toInt()
                if (descriptor) {
                    val unsigned = u32(next) == record.crc && u32(next + 4) == record.compressed.toLong() &&
                        u32(next + 8) == record.size.toLong()
                    val signed = u32(next) == 0x08074b50L && u32(next + 4) == record.crc &&
                        u32(next + 8) == record.compressed.toLong() && u32(next + 12) == record.size.toLong()
                    next += when { unsigned -> 12; signed -> 16; else -> docxFail() }
                }
                if (next > central) docxFail()
            }
            if (next != central) docxFail()
            return result
        }
    }
}

/** One decoding step; encoded traversal/separators and alternate spellings cannot alias a package item. */
internal fun canonicalDocxPart(raw: String): String {
    if (raw == "[Content_Types].xml") return raw
    if (raw.isBlank() || raw.startsWith('/') || '\\' in raw || ':' in raw ||
        raw.any { it.isISOControl() } || Regex("(?i)%2f|%5c").containsMatchIn(raw)) docxFail()
    val uri = try { URI("docx:/" + raw) } catch (_: Exception) { docxFail() }
    if (uri.rawQuery != null || uri.rawFragment != null) docxFail()
    val decoded = ByteArrayOutputStream()
    var cursor = 0
    while (cursor < raw.length) {
        val escape = raw.indexOf('%', cursor).let { if (it < 0) raw.length else it }
        decoded.write(raw.substring(cursor, escape).toByteArray(Charsets.UTF_8))
        if (escape == raw.length) break
        decoded.write(raw.substring(escape + 1, escape + 3).toInt(16))
        cursor = escape + 3
    }
    val name = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded.toByteArray())).toString()
    } catch (_: Exception) { docxFail() }
    val directory = name.endsWith('/')
    val segments = name.removeSuffix("/").split('/')
    if (segments.any { it.isEmpty() || it.endsWith('.') || it.any { c -> c.isISOControl() || c == '\\' || c == ':' } } ||
        name == "[Content_Types].xml") docxFail()
    return segments.joinToString("/") + if (directory) "/" else ""
}

/** OPC part equivalence folds ASCII letters only, never locale-dependent Unicode. */
internal fun docxPartKey(name: String): String = buildString(name.length) {
    name.forEach { append(if (it in 'A'..'Z') it + 32 else it) }
}
